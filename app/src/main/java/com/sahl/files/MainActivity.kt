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
    private lateinit var swipe: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private val adapter = Adapter()

    private var dir: File = Fs.root
    private var items: List<File> = emptyList()
    private var searching: String? = null
    private val sel = LinkedHashSet<File>()
    private var clip: List<File> = emptyList()
    private var clipMove = false
    private val positions = HashMap<String, Pair<Int, Int>>()   // folder -> (first row, its offset)
    private var pendingSelect: String? = null

    private fun savePos() {
        if (!::list.isInitialized || searching != null) return
        positions[dir.absolutePath] = list.firstVisiblePosition to (list.getChildAt(0)?.top ?: 0)
    }

    private var sort: Int
        get() = prefs.getInt("sort", 0)              // 0 name, 1 newest, 2 largest, 3 type
        set(v) { prefs.edit().putInt("sort", v).apply() }
    private var showSizes: Boolean
        get() = prefs.getBoolean("sizes", false)
        set(v) { prefs.edit().putBoolean("sizes", v).apply() }
    private val dirSizes = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var sizeJob = 0
    private var favs: Set<String>
        get() = prefs.getStringSet("favs", emptySet())!!.toSet()
        set(v) { prefs.edit().putStringSet("favs", v).apply() }
    private var showHidden: Boolean
        get() = prefs.getBoolean("hidden", false)
        set(v) { prefs.edit().putBoolean("hidden", v).apply() }

    override fun onCreate(b: Bundle?) {
        SahlCore.catchCrashes(this)
        super.onCreate(b)
        C.init(this)
        intent.getStringExtra("path")?.let { dir = File(it) }
        pendingSelect = intent.getStringExtra("select")
    }

    override fun onPause() { super.onPause(); savePos() }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        // remember where to go; onResume (right after this) loads it, so the two never race
        i.getStringExtra("path")?.let { savePos(); dir = File(it); searching = null; pendingSelect = i.getStringExtra("select") }
    }

    override fun onResume() {
        super.onResume()
        if (!Fs.hasAccess()) { built = false; showPermission(); return }
        if (!built) { build(); built = true; Thread { Trash.autoClean() }.start() }
        val v = virtual; virtual = null
        if (v != null) showVirtual("📊 " + v.first, v.second)
        else if (searching == null) { val sel0 = pendingSelect; pendingSelect = null; load(dir, keep = true, select = sel0) }
        UpdatePrompt.checkOnStart(this)
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
        rootView.addView(topBarRes(t("Sahl Files", "سهل للملفات"), false,
            R.drawable.ic_sahl_refresh to { refresh() },
            R.drawable.ic_sahl_search to { search() },
            R.drawable.ic_sahl_more to { menu() }))

        places = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), 0, dp(12), 0) }
        rootView.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(places) })
        buildPlaces()

        storageTv = label("", 12f, C.SUB).apply {
            setPadding(dp(18), dp(6), dp(18), 0)
            setOnClickListener { startActivity(Intent(this@MainActivity, StorageActivity::class.java)) }
        }
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
        swipe = androidx.swiperefreshlayout.widget.SwipeRefreshLayout(this).apply {
            setColorSchemeColors(C.GREEN, C.GOLD)
            setProgressBackgroundColorSchemeColor(C.CARD)
            setOnChildScrollUpCallback { _, _ -> list.canScrollVertically(-1) }   // only pull when the list is at the top
            setOnRefreshListener { refresh() }
            addView(FrameLayout(this@MainActivity).apply {
                addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(empty, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            })
        }
        rootView.addView(swipe, lp(MATCH_PARENT, 0, 1f))

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
        act("⧉↗", t("Open with", "فتح بواسطة")) {
            val f = sel.singleOrNull()?.takeIf { it.isFile }
            if (f == null) toast(t("Select one file", "حدّد ملفًا واحدًا")) else { sel.clear(); refreshBars(); adapter.notifyDataSetChanged(); Fs.open(this, f) }
        }
        act("🗜", t("Zip", "ضغط")) { zipSelected() }
        act("★", t("Favourite", "مفضلة")) { toggleFav() }
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
        fun navItem(iconRes: Int, name: String, tint: Int = C.TEXT, run: () -> Unit) = nav.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6)); background = ripple(null)
            addView(ImageView(this@MainActivity).apply {
                setImageResource(iconRes); setColorFilter(tint)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(label(name, 11f, C.SUB).apply { gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0) })
            setOnClickListener { run() }
        }, lp(0, WRAP_CONTENT, 1f))
        val gold = 0xFFF5C84C.toInt()
        navItem(R.drawable.ic_sahl_add, t("New", "جديد"), C.GREEN) { addMenu() }
        navItem(R.drawable.ic_sahl_cleaner, t("Cleaner", "التنظيف"), C.TEXT) { startActivity(Intent(this, CleanerActivity::class.java)) }
        navItem(R.drawable.ic_sahl_vault, t("Vault", "الخزنة"), gold) { startActivity(Intent(this, VaultActivity::class.java)) }
        navItem(R.drawable.ic_sahl_share, t("Share", "المشاركة"), C.TEXT) { startActivity(Intent(this, ShareActivity::class.java)) }
        navItem(R.drawable.ic_sahl_bin, t("Bin", "المحذوفات"), C.SUB) { recycleBin() }
        rootView.addView(nav, lp().margins(this, 10, 0, 10, 10))

        setContentView(rootView)
        refreshBars()
    }

    private fun chip(iconRes: Int, text: String, tint: Int = C.GREEN, run: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(7), dp(14), dp(7))
        background = ripple(round(C.CARD, 18))
        addView(ImageView(this@MainActivity).apply {
            setImageResource(iconRes); setColorFilter(tint)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, LinearLayout.LayoutParams(dp(16), dp(16)).margins(this@MainActivity, r = 6))
        addView(label(text, 13f, C.TEXT))
        setOnClickListener { run() }
    }

    private fun buildPlaces() {
        places.removeAllViews()
        fun add(iconRes: Int, name: String, f: File, tint: Int = C.GREEN) {
            if (!f.exists()) return
            places.addView(chip(iconRes, name, tint) { load(f) }, lp(WRAP_CONTENT).margins(this, r = 8))
        }
        val r = Fs.root
        val gold = 0xFFF5C84C.toInt()
        add(R.drawable.ic_sahl_phone, t("Phone", "الهاتف"), r)
        places.addView(chip(R.drawable.ic_sahl_recent, t("Recent", "الأخيرة")) { showRecent() }, lp(WRAP_CONTENT).margins(this, r = 8))
        for (p in favs.sorted()) {
            val f = File(p); if (!f.exists()) continue
            places.addView(chip(R.drawable.ic_sahl_star, f.name, gold) { if (f.isDirectory) load(f) else openFile(f) }.apply {
                setOnLongClickListener {
                    confirm(t("Remove from favourites?", "إزالة من المفضلة؟"), f.name, t("Remove", "إزالة")) { favs = favs - p; buildPlaces() }; true
                }
            }, lp(WRAP_CONTENT).margins(this, r = 8))
        }
        add(R.drawable.ic_sahl_download, t("Download", "التنزيلات"), File(r, Environment.DIRECTORY_DOWNLOADS))
        add(R.drawable.ic_sahl_camera, t("Camera", "الكاميرا"), File(r, "DCIM/Camera"))
        add(R.drawable.ic_sahl_image, t("Pictures", "الصور"), File(r, Environment.DIRECTORY_PICTURES))
        add(R.drawable.ic_sahl_doc, t("Documents", "المستندات"), File(r, Environment.DIRECTORY_DOCUMENTS))
        add(R.drawable.ic_sahl_audio, t("Music", "الصوتيات"), File(r, Environment.DIRECTORY_MUSIC))
        add(R.drawable.ic_sahl_video, t("Movies", "الفيديو"), File(r, Environment.DIRECTORY_MOVIES))
        add(R.drawable.ic_sahl_chat, "WhatsApp", File(r, "Android/media/com.whatsapp/WhatsApp/Media"))
        add(R.drawable.ic_sahl_share, t("Received", "المستلمة"), Fs.received)
        try {
            val sm = getSystemService(StorageManager::class.java)
            for (v in sm.storageVolumes) if (v.isRemovable) v.directory?.let { add(R.drawable.ic_sahl_sd, v.getDescription(this), it) }
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ listing

    private fun sorted(fs: List<File>): List<File> {
        val len = HashMap<File, Long>(fs.size * 2); val mod = HashMap<File, Long>(fs.size * 2); val isDir = HashMap<File, Boolean>(fs.size * 2)
        for (f in fs) { val d = f.isDirectory; isDir[f] = d; len[f] = if (d) dirSizes[f.absolutePath] ?: 0L else f.length(); if (sort == 1) mod[f] = f.lastModified() }
        val byName = compareBy<File, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        val c: Comparator<File> = when (sort) {
            1 -> compareByDescending<File> { mod[it] ?: 0L }
            2 -> compareByDescending<File> { len[it] ?: 0L }.then(byName)
            3 -> compareBy<File> { it.extension.lowercase() }.then(byName)
            else -> byName
        }
        return fs.sortedWith(compareBy<File> { isDir[it] != true }.then(c))
    }

    private fun load(d: File, keep: Boolean = false, select: String? = null) {
        if (!keep) savePos()
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
                val picked = select?.let { p -> items.indexOfFirst { it.absolutePath == p } }?.takeIf { it >= 0 }
                val saved = positions[target.absolutePath]
                if (items.isNotEmpty()) when {
                    picked != null -> list.setSelectionFromTop(picked, dp(120))
                    saved != null -> list.setSelectionFromTop(saved.first.coerceIn(0, items.size - 1), saved.second)
                    else -> list.setSelection(0)
                }
                measureFolders()
                swipe.isRefreshing = false
            }
        }.start()
    }

    /** Reloads what is on screen (pull down, or the ↻ button). */
    private fun refresh() {
        val s = searching
        when {
            s == null -> { dirSizes.keys.removeAll { it.startsWith(dir.absolutePath) }; buildPlaces(); load(dir, keep = true) }
            s.startsWith("🕘") -> { showRecent(); swipe.isRefreshing = false }
            else -> { items = items.filter { it.exists() }; adapter.notifyDataSetChanged(); swipe.isRefreshing = false }
        }
    }

    // ------------------------------------------------------------------ ＋ new

    private fun addMenu() {
        if (searching != null) { toast(t("Open a folder first", "افتح مجلدًا أولًا")); return }
        dialog().setTitle(t("Add to this folder", "إضافة إلى هذا المجلد")).setItems(arrayOf(
            t("📁  New folder", "📁  مجلد جديد"),
            t("📝  New text file", "📝  ملف نصي جديد"),
            t("📥  Add files from another app…", "📥  إضافة ملفات من تطبيق آخر…"))) { _, w ->
            when (w) {
                0 -> newFolder()
                1 -> newTextFile()
                2 -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 11)
            }
        }.show()
    }

    private fun newTextFile() = ask(t("File name", "اسم الملف"), t("Note", "ملاحظة")) { n ->
        val name = Fs.cleanName(n).let { if (it.contains('.')) it else "$it.txt" }
        val f = File(dir, name)
        if (f.exists()) { toast(t("That name is taken", "الاسم مستخدم")); return@ask }
        try { f.writeText("") } catch (e: Exception) { toast(t("Cannot create a file here", "لا يمكن إنشاء ملف هنا")); return@ask }
        load(dir, keep = true, select = f.absolutePath)
        startActivity(Intent(this, ViewerActivity::class.java).putExtra("path", f.absolutePath).putExtra("edit", true))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 11 || res != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { c -> for (i in 0 until c.itemCount) uris.add(c.getItemAt(i).uri) } ?: data.data?.let { uris.add(it) }
        val to = dir
        val (d, set) = progress(t("Adding files", "جارٍ إضافة الملفات"))
        Thread {
            var last: File? = null; var bad = 0
            uris.forEachIndexed { i, u ->
                try {
                    var name = "file"
                    contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: name }
                    val target = Fs.freeName(to, Fs.cleanName(name))
                    set("${i + 1}/${uris.size} · ${target.name}", i * 100 / uris.size)
                    contentResolver.openInputStream(u)!!.use { inp -> target.outputStream().use { inp.copyTo(it, 256 * 1024) } }
                    last = target
                } catch (e: Exception) { bad++ }
            }
            main.post {
                d.dismiss()
                toast(if (bad > 0) t("$bad files could not be added", "تعذّرت إضافة $bad ملف") else t("Added ${uris.size} files ✓", "تمت إضافة ${uris.size} ملف ✓"))
                load(to, keep = true, select = last?.absolutePath)
            }
        }.start()
    }

    /** Folder sizes: measured in the background, one folder at a time, and remembered. */
    private fun measureFolders() {
        if (!showSizes) return
        val job = ++sizeJob
        val dirs = items.filter { it.isDirectory && !dirSizes.containsKey(it.absolutePath) }
        if (dirs.isEmpty()) return
        Thread {
            for (d in dirs) {
                if (job != sizeJob) return@Thread
                dirSizes[d.absolutePath] = Fs.sizeOf(d)
                main.post { if (job == sizeJob) adapter.notifyDataSetChanged() }
            }
            main.post { if (job == sizeJob && sort == 2 && searching == null) { items = sorted(items); adapter.notifyDataSetChanged() } }
        }.start()
    }

    private fun showVirtual(title: String, files: List<File>) {
        sizeJob++
        searching = title; items = files; sel.clear()
        adapter.notifyDataSetChanged(); refreshBars()
        pathTv.text = title
        empty.text = t("Nothing here", "لا يوجد شيء"); empty.gone(items.isNotEmpty())
        if (items.isNotEmpty()) list.setSelection(0)
    }

    private fun showRecent() {
        val l = (prefs.getString("recent", "") ?: "").split('\n').filter { it.isNotEmpty() }.map { File(it) }.filter { it.exists() }
        showVirtual(t("🕘 Recent files", "🕘 الملفات الأخيرة"), l)
    }

    private fun openFile(f: File) {
        val old = (prefs.getString("recent", "") ?: "").split('\n').filter { it.isNotEmpty() && it != f.absolutePath }
        prefs.edit().putString("recent", (listOf(f.absolutePath) + old).take(40).joinToString("\n")).apply()
        when {
            Zip.isZip(f) -> zipMenu(f)
            ViewerActivity.kind(f) != null -> startActivity(Intent(this, ViewerActivity::class.java).putExtra("path", f.absolutePath))
            else -> Fs.open(this, f)
        }
    }

    private fun toggleFav() {
        val ps = sel.map { it.absolutePath }.toSet()
        val cur = favs
        favs = if (ps.all { it in cur }) cur - ps else cur + ps
        toast(if (ps.all { it in favs }) t("Added to favourites ★", "أُضيفت إلى المفضلة ★") else t("Removed from favourites", "أُزيلت من المفضلة"))
        sel.clear(); refreshBars(); adapter.notifyDataSetChanged(); buildPlaces()
    }

    // ------------------------------------------------------------------ zip

    private fun zipSelected() {
        val src = sel.toList()
        val base = if (src.size == 1) src[0].nameWithoutExtension.ifEmpty { src[0].name } else t("Archive", "ملفات")
        ask(t("Zip file name", "اسم الملف المضغوط"), base) { n ->
            val target = Fs.freeName(dir, Fs.cleanName(n).removeSuffix(".zip") + ".zip")
            val total = src.sumOf { Fs.sizeOf(it) }.coerceAtLeast(1)
            val (d, set) = progress(t("Compressing", "جارٍ الضغط"))
            Thread {
                val ok = try { Zip.create(src, target) { name, done -> set("$name · ${done.human()} / ${total.human()}", (done * 100 / total).toInt()) }; true }
                         catch (e: Exception) { target.delete(); false }
                main.post {
                    d.dismiss()
                    toast(if (ok) t("Created ${target.name} · ${target.length().human()}", "تم إنشاء ${target.name} · ${target.length().human()}") else t("Could not create the zip", "تعذّر إنشاء الملف المضغوط"))
                    load(dir, select = target.absolutePath)
                }
            }.start()
        }
    }

    private fun zipMenu(f: File) {
        val (d, _) = progress(t("Reading", "جارٍ القراءة"))
        Thread {
            val entries = try { Zip.list(f) } catch (e: Exception) { null }
            main.post {
                d.dismiss()
                if (entries == null) {
                    dialog().setTitle(f.name).setMessage(t("This zip is damaged or uses a format Sahl Files can't read (password or RAR/7z).", "هذا الملف تالف أو بصيغة لا يقرؤها سهل للملفات (كلمة مرور أو RAR/7z)."))
                        .setPositiveButton(t("Open with…", "فتح بواسطة…")) { _, _ -> Fs.open(this, f) }.setNegativeButton(t("Close", "إغلاق"), null).show()
                    return@post
                }
                val files = entries.filter { !it.dir }
                val total = files.sumOf { it.size.coerceAtLeast(0) }
                val preview = files.take(12).joinToString("\n") { "• ${it.name}" } + if (files.size > 12) "\n…" else ""
                val folder = File(f.parentFile, f.nameWithoutExtension)
                dialog().setTitle("🗜 ${f.name}")
                    .setMessage(t("${files.size} files · ${total.human()} when extracted\n\n", "${files.size} ملف · ${total.human()} بعد الفك\n\n") + preview)
                    .setPositiveButton(t("Extract all", "فك الكل")) { _, _ -> unzip(f, Fs.freeName(f.parentFile!!, folder.name), null, total) }
                    .setNeutralButton(t("Choose files", "اختيار ملفات")) { _, _ -> pickFromZip(f, files, folder) }
                    .setNegativeButton(t("Open with…", "فتح بواسطة…")) { _, _ -> Fs.open(this, f) }
                    .show()
            }
        }.start()
    }

    private fun pickFromZip(f: File, files: List<Zip.Entry>, folder: File) {
        val shown = files.take(500)
        val checked = BooleanArray(shown.size)
        dialog().setTitle(t("Choose files to extract", "اختر الملفات لفكّها"))
            .setMultiChoiceItems(shown.map { "${it.name}  (${it.size.coerceAtLeast(0).human()})" }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(t("Extract", "فك")) { _, _ ->
                val only = shown.filterIndexed { i, _ -> checked[i] }
                if (only.isEmpty()) toast(t("Nothing chosen", "لم يتم اختيار شيء"))
                else unzip(f, Fs.freeName(f.parentFile!!, folder.name), only.map { it.name }.toSet(), only.sumOf { it.size.coerceAtLeast(0) })
            }
            .setNegativeButton(t("Cancel", "إلغاء"), null).show()
    }

    private fun unzip(f: File, dest: File, only: Set<String>?, total: Long) {
        val (d, set) = progress(t("Extracting", "جارٍ الفك"))
        val tot = total.coerceAtLeast(1)
        Thread {
            val n = try { Zip.extract(f, dest, only) { name, done -> set("$name · ${done.human()}", (done * 100 / tot).toInt().coerceAtMost(100)) } } catch (e: Exception) { -1 }
            main.post {
                d.dismiss()
                if (n < 0) toast(t("Could not extract — the zip may be damaged", "تعذّر الفك — قد يكون الملف تالفًا"))
                else { toast(t("Extracted $n files ✓", "تم فك $n ملف ✓")); load(dest) }
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
        if (f.isDirectory) load(f) else openFile(f)
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
                showVirtual(t("🔍 “$q” · ${res.size} results", "🔍 «$q» · ${res.size} نتيجة"), sorted(res))
            }
        }.start()
    }

    private fun menu() {
        val sorts = arrayOf(t("Name", "الاسم"), t("Newest", "الأحدث"), t("Largest", "الأكبر"), t("Type", "النوع"))
        val opts = arrayOf(
            t("Sort: ", "الترتيب: ") + sorts[sort],
            if (showHidden) t("Hide hidden files", "إخفاء الملفات المخفية") else t("Show hidden files", "إظهار الملفات المخفية"),
            t("📊 Storage map", "📊 خريطة المساحة"),
            if (showSizes) t("Hide folder sizes", "إخفاء أحجام المجلدات") else t("Show folder sizes", "إظهار أحجام المجلدات"),
            t("Check for updates", "البحث عن تحديث"),
            t("About", "حول التطبيق"))
        dialog().setItems(opts) { _, w ->
            when (w) {
                0 -> dialog().setTitle(t("Sort by", "ترتيب حسب")).setSingleChoiceItems(sorts, sort) { dd, s -> sort = s; dd.dismiss(); load(dir, keep = true) }.show()
                1 -> { showHidden = !showHidden; load(dir, keep = true) }
                2 -> startActivity(Intent(this, StorageActivity::class.java))
                3 -> { showSizes = !showSizes; if (!showSizes) dirSizes.clear(); load(dir, keep = true) }
                4 -> UpdatePrompt.checkNow(this)
                5 -> dialog().setTitle(t("Sahl Files", "سهل للملفات"))
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
        var favSet: Set<String> = emptySet()
        override fun notifyDataSetChanged() { favSet = favs; super.notifyDataSetChanged() }
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val row = (convert as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
                val frame = FrameLayout(this@MainActivity).apply {
                    background = round(C.CARD, 12)
                    addView(ImageView(this@MainActivity).apply {
                        scaleType = ImageView.ScaleType.CENTER_INSIDE; tag = "ic"
                    }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
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
            val ic = row.findViewWithTag<ImageView>("ic"); val img = row.findViewWithTag<ImageView>("img")
            row.findViewWithTag<TextView>("name").text = if (f.absolutePath in favSet) "★ " + f.name else f.name
            row.findViewWithTag<TextView>("sub").text = when {
                searching != null -> f.parent?.removePrefix(Fs.root.absolutePath)?.ifEmpty { "/" } ?: ""
                f.isDirectory -> t("${f.list()?.size ?: 0} items", "${f.list()?.size ?: 0} عنصر") +
                    (if (showSizes) " · " + (dirSizes[f.absolutePath]?.human() ?: "…") else "") + " · " + df.format(Date(f.lastModified()))
                else -> f.length().human() + " · " + df.format(Date(f.lastModified()))
            }
            val on = f in sel
            row.findViewWithTag<TextView>("check").text = if (on) "✔" else ""
            row.background = if (on) round(C.SEL, 14) else null
            ic.setImageResource(Fs.iconRes(f))
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


    companion object {
        /** A ready list of files to show (from the Storage map). */
        @Volatile var virtual: Pair<String, List<File>>? = null
    }
}
