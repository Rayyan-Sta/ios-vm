package com.iosvm.emu.setup

import java.io.File
import java.io.RandomAccessFile

/** Minimal USTAR reader for the helper VM's export disk (raw block image holding a tar stream). */
object TarExtract {
    fun extract(img: File, dest: File): List<String> {
        dest.mkdirs()
        val root = dest.canonicalPath + File.separator
        val names = mutableListOf<String>()
        RandomAccessFile(img, "r").use { f ->
            val hdr = ByteArray(512)
            while (f.filePointer + 512 <= f.length()) {
                f.readFully(hdr)
                if (hdr.all { it == 0.toByte() }) break
                val name = cstr(hdr, 0, 100)
                val size = cstr(hdr, 124, 12).trim().ifEmpty { "0" }.toLong(8)
                val type = hdr[156].toInt().toChar()
                val target = File(dest, name).canonicalFile
                require((target.path + File.separator).startsWith(root) || target.path + File.separator == root) { "bad tar path: $name" }
                when (type) {
                    '5' -> target.mkdirs()
                    '0', '\u0000' -> {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out ->
                            val buf = ByteArray(1 shl 16); var left = size
                            while (left > 0) {
                                val n = f.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (n < 0) error("truncated tar")
                                out.write(buf, 0, n); left -= n
                            }
                        }
                        names += name
                        f.seek(f.filePointer + (512 - size % 512) % 512)
                    }
                    else -> f.seek(f.filePointer + (size + 511) / 512 * 512)   // skip links, pax headers, ...
                }
            }
        }
        return names
    }

    private fun cstr(b: ByteArray, off: Int, len: Int): String {
        var end = off; while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }
}
