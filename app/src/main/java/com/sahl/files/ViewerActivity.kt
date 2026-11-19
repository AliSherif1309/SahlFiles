package com.sahl.files

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.Executors

/**
 * Built-in viewers: pictures (pinch to zoom, next / previous), text and code, web pages (HTML),
 * PDF, video and music. "Open with…" is always one tap away.
 */
class ViewerActivity : Activity() {
    companion object {
        private val text = setOf("txt", "log", "md", "markdown", "json", "xml", "csv", "tsv", "ini", "cfg", "conf", "yml", "yaml", "properties",
            "kt", "kts", "java", "py", "js", "ts", "css", "c", "h", "cpp", "cs", "sh", "bat", "ps1", "sql", "gradle", "srt", "vtt", "nfo", "toml", "env")
        private val html = setOf("html", "htm", "xhtml", "svg", "mht")
        private val image = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
        private val video = setOf("mp4", "mkv", "webm", "3gp", "m4v", "mov")
        private val audio = setOf("mp3", "m4a", "ogg", "wav", "flac", "aac", "opus", "amr")

        fun kind(f: File): String? = when (f.extension.lowercase()) {
            in text -> "text"; in html -> "html"; in image -> "image"; in video -> "video"; in audio -> "audio"; "pdf" -> "pdf"
            else -> if (f.extension.isEmpty() && f.length() in 1..2_000_000 && looksText(f)) "text" else null
        }

        private fun looksText(f: File): Boolean = try {
            val b = f.inputStream().use { i -> ByteArray(2048).let { it.copyOf(i.read(it).coerceAtLeast(0)) } }
            b.isNotEmpty() && b.none { it.toInt() == 0 }
        } catch (_: Exception) { false }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var file: File
    private lateinit var body: FrameLayout
    private lateinit var title: TextView
    private var siblings: List<File> = emptyList()
    private var pdf: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var wrap = true

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        C.init(this)
        file = File(intent.getStringExtra("path") ?: run { finish(); return })
        title = label("", 17f, C.TEXT, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE; setPadding(dp(8), 0, dp(8), 0) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(8), dp(6), dp(4))
            addView(iconBtn(if (isAr) "→" else "←") { finish() })
            addView(title, lp(0, WRAP_CONTENT, 1f))
            addView(iconBtn("↗") { Fs.share(this@ViewerActivity, listOf(file)) })
            addView(TextView(this@ViewerActivity).apply {
                text = t("Open with…", "فتح بواسطة…"); textSize = 13f; setTextColor(C.GREEN)
                setPadding(dp(12), dp(8), dp(12), dp(8)); background = ripple(round(Color.TRANSPARENT, 18, C.GREEN))
                setOnClickListener { Fs.open(this@ViewerActivity, file) }
            })
        }
        body = FrameLayout(this)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar); addView(body, lp(h = 0, weight = 1f))
        })
        show(file)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { pdf?.close(); pfd?.close() } catch (_: Exception) {}
    }

    private fun show(f: File) {
        file = f; title.text = f.name
        body.removeAllViews()
        try { pdf?.close(); pfd?.close() } catch (_: Exception) {}; pdf = null; pfd = null
        when (kind(f)) {
            "text" -> showText(f)
            "html" -> showWeb(Uri.fromFile(f).toString(), html = true)
            "image" -> showImage(f)
            "video", "audio" -> showMedia(f)
            "pdf" -> showPdf(f)
            else -> { Fs.open(this, f); finish() }
        }
    }

    private fun message(s: String) = body.addView(label(s, 15f, C.SUB).apply { gravity = Gravity.CENTER; setPadding(dp(30), dp(40), dp(30), 0) })

    // ---------------------------------------------------------------- text & code

    private fun showText(f: File) {
        message(t("Opening…", "جارٍ الفتح…"))
        Thread {
            val limit = 2L * 1024 * 1024
            val bytes = f.inputStream().use { i -> i.readNBytesCompat(minOf(f.length(), limit).toInt()) }
            var s = String(bytes, Charsets.UTF_8)
            if (s.count { it == '�' } > 3) s = String(bytes, Charset.forName("windows-1256"))   // older Arabic files
            if (f.extension.equals("json", true)) s = try {
                val tr = s.trim(); if (tr.startsWith("[")) JSONArray(tr).toString(2) else JSONObject(tr).toString(2)
            } catch (_: Exception) { s }
            val cut = f.length() > limit
            main.post { if (!isFinishing) { if (intent.getBooleanExtra("edit", false) && !cut) { intent.removeExtra("edit"); editText(s) } else drawText(s, cut) } }
        }.start()
    }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val b = ByteArray(n); var off = 0
        while (off < n) { val r = read(b, off, n - off); if (r < 0) break; off += r }
        return if (off == n) b else b.copyOf(off)
    }

    private fun drawText(s: String, cut: Boolean) {
        body.removeAllViews()
        val code = file.extension.lowercase() !in setOf("txt", "md", "markdown", "srt", "vtt", "nfo", "")
        val tv = TextView(this).apply {
            text = s; textSize = if (code) 13f else 16f; setTextColor(C.TEXT); setTextIsSelectable(true)
            typeface = if (code) Typeface.MONOSPACE else Typeface.DEFAULT
            setLineSpacing(0f, if (code) 1.1f else 1.35f)
            setPadding(dp(16), dp(12), dp(16), dp(40))
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (cut) addView(label(t("Large file — showing the first 2 MB. Use “Open with…” for the rest.", "ملف كبير — يُعرض أول 2 م.ب فقط. استخدم «فتح بواسطة…» للباقي."), 13f, C.GOLD).apply { setPadding(dp(16), dp(8), dp(16), 0) })
            addView(tv)
        }
        val scroll = ScrollView(this)
        if (wrap) scroll.addView(col) else scroll.addView(HorizontalScrollView(this).apply { addView(col) })
        body.addView(scroll)
        // edit (files up to 2 MB)
        if (!cut) body.addView(TextView(this).apply {
            text = t("✎ Edit", "✎ تعديل"); textSize = 13f; setTextColor(Color.WHITE)
            setPadding(dp(16), dp(9), dp(16), dp(9)); background = round(C.GREEN, 18)
            setOnClickListener { editText(s) }
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(16), dp(16), dp(16), dp(16)) })
        // wrap toggle for code / logs
        if (code) body.addView(TextView(this).apply {
            text = if (wrap) t("No wrap", "بدون التفاف") else t("Wrap", "التفاف"); textSize = 12f; setTextColor(Color.WHITE)
            setPadding(dp(14), dp(8), dp(14), dp(8)); background = round(C.GREEN, 18)
            setOnClickListener { wrap = !wrap; drawText(s, cut) }
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { setMargins(dp(16), dp(16), dp(16), dp(16)) })
    }

    private fun editText(original: String) {
        body.removeAllViews()
        val code = file.extension.lowercase() !in setOf("txt", "md", "markdown", "srt", "vtt", "nfo", "")
        val ed = android.widget.EditText(this).apply {
            setText(original); textSize = if (code) 13f else 16f; setTextColor(C.TEXT)
            typeface = if (code) Typeface.MONOSPACE else Typeface.DEFAULT
            gravity = Gravity.TOP or Gravity.START; background = null
            setPadding(dp(16), dp(12), dp(16), dp(80))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            requestFocus()
        }
        body.addView(ed, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button(t("Cancel", "إلغاء"), false) {
                if (ed.text.toString() == original) drawText(original, false)
                else confirm(t("Discard changes?", "تجاهل التعديلات؟"), "", t("Discard", "تجاهل")) { drawText(original, false) }
            }, lp(WRAP_CONTENT).margins(this@ViewerActivity, r = 10))
            addView(button(t("Save", "حفظ")) {
                val txt = ed.text.toString()
                try {
                    val tmp = File(file.parentFile, "." + file.name + ".saving")
                    tmp.writeText(txt, Charsets.UTF_8)
                    if (!tmp.renameTo(file)) { file.writeText(txt, Charsets.UTF_8); tmp.delete() }
                    toast(t("Saved ✓", "تم الحفظ ✓")); drawText(txt, false)
                } catch (e: Exception) { toast(t("Could not save here", "تعذّر الحفظ هنا")) }
            })
        }
        body.addView(bar, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { setMargins(dp(16), dp(16), dp(16), dp(16)) })
    }

    // ---------------------------------------------------------------- web pages & pictures (WebView gives smooth pinch-zoom)

    private fun web() = WebView(this).apply {
        setBackgroundColor(if (C.night) Color.BLACK else Color.WHITE)
        settings.apply {
            builtInZoomControls = true; displayZoomControls = false
            loadWithOverviewMode = true; useWideViewPort = true
            allowFileAccess = true
            javaScriptEnabled = false        // local pages are shown, not run: safer
        }
    }

    private fun showWeb(url: String, html: Boolean) {
        val w = web()
        body.addView(w, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        w.loadUrl(url)
        if (html) body.addView(TextView(this).apply {
            text = t("Turn on scripts", "تشغيل السكربتات"); textSize = 12f; setTextColor(Color.WHITE)
            setPadding(dp(14), dp(8), dp(14), dp(8)); background = round(C.GREEN, 18)
            setOnClickListener {
                confirm(t("Run this page's scripts?", "تشغيل سكربتات الصفحة؟"), t("Only for pages you trust.", "فقط للصفحات التي تثق بها."), t("Run", "تشغيل")) {
                    w.settings.javaScriptEnabled = true; w.reload(); visibility = View.GONE
                }
            }
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { setMargins(dp(16), dp(16), dp(16), dp(16)) })
    }

    private fun showImage(f: File) {
        if (siblings.isEmpty()) siblings = (f.parentFile?.listFiles() ?: emptyArray())
            .filter { kind(it) == "image" }.sortedBy { it.name.lowercase() }
        val bg = if (C.night) "#000" else "#111"
        val page = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=8'>" +
            "<style>html,body{margin:0;height:100%;background:$bg;display:flex;align-items:center;justify-content:center}" +
            "img{max-width:100%;max-height:100%;object-fit:contain;image-orientation:from-image}</style></head>" +
            "<body><img src='${Uri.fromFile(f)}'></body></html>"
        val w = web().apply { setBackgroundColor(Color.BLACK); settings.useWideViewPort = false }
        body.addView(w, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        w.loadDataWithBaseURL(Uri.fromFile(f.parentFile ?: f).toString() + "/", page, "text/html", "utf-8", null)

        val i = siblings.indexOfFirst { it.absolutePath == f.absolutePath }
        if (siblings.size > 1 && i >= 0) {
            fun nav(sym: String, to: Int, g: Int) = body.addView(TextView(this).apply {
                text = sym; textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                background = round(0x66000000, 30)
                setOnClickListener { show(siblings[(to + siblings.size) % siblings.size]) }
            }, FrameLayout.LayoutParams(dp(52), dp(52), g or Gravity.CENTER_VERTICAL).apply { setMargins(dp(10), 0, dp(10), 0) })
            nav("‹", i - 1, Gravity.LEFT); nav("›", i + 1, Gravity.RIGHT)
            body.addView(label("${i + 1} / ${siblings.size}", 13f, Color.WHITE).apply {
                setPadding(dp(12), dp(4), dp(12), dp(4)); background = round(0x66000000, 14)
            }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(18) })
        }
    }

    // ---------------------------------------------------------------- video & music

    private fun showMedia(f: File) {
        body.setBackgroundColor(Color.BLACK)
        val v = VideoView(this)
        val mc = MediaController(this).apply { setAnchorView(v) }
        v.setMediaController(mc)
        v.setVideoURI(Uri.fromFile(f))
        v.setOnPreparedListener { it.isLooping = false; v.start(); mc.show(0) }
        v.setOnErrorListener { _, _, _ -> toast(t("Can't play this here — opening another app", "لا يمكن تشغيله هنا — فتح تطبيق آخر")); Fs.open(this, f); true }
        body.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
        if (kind(f) == "audio") body.addView(label("🎵\n${f.nameWithoutExtension}", 22f, Color.WHITE).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
        body.setOnClickListener { mc.show(0) }
    }

    override fun onPause() { super.onPause(); (body.getChildAt(0) as? VideoView)?.pause() }

    // ---------------------------------------------------------------- PDF

    private val pages = LruCache<Int, Bitmap>(8)
    private val renderPool = Executors.newSingleThreadExecutor()

    private fun showPdf(f: File) {
        val r = try {
            pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY); PdfRenderer(pfd!!)
        } catch (e: Exception) { message(t("This PDF is protected or damaged. Use “Open with…”.", "ملف PDF محمي أو تالف. استخدم «فتح بواسطة…».")); return }
        pdf = r; pages.evictAll()
        val width = resources.displayMetrics.widthPixels
        val lv = ListView(this).apply {
            divider = null; setBackgroundColor(if (C.night) 0xFF222222.toInt() else 0xFFE0E0E0.toInt())
            adapter = object : BaseAdapter() {
                override fun getCount() = r.pageCount
                override fun getItem(p: Int) = p
                override fun getItemId(p: Int) = p.toLong()
                override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
                    val iv = (convert as? ImageView) ?: ImageView(this@ViewerActivity).apply {
                        adjustViewBounds = true; setPadding(0, dp(4), 0, dp(4))
                    }
                    iv.tag = p
                    val cached = pages.get(p)
                    if (cached != null) iv.setImageBitmap(cached)
                    else {
                        iv.setImageBitmap(null); iv.minimumHeight = (width * 1.4).toInt()
                        renderPool.execute {
                            val bmp = synchronized(r) {
                                if (pdf !== r) return@execute
                                r.openPage(p).use { pg ->
                                    val h = (width.toFloat() * pg.height / pg.width).toInt()
                                    Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).also {
                                        it.eraseColor(Color.WHITE); pg.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    }
                                }
                            }
                            pages.put(p, bmp)
                            main.post { if (iv.tag == p) { iv.minimumHeight = 0; iv.setImageBitmap(bmp) } }
                        }
                    }
                    return iv
                }
            }
        }
        body.addView(lv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        body.addView(label(t("${r.pageCount} pages", "${r.pageCount} صفحة"), 12f, Color.WHITE).apply {
            setPadding(dp(12), dp(4), dp(12), dp(4)); background = round(0x88000000.toInt(), 14)
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(16) })
    }
}
