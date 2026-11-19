package com.sahl.files

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

object Fs {
    val root: File get() = Environment.getExternalStorageDirectory()
    val received: File get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Sahl Files")
    fun hasAccess() = Environment.isExternalStorageManager()

    private val img = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp")
    private val vid = setOf("mp4", "mkv", "webm", "mov", "3gp", "avi", "m4v")
    private val aud = setOf("mp3", "m4a", "ogg", "wav", "flac", "aac", "opus", "amr")
    fun isImage(f: File) = f.extension.lowercase() in img
    fun isVideo(f: File) = f.extension.lowercase() in vid

    fun icon(f: File): String = if (f.isDirectory) "📁" else when (f.extension.lowercase()) {
        in img -> "🖼"; in vid -> "🎬"; in aud -> "🎵"
        "pdf" -> "📕"; "apk", "apks", "xapk" -> "📦"
        "zip", "rar", "7z", "tar", "gz" -> "🗜"
        "doc", "docx", "odt" -> "📘"; "xls", "xlsx", "csv" -> "📗"; "ppt", "pptx" -> "📙"
        else -> "📄"
    }

    fun mime(name: String) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        ?: "application/octet-stream"
    fun mime(f: File) = mime(f.name)

    fun uri(c: Context, f: File): Uri = FileProvider.getUriForFile(c, c.packageName + ".files", f)

    fun open(a: Activity, f: File) {
        try {
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri(a, f), mime(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            a.startActivity(Intent.createChooser(i, f.name))
        } catch (e: Exception) { a.toast(t("No app can open this file", "لا يوجد تطبيق يفتح هذا الملف")) }
    }

    fun share(a: Activity, files: List<File>) {
        val uris = ArrayList(files.filter { it.isFile }.map { uri(a, it) })
        if (uris.isEmpty()) { a.toast(t("Pick files, not folders", "اختر ملفات وليس مجلدات")); return }
        val i = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (files.size == 1) mime(files[0]) else "*/*"
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newRawUri("", uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        a.startActivity(Intent.createChooser(i, t("Share", "مشاركة")))
    }

    /** A safe file name: no folders, no hidden or control characters. */
    fun cleanName(raw: String): String {
        var n = raw.substringAfterLast('/').substringAfterLast('\\').filter { it >= ' ' && it !in "<>:\"|?*" }.trim().trimStart('.')
        if (n.length > 120) { val ext = n.substringAfterLast('.', ""); n = n.take(110) + if (ext.isNotEmpty() && ext.length < 8) ".$ext" else "" }
        return n.ifEmpty { "file" }
    }

    /** "Book.pdf" → "Book (2).pdf" when taken, so nothing is ever overwritten. */
    fun freeName(dir: File, name: String): File {
        var f = File(dir, name); var i = 2
        val base = name.substringBeforeLast('.', name); val ext = if (name.contains('.') && !name.startsWith('.')) "." + name.substringAfterLast('.') else ""
        while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
        return f
    }

    fun sizeOf(f: File): Long = if (f.isDirectory) (f.listFiles() ?: emptyArray()).sumOf { sizeOf(it) } else f.length()
    fun countIn(f: File): Int = if (f.isDirectory) (f.listFiles() ?: emptyArray()).sumOf { countIn(it) } else 1

    /** Copies (or moves) [src] into [dstDir]. [tick] gets the file name and bytes done so far. */
    fun transfer(src: List<File>, dstDir: File, move: Boolean, tick: (String, Long) -> Unit): Boolean {
        var ok = true; var done = 0L
        val buf = ByteArray(512 * 1024)
        fun copyFile(a: File, b: File) {
            a.inputStream().use { i -> b.outputStream().use { o ->
                while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n; tick(a.name, done) }
            } }
            b.setLastModified(a.lastModified())
        }
        fun copyAny(a: File, b: File) {
            if (a.isDirectory) { b.mkdirs(); (a.listFiles() ?: emptyArray()).forEach { copyAny(it, File(b, it.name)) } } else copyFile(a, b)
        }
        for (s in src) {
            try {
                if (dstDir.absolutePath == s.absolutePath || dstDir.absolutePath.startsWith(s.absolutePath + "/")) { ok = false; continue }
                val target = freeName(dstDir, s.name)
                if (move && s.renameTo(target)) continue          // same storage: instant
                copyAny(s, target)
                if (move) s.deleteRecursively()
            } catch (e: Exception) { ok = false }
        }
        return ok
    }

    /** Every file under [f], with its path relative to f's parent ("Folder/sub/x.jpg"). */
    fun walkFiles(f: File): List<Pair<File, String>> {
        val base = f.parentFile?.absolutePath?.length?.plus(1) ?: 0
        return f.walkTopDown().filter { it.isFile }.map { it to it.absolutePath.substring(base) }.toList()
    }
}

/** Recycle bin: deleted items wait in a hidden folder for 30 days and can be restored. */
object Trash {
    val dir: File get() = File(Fs.root, ".SahlFiles/Trash")
    private val index: File get() = File(dir, ".index")

    class Item(val file: File, val from: String, val at: Long)

    private fun ensure() { dir.mkdirs(); File(dir.parentFile, ".nomedia").let { if (!it.exists()) it.createNewFile() } }

    fun put(f: File): Boolean {
        ensure()
        val now = System.currentTimeMillis()
        val target = File(dir, "${now}_${f.name}")
        val ok = f.renameTo(target) || run {
            // other storage (USB / SD): copy into a private temp folder, then rename into place
            val tmp = File(dir, ".tmp$now").apply { mkdirs() }
            val copied = Fs.transfer(listOf(f), tmp, true) { _, _ -> } && File(tmp, f.name).renameTo(target)
            tmp.deleteRecursively(); copied
        }
        if (ok) index.appendText("${target.name}\t${f.absolutePath}\t$now\n")
        return ok
    }

    fun items(): List<Item> {
        if (!index.exists()) return emptyList()
        return index.readLines().mapNotNull { l ->
            val p = l.split('\t'); if (p.size < 3) return@mapNotNull null
            val f = File(dir, p[0]); if (!f.exists()) null else Item(f, p[1], p[2].toLongOrNull() ?: 0)
        }.sortedByDescending { it.at }
    }

    private fun rewrite(keep: List<Item>) {
        ensure(); index.writeText(keep.joinToString("") { "${it.file.name}\t${it.from}\t${it.at}\n" })
    }

    fun restore(it: Item): Boolean {
        val orig = File(it.from); orig.parentFile?.mkdirs()
        val target = if (orig.exists()) Fs.freeName(orig.parentFile!!, orig.name) else orig
        val ok = it.file.renameTo(target)
        if (ok) rewrite(items().filter { x -> x.file != it.file })
        return ok
    }

    fun purge(it: Item) { it.file.deleteRecursively(); rewrite(items().filter { x -> x.file != it.file }) }
    fun emptyAll() { items().forEach { it.file.deleteRecursively() }; rewrite(emptyList()) }
    fun size() = items().sumOf { Fs.sizeOf(it.file) }

    /** Removes items older than [days]. */
    fun autoClean(days: Int = 30) {
        val limit = System.currentTimeMillis() - days * 86_400_000L
        val all = items(); val old = all.filter { it.at < limit }
        if (old.isEmpty()) return
        old.forEach { it.file.deleteRecursively() }
        rewrite(all - old.toSet())
    }
}
