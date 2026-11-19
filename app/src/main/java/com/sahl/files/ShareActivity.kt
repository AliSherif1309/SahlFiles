package com.sahl.files

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.io.File

/**
 * Sahl Share — replaces LocalSend. Sends to / receives from LocalSend on any device,
 * other phones with Sahl Files, and any browser (address + code).
 */
class ShareActivity : Activity(), Lan.Events {
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("share", 0) }
    private var items: List<Lan.Item> = emptyList()
    private var text: String? = null

    private lateinit var meTv: TextView
    private lateinit var webTv: TextView
    private lateinit var sendTv: TextView
    private lateinit var peersBox: LinearLayout
    private lateinit var statusTv: TextView
    private var recvDialog: Pair<AlertDialog, (String, Int) -> Unit>? = null
    private var asking: AlertDialog? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        C.init(this)
        if (intent.getBooleanExtra("stop", false)) { ShareService.stop(this); finish(); return }
        take(intent)
        build()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        ShareService.start(this)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        if (i.getBooleanExtra("stop", false)) { ShareService.stop(this); finish(); return }
        take(i); refreshSend()
    }

    override fun onResume() {
        super.onResume()
        ShareService.ui = this
        Lan.onPeers = { main.post { drawPeers() } }
        main.postDelayed({ refreshMe(); ShareService.inst?.discovery?.announce(); webShare() }, 600)
        drawPeers()
        ShareService.waiting.peek()?.let { ask(it) }
    }

    override fun onPause() {
        super.onPause()
        if (ShareService.ui === this) ShareService.ui = null
        Lan.onPeers = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations && !prefs.getBoolean("background", false) && ShareService.waiting.isEmpty()) ShareService.stop(this)
    }

    /** Files handed over by Sahl Files (paths) or by any app's Share menu (streams / text). */
    private fun take(i: Intent) {
        i.getStringArrayListExtra("paths")?.let { items = Lan.fileItems(it); text = null; return }
        val uris = ArrayList<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> {
                i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
                if (uris.isEmpty()) i.getStringExtra(Intent.EXTRA_TEXT)?.let { text = it; items = emptyList(); return }
            }
            Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        }
        if (uris.isNotEmpty()) { items = uris.mapNotNull { Lan.uriItem(this, it) }; text = null }
    }

    // ---------------------------------------------------------------- layout

    private fun build() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(14), dp(20)) }

        // this phone
        val me = cardBox()
        meTv = label("", 16f, C.TEXT, true)
        statusTv = label("", 13f, C.SUB)
        me.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(label("📱", 28f), lp(WRAP_CONTENT).margins(this@ShareActivity, r = 12))
            addView(LinearLayout(this@ShareActivity).apply { orientation = LinearLayout.VERTICAL; addView(meTv); addView(statusTv) }, lp(0, WRAP_CONTENT, 1f))
            addView(iconBtn("✎") { ask(t("Name shown to other devices", "الاسم الظاهر للأجهزة الأخرى"), Lan.alias(this@ShareActivity)) { Lan.setAlias(this@ShareActivity, it); refreshMe(); ShareService.inst?.discovery?.announce() } })
        })
        me.addView(toggle(t("Keep receiving after I close this screen", "استمر في الاستلام بعد إغلاق هذه الشاشة"), "background"))
        me.addView(toggle(t("Accept files without asking", "قبول الملفات دون سؤال"), "quick"))
        col.addView(me, lp().margins(this, b = 12))

        // what to send
        val send = cardBox()
        send.addView(label(t("To send", "للإرسال"), 15f, C.GREEN, true))
        sendTv = label("", 14f, C.SUB).apply { setPadding(0, dp(4), 0, dp(8)) }
        send.addView(sendTv)
        send.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button(t("Files", "ملفات"), false) { pick() }, lp(0, WRAP_CONTENT, 1f).margins(this@ShareActivity, r = 6))
            addView(button(t("Text", "نص"), false) {
                ask(t("Message to send", "الرسالة")) { text = it; items = emptyList(); refreshSend() }
            }, lp(0, WRAP_CONTENT, 1f).margins(this@ShareActivity, r = 6))
            addView(button(t("Clear", "مسح"), false) { items = emptyList(); text = null; refreshSend() }, lp(0, WRAP_CONTENT, 1f))
        })
        col.addView(send, lp().margins(this, b = 12))

        // nearby
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(6), 0, dp(4))
            addView(label(t("Nearby devices", "الأجهزة القريبة"), 16f, C.TEXT, true), lp(0, WRAP_CONTENT, 1f))
            addView(iconBtn("↻") { Lan.peers.clear(); drawPeers(); ShareService.inst?.discovery?.announce() })
            addView(iconBtn("⌕") { scan() })
            addView(iconBtn("＋") { addByIp() })
        })
        peersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(peersBox, lp().margins(this, b = 12))

        // browser
        val web = cardBox()
        web.addView(label(t("From a browser (PC, TV, iPhone…)", "من المتصفح (كمبيوتر، تلفاز، آيفون…)"), 15f, C.GREEN, true))
        webTv = label("", 15f, C.TEXT).apply { setPadding(0, dp(6), 0, dp(4)); setTextIsSelectable(true) }
        web.addView(webTv)
        web.addView(label(t("Open the address on the same Wi-Fi and type the code. They can send you files, and download what is in “To send”.",
            "افتح العنوان على نفس شبكة الواي فاي واكتب الرمز. يمكنهم إرسال ملفات إليك وتنزيل ما في «للإرسال»."), 13f, C.SUB))
        col.addView(web, lp().margins(this, b = 12))

        col.addView(button(t("📂  Open received files", "📂  فتح الملفات المستلمة"), false) {
            startActivity(Intent(this, MainActivity::class.java).putExtra("path", Fs.received.apply { mkdirs() }.absolutePath).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        })
        col.addView(label(t("Works with the LocalSend app on Windows, Mac, Linux, iPhone and Android. Same Wi-Fi only; nothing goes through the internet.",
            "يعمل مع تطبيق LocalSend على ويندوز وماك ولينكس وآيفون وأندرويد. على نفس الواي فاي فقط؛ لا يمر شيء عبر الإنترنت."), 12f, C.SUB)
            .apply { setPadding(dp(6), dp(14), dp(6), 0); gravity = Gravity.CENTER })

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(topBar(t("Sahl Share", "سهل للمشاركة"), true))
            addView(ScrollView(this@ShareActivity).apply { addView(col) }, lp(h = 0, weight = 1f))
        })
        refreshMe(); refreshSend()
    }

    private fun toggle(text: String, key: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0)
        addView(label(text, 14f), lp(0, WRAP_CONTENT, 1f))
        addView(Switch(this@ShareActivity).apply {
            isChecked = prefs.getBoolean(key, false)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
        })
    }

    private fun refreshMe() {
        if (!::meTv.isInitialized) return
        meTv.text = Lan.alias(this)
        val ip = Lan.ip()
        statusTv.text = if (ip == "?") t("Not on Wi-Fi — connect to Wi-Fi or turn on hotspot", "غير متصل بالواي فاي — اتصل بشبكة أو شغّل نقطة الاتصال")
                        else t("Visible on Wi-Fi · $ip", "ظاهر على الواي فاي · $ip")
        val code = ShareService.inst?.server?.code ?: "…"
        webTv.text = if (ip == "?") "—" else "http://$ip:${Lan.PORT}\n" + t("Code: ", "الرمز: ") + code
    }

    private fun refreshSend() {
        if (!::sendTv.isInitialized) return
        sendTv.text = when {
            text != null -> "💬 " + text!!.take(80)
            items.isEmpty() -> t("Nothing yet. Pick files or text, then tap a device below.", "لا شيء بعد. اختر ملفات أو نصًا ثم اضغط على جهاز بالأسفل.")
            items.size == 1 -> "📄 ${items[0].name} · ${items[0].size.human()}"
            else -> t("${items.size} files · ${items.sumOf { it.size }.human()}", "${items.size} ملف · ${items.sumOf { it.size }.human()}")
        }
        webShare()
    }

    private fun webShare() { ShareService.inst?.server?.webShare = items }

    private fun drawPeers() {
        if (!::peersBox.isInitialized) return
        peersBox.removeAllViews()
        val list = Lan.peers.values.sortedBy { it.alias.lowercase() }
        if (list.isEmpty()) {
            peersBox.addView(label(t("Looking… Open LocalSend or Sahl Share on the other device.\nNot showing? Tap ⌕ to search the network or ＋ to add by address.",
                "جارٍ البحث… افتح LocalSend أو سهل للمشاركة على الجهاز الآخر.\nلا يظهر؟ اضغط ⌕ للبحث في الشبكة أو ＋ للإضافة بالعنوان."), 13f, C.SUB).apply { setPadding(dp(6), dp(8), dp(6), dp(8)) })
            return
        }
        for (p in list) peersBox.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = ripple(round(C.CARD, 16)); setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(label(p.icon, 26f), lp(WRAP_CONTENT).margins(this@ShareActivity, r = 12))
            addView(LinearLayout(this@ShareActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(p.alias, 15f, C.TEXT, true)); addView(label(p.ip, 12f, C.SUB))
            }, lp(0, WRAP_CONTENT, 1f))
            addView(label(t("Send ➜", "إرسال ➜"), 14f, C.GREEN, true))
            setOnClickListener { sendTo(p) }
        }, lp().margins(this, b = 8))
    }

    // ---------------------------------------------------------------- actions

    private fun pick() = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 5)

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 5 || res != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { c -> for (i in 0 until c.itemCount) uris.add(c.getItemAt(i).uri) } ?: data.data?.let { uris.add(it) }
        Thread { val its = uris.mapNotNull { Lan.uriItem(this, it) }; main.post { items = its; text = null; refreshSend() } }.start()
    }

    private fun sendTo(p: Lan.Peer) {
        val msg = text
        if (msg == null && items.isEmpty()) { toast(t("Pick files or text first", "اختر ملفات أو نصًا أولًا")); return }
        val (d, set) = progress(t("Sending to ${p.alias}", "إرسال إلى ${p.alias}"))
        val its = items
        Thread {
            val result = if (msg != null) Lan.sendText(this, p, msg)
                else Lan.send(this, p, its) { name, done, total -> set("$name · ${done.human()} / ${total.human()}", if (done == 0L) -1 else (done * 100 / total).toInt()) }
            main.post { d.dismiss(); dialog().setMessage(result).setPositiveButton(t("OK", "موافق"), null).show() }
        }.start()
    }

    private fun scan() {
        val (d, set) = progress(t("Searching this Wi-Fi", "البحث في شبكة الواي فاي"))
        set(t("Checking every address… a few seconds", "فحص كل العناوين… ثوانٍ قليلة"), -1)
        Thread { Lan.scan(this); main.post { d.dismiss(); drawPeers(); if (Lan.peers.isEmpty()) toast(t("No devices found", "لم يُعثر على أجهزة")) } }.start()
    }

    private fun addByIp() = ask(t("Device address (e.g. 192.168.1.20)", "عنوان الجهاز (مثل 192.168.1.20)")) { ip ->
        Thread { val p = Lan.probe(this, ip.trim(), 3000); main.post { if (p == null) toast(t("No LocalSend device at $ip", "لا يوجد جهاز LocalSend على $ip")) else drawPeers() } }.start()
    }

    // ---------------------------------------------------------------- events (on screen)

    override fun ask(r: Lan.Request) {
        if (asking?.isShowing == true) return
        val list = r.names.take(8).joinToString("\n") { "• $it" } + if (r.names.size > 8) "\n…" else ""
        asking = dialog().setTitle(t("${r.from} wants to send ${r.names.size} files (${r.total.human()})", "${r.from} يريد إرسال ${r.names.size} ملف (${r.total.human()})"))
            .setMessage(list).setCancelable(false)
            .setPositiveButton(t("Accept", "قبول")) { _, _ -> ShareService.waiting.remove(r); r.answer(true) }
            .setNegativeButton(t("Decline", "رفض")) { _, _ -> ShareService.waiting.remove(r); r.answer(false) }
            .show()
    }

    override fun progress(name: String, done: Long, total: Long) {
        main.post {
            val pd = recvDialog ?: progress(t("Receiving", "جارٍ الاستلام")).also { recvDialog = it }
            pd.second("$name · ${done.human()} / ${total.human()}", if (total > 0) (done * 100 / total).toInt() else -1)
        }
    }

    override fun received(f: File) {}

    override fun finished(count: Int, from: String) {
        recvDialog?.first?.dismiss(); recvDialog = null
        dialog().setTitle(t("Received $count files ✓", "تم استلام $count ملف ✓"))
            .setMessage(t("From $from · saved in Download/Sahl Files", "من $from · محفوظة في التنزيلات/Sahl Files"))
            .setPositiveButton(t("Open", "فتح")) { _, _ ->
                startActivity(Intent(this, MainActivity::class.java).putExtra("path", Fs.received.absolutePath).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }
            .setNegativeButton(t("Close", "إغلاق"), null).show()
    }

    override fun message(from: String, text: String) {
        dialog().setTitle(t("Message from $from", "رسالة من $from")).setMessage(text)
            .setPositiveButton(t("Copy", "نسخ")) { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("text", text)); toast(t("Copied ✓", "تم النسخ ✓"))
            }
            .setNegativeButton(t("Close", "إغلاق"), null).show()
    }
}
