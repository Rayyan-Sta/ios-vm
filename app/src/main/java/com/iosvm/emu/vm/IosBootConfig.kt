package com.iosvm.emu.vm

enum class BootMode { RESTORE, NORMAL }

data class IosBootConfig(
    val mode: BootMode = BootMode.NORMAL,
    val smp: Int = 6,
    val tbSizeMiB: Int = 512,
    /** Guest DRAM. Must match the board model; the machine reports the real value via x-ios-vm-info. */
    val dramBytes: Long = 4L shl 30,
    /** NORMAL boots run on qcow2 overlays over the restored raw images (cheap resets + snapshots). */
    val useOverlays: Boolean = true,
    /** Cold-boot straight into a live snapshot (needs migratable devices in the fork; see NOTES.md). */
    val loadvmTag: String? = null,
    val usbPort: Int = 8030,
    val bootArgsOverride: String? = null,
)
