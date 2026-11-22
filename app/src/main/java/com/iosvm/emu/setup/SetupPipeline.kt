package com.iosvm.emu.setup

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import android.util.Log
import com.iosvm.emu.CpuReport
import com.iosvm.emu.vm.BootMode
import com.iosvm.emu.vm.DiskLayout
import com.iosvm.emu.vm.IosBootConfig
import com.iosvm.emu.vm.QemuLauncher
import com.iosvm.emu.vm.T8030Board
import com.iosvm.emu.vm.VmController
import com.iosvm.emu.vm.VmPaths
import com.iosvm.emu.vm.VmState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class SetupRequest(val ipsw: Uri?, val ipswUrl: String?, val keys: Uri, val sepRom: Uri)
data class SetupProgress(
    val stage: String = "idle", val detail: String = "", val error: String? = null, val done: Boolean = false)

/** Line protocol with the helper VM's asoft_runner.py: `ASOFT {json}` out, `ASOFT-GO <stage>` in. */
class HelperConsole(private val sock: File) {
    val events = LinkedBlockingQueue<JSONObject>()
    private var s: LocalSocket? = null

    fun connect(timeoutMs: Long) {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            try {
                val ls = LocalSocket()
                ls.connect(LocalSocketAddress(sock.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                s = ls; break
            } catch (e: Exception) {
                if (System.currentTimeMillis() > end) throw IllegalStateException("helper console unreachable")
                Thread.sleep(300)
            }
        }
        thread(name = "helper-console", isDaemon = true) {
            runCatching {
                s!!.inputStream.bufferedReader().forEachLine { l ->
                    Log.i("helper", l)
                    if (l.startsWith("ASOFT ")) runCatching { events.put(JSONObject(l.removePrefix("ASOFT "))) }
                }
            }
        }
    }

    fun send(line: String) { s?.outputStream?.apply { write((line + "\n").toByteArray()); flush() } }
    fun close() { runCatching { s?.close() } }
}

object SetupPipeline {
    private const val TAG = "SetupPipeline"
    private const val USB_PORT = 8030
    private val _progress = MutableStateFlow(SetupProgress())
    val progress: StateFlow<SetupProgress> = _progress.asStateFlow()
    @Volatile var pending: SetupRequest? = null

    private fun post(stage: String, detail: String = "") { _progress.value = SetupProgress(stage, detail) }

    fun run(ctx: Context) {
        val req = pending ?: return
        var helper: Process? = null
        var console: HelperConsole? = null
        try {
            val p = VmPaths(ctx)

            post("helper", "staging VM assets")
            val cpu = CpuReport.detect()
            copyAsset(ctx, "helper/${cpu.helperKernelAsset}", File(p.helper, "Image.gz"))
            copyAsset(ctx, "helper/initrd.gz", File(p.helper, "initrd.gz"))
            copyAsset(ctx, "helper/asoft-setup.json", File(p.helper, "asoft-setup.json"))

            post("ipsw", "ingesting")
            ingestIpsw(ctx, req, File(p.helper, "ipsw.zip"))

            post("disks", "creating work/export/config disks")
            sparse(File(p.helper, "work.img"), 14L shl 30)
            File(p.helper, "out.img").delete(); sparse(File(p.helper, "out.img"), 2L shl 30)
            writeConfig(ctx, req.keys, File(p.helper, "cfg.img"))

            post("helper", "booting companion VM")
            File(p.helper, "helper.log").delete()
            p.helperConsoleSock.delete()
            helper = QemuLauncher.start(p, HelperVmBoard.argv(p, USB_PORT), emptyMap(), File(p.logs, "helper-qemu.log"))
            console = HelperConsole(p.helperConsoleSock).also { it.connect(60_000) }

            awaitStage(console, "A", 4 * 3600_000L)               // extract + decrypt + forge ticket + export
            post("firmware", "importing exported artifacts")
            TarExtract.extract(File(p.helper, "out.img"), p.fw)
            ctx.contentResolver.openInputStream(req.sepRom)!!.use { i ->
                p.fwFile(T8030Board.SEP_ROM).outputStream().use { o -> i.copyTo(o) }
            }
            DiskLayout.ensureBases(p)

            post("restore", "booting iOS in restore mode")
            VmController.start(ctx, IosBootConfig(mode = BootMode.RESTORE, useOverlays = false))
            waitFor(10 * 60_000L) { VmController.state.value.let { it is VmState.Running || it is VmState.Failed } }
            (VmController.state.value as? VmState.Failed)?.let { error("iOS restore VM: ${it.message}") }

            post("restore", "running idevicerestore over emulated USB (slow under TCG)")
            console.send("ASOFT-GO B")
            awaitStage(console, "B", 8 * 3600_000L)

            post("finalize", "closing restore VM")
            VmController.turnOff()
            waitFor(60_000L) { VmController.state.value is VmState.Idle }

            console.send("ASOFT-GO C")                              // optional filesystem patches
            awaitStage(console, "C", 2 * 3600_000L)

            DiskLayout.resetOverlays(p)
            p.setupDone.writeText("ok")
            _progress.value = SetupProgress("done", done = true)
        } catch (t: Throwable) {
            Log.e(TAG, "setup failed", t)
            _progress.value = SetupProgress("failed", error = t.message ?: t.javaClass.simpleName)
        } finally {
            console?.close()
            helper?.destroyForcibly()
        }
    }

    // ---------------------------------------------------------------- helpers
    private fun awaitStage(c: HelperConsole, stage: String, timeoutMs: Long) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val ev = c.events.poll(1, TimeUnit.SECONDS) ?: continue
            when (ev.optString("ev")) {
                "step" -> {
                    post("helper:$stage", "${ev.optString("id")} ${ev.optString("state")} ${ev.optString("msg")}")
                    if (ev.optString("state") == "fail") error("helper step ${ev.optString("id")}: ${ev.optString("msg")}")
                }
                "stage" -> if (ev.optString("id") == stage && ev.optString("state") == "done") return
            }
        }
        error("timeout waiting for helper stage $stage")
    }

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) { if (System.currentTimeMillis() > end) error("timeout"); Thread.sleep(500) }
    }

    private fun sparse(f: File, size: Long) { if (!f.exists()) RandomAccessFile(f, "rw").use { it.setLength(size) } }

    private fun copyAsset(ctx: Context, asset: String, dest: File) {
        ctx.assets.open(asset).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
    }

    /** cfg.img = {"keys": <user keys.json>} padded with NULs; the runner reads until the first NUL. */
    private fun writeConfig(ctx: Context, keys: Uri, dest: File) {
        val text = ctx.contentResolver.openInputStream(keys)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val body = JSONObject().put("keys", JSONObject(text)).toString().toByteArray()
        require(body.size < (1 shl 20)) { "keys.json too large" }
        dest.outputStream().use { o -> o.write(body); o.write(ByteArray((1 shl 20) - body.size)) }
    }

    private fun ingestIpsw(ctx: Context, req: SetupRequest, dest: File) {
        req.ipsw?.let { uri ->
            ctx.contentResolver.openInputStream(uri)!!.use { i -> dest.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
            return
        }
        val url = requireNotNull(req.ipswUrl)
        require(url.startsWith("https://")) { "IPSW URL must be https" }
        var have = if (dest.exists()) dest.length() else 0L
        val c = URL(url).openConnection() as HttpURLConnection
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        c.connect()
        val total = c.contentLengthLong + (if (c.responseCode == 206) have else 0L)
        if (c.responseCode == 200) have = 0L                       // server ignored Range: restart
        java.io.FileOutputStream(dest, have > 0).use { out ->
            c.inputStream.use { inp ->
                val buf = ByteArray(1 shl 18); var done = have; var last = 0L
                while (true) {
                    val n = inp.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    if (done - last > (32 shl 20)) { last = done; post("ipsw", "${done shr 20} / ${total shr 20} MiB") }
                }
            }
        }
    }
}
