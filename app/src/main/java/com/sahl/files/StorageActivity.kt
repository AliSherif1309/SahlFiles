package com.sahl.files

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** Storage map: what fills the phone, by type and by folder. Tap a row to see those files. */
class StorageActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var stop = false

    private class Cat(val id: String, val icon: String, val en: String, val ar: String, val color: Int) {
        var size = 0L; var count = 0
        // the 300 biggest files, kept while scanning (smallest on top, so it is cheap to replace)
        val top = java.util.PriorityQueue<Pair<Long, File>>(301, compareBy<Pair<Long, File>> { it.first })
        fun offer(f: File, size: Long) { if (top.size < 300) top.add(size to f) else if (size > top.peek()!!.first) { top.poll(); top.add(size to f) } }
        fun biggest() = top.sortedByDescending { it.first }.map { it.second }
    }

    private val cats = listOf(
        Cat("img", "🖼", "Photos", "الصور", 0xFF2E9E6E.toInt()),
        Cat("vid", "🎬", "Videos", "الفيديو", 0xFFC9A227.toInt()),
        Cat("wa", "💬", "WhatsApp", "واتساب", 0xFF25A35A.toInt()),
        Cat("aud", "🎵", "Audio", "الصوتيات", 0xFF5B7DB1.toInt()),
        Cat("doc", "📄", "Documents", "المستندات", 0xFF8E6BBF.toInt()),
        Cat("apk", "📦", "Installers (APK)", "ملفات التثبيت", 0xFFD9822B.toInt()),
        Cat("zip", "🗜", "Archives", "الملفات المضغوطة", 0xFF9C7A54.toInt()),
        Cat("oth", "📁", "Other files", "ملفات أخرى", 0xFF7F8C8D.toInt()))

    private lateinit var col: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        C.init(this)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(14), dp(20)) }
        status = label(t("Measuring your storage…", "جارٍ قياس المساحة…"), 14f, C.SUB).apply { setPadding(dp(4), 0, dp(4), dp(10)) }
        col.addView(status)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(topBar(t("Storage map", "خريطة المساحة"), true))
            addView(ScrollView(this@StorageActivity).apply { addView(col) }, lp(h = 0, weight = 1f))
        })
        scan()
    }

    override fun onDestroy() { stop = true; super.onDestroy() }

    private fun catOf(f: File, wa: String): Cat {
        if (f.absolutePath.startsWith(wa)) return cats[2]
        return when (f.extension.lowercase()) {
            "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "dng", "raw" -> cats[0]
            "mp4", "mkv", "webm", "mov", "3gp", "avi", "m4v", "ts" -> cats[1]
            "mp3", "m4a", "ogg", "wav", "flac", "aac", "opus", "amr" -> cats[3]
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "odt", "epub", "md", "rtf" -> cats[4]
            "apk", "apks", "xapk" -> cats[5]
            "zip", "rar", "7z", "tar", "gz", "xz" -> cats[6]
            else -> cats[7]
        }
    }

    private fun scan() = Thread {
        val r = Fs.root
        val wa = File(r, "Android/media/com.whatsapp").absolutePath
        val top = HashMap<File, Long>()
        var n = 0
        fun walk(d: File, topDir: File?) {
            if (stop) return
            for (k in d.listFiles() ?: return) {
                if (k.isDirectory) {
                    val p = k.absolutePath
                    if (p == "${r.absolutePath}/Android/data" || p == "${r.absolutePath}/Android/obb") continue
                    if (java.nio.file.Files.isSymbolicLink(k.toPath())) continue
                    walk(k, topDir ?: k)
                } else {
                    val s = k.length(); val c = catOf(k, wa)
                    c.size += s; c.count++; c.offer(k, s)
                    if (topDir != null) top[topDir] = (top[topDir] ?: 0L) + s
                    if (++n % 2000 == 0) main.post { status.text = t("Measuring… $n files", "جارٍ القياس… $n ملف") }
                }
            }
        }
        try { walk(r, null) } catch (e: Throwable) { }
        main.post { status.text = t("Almost done…", "على وشك الانتهاء…") }
        val st = StatFs(r.absolutePath)
        val used = st.totalBytes - st.availableBytes
        val counted = cats.sumOf { it.size }
        val bigFolders = top.entries.sortedByDescending { it.value }.take(12)
        main.post { if (!isFinishing) show(st.totalBytes, used, st.availableBytes, counted, bigFolders) }
    }.start()

    private fun show(total: Long, used: Long, free: Long, counted: Long, bigFolders: List<Map.Entry<File, Long>>) {
        col.removeAllViews()
        val system = (used - counted).coerceAtLeast(0)

        // summary + stacked bar
        val card = cardBox()
        card.addView(label(t("${used.human()} used of ${total.human()}", "مستخدم ${used.human()} من ${total.human()}"), 20f, C.TEXT, true))
        card.addView(label(t("${free.human()} free", "متاح ${free.human()}"), 14f, C.SUB))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = round(C.LINE, 8); clipToOutline = true }
        fun part(size: Long, color: Int) {
            if (size <= 0 || total <= 0) return
            bar.addView(View(this).apply { setBackgroundColor(color) }, LinearLayout.LayoutParams(0, dp(16), size.toFloat()))
        }
        cats.forEach { part(it.size, it.color) }
        part(system, 0xFF3D4A45.toInt())
        part(free, C.LINE)
        card.addView(bar, lp().margins(this, tp = 12))
        col.addView(card, lp().margins(this, b = 14))

        // by type
        col.addView(label(t("By type", "حسب النوع"), 16f, C.TEXT, true).apply { setPadding(dp(4), dp(4), 0, dp(6)) })
        for (c in cats.filter { it.size > 0 }.sortedByDescending { it.size })
            col.addView(row(c.icon, if (isAr) c.ar else c.en, t("${c.count} files", "${c.count} ملف"), c.size, used, c.color) {
                MainActivity.virtual = (if (isAr) c.ar else c.en) + t(" · biggest 300", " · أكبر 300") to c.biggest()
                startActivity(Intent(this, MainActivity::class.java).putExtra("virtual", true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }, lp().margins(this, b = 8))
        col.addView(row("⚙", t("System & apps", "النظام والتطبيقات"), t("Android, apps and their data", "أندرويد والتطبيقات وبياناتها"), system, used, 0xFF3D4A45.toInt(), null), lp().margins(this, b = 14))

        // biggest folders
        col.addView(label(t("Biggest folders", "أكبر المجلدات"), 16f, C.TEXT, true).apply { setPadding(dp(4), dp(4), 0, dp(6)) })
        for ((dir, size) in bigFolders)
            col.addView(row("📁", dir.name, dir.absolutePath.removePrefix(Fs.root.absolutePath), size, used, C.GREEN) {
                startActivity(Intent(this, MainActivity::class.java).putExtra("path", dir.absolutePath).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }, lp().margins(this, b = 8))
    }

    private fun row(icon: String, name: String, sub: String, size: Long, of: Long, color: Int, tap: (() -> Unit)?) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = ripple(round(C.CARD, 16)); setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(LinearLayout(this@StorageActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(label(icon, 22f), lp(WRAP_CONTENT).margins(this@StorageActivity, r = 12))
            addView(LinearLayout(this@StorageActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(name, 15f, C.TEXT, true)); addView(label(sub, 12f, C.SUB).apply { maxLines = 1 })
            }, lp(0, WRAP_CONTENT, 1f))
            addView(label(size.human(), 14f, C.TEXT, true))
        })
        val pct = if (of > 0) (size * 1000 / of).toFloat() else 0f
        addView(LinearLayout(this@StorageActivity).apply {
            orientation = LinearLayout.HORIZONTAL; background = round(C.LINE, 3); clipToOutline = true
            addView(View(this@StorageActivity).apply { background = round(color, 3) }, LinearLayout.LayoutParams(0, dp(5), pct.coerceAtLeast(1f)))
            addView(View(this@StorageActivity), LinearLayout.LayoutParams(0, dp(5), (1000f - pct).coerceAtLeast(0f)))
        }, lp().margins(this@StorageActivity, tp = 8))
        if (tap != null) setOnClickListener { tap() }
    }
}
