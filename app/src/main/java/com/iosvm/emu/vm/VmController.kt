package com.iosvm.emu.vm

import android.content.Context
import android.os.Process as AndroidProcess
import android.util.Log
import com.iosvm.emu.NativeBridge
import com.iosvm.emu.input.GuestInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

sealed interface VmState {
    data object Idle : VmState
    data object Starting : VmState
    data object Running : VmState
    data object Saving : VmState
    data object Stopping : VmState
    data class Crashed(val exitCode: Int) : VmState
    data class Failed(val message: String) : VmState
}

/**
 * Process-wide VM lifecycle: launch QEMU, bring up QMP, discover guest RAM/framebuffer geometry
 * from the fork's `x-ios-vm-info` command, and run the save/shutdown flows.
 * All mutating commands run on one worker thread; the UI only observes [state].
 */
object VmController {
    private const val TAG = "VmController"
    const val QUICK_TAG = "quick"

    private val _state = MutableStateFlow<VmState>(VmState.Idle)
    val state: StateFlow<VmState> = _state.asStateFlow()

    /** Flip to true once the fork's Apple devices carry VMState (migration-safe); see NOTES.md. */
    @Volatile var liveSnapshotsSupported = false

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "vm-ctl").apply { isDaemon = true } }
    @Volatile private var proc: Process? = null
    @Volatile private var paths: VmPaths? = null
    @Volatile private var cfg = IosBootConfig()
    @Volatile private var stopRequested = false

    // ------------------------------------------------------------------ start
    fun start(ctx: Context, config: IosBootConfig, resumeCheckpoint: Boolean = false) {
        val s = _state.value
        if (s is VmState.Starting || s is VmState.Running || s is VmState.Saving || s is VmState.Stopping) return
        _state.value = VmState.Starting
        val app = ctx.applicationContext
        worker.execute {
            try { doStart(app, config, resumeCheckpoint) }
            catch (t: Throwable) { Log.e(TAG, "start failed", t); fail(t.message ?: t.javaClass.simpleName) }
        }
    }

    private fun doStart(ctx: Context, config: IosBootConfig, resume: Boolean) {
        val p = VmPaths(ctx); paths = p; cfg = config; stopRequested = false
        val missing = T8030Board.requiredFiles(config).filter { !p.fwFile(it).exists() }
        if (missing.isNotEmpty()) return fail("Missing firmware files: $missing (run setup)")

        DiskLayout.ensureBases(p)
        val overlays = config.mode == BootMode.NORMAL && config.useOverlays
        if (overlays) {
            DiskLayout.ensureOverlays(p)
            if (resume && DiskLayout.hasCheckpoint(p, QUICK_TAG)) DiskLayout.restoreCheckpoint(p, QUICK_TAG)
        }

        p.qmpSock.delete()
        NativeBridge.nativeRamClose()
        val fd = NativeBridge.nativeRamCreate(config.dramBytes)
        if (fd < 0) return fail("Cannot allocate ${config.dramBytes shr 20} MiB of shared guest RAM")

        // QEMU (our child) maps guest DRAM from this fd via /proc/<our pid>/fd/<fd>: RAM-only, never flash-backed.
        val env = mapOf("IOSVM_RAM_FILE" to "/proc/${AndroidProcess.myPid()}/fd/$fd")
        val argv = T8030Board.argv(config, p, File(p.logs, "ios-serial.log"))
        val pr = QemuLauncher.start(p, argv, env, File(p.logs, "ios-qemu.log"))
        proc = pr
        thread(name = "vm-wait", isDaemon = true) { onExit(pr.waitFor()) }

        if (!NativeBridge.nativeQmpConnect(p.qmpSock.absolutePath, 90_000)) return fail("QMP did not come up (see logs/ios-qemu.log)")
        thread(name = "vm-monitor", isDaemon = true) { monitor(fd) }
    }

    private fun onExit(code: Int) {
        NativeBridge.nativeQmpDisconnect()
        NativeBridge.nativeRamClose()
        proc = null
        val cur = _state.value
        _state.value = when {
            cur is VmState.Failed -> cur
            stopRequested -> VmState.Idle
            else -> VmState.Crashed(code)
        }
    }

    private fun fail(msg: String) {
        Log.e(TAG, msg)
        stopRequested = true
        _state.value = VmState.Failed(msg)
        proc?.destroyForcibly()
    }

    // ------------------------------------------------------------------ QMP helpers
    private fun qmp(cmd: String, args: JSONObject? = null, timeoutMs: Int = 5000): JSONObject? {
        val raw = NativeBridge.nativeQmpExecute(cmd, args?.toString() ?: "", timeoutMs) ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    /** Waits for guest DRAM + framebuffer info, attaches the RAM mapping, tracks resolution changes. */
    private fun monitor(fd: Int) {
        var attached = false; var lastSig: String? = null
        val t0 = System.currentTimeMillis()
        while (proc != null) {
            val st = _state.value
            if (st !is VmState.Starting && st !is VmState.Running) return
            val reply = qmp("x-ios-vm-info")
            val info = reply?.optJSONObject("return")
            if (info == null) {
                if (reply?.optJSONObject("error")?.optString("class") == "CommandNotFound")
                    return fail("QEMU fork lacks x-ios-vm-info: apply qemu-patches/ and rebuild libqemu.so")
                if (!attached && System.currentTimeMillis() - t0 > 300_000) return fail("Timed out waiting for guest info")
            } else {
                if (!attached) attached = NativeBridge.nativeRamAttach(fd, info.getLong("dram-base"), info.getLong("dram-size"))
                if (attached && info.getBoolean("fb-valid")) {
                    val sig = "${info.getLong("fb-base")}/${info.getInt("fb-width")}x${info.getInt("fb-height")}/${info.getInt("fb-stride")}/${info.getString("fb-format")}"
                    if (sig != lastSig) {
                        NativeBridge.nativeSetFramebuffer(
                            info.getLong("fb-base"), info.getInt("fb-width"), info.getInt("fb-height"),
                            info.getInt("fb-stride"),
                            if (info.getString("fb-format") == "rgba8888") NativeBridge.RGBA8888 else NativeBridge.BGRA8888)
                        lastSig = sig
                        if (_state.value is VmState.Starting) _state.value = VmState.Running
                    }
                }
            }
            Thread.sleep(if (attached) 1000 else 500)
        }
    }

    // ------------------------------------------------------------------ stop / save
    private fun awaitExit(pr: Process, seconds: Long) {
        if (!pr.waitFor(seconds, TimeUnit.SECONDS)) { pr.destroyForcibly(); pr.waitFor() }
    }

    /** Immediate power-cut. Disks stay crash-consistent (APFS journal replays on next boot). */
    fun turnOff(onDone: () -> Unit = {}) = worker.execute {
        val pr = proc
        if (pr != null) {
            stopRequested = true; _state.value = VmState.Stopping
            qmp("quit", timeoutMs = 2000)
            awaitExit(pr, 5)
        }
        onDone()
    }

    /** Ask iOS to shut down through its own UI, then fall back to power-cut. */
    fun cleanShutdown(onDone: () -> Unit = {}) = worker.execute {
        val pr = proc
        if (pr != null) {
            stopRequested = true; _state.value = VmState.Stopping
            GuestInput.powerOffSequenceNow()
            if (!pr.waitFor(45, TimeUnit.SECONDS)) { qmp("quit", timeoutMs = 2000); awaitExit(pr, 5) }
        }
        onDone()
    }

    /**
     * Save & Close. Preferred path: live snapshot (vmstate + disks) -> fast resume.
     * Fallback (default until the fork's devices are migratable): freeze vCPUs, power-cut, then take a
     * disk-level qcow2 checkpoint. Resuming from it is a normal (cold) iOS boot on that disk state.
     */
    fun saveAndClose(onDone: () -> Unit = {}) = worker.execute {
        val pr = proc; val p = paths
        if (pr != null && p != null) {
            stopRequested = true; _state.value = VmState.Saving
            val live = liveSnapshotsSupported && cfg.useOverlays && liveJob("snapshot-save", QUICK_TAG)
            if (!live) qmp("stop", timeoutMs = 5000)
            qmp("quit", timeoutMs = 2000)
            awaitExit(pr, 8)
            if (!live && cfg.useOverlays && cfg.mode == BootMode.NORMAL)
                runCatching { DiskLayout.checkpoint(p, QUICK_TAG) }.onFailure { Log.e(TAG, "checkpoint failed", it) }
        }
        onDone()
    }

    // ------------------------------------------------------------------ live snapshots (overlay menu)
    /** Returns a short human-readable result for the UI. */
    fun snapshotSave(tag: String = QUICK_TAG): String {
        if (!liveSnapshotsSupported) return "Live snapshots unsupported by this fork build: use Save & Close"
        return if (liveJob("snapshot-save", tag)) "Snapshot saved" else "Snapshot failed (see logs)"
    }

    fun snapshotLoad(tag: String = QUICK_TAG): String {
        if (!liveSnapshotsSupported) return "Live snapshots unsupported: close, then Resume from the launch screen"
        val ok = liveJob("snapshot-load", tag)
        if (ok) qmp("cont")
        return if (ok) "Snapshot loaded" else "Load failed (see logs)"
    }

    private fun liveJob(cmd: String, tag: String): Boolean {
        val nodes = DiskLayout.NAMESPACES.map { "ns${it.nsid}" }
        val id = "job-$cmd"
        val args = JSONObject().put("job-id", id).put("tag", tag).put("vmstate", nodes.first()).put("devices", JSONArray(nodes))
        val r = qmp(cmd, args, 10_000)
        if (r == null || r.has("error")) { Log.e(TAG, "$cmd: $r"); return false }
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            val jobs = qmp("query-jobs")?.optJSONArray("return") ?: return false
            val job = (0 until jobs.length()).map { jobs.getJSONObject(it) }.firstOrNull { it.optString("id") == id }
            if (job == null) return true
            if (job.optString("status") == "concluded") {
                val err = job.optString("error", "")
                qmp("job-dismiss", JSONObject().put("id", id))
                if (err.isNotEmpty()) Log.e(TAG, "$cmd error: $err")
                return err.isEmpty()
            }
            Thread.sleep(250)
        }
        return false
    }
}
