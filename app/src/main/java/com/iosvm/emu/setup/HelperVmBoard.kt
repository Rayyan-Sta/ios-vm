package com.iosvm.emu.setup

import com.iosvm.emu.vm.VmPaths
import java.io.File

/**
 * Companion Linux VM (qemu `virt`, TCG) that runs idevicerestore against the emulated iPhone over
 * USB-over-TCP. It must be started BEFORE the iOS machine (the fork connects out to it).
 *
 * Block device order matters (kernel names them in probe order):
 *   /dev/vda  IPSW (read-only)          /dev/vdb  scratch/work (helper mkfs.ext4's it)
 *   /dev/vdc  export disk (tar stream)  /dev/vdd  config (JSON, NUL-padded)
 */
object HelperVmBoard {
    fun argv(p: VmPaths, usbPort: Int, ramMiB: Int = 2048, smp: Int = 4): List<String> {
        val h = p.helper
        fun blk(i: Int, f: File, ro: Boolean) = listOf(
            "-drive", "file=$f,if=none,id=d$i,format=raw" + if (ro) ",readonly=on" else "",
            "-device", "virtio-blk-pci,drive=d$i",
        )
        return listOf(
            "-M", "virt,gic-version=3", "-cpu", "max", "-smp", "$smp", "-m", "$ramMiB",
            "-accel", "tcg,thread=multi,tb-size=256",
            "-kernel", File(h, "Image.gz").absolutePath,
            "-initrd", File(h, "initrd.gz").absolutePath,
            "-append", "console=ttyAMA0 loglevel=3 asoft=1",
            "-nodefaults", "-display", "none", "-monitor", "none",
            "-serial", "unix:${p.helperConsoleSock},server=on,wait=off",
        ) + blk(0, File(h, "ipsw.zip"), true) + blk(1, File(h, "work.img"), false) +
            blk(2, File(h, "out.img"), false) + blk(3, File(h, "cfg.img"), true) + listOf(
            "-nic", "user,model=virtio-net-pci",
            "-device", "usb-ehci,id=ehci",
            "-device", "usb-tcp-remote,conn-type=ipv4,conn-addr=127.0.0.1,conn-port=$usbPort,bus=ehci.0",
        )
    }
}
