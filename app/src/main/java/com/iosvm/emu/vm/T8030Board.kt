package com.iosvm.emu.vm

import java.io.File

/**
 * qemu-system-aarch64 command line for the Apple-SoC fork's `t8030` machine (iPhone 11, n104ap),
 * modelled on the fork's documented invocation. Option names (trustcache=, ticket=, sep-fw=, ...)
 * and the nvme-ns nsid/nstype assignments are fork-version dependent: verify against the fork's
 * README/wiki when you pin a commit. Artifact file names are what the helper VM exports.
 */
object T8030Board {
    const val KERNEL = "kernelcache.bin"
    const val DEVICETREE = "devicetree.bin"
    const val TRUSTCACHE = "trustcache.bin"
    const val TICKET = "ticket.der"            // forged root ticket (root_ticket.der)
    const val SEP_FW = "sep-fw.img4"
    const val SEP_ROM = "sep-rom.bin"          // user-supplied
    const val RAMDISK = "ramdisk.bin"          // restore ramdisk (RESTORE mode only)

    private const val ARGS_COMMON = "serial=3 -v wdt=-1 debug=0x14e"
    private const val ARGS_RESTORE = "rd=md0 $ARGS_COMMON"

    fun requiredFiles(cfg: IosBootConfig): List<String> = buildList {
        add(KERNEL); add(DEVICETREE); add(TICKET); add(SEP_FW); add(SEP_ROM)
        if (cfg.mode == BootMode.RESTORE) { add(RAMDISK); add(TRUSTCACHE) }
    }

    fun argv(cfg: IosBootConfig, p: VmPaths, serialLog: File): List<String> {
        fun fw(n: String) = p.fwFile(n)
        val machine = buildList {
            add("t8030")
            if (fw(TRUSTCACHE).exists()) add("trustcache=${fw(TRUSTCACHE)}")
            add("ticket=${fw(TICKET)}")
            add("sep-fw=${fw(SEP_FW)}")
            add("sep-rom=${fw(SEP_ROM)}")
            add("kaslr-off=true")
            add("usb-conn-type=ipv4"); add("usb-conn-addr=127.0.0.1"); add("usb-conn-port=${cfg.usbPort}")
        }.joinToString(",")

        val a = mutableListOf(
            "-M", machine,
            "-cpu", "max",
            "-smp", cfg.smp.toString(),
            "-accel", "tcg,thread=multi,tb-size=${cfg.tbSizeMiB}",
            "-kernel", fw(KERNEL).absolutePath,
            "-dtb", fw(DEVICETREE).absolutePath,
        )
        if (cfg.mode == BootMode.RESTORE) a += listOf("-initrd", fw(RAMDISK).absolutePath)
        a += listOf("-append", cfg.bootArgsOverride ?: if (cfg.mode == BootMode.RESTORE) ARGS_RESTORE else ARGS_COMMON)

        a += listOf("-drive", "file=${DiskLayout.sepSsc(p)},if=pflash,format=raw")
        val useOverlay = cfg.useOverlays && cfg.mode == BootMode.NORMAL
        for (ns in DiskLayout.NAMESPACES) {
            val file = if (useOverlay) DiskLayout.overlay(p, ns) else DiskLayout.base(p, ns)
            val fmt = if (useOverlay) "qcow2" else "raw"
            val id = "ns${ns.nsid}"
            a += listOf("-drive", "file=$file,format=$fmt,if=none,id=$id,node-name=$id")
            a += listOf("-device",
                "nvme-ns,drive=$id,bus=nvme-bus.0,nsid=${ns.nsid},nstype=${ns.nsid}," +
                    "logical_block_size=4096,physical_block_size=4096")
        }

        a += listOf(
            "-qmp", "unix:${p.qmpSock},server=on,wait=off",
            "-serial", "file:$serialLog",
            "-display", "none",
            "-monitor", "none",
        )
        cfg.loadvmTag?.let { a += listOf("-loadvm", it) }
        return a
    }
}
