package com.sahl.files

import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Zip and unzip with Android's built-in support. Arabic names work (UTF-8, with a Windows Arabic fallback). */
object Zip {
    class Entry(val name: String, val size: Long, val dir: Boolean)

    private val charsets = listOf(Charsets.UTF_8, runCatching { Charset.forName("windows-1256") }.getOrNull(), runCatching { Charset.forName("IBM437") }.getOrNull())
        .filterNotNull()

    private fun open(f: File): ZipFile {
        var last: Exception? = null
        for (cs in charsets) {
            var z: ZipFile? = null
            try { z = ZipFile(f, cs); z.entries().toList(); return z } catch (e: Exception) { last = e; try { z?.close() } catch (_: Exception) {} }
        }
        throw last ?: IllegalStateException("bad zip")
    }

    fun isZip(f: File) = f.isFile && f.extension.lowercase() in setOf("zip", "jar", "epub", "apks", "xapk")

    fun list(f: File): List<Entry> = open(f).use { z -> z.entries().toList().map { Entry(it.name, it.size, it.isDirectory) } }

    /** Extracts all entries (or only [only]) into [dest]. Never writes outside [dest]; never overwrites. Returns files written. */
    fun extract(f: File, dest: File, only: Set<String>? = null, tick: (String, Long) -> Unit): Int {
        dest.mkdirs()
        val root = dest.canonicalPath + File.separator
        var done = 0L; var count = 0
        val buf = ByteArray(256 * 1024)
        open(f).use { z ->
            for (e in z.entries().toList()) {
                if (only != null && e.name !in only) continue
                val clean = e.name.replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." && it != ".." }.joinToString("/")
                if (clean.isEmpty()) continue
                var out = File(dest, clean)
                if (!out.canonicalPath.startsWith(root)) continue          // "zip slip" protection
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                if (out.exists()) out = Fs.freeName(out.parentFile!!, out.name)
                z.getInputStream(e).use { i -> out.outputStream().use { o ->
                    while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n; tick(out.name, done) }
                } }
                if (e.time > 0) out.setLastModified(e.time)
                count++
            }
        }
        return count
    }

    /** Puts [src] (files and folders) into one new zip [target]. */
    fun create(src: List<File>, target: File, tick: (String, Long) -> Unit) {
        var done = 0L
        val buf = ByteArray(256 * 1024)
        ZipOutputStream(target.outputStream().buffered(256 * 1024), Charsets.UTF_8).use { z ->
            for (s in src) {
                if (s.isDirectory) {
                    val base = s.parentFile?.absolutePath?.length?.plus(1) ?: 0
                    s.walkTopDown().forEach { f ->
                        if (f.absolutePath == target.absolutePath) return@forEach
                        val rel = f.absolutePath.substring(base) + if (f.isDirectory) "/" else ""
                        z.putNextEntry(ZipEntry(rel).apply { time = f.lastModified() })
                        if (f.isFile) f.inputStream().use { i ->
                            while (true) { val n = i.read(buf); if (n < 0) break; z.write(buf, 0, n); done += n; tick(f.name, done) }
                        }
                        z.closeEntry()
                    }
                } else {
                    z.putNextEntry(ZipEntry(s.name).apply { time = s.lastModified() })
                    s.inputStream().use { i ->
                        while (true) { val n = i.read(buf); if (n < 0) break; z.write(buf, 0, n); done += n; tick(s.name, done) }
                    }
                    z.closeEntry()
                }
            }
        }
    }
}
