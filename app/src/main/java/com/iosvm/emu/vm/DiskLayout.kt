package com.iosvm.emu.vm

import java.io.File
import java.io.RandomAccessFile

/**
 * iPhone 11 (t8030) storage layout used by the fork's docs: NVMe namespaces 1..8 plus a pflash SSC.
 * Raw "base" images are written by the restore. NORMAL boots use qcow2 overlays on top of them so
 * "reset" and disk-level snapshots are a qemu-img call away.
 */
object DiskLayout {
    data class Ns(val name: String, val nsid: Int, val sizeBytes: Long)

    val NAMESPACES = listOf(
        Ns("root", 1, 16L shl 30),
        Ns("firmware", 2, 8L shl 20),
        Ns("syscfg", 3, 128L shl 10),
        Ns("ctrl_bits", 4, 8L shl 10),
        Ns("nvram", 5, 8L shl 10),
        Ns("effaceable", 6, 4L shl 10),
        Ns("panic_log", 7, 1L shl 20),
        Ns("sep_nvram", 8, 2L shl 10),
    )
    const val SEP_SSC_NAME = "sep_ssc"
    const val SEP_SSC_SIZE = 128L shl 10

    fun base(p: VmPaths, ns: Ns) = File(p.disks, "${ns.name}.img")
    fun overlay(p: VmPaths, ns: Ns) = File(p.disks, "${ns.name}.qcow2")
    fun sepSsc(p: VmPaths) = File(p.disks, "$SEP_SSC_NAME.img")

    private fun sparse(f: File, size: Long) {
        if (!f.exists()) RandomAccessFile(f, "rw").use { it.setLength(size) }
    }

    fun ensureBases(p: VmPaths) {
        NAMESPACES.forEach { sparse(base(p, it), it.sizeBytes) }
        sparse(sepSsc(p), SEP_SSC_SIZE)
    }

    /** Recreates every overlay (discarding guest changes since the last restore). */
    fun resetOverlays(p: VmPaths) {
        for (ns in NAMESPACES) {
            overlay(p, ns).delete()
            val r = QemuImg.createOverlay(p, base(p, ns), overlay(p, ns))
            check(r.ok) { "overlay ${ns.name}: ${r.output}" }
        }
    }

    fun ensureOverlays(p: VmPaths) {
        if (NAMESPACES.any { !overlay(p, it).exists() }) resetOverlays(p)
    }

    // ---- cold, disk-level checkpoints (VM must be stopped; crash-consistent) ----
    fun checkpoint(p: VmPaths, tag: String) {
        for (ns in NAMESPACES) {
            QemuImg.snapshotDelete(p, overlay(p, ns), tag)   // ignore "not found"
            val r = QemuImg.snapshotCreate(p, overlay(p, ns), tag)
            check(r.ok) { "snapshot ${ns.name}: ${r.output}" }
        }
    }

    fun restoreCheckpoint(p: VmPaths, tag: String) {
        for (ns in NAMESPACES) {
            val r = QemuImg.snapshotApply(p, overlay(p, ns), tag)
            check(r.ok) { "apply ${ns.name}: ${r.output}" }
        }
    }

    fun hasCheckpoint(p: VmPaths, tag: String): Boolean {
        val r = QemuImg.snapshotList(p, overlay(p, NAMESPACES.first()))
        return r.ok && r.output.lines().any { l -> l.trim().split(Regex("\\s+")).getOrNull(1) == tag }
    }
}
