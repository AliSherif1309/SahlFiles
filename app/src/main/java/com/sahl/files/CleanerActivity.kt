package com.sahl.files

import android.app.Activity
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File
import java.security.MessageDigest

/**
 * Storage cleaner: large files, duplicates, empty folders, leftover APKs and old downloads.
 * Safe by default: cleaned files go to the Recycle bin unless you choose "delete forever".
 */
class CleanerActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())

    private class Row(val header: String? = null, val section: Int = 0, val file: File? = null, var checked: Boolean = false, val note: String = "")
    private var rows = listOf<Row>()
    private lateinit var summary: TextView
    private lateinit var list: ListView
    private lateinit var cleanBtn: TextView
    private val adapter = Adapter()
    @Volatile private var stop = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        C.init(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(topBar(t("Cleaner", "التنظيف"), true))
        summary = label(t("Find what takes space on your phone.", "اعرف ما الذي يشغل مساحة هاتفك."), 14f, C.SUB).apply { setPadding(dp(18), 0, dp(18), dp(8)) }
        root.addView(summary)
        list = ListView(this).apply {
            adapter = this@CleanerActivity.adapter; divider = null
            setOnItemClickListener { _, _, p, _ -> tap(p) }
            setOnItemLongClickListener { _, _, p, _ -> rows[p].file?.let { if (it.isFile) Fs.open(this@CleanerActivity, it) }; true }
        }
        root.addView(list, lp(weight = 1f, h = 0))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(8), dp(12), dp(12)) }
        bar.addView(button(t("Scan", "فحص"), false) { scan() }, lp(0, WRAP_CONTENT, 1f).margins(this, r = 8))
        cleanBtn = button(t("Clean", "تنظيف")) { clean() }
        bar.addView(cleanBtn, lp(0, WRAP_CONTENT, 1f))
        root.addView(bar)
        setContentView(root)
        update()
        scan()
    }

    override fun onDestroy() { stop = true; super.onDestroy() }

    private fun tap(p: Int) {
        val r = rows[p]
        if (r.header != null) {            // header: select / unselect the whole section
            val sec = rows.filter { it.section == r.section && it.file != null }
            val on = sec.any { !it.checked }
            sec.forEach { it.checked = on }
        } else r.checked = !r.checked
        adapter.notifyDataSetChanged(); update()
    }

    private fun update() {
        val picked = rows.filter { it.checked && it.file != null }
        val size = picked.sumOf { if (it.file!!.isFile) it.file.length() else 0L }
        cleanBtn.text = if (picked.isEmpty()) t("Clean", "تنظيف") else t("Clean ${size.human()}", "تنظيف ${size.human()}")
        cleanBtn.alpha = if (picked.isEmpty()) 0.5f else 1f
    }

    private fun skip(d: File): Boolean {
        val p = d.absolutePath; val r = Fs.root.absolutePath
        return p == "$r/Android/data" || p == "$r/Android/obb" || p.startsWith("$r/.SahlFiles")
    }

    private fun scan() {
        stop = false
        rows = emptyList(); adapter.notifyDataSetChanged(); update()
        summary.text = t("Scanning…", "جارٍ الفحص…")
        Thread {
            val files = ArrayList<File>(); val empties = ArrayList<File>()
            var n = 0
            fun walk(d: File): Boolean {          // returns true when the folder has no files inside (at any depth)
                if (stop) return false
                val kids = d.listFiles() ?: return false
                var emptyHere = true
                for (k in kids) {
                    if (k.isDirectory) {
                        if (skip(k)) { emptyHere = false; continue }
                        if (!walk(k)) emptyHere = false
                    } else {
                        emptyHere = false; files.add(k)
                        if (++n % 1000 == 0) main.post { summary.text = t("Scanning… $n files", "جارٍ الفحص… $n ملف") }
                    }
                }
                if (emptyHere && d != Fs.root && !d.name.startsWith(".") && d.parentFile?.absolutePath != Fs.root.absolutePath) empties.add(d)
                return emptyHere
            }
            walk(Fs.root)
            // only report the top-most empty folders
            val topEmpty = empties.filter { e -> empties.none { o -> o != e && e.absolutePath.startsWith(o.absolutePath + "/") } }

            val out = ArrayList<Row>()
            fun section(id: Int, title: String, list: List<Row>) {
                if (list.isEmpty()) return
                val total = list.sumOf { if (it.file!!.isFile) it.file.length() else 0L }
                out.add(Row(header = "$title  ·  ${list.size}" + if (total > 0) "  ·  ${total.human()}" else "", section = id))
                out.addAll(list)
            }

            // 1. duplicates
            main.post { summary.text = t("Looking for duplicates…", "البحث عن الملفات المكررة…") }
            val dupRows = ArrayList<Row>()
            val bySize = files.filter { it.length() >= 64 * 1024 && !it.name.startsWith(".") }.groupBy { it.length() }.values.filter { it.size > 1 }
            for (group in bySize) {
                if (stop) break
                val byQuick = group.groupBy { quickHash(it) }.values.filter { it.size > 1 }
                for (g in byQuick) {
                    val byFull = g.groupBy { fullHash(it) }.values.filter { it.size > 1 }
                    for (same in byFull) {
                        val keep = same.minByOrNull { it.lastModified() }!!
                        for (f in same.sortedBy { it.lastModified() }) dupRows.add(Row(section = 1, file = f, checked = f != keep,
                            note = if (f == keep) t("original · kept", "الأصل · يُحتفظ به") else t("copy", "نسخة")))
                    }
                }
            }
            section(1, t("Duplicate files", "ملفات مكررة"), dupRows)

            // 2. large files
            section(2, t("Large files (over 100 MB)", "ملفات كبيرة (أكثر من 100 م.ب)"),
                files.filter { it.length() >= 100L * 1024 * 1024 }.sortedByDescending { it.length() }.map { Row(section = 2, file = it) })

            // 3. installer files
            section(3, t("Installer files (APK)", "ملفات التثبيت (APK)"),
                files.filter { it.extension.lowercase() in setOf("apk", "apks", "xapk") }.sortedByDescending { it.length() }.map { Row(section = 3, file = it, checked = true) })

            // 4. old downloads
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath + "/"
            val old = System.currentTimeMillis() - 180L * 86_400_000L
            section(4, t("Downloads older than 6 months", "تنزيلات أقدم من 6 أشهر"),
                files.filter { it.absolutePath.startsWith(dl) && it.lastModified() < old }.sortedByDescending { it.length() }.map { Row(section = 4, file = it) })

            // 5. empty folders
            section(5, t("Empty folders", "مجلدات فارغة"), topEmpty.map { Row(section = 5, file = it, checked = true) })

            val total = files.sumOf { it.length() }
            main.post {
                if (isFinishing) return@post
                rows = out; adapter.notifyDataSetChanged(); update()
                summary.text = if (out.isEmpty()) t("All clean ✓  ($n files, ${total.human()})", "كل شيء نظيف ✓  ($n ملف، ${total.human()})")
                else t("$n files checked · tap a title to select the whole group · hold a file to open it",
                    "تم فحص $n ملف · اضغط العنوان لتحديد المجموعة · اضغط مطولًا لفتح الملف")
            }
        }.start()
    }

    private fun quickHash(f: File): String = try {
        val md = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(64 * 1024)
        java.io.RandomAccessFile(f, "r").use { r ->
            var n = r.read(buf); if (n > 0) md.update(buf, 0, n)
            if (r.length() > buf.size * 2) { r.seek(r.length() - buf.size); n = r.read(buf); if (n > 0) md.update(buf, 0, n) }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { f.absolutePath }

    private fun fullHash(f: File): String = try {
        val md = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(512 * 1024)
        f.inputStream().use { i -> while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { f.absolutePath }

    private fun clean() {
        val picked = rows.filter { it.checked && it.file != null }.map { it.file!! }
        if (picked.isEmpty()) { toast(t("Nothing selected", "لم يتم تحديد شيء")); return }
        dialog().setTitle(t("Clean ${picked.size} items?", "تنظيف ${picked.size} عنصر؟"))
            .setMessage(t("They go to the Recycle bin for 30 days, so you can restore them.", "ستنتقل إلى المحذوفات لمدة 30 يومًا ويمكنك استعادتها."))
            .setPositiveButton(t("Recycle bin", "إلى المحذوفات")) { _, _ -> doClean(picked, true) }
            .setNeutralButton(t("Delete forever", "حذف نهائي")) { _, _ ->
                confirm(t("Delete forever?", "حذف نهائي؟"), t("This frees the space now and cannot be undone.", "سيُحرّر المساحة الآن ولا يمكن التراجع."), t("Delete", "حذف")) { doClean(picked, false) }
            }
            .setNegativeButton(t("Cancel", "إلغاء"), null).show()
    }

    private fun doClean(picked: List<File>, toBin: Boolean) {
        val (d, set) = progress(t("Cleaning", "جارٍ التنظيف"))
        Thread {
            var freed = 0L; var bad = 0
            picked.forEachIndexed { i, f ->
                set(f.name, i * 100 / picked.size)
                val s = if (f.isFile) f.length() else 0L
                val ok = if (f.isDirectory && (f.list()?.isEmpty() != false)) f.deleteRecursively()
                         else if (toBin) Trash.put(f) else f.deleteRecursively()
                if (ok) freed += s else bad++
            }
            main.post {
                d.dismiss()
                dialog().setTitle(t("Done ✓", "تم ✓"))
                    .setMessage((if (toBin) t("Moved ${freed.human()} to the Recycle bin. Empty it from the file screen to free the space.",
                        "نُقل ${freed.human()} إلى المحذوفات. أفرغها من شاشة الملفات لتحرير المساحة.")
                        else t("Freed ${freed.human()}.", "تم تحرير ${freed.human()}.")) +
                        if (bad > 0) "\n" + t("$bad items could not be removed.", "تعذّر حذف $bad عنصر.") else "")
                    .setPositiveButton(t("OK", "موافق"), null).show()
                scan()
            }
        }.start()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (rows[p].header != null) 0 else 1

        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val r = rows[p]
            if (r.header != null) {
                val tv = (convert as? TextView) ?: label("", 15f, C.GREEN, true).apply { setPadding(dp(18), dp(18), dp(18), dp(6)) }
                tv.text = r.header; return tv
            }
            val row = (convert as? LinearLayout) ?: LinearLayout(this@CleanerActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
                addView(label("", 20f, C.GREEN).apply { tag = "box"; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(34), WRAP_CONTENT))
                addView(LinearLayout(this@CleanerActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, 0, 0)
                    addView(label("", 14f).apply { tag = "name"; maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE })
                    addView(label("", 12f, C.SUB).apply { tag = "sub"; maxLines = 1; ellipsize = TextUtils.TruncateAt.START })
                }, lp(0, WRAP_CONTENT, 1f))
                addView(label("", 13f, C.SUB).apply { tag = "size" })
            }
            val f = r.file!!
            row.findViewWithTag<TextView>("box").text = if (r.checked) "☑" else "☐"
            row.findViewWithTag<TextView>("name").text = "${Fs.icon(f)}  ${f.name}"
            row.findViewWithTag<TextView>("sub").text = (if (r.note.isNotEmpty()) r.note + " · " else "") + (f.parent?.removePrefix(Fs.root.absolutePath) ?: "")
            row.findViewWithTag<TextView>("size").text = if (f.isFile) f.length().human() else ""
            return row
        }
    }
}
