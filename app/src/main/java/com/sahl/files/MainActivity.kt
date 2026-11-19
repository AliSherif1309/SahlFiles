package com.sahl.files

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.TextUtils
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * Sahl Files — the phone file manager.
 * Tap opens, long-press selects. Selected items can be copied, moved, renamed, shared,
 * sent over Wi-Fi (Sahl Share / LocalSend), locked in the Vault or moved to the Recycle bin.
 */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }
    private val thumbs = LruCache<String, Bitmap>(120)
    private val thumbPool = Executors.newFixedThreadPool(2)

    private var built = false
    private lateinit var pathTv: TextView
    private lateinit var storageTv: TextView
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var selBar: LinearLayout
    private lateinit var selCount: TextView
    private lateinit var pasteBar: LinearLayout
    private lateinit var pasteTv: TextView
    private lateinit var places: LinearLayout
    private val adapter = Adapter()

    private var dir: File = Fs.root
    private var items: List<File> = emptyList()
    private var searching: String? = null
    private val sel = LinkedHashSet<File>()
    private var clip: List<File> = emptyList()
    private var clipMove = false
    private val positions = HashMap<String, Int>()

    private var sort: Int
        get() = prefs.getInt("sort", 0)              // 0 name, 1 newest, 2 largest, 3 type
        set(v) { prefs.edit().putInt("sort", v).apply() }
    private var showHidden: Boolean
        get() = prefs.getBoolean("hidden", false)
        set(v) { prefs.edit().putBoolean("hidden", v).apply() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        C.init(this)
        intent.getStringExtra("path")?.let { dir = File(it) }
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        i.getStringExtra("path")?.let { if (built) load(File(it)) else dir = File(it) }
    }

    override fun onResume() {
        super.onResume()
        if (!Fs.hasAccess()) { built = false; showPermission(); return }
        if (!built) { build(); built = true; Thread { Trash.autoClean() }.start() }
        if (searching == null) load(dir, keep = true)
        Updater.checkOnStart(this)
    }

    override fun onDestroy() { super.onDestroy(); thumbPool.shutdownNow() }

    // ------------------------------------------------------------------ permission

    private fun showPermission() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            addView(label("📁", 64f).apply { gravity = Gravity.CENTER }, lp())
            addView(label(t("Sahl Files", "سهل للملفات"), 26f, C.TEXT, true).apply { gravity = Gravity.CENTER }, lp().margins(this@MainActivity, tp = 12))
            addView(label(t("To show and manage your files, allow “All files access” for Sahl Files.\nNothing leaves your phone unless you send it.",
                "لعرض ملفاتك وإدارتها، اسمح بـ «الوصول إلى جميع الملفات» لتطبيق سهل للملفات.\nلا يخرج شيء من هاتفك إلا إذا أرسلته أنت."), 15f, C.SUB)
                .apply { gravity = Gravity.CENTER }, lp().margins(this@MainActivity, tp = 10, b = 22))
            addView(button(t("Allow access", "السماح بالوصول")) {
                try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
                catch (e: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }, lp(WRAP_CONTENT))
        }
        setContentView(box)
    }

    // ------------------------------------------------------------------ layout

    private fun build() {
        val rootView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        rootView.addView(topBar(t("Sahl Files", "سهل للملفات"), false,
            "🔍" to { search() }, "⋮" to { menu() }))

        places = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), 0, dp(12), 0) }
        rootView.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(places) })
        buildPlaces()

        storageTv = label("", 12f, C.SUB).apply { setPadding(dp(18), dp(6), dp(18), 0) }
        rootView.addView(storageTv)
        pathTv = label("", 13f, C.GREEN, true).apply {
            setPadding(dp(18), dp(6), dp(18), dp(6)); maxLines = 1; ellipsize = TextUtils.TruncateAt.START
        }
        rootView.addView(pathTv)

        pasteTv = label("", 14f, C.TEXT)
        pasteBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = round(C.SEL, 14); setPadding(dp(14), dp(6), dp(8), dp(6))
            addView(pasteTv, lp(0, WRAP_CONTENT, 1f))
            addView(button(t("Paste here", "لصق هنا")) { paste() })
            addView(iconBtn("✕") { clip = emptyList(); refreshBars() })
        }
        rootView.addView(pasteBar, lp().margins(this, 12, 2, 12, 4))

        list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            divider = null; selector = ripple(null)
            setOnItemClickListener { _, _, p, _ -> tap(items[p]) }
            setOnItemLongClickListener { _, _, p, _ -> toggle(items[p]); true }
        }
        empty = label("", 15f, C.SUB).apply { gravity = Gravity.CENTER; setPadding(dp(30), dp(60), dp(30), 0) }
        rootView.addView(FrameLayout(this).apply {
            addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(empty, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }, lp(MATCH_PARENT, 0, 1f))

        // selection actions
        selCount = label("", 14f, C.TEXT, true).apply { setPadding(dp(14), 0, dp(10), 0) }
        val acts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun act(icon: String, name: String, run: () -> Unit) = acts.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6)); background = ripple(null)
            addView(label(icon, 20f).apply { gravity = Gravity.CENTER })
            addView(label(name, 11f, C.SUB).apply { gravity = Gravity.CENTER })
            setOnClickListener { run() }
        })
        act("✓", t("All", "الكل")) { sel.addAll(items); refreshBars(); adapter.notifyDataSetChanged() }
        act("⧉", t("Copy", "نسخ")) { setClip(false) }
        act("➜", t("Move", "نقل")) { setClip(true) }
        act("✎", t("Rename", "تسمية")) { rename() }
        act("🗑", t("Delete", "حذف")) { delete() }
        act("↗", t("Share", "مشاركة")) { Fs.share(this, sel.toList()) }
        act("📡", t("Send", "إرسال")) { sendWifi() }
        act("🔒", t("Vault", "الخزنة")) { toVault() }
        act("ⓘ", t("Details", "تفاصيل")) { details() }
        selBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = round(C.CARD, 18)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(selCount, lp(0, WRAP_CONTENT, 1f))
                addView(iconBtn("✕") { sel.clear(); refreshBars(); adapter.notifyDataSetChanged() })
            })
            addView(HorizontalScrollView(this@MainActivity).apply { isHorizontalScrollBarEnabled = false; addView(acts) })
        }
        rootView.addView(selBar, lp().margins(this, 10, 4, 10, 6))

        // bottom: the premium tools
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; background = round(C.CARD, 22)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        fun navItem(icon: String, name: String, run: () -> Unit) = nav.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6)); background = ripple(null)
            addView(label(icon, 20f).apply { gravity = Gravity.CENTER })
            addView(label(name, 11f, C.SUB).apply { gravity = Gravity.CENTER })
            setOnClickListener { run() }
        }, lp(0, WRAP_CONTENT, 1f))
        navItem("＋", t("Folder", "مجلد")) { newFolder() }
        navItem("🧹", t("Cleaner", "التنظيف")) { startActivity(Intent(this, CleanerActivity::class.java)) }
        navItem("🔒", t("Vault", "الخزنة")) { startActivity(Intent(this, VaultActivity::class.java)) }
        navItem("📡", t("Share", "المشاركة")) { startActivity(Intent(this, ShareActivity::class.java)) }
        navItem("🗑", t("Bin", "المحذوفات")) { recycleBin() }
        rootView.addView(nav, lp().margins(this, 10, 0, 10, 10))

        setContentView(rootView)
        refreshBars()
    }

    private fun chip(text: String, run: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 13f; setTextColor(C.TEXT)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = ripple(round(C.CARD, 18))
        setOnClickListener { run() }
    }

    private fun buildPlaces() {
        places.removeAllViews()
        fun add(name: String, f: File) {
            if (!f.exists()) return
            places.addView(chip(name) { load(f) }, lp(WRAP_CONTENT).margins(this, r = 8))
        }
        val r = Fs.root
        add(t("📱 Phone", "📱 الهاتف"), r)
        add(t("⬇ Download", "⬇ التنزيلات"), File(r, Environment.DIRECTORY_DOWNLOADS))
        add(t("📷 Camera", "📷 الكاميرا"), File(r, "DCIM/Camera"))
        add(t("🖼 Pictures", "🖼 الصور"), File(r, Environment.DIRECTORY_PICTURES))
        add(t("📄 Documents", "📄 المستندات"), File(r, Environment.DIRECTORY_DOCUMENTS))
        add(t("🎵 Music", "🎵 الصوتيات"), File(r, Environment.DIRECTORY_MUSIC))
        add(t("🎬 Movies", "🎬 الفيديو"), File(r, Environment.DIRECTORY_MOVIES))
        add("💬 WhatsApp", File(r, "Android/media/com.whatsapp/WhatsApp/Media"))
        add(t("📡 Received", "📡 المستلمة"), Fs.received)
        try {
            val sm = getSystemService(StorageManager::class.java)
            for (v in sm.storageVolumes) if (v.isRemovable) v.directory?.let { add("💾 " + v.getDescription(this), it) }
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ listing

    private fun sorted(fs: List<File>): List<File> {
        val byName = compareBy<File, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        val c: Comparator<File> = when (sort) {
            1 -> compareByDescending<File> { it.lastModified() }
            2 -> compareByDescending<File> { if (it.isFile) it.length() else 0L }.then(byName)
            3 -> compareBy<File> { it.extension.lowercase() }.then(byName)
            else -> byName
        }
        return fs.sortedWith(compareBy<File> { !it.isDirectory }.then(c))
    }

    private fun load(d: File, keep: Boolean = false, select: String? = null) {
        if (!keep && ::list.isInitialized) positions[dir.absolutePath] = list.firstVisiblePosition
        searching = null
        val target = if (d.isDirectory) d else Fs.root
        Thread {
            val all = (target.listFiles() ?: emptyArray()).filter { showHidden || !it.name.startsWith(".") }
            val s = sorted(all)
            main.post {
                dir = target; items = s
                if (!keep) sel.clear()
                adapter.notifyDataSetChanged()
                val rel = target.absolutePath.removePrefix(Fs.root.absolutePath).trim('/')
                pathTv.text = (listOf(t("Phone", "الهاتف")) + rel.split('/').filter { it.isNotEmpty() }).joinToString("  ›  ")
                empty.text = t("This folder is empty", "هذا المجلد فارغ")
                empty.gone(items.isNotEmpty())
                storage()
                refreshBars()
                val pos = select?.let { p -> items.indexOfFirst { it.absolutePath == p } }?.takeIf { it >= 0 } ?: positions[target.absolutePath] ?: 0
                if (items.isNotEmpty()) list.setSelection(pos.coerceIn(0, items.size - 1))
            }
        }.start()
    }

    private fun storage() {
        try {
            val st = StatFs(dir.absolutePath)
            storageTv.text = t("Free ${st.availableBytes.human()} of ${st.totalBytes.human()}", "متاح ${st.availableBytes.human()} من ${st.totalBytes.human()}")
        } catch (_: Exception) { storageTv.text = "" }
    }

    private fun tap(f: File) {
        if (sel.isNotEmpty()) { toggle(f); return }
        if (f.isDirectory) load(f) else Fs.open(this, f)
    }

    private fun toggle(f: File) {
        if (!sel.remove(f)) sel.add(f)
        adapter.notifyDataSetChanged(); refreshBars()
    }

    private fun refreshBars() {
        selBar.gone(sel.isEmpty())
        selCount.text = t("${sel.size} selected", "تم تحديد ${sel.size}")
        pasteBar.gone(clip.isEmpty())
        pasteTv.text = if (clipMove) t("Move ${clip.size} items", "نقل ${clip.size} عنصر") else t("Copy ${clip.size} items", "نسخ ${clip.size} عنصر")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            !built -> super.onBackPressed()
            sel.isNotEmpty() -> { sel.clear(); adapter.notifyDataSetChanged(); refreshBars() }
            searching != null -> load(dir, keep = true)
            dir.absolutePath != Fs.root.absolutePath && dir.parentFile?.canRead() == true -> load(dir.parentFile!!, select = dir.absolutePath)
            else -> super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------ actions

    private fun newFolder() = ask(t("New folder name", "اسم المجلد الجديد")) { n ->
        val f = File(dir, Fs.cleanName(n))
        if (f.exists() || f.mkdirs()) load(dir, keep = true, select = f.absolutePath) else toast(t("Cannot create a folder here", "لا يمكن إنشاء مجلد هنا"))
    }

    private fun setClip(move: Boolean) {
        clip = sel.toList(); clipMove = move; sel.clear()
        adapter.notifyDataSetChanged(); refreshBars()
        toast(t("Open the destination folder, then tap Paste", "افتح المجلد الهدف ثم اضغط لصق"))
    }

    private fun paste() {
        val src = clip; val move = clipMove; val to = dir
        val total = src.sumOf { Fs.sizeOf(it) }.coerceAtLeast(1)
        val (d, set) = progress(if (move) t("Moving", "جارٍ النقل") else t("Copying", "جارٍ النسخ"))
        Thread {
            val ok = Fs.transfer(src, to, move) { name, done -> set("$name · ${done.human()} / ${total.human()}", (done * 100 / total).toInt()) }
            main.post {
                d.dismiss(); clip = emptyList()
                toast(if (!ok) t("Some items could not be copied", "تعذّر نسخ بعض العناصر") else if (move) t("Moved ✓", "تم النقل ✓") else t("Copied ✓", "تم النسخ ✓"))
                load(to, keep = true)
            }
        }.start()
    }

    private fun rename() {
        val f = sel.singleOrNull() ?: run { toast(t("Select one item to rename", "حدّد عنصرًا واحدًا لإعادة التسمية")); return }
        ask(t("New name", "الاسم الجديد"), f.name) { n ->
            val to = File(f.parentFile, Fs.cleanName(n))
            if (to.exists()) toast(t("That name is taken", "الاسم مستخدم"))
            else if (f.renameTo(to)) load(dir, keep = true, select = to.absolutePath)
            else toast(t("Could not rename", "تعذّرت إعادة التسمية"))
        }
    }

    private fun delete() {
        val list = sel.toList()
        dialog().setTitle(t("Delete ${list.size} items?", "حذف ${list.size} عنصر؟"))
            .setMessage(t("They go to the Recycle bin for 30 days.", "ستنتقل إلى المحذوفات لمدة 30 يومًا."))
            .setPositiveButton(t("Recycle bin", "إلى المحذوفات")) { _, _ -> removeAll(list, true) }
            .setNeutralButton(t("Delete forever", "حذف نهائي")) { _, _ ->
                confirm(t("Delete forever?", "حذف نهائي؟"), t("This cannot be undone.", "لا يمكن التراجع عن هذا."), t("Delete", "حذف")) { removeAll(list, false) }
            }
            .setNegativeButton(t("Cancel", "إلغاء"), null).show()
    }

    private fun removeAll(list: List<File>, toBin: Boolean) {
        val (d, set) = progress(t("Deleting", "جارٍ الحذف"))
        Thread {
            var bad = 0
            list.forEachIndexed { i, f ->
                set(f.name, i * 100 / list.size)
                val ok = if (toBin) Trash.put(f) else f.deleteRecursively()
                if (!ok) bad++
            }
            main.post {
                d.dismiss()
                toast(if (bad > 0) t("$bad items could not be deleted", "تعذّر حذف $bad عنصر") else t("Done ✓", "تم ✓"))
                load(dir, keep = true)
            }
        }.start()
    }

    private fun sendWifi() {
        startActivity(Intent(this, ShareActivity::class.java).putStringArrayListExtra("paths", ArrayList(sel.map { it.absolutePath })))
        sel.clear(); refreshBars(); adapter.notifyDataSetChanged()
    }

    private fun toVault() {
        val fs = sel.filter { it.isFile }
        if (fs.isEmpty()) { toast(t("Select files (not folders) for the Vault", "حدّد ملفات (وليس مجلدات) للخزنة")); return }
        startActivity(Intent(this, VaultActivity::class.java).putStringArrayListExtra("add", ArrayList(fs.map { it.absolutePath })))
        sel.clear(); refreshBars(); adapter.notifyDataSetChanged()
    }

    private fun details() {
        val fs = sel.toList()
        val (d, set) = progress(t("Details", "التفاصيل"))
        Thread {
            val size = fs.sumOf { Fs.sizeOf(it) }; val count = fs.sumOf { Fs.countIn(it) }
            val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            val msg = if (fs.size == 1) {
                val f = fs[0]
                "${f.absolutePath}\n\n" + t("Size", "الحجم") + ": ${size.human()}\n" +
                    (if (f.isDirectory) t("Files", "الملفات") + ": $count\n" else t("Type", "النوع") + ": ${Fs.mime(f)}\n") +
                    t("Modified", "آخر تعديل") + ": ${df.format(Date(f.lastModified()))}"
            } else t("${fs.size} items · $count files\nTotal ${size.human()}", "${fs.size} عنصر · $count ملف\nالمجموع ${size.human()}")
            set("", -1)
            main.post { d.dismiss(); dialog().setTitle(if (fs.size == 1) fs[0].name else t("Details", "التفاصيل")).setMessage(msg).setPositiveButton(t("OK", "موافق"), null).show() }
        }.start()
    }

    private fun search() = ask(t("Search in this folder", "بحث في هذا المجلد")) { q ->
        val from = dir
        val (d, set) = progress(t("Searching…", "جارٍ البحث…"))
        Thread {
            val res = ArrayList<File>(); var n = 0
            for (f in from.walkTopDown().onEnter { showHidden || !it.name.startsWith(".") || it == from }) {
                if (f != from && f.name.contains(q, true)) { res.add(f); if (res.size >= 3000) break }
                if (++n % 500 == 0) set(t("$n checked · ${res.size} found", "تم فحص $n · وُجد ${res.size}"), -1)
            }
            main.post {
                d.dismiss()
                searching = q; items = sorted(res); sel.clear()
                adapter.notifyDataSetChanged(); refreshBars()
                pathTv.text = t("🔍 “$q” · ${res.size} results", "🔍 «$q» · ${res.size} نتيجة")
                empty.text = t("Nothing found", "لا توجد نتائج"); empty.gone(items.isNotEmpty())
            }
        }.start()
    }

    private fun menu() {
        val sorts = arrayOf(t("Name", "الاسم"), t("Newest", "الأحدث"), t("Largest", "الأكبر"), t("Type", "النوع"))
        val opts = arrayOf(
            t("Sort: ", "الترتيب: ") + sorts[sort],
            if (showHidden) t("Hide hidden files", "إخفاء الملفات المخفية") else t("Show hidden files", "إظهار الملفات المخفية"),
            t("Check for updates", "البحث عن تحديث"),
            t("About", "حول التطبيق"))
        dialog().setItems(opts) { _, w ->
            when (w) {
                0 -> dialog().setTitle(t("Sort by", "ترتيب حسب")).setSingleChoiceItems(sorts, sort) { dd, s -> sort = s; dd.dismiss(); load(dir, keep = true) }.show()
                1 -> { showHidden = !showHidden; load(dir, keep = true) }
                2 -> Updater.checkNow(this)
                3 -> dialog().setTitle(t("Sahl Files", "سهل للملفات"))
                    .setMessage("v${BuildConfig.VERSION_NAME}\n\n" + t("File manager, cleaner, private vault and Wi-Fi sharing (works with LocalSend).\nPart of the Sahl apps family.",
                        "مدير ملفات ومنظّف وخزنة خاصة ومشاركة عبر الواي فاي (يعمل مع LocalSend).\nمن عائلة تطبيقات سهل."))
                    .setPositiveButton(t("OK", "موافق"), null).show()
            }
        }.show()
    }

    private fun recycleBin() {
        val (d, set) = progress(t("Recycle bin", "المحذوفات"))
        Thread {
            val its = Trash.items(); val total = its.sumOf { Fs.sizeOf(it.file) }
            set("", -1)
            main.post {
                d.dismiss()
                if (its.isEmpty()) { toast(t("The Recycle bin is empty", "سلة المحذوفات فارغة")); return@post }
                val names = its.map { "${Fs.icon(it.file)}  ${it.file.name.substringAfter('_')}\n     ${it.from.removePrefix(Fs.root.absolutePath).substringBeforeLast('/')}" }.toTypedArray()
                dialog().setTitle(t("Recycle bin · ${total.human()}", "المحذوفات · ${total.human()}"))
                    .setItems(names) { _, i ->
                        val it = its[i]
                        dialog().setTitle(it.file.name.substringAfter('_'))
                            .setPositiveButton(t("Restore", "استعادة")) { _, _ ->
                                toast(if (Trash.restore(it)) t("Restored ✓", "تمت الاستعادة ✓") else t("Could not restore", "تعذّرت الاستعادة")); load(dir, keep = true)
                            }
                            .setNeutralButton(t("Delete forever", "حذف نهائي")) { _, _ -> Thread { Trash.purge(it) }.start() }
                            .setNegativeButton(t("Cancel", "إلغاء"), null).show()
                    }
                    .setNeutralButton(t("Empty bin", "إفراغ")) { _, _ ->
                        confirm(t("Empty the Recycle bin?", "إفراغ المحذوفات؟"), t("Frees ${total.human()}. This cannot be undone.", "سيُحرّر ${total.human()}. لا يمكن التراجع."), t("Empty", "إفراغ")) {
                            Thread { Trash.emptyAll(); main.post { toast(t("Emptied ✓", "تم الإفراغ ✓")) } }.start()
                        }
                    }
                    .setNegativeButton(t("Close", "إغلاق"), null).show()
            }
        }.start()
    }

    // ------------------------------------------------------------------ rows

    private inner class Adapter : BaseAdapter() {
        private val df = DateFormat.getDateInstance(DateFormat.MEDIUM)
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val row = (convert as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
                val frame = FrameLayout(this@MainActivity).apply {
                    background = round(C.CARD, 12)
                    addView(label("", 24f).apply { gravity = Gravity.CENTER; tag = "ic" }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                    addView(ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; tag = "img"; clipToOutline = true; background = round(C.CARD, 12) },
                        FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                }
                addView(frame, LinearLayout.LayoutParams(dp(46), dp(46)))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0)
                    addView(label("", 15f).apply { tag = "name"; maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE })
                    addView(label("", 12f, C.SUB).apply { tag = "sub"; maxLines = 1 })
                }, lp(0, WRAP_CONTENT, 1f))
                addView(label("", 18f, C.GREEN).apply { tag = "check" })
            }
            val f = items[p]
            val ic = row.findViewWithTag<TextView>("ic"); val img = row.findViewWithTag<ImageView>("img")
            row.findViewWithTag<TextView>("name").text = f.name
            row.findViewWithTag<TextView>("sub").text = when {
                searching != null -> f.parent?.removePrefix(Fs.root.absolutePath)?.ifEmpty { "/" } ?: ""
                f.isDirectory -> t("${f.list()?.size ?: 0} items", "${f.list()?.size ?: 0} عنصر") + " · " + df.format(Date(f.lastModified()))
                else -> f.length().human() + " · " + df.format(Date(f.lastModified()))
            }
            val on = f in sel
            row.findViewWithTag<TextView>("check").text = if (on) "✔" else ""
            row.background = if (on) round(C.SEL, 14) else null
            ic.text = Fs.icon(f)
            img.setImageDrawable(null); img.visibility = View.GONE
            if (f.isFile && (Fs.isImage(f) || Fs.isVideo(f))) {
                val key = f.absolutePath + f.lastModified()
                img.tag = "img"; img.setTag(R.id.thumb_key, key)
                val cached = thumbs.get(key)
                if (cached != null) { img.setImageBitmap(cached); img.visibility = View.VISIBLE }
                else thumbPool.execute {
                    val bmp = try {
                        if (Fs.isImage(f)) ThumbnailUtils.createImageThumbnail(f, Size(160, 160), null)
                        else ThumbnailUtils.createVideoThumbnail(f, Size(160, 160), null)
                    } catch (_: Exception) { null } ?: return@execute
                    thumbs.put(key, bmp)
                    main.post { if (img.getTag(R.id.thumb_key) == key) { img.setImageBitmap(bmp); img.visibility = View.VISIBLE } }
                }
            } else img.setTag(R.id.thumb_key, null)
            return row
        }
    }

}
