package com.iosvm.emu.vm

import java.io.File

/** Thin wrapper over libqemuimg.so (short-lived child process; safe to exec repeatedly). */
object QemuImg {
    data class Result(val code: Int, val output: String) { val ok get() = code == 0 }

    fun run(p: VmPaths, vararg args: String): Result {
        val pb = ProcessBuilder(listOf(p.qemuImg.absolutePath) + args).redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = p.nativeLibDir
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        return Result(proc.waitFor(), out)
    }

    fun createOverlay(p: VmPaths, base: File, overlay: File) =
        run(p, "create", "-f", "qcow2", "-b", base.absolutePath, "-F", "raw", overlay.absolutePath)

    fun snapshotCreate(p: VmPaths, img: File, tag: String) = run(p, "snapshot", "-c", tag, img.absolutePath)
    fun snapshotApply(p: VmPaths, img: File, tag: String) = run(p, "snapshot", "-a", tag, img.absolutePath)
    fun snapshotDelete(p: VmPaths, img: File, tag: String) = run(p, "snapshot", "-d", tag, img.absolutePath)
    fun snapshotList(p: VmPaths, img: File) = run(p, "snapshot", "-l", img.absolutePath)
}
