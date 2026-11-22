package com.iosvm.emu.vm

import android.content.Context
import java.io.File

/** Everything lives in app-private storage; Unix socket paths must stay < 108 bytes. */
class VmPaths(ctx: Context) {
    val root = File(ctx.filesDir, "vm").apply { mkdirs() }
    val disks = File(root, "disks").apply { mkdirs() }
    val fw = File(root, "fw").apply { mkdirs() }          // kernelcache/devicetree/ticket/... (from helper export)
    val helper = File(root, "helper").apply { mkdirs() }  // helper-VM kernel/initrd/work disks
    val logs = File(root, "logs").apply { mkdirs() }

    val qmpSock = File(root, "qmp.sock")
    val helperConsoleSock = File(root, "helper-console.sock")
    val setupDone = File(root, "setup.done")

    val nativeLibDir: String = ctx.applicationInfo.nativeLibraryDir
    val qemu = File(nativeLibDir, "libqemu.so")           // qemu-system-aarch64 (Apple-SoC fork)
    val qemuImg = File(nativeLibDir, "libqemuimg.so")     // qemu-img

    fun fwFile(name: String) = File(fw, name)
}
