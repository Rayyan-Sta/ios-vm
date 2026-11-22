package com.iosvm.emu.vm

import java.io.File

/**
 * Starts a QEMU binary shipped as libqemu.so from nativeLibraryDir (the only place a non-rooted
 * app may exec from on targetSdk >= 29). Children of a foreground service; keep the count low
 * (iOS VM + at most one helper VM) to stay far below Android 12+'s phantom-process limit.
 */
object QemuLauncher {
    fun start(p: VmPaths, argv: List<String>, env: Map<String, String>, log: File): Process {
        require(p.qemu.exists()) { "libqemu.so missing: build it with tools/build-qemu-android.sh" }
        val pb = ProcessBuilder(listOf(p.qemu.absolutePath) + argv)
            .directory(p.root)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        pb.environment().putAll(env)
        pb.environment()["LD_LIBRARY_PATH"] = p.nativeLibDir
        return pb.start()
    }
}
