package com.sahl.files

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * Cleaner (Pro): app caches (Shizuku), leftovers of uninstalled apps, junk files, your own rules,
 * duplicates, large files, installers, old downloads, empty folders, exclusions and scheduled auto-clean.
 * Safe by default: personal files go to the Recycle bin.
 */
class CleanerActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())

    private class Row(val header: Int = 0, val found: CleanEngine.Found? = null)
    private var found: List<CleanEngine.Found> = emptyList()
    private var rows: List<Row> = emptyList()
    private lateinit var summary: TextView
    private lateinit var cacheCard: LinearLayout
    private lateinit var list: ListView
    private lateinit var cleanBtn: TextView
    private val adapter = Adapter()
    @Volatile private var stop = false
    private var scanning = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        C.init(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(topBar(t("Cleaner", "التنظيف"), true, "📋" to { rulesList() }, "🚫" to { exclusionsList() }, "⏰" to { scheduleDialog() }))

        val head = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(14), 0) }
        cacheCard = cardBox()
        head.addView(cacheCard, lp().margins(this, b = 8))
        summary = label(t("Find what takes space on your phone.", "اعرف ما الذي يشغل مساحة هاتفك."), 13f, C.SUB).apply { setPadding(dp(4), dp(4), dp(4), dp(4)) }
        head.addView(summary)

        list = ListView(this).apply {
            divider = null
            addHeaderView(head, null, false)
            adapter = this@CleanerActivity.adapter
            setOnItemClickListener { _, _, p, _ -> rows.getOrNull(p - headerViewsCount)?.let { tap(it) } }
            setOnItemLongClickListener { _, _, p, _ -> rows.getOrNull(p - headerViewsCount)?.found?.let { more(it) }; true }
        }
        root.addView(list, lp(h = 0, weight = 1f))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(8), dp(12), dp(12)) }
        bar.addView(button(t("Scan", "فحص"), false) { scan() }, lp(0, WRAP_CONTENT, 1f).margins(this, r = 8))
        cleanBtn = button(t("Clean", "تنظيف")) { clean() }
        bar.addView(cleanBtn, lp(0, WRAP_CONTENT, 1f))
        root.addView(bar)
        setContentView(root)
        update()
        scan()
    }

    override fun onResume() { super.onResume(); drawCacheCard() }
    override fun onDestroy() { stop = true; super.onDestroy() }

    // ---------------------------------------------------------------- app caches card

    private fun drawCacheCard() {
        cacheCard.removeAllViews()
        cacheCard.addView(label(t("🧽  App caches", "🧽  ذاكرة التطبيقات المؤقتة"), 16f, C.TEXT, true))
        val sub = label(t("Checking…", "جارٍ الفحص…"), 13f, C.SUB).apply { setPadding(0, dp(4), 0, dp(8)) }
        cacheCard.addView(sub)
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cacheCard.addView(btns)
        val usage = Sh.usageAccess(this)
        when {
            !Sh.installed(this) -> { sub.text = t("Clearing other apps' caches needs the Shizuku app (no root).", "مسح ذاكرة التطبيقات الأخرى يحتاج تطبيق Shizuku (بدون روت).") }
            !Sh.running() -> { sub.text = t("Shizuku is installed but not running. Open it and press Start.", "Shizuku مثبّت لكنه لا يعمل. افتحه واضغط تشغيل.")
                btns.addView(button(t("Open Shizuku", "فتح Shizuku"), false) { packageManager.getLaunchIntentForPackage(Sh.PKG)?.let { startActivity(it) } }) }
            !Sh.granted() -> { sub.text = t("Allow Sahl Files in Shizuku to clear caches and find all leftovers.", "اسمح لسهل للملفات في Shizuku لمسح الذاكرة المؤقتة وإيجاد كل البقايا.")
                btns.addView(button(t("Allow", "السماح")) { Sh.request() }) }
            else -> sub.text = t("Ready.", "جاهز.")
        }
        if (!usage) btns.addView(button(t("Show sizes", "إظهار الأحجام"), false) {
            toast(t("Find Sahl Files and allow usage access", "ابحث عن سهل للملفات واسمح بالوصول للاستخدام"))
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }, lp(WRAP_CONTENT).margins(this, l = 8))
        if (!usage && !Sh.granted()) return
        Thread {
            val apps = if (usage) Sh.appCaches(this) else emptyList()
            val total = apps.sumOf { it.cache }
            main.post {
                if (isFinishing) return@post
                if (usage) sub.text = t("${total.human()} in ${apps.size} apps", "${total.human()} في ${apps.size} تطبيق") +
                    (if (Sh.granted()) "" else "\n" + t("Tap an app to clear it from its settings page, or allow Shizuku to clear all at once.", "اضغط تطبيقًا لمسحه من صفحة إعداداته، أو اسمح لـ Shizuku لمسح الكل مرة واحدة."))
                if (Sh.granted()) btns.addView(button(t("Clear all caches", "مسح كل الذاكرة المؤقتة")) {
                    val (d, _) = progress(t("Clearing caches", "مسح الذاكرة المؤقتة"))
                    Thread { val ok = Sh.clearAllCaches(); main.post { d.dismiss(); toast(if (ok) t("Freed ${total.human()} ✓", "تم تحرير ${total.human()} ✓") else t("Android refused — try again", "رفض أندرويد — أعد المحاولة")); drawCacheCard() } }.start()
                }, 0)
                if (apps.isNotEmpty()) btns.addView(button(t("Per app", "لكل تطبيق"), false) { perApp(apps) }, lp(WRAP_CONTENT).margins(this, l = 8))
            }
        }.start()
    }

    private fun perApp(apps: List<Sh.AppCache>) {
        dialog().setTitle(t("Biggest app caches", "أكبر ذاكرة مؤقتة للتطبيقات"))
            .setItems(apps.take(60).map { "${it.label}  ·  ${it.cache.human()}" }.toTypedArray()) { _, i ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + apps[i].pkg)))
                toast(t("Storage → Clear cache", "التخزين ← مسح الذاكرة المؤقتة"))
            }.setNegativeButton(t("Close", "إغلاق"), null).show()
    }

    // ---------------------------------------------------------------- results

    private fun tap(r: Row) {
        if (r.found == null) {
            val sec = found.filter { it.section == r.header }
            val on = sec.any { !it.checked }; sec.forEach { it.checked = on }
        } else r.found.checked = !r.found.checked
        adapter.notifyDataSetChanged(); update()
    }

    private fun more(x: CleanEngine.Found) {
        val f = x.file
        dialog().setTitle(f.name).setItems(arrayOf(
            t("Open", "فتح"), t("Show in folder", "عرض في المجلد"), t("Never show this again (exclude)", "عدم إظهاره مجددًا (استثناء)"))) { _, w ->
            when (w) {
                0 -> if (f.isDirectory || x.shell) toast(t("This is a folder", "هذا مجلد")) else Fs.open(this, f)
                1 -> startActivity(Intent(this, MainActivity::class.java).putExtra("path", f.parent).putExtra("select", f.absolutePath)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                2 -> { CleanEngine.exclude(this, x.path); setFound(found.filter { it.path != x.path }); toast(t("Excluded ✓", "تم الاستثناء ✓")) }
            }
        }.show()
    }

    private fun setFound(f: List<CleanEngine.Found>) {
        found = f
        val order = listOf(CleanEngine.LEFTOVER, CleanEngine.JUNK, CleanEngine.RULES, CleanEngine.DUP, CleanEngine.LARGE, CleanEngine.APK, CleanEngine.OLD, CleanEngine.EMPTY)
        val out = ArrayList<Row>()
        for (sec in order) { val items = f.filter { it.section == sec }; if (items.isEmpty()) continue; out.add(Row(header = sec)); items.forEach { out.add(Row(found = it)) } }
        rows = out; adapter.notifyDataSetChanged(); update()
    }

    private fun update() {
        val picked = found.filter { it.checked }
        val size = picked.sumOf { it.size }
        cleanBtn.text = if (picked.isEmpty()) t("Clean", "تنظيف") else t("Clean ${size.human()}", "تنظيف ${size.human()}")
        cleanBtn.alpha = if (picked.isEmpty() || scanning) 0.5f else 1f
    }

    private fun scan() {
        if (scanning) return
        scanning = true; stop = false
        setFound(emptyList())
        summary.text = t("Scanning…", "جارٍ الفحص…")
        Thread {
            val res = try { CleanEngine.scan(this, stop = { stop }) { s -> main.post { summary.text = s } } } catch (e: Throwable) { emptyList() }
            main.post {
                scanning = false
                if (isFinishing) return@post
                setFound(res)
                summary.text = if (res.isEmpty()) t("All clean ✓", "كل شيء نظيف ✓")
                    else t("Tap a title to select its group · hold an item for more", "اضغط العنوان لتحديد المجموعة · اضغط مطولًا على عنصر للمزيد")
            }
        }.start()
    }

    private fun clean() {
        val picked = found.filter { it.checked }
        if (picked.isEmpty() || scanning) return
        dialog().setTitle(t("Clean ${picked.size} items?", "تنظيف ${picked.size} عنصر؟"))
            .setMessage(t("Junk and empty folders are deleted. Your own files go to the Recycle bin for 30 days.", "تُحذف الملفات غير المفيدة والمجلدات الفارغة. ملفاتك تنتقل إلى المحذوفات لمدة 30 يومًا."))
            .setPositiveButton(t("Clean", "تنظيف")) { _, _ -> doClean(picked, false) }
            .setNeutralButton(t("Delete forever", "حذف نهائي")) { _, _ ->
                confirm(t("Delete forever?", "حذف نهائي؟"), t("This frees the space now and cannot be undone.", "سيُحرّر المساحة الآن ولا يمكن التراجع."), t("Delete", "حذف")) { doClean(picked, true) }
            }
            .setNegativeButton(t("Cancel", "إلغاء"), null).show()
    }

    private fun doClean(picked: List<CleanEngine.Found>, forever: Boolean) {
        val (d, set) = progress(t("Cleaning", "جارٍ التنظيف"))
        Thread {
            val (freed, bad) = CleanEngine.clean(picked, forever) { n, p -> set(n, p) }
            main.post {
                d.dismiss()
                dialog().setTitle(t("Done ✓  ${freed.human()}", "تم ✓  ${freed.human()}"))
                    .setMessage(t("Anything moved to the Recycle bin can be restored for 30 days.", "يمكن استعادة ما نُقل إلى المحذوفات خلال 30 يومًا.") +
                        if (bad > 0) "\n" + t("$bad items could not be removed.", "تعذّر حذف $bad عنصر.") else "")
                    .setPositiveButton(t("OK", "موافق"), null).show()
                scan()
            }
        }.start()
    }

    // ---------------------------------------------------------------- your rules

    private fun rulesList() {
        val rules = CleanEngine.rules(this)
        val names = rules.map { (if (it.on) "✓ " else "✗ ") + it.name + (if (it.auto) "  ⏰" else "") }.toTypedArray()
        dialog().setTitle(t("Your cleaning rules", "قواعد التنظيف الخاصة بك"))
            .setItems(if (names.isEmpty()) arrayOf(t("No rules yet. Example: all .log files in Download older than 30 days.", "لا توجد قواعد بعد. مثال: كل ملفات ‎.log‎ في التنزيلات الأقدم من 30 يومًا.")) else names) { _, i ->
                if (rules.isNotEmpty()) editRule(rules, i)
            }
            .setPositiveButton(t("＋ New rule", "＋ قاعدة جديدة")) { _, _ -> editRule(rules, -1) }
            .setNegativeButton(t("Close", "إغلاق"), null).show()
    }

    private fun editRule(rules: MutableList<CleanEngine.Rule>, index: Int) {
        val r = rules.getOrNull(index) ?: CleanEngine.Rule(t("My rule", "قاعدتي"))
        fun field(hint: String, v: String, num: Boolean = false) = EditText(this).apply {
            this.hint = hint; setText(v); textSize = 15f
            inputType = if (num) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        }
        val name = field(t("Rule name", "اسم القاعدة"), r.name)
        val nameHas = field(t("File name contains (e.g. screenshot)", "اسم الملف يحتوي (مثل screenshot)"), r.nameHas)
        val exts = field(t("Extensions (e.g. log, tmp, mp4)", "الامتدادات (مثل log, tmp, mp4)"), r.exts)
        val pathHas = field(t("Folder path contains (e.g. Download/Telegram)", "مسار المجلد يحتوي (مثل Download/Telegram)"), r.pathHas)
        val older = field(t("Older than (days, 0 = any)", "أقدم من (أيام، 0 = أي عمر)"), if (r.olderDays > 0) r.olderDays.toString() else "", true)
        val minMb = field(t("Bigger than (MB, 0 = any)", "أكبر من (م.ب، 0 = أي حجم)"), if (r.minMb > 0) r.minMb.toString() else "", true)
        val on = CheckBox(this).apply { text = t("Rule is on", "القاعدة مفعّلة"); isChecked = r.on; setTextColor(C.TEXT) }
        val auto = CheckBox(this).apply { text = t("Also use in scheduled auto-clean", "استخدمها أيضًا في التنظيف التلقائي"); isChecked = r.auto; setTextColor(C.TEXT) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), 0)
            listOf(name, nameHas, exts, pathHas, older, minMb, on, auto).forEach { addView(it, lp()) }
            addView(label(t("All filled fields must match. Leave a field empty to ignore it. Matching files go to the Recycle bin when cleaned.",
                "يجب أن تتطابق كل الحقول المملوءة. اترك الحقل فارغًا لتجاهله. الملفات المطابقة تنتقل إلى المحذوفات عند التنظيف."), 12f, C.SUB))
        }
        val dlg = dialog().setTitle(if (index < 0) t("New rule", "قاعدة جديدة") else t("Edit rule", "تعديل القاعدة"))
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(t("Save", "حفظ")) { _, _ ->
                r.name = name.text.toString().ifBlank { t("My rule", "قاعدتي") }; r.nameHas = nameHas.text.toString(); r.exts = exts.text.toString()
                r.pathHas = pathHas.text.toString(); r.olderDays = older.text.toString().toIntOrNull() ?: 0; r.minMb = minMb.text.toString().toIntOrNull() ?: 0
                r.on = on.isChecked; r.auto = auto.isChecked
                if (r.empty()) { toast(t("Fill a name, extension or folder", "املأ الاسم أو الامتداد أو المجلد")); return@setPositiveButton }
                if (index < 0) rules.add(r)
                CleanEngine.saveRules(this, rules); toast(t("Saved — scanning again", "تم الحفظ — إعادة الفحص")); scan()
            }
            .setNegativeButton(t("Cancel", "إلغاء"), null)
        if (index >= 0) dlg.setNeutralButton(t("Delete", "حذف")) { _, _ -> rules.removeAt(index); CleanEngine.saveRules(this, rules); scan() }
        dlg.show()
    }

    // ---------------------------------------------------------------- exclusions

    private fun exclusionsList() {
        val ex = CleanEngine.exclusions(this).sorted()
        dialog().setTitle(t("Never cleaned", "مستثناة من التنظيف"))
            .setItems(if (ex.isEmpty()) arrayOf(t("Nothing excluded. Hold any item in the results to exclude it.", "لا يوجد استثناء. اضغط مطولًا على أي عنصر في النتائج لاستثنائه.")) else
                ex.map { it.removePrefix(Fs.root.absolutePath).ifEmpty { "/" } }.toTypedArray()) { _, i ->
                if (ex.isEmpty()) return@setItems
                confirm(t("Remove this exclusion?", "إزالة هذا الاستثناء؟"), ex[i], t("Remove", "إزالة")) { CleanEngine.unexclude(this, ex[i]) }
            }
            .setPositiveButton(t("＋ Folder", "＋ مجلد")) { _, _ ->
                ask(t("Folder to protect (e.g. DCIM/Camera)", "مجلد للحماية (مثل DCIM/Camera)")) { p ->
                    val f = File(Fs.root, p.trim().trim('/'))
                    if (!f.exists()) toast(t("Folder not found", "المجلد غير موجود")) else { CleanEngine.exclude(this, f.absolutePath); toast(t("Protected ✓", "تمت الحماية ✓")) }
                }
            }
            .setNegativeButton(t("Close", "إغلاق"), null).show()
    }

    // ---------------------------------------------------------------- schedule

    private fun scheduleDialog() {
        val opts = arrayOf(t("Off", "إيقاف"), t("Every day", "كل يوم"), t("Every week", "كل أسبوع"))
        val cur = when (AutoClean.every(this)) { 1 -> 1; 7 -> 2; else -> 0 }
        var pick = cur
        val caches = CheckBox(this).apply {
            text = t("Also clear app caches (needs Shizuku)", "امسح أيضًا ذاكرة التطبيقات المؤقتة (يحتاج Shizuku)")
            isChecked = AutoClean.caches(this@CleanerActivity); setTextColor(C.TEXT)
        }
        dialog().setTitle(t("Auto clean", "التنظيف التلقائي"))
            .setSingleChoiceItems(opts, cur) { _, w -> pick = w }
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(22), 0, dp(22), 0)
                addView(caches)
                addView(label(t("Runs while charging. Cleans junk, leftovers, empty folders and rules marked ⏰. Your own files go to the Recycle bin.",
                    "يعمل أثناء الشحن. ينظّف الملفات غير المفيدة والبقايا والمجلدات الفارغة والقواعد المعلّمة ⏰. ملفاتك تنتقل إلى المحذوفات."), 12f, C.SUB))
            })
            .setPositiveButton(t("Save", "حفظ")) { _, _ ->
                AutoClean.schedule(this, when (pick) { 1 -> 1; 2 -> 7; else -> 0 }, caches.isChecked)
                toast(if (pick == 0) t("Auto clean is off", "التنظيف التلقائي متوقف") else t("Auto clean is on ✓", "التنظيف التلقائي مفعّل ✓"))
            }
            .setNegativeButton(t("Cancel", "إلغاء"), null).show()
    }

    // ---------------------------------------------------------------- rows

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (rows[p].found == null) 0 else 1

        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val r = rows[p]
            if (r.found == null) {
                val tv = (convert as? TextView) ?: label("", 15f, C.GREEN, true).apply { setPadding(dp(18), dp(18), dp(18), dp(6)) }
                val items = found.filter { it.section == r.header }
                tv.text = "${CleanEngine.title(r.header)}  ·  ${items.size}  ·  ${items.sumOf { it.size }.human()}"
                return tv
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
            val x = r.found!!; val f = x.file
            row.findViewWithTag<TextView>("box").text = if (x.checked) "☑" else "☐"
            row.findViewWithTag<TextView>("name").text = (if (x.section == CleanEngine.LEFTOVER || x.section == CleanEngine.EMPTY) "📁  " else "${Fs.icon(f)}  ") + f.name
            row.findViewWithTag<TextView>("sub").text = (if (x.note.isNotEmpty()) x.note + " · " else "") + (f.parent?.removePrefix(Fs.root.absolutePath) ?: "")
            row.findViewWithTag<TextView>("size").text = if (x.size > 0) x.size.human() else ""
            return row
        }
    }
}
