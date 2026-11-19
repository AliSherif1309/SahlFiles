package com.sahl.files

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File
import java.text.DateFormat
import java.util.Date

/** The private Vault screen: unlock with fingerprint or PIN, add / open / restore / delete hidden files. */
class VaultActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var pendingAdd: List<String> = emptyList()
    private var entries: List<Vault.Entry> = emptyList()
    private val adapter = Adapter()
    private var openingOther = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)   // no screenshots, hidden in recent apps
        C.init(this)
        pendingAdd = intent.getStringArrayListExtra("add") ?: emptyList()
        Vault.lock(this)
        if (!Vault.isSetUp(this)) setupScreen() else lockedScreen()
    }

    override fun onStop() {
        super.onStop()
        // leaving the app locks the vault (but not while a picker / viewer we opened is in front)
        if (!openingOther && !isChangingConfigurations) { Vault.master = null; finish() }
    }

    override fun onResume() { super.onResume(); openingOther = false }

    override fun onDestroy() { super.onDestroy(); if (!isChangingConfigurations && !openingOther) Vault.master = null }

    // ---------------------------------------------------------------- setup & unlock

    private fun page(icon: String, title: String, text: String, vararg buttons: View) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            addView(label(icon, 60f).apply { gravity = Gravity.CENTER }, lp())
            addView(label(title, 24f, C.TEXT, true).apply { gravity = Gravity.CENTER }, lp().margins(this@VaultActivity, tp = 10))
            addView(label(text, 14f, C.SUB).apply { gravity = Gravity.CENTER }, lp().margins(this@VaultActivity, tp = 8, b = 20))
            buttons.forEach { addView(it, lp(WRAP_CONTENT).margins(this@VaultActivity, b = 10)) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(topBar(t("Vault", "الخزنة"), true))
            addView(box, lp(h = 0, weight = 1f))
        })
    }

    private fun setupScreen() = page("🔒", t("Your private Vault", "خزنتك الخاصة"),
        t("Files you put here are encrypted on this phone and hidden from the gallery and other apps.\n\nChoose a PIN (at least 4 digits). If you forget it and fingerprint stops working, the files cannot be recovered — nobody can open them, not even us.",
          "الملفات التي تضعها هنا تُشفَّر على هذا الهاتف وتُخفى عن المعرض والتطبيقات الأخرى.\n\nاختر رمزًا سريًا (4 أرقام على الأقل). إذا نسيته وتعطّلت البصمة فلا يمكن استرجاع الملفات — لا أحد يستطيع فتحها، حتى نحن."),
        button(t("Create PIN", "إنشاء الرمز")) {
            ask(t("New PIN", "الرمز الجديد"), password = true, numeric = true) { p1 ->
                if (p1.length < 4) { toast(t("Use at least 4 digits", "استخدم 4 أرقام على الأقل")); return@ask }
                ask(t("Repeat the PIN", "أعد كتابة الرمز"), password = true, numeric = true) { p2 ->
                    if (p1 != p2) { toast(t("The PINs don't match", "الرمزان غير متطابقين")); return@ask }
                    val (d, _) = progress(t("Creating the Vault", "إنشاء الخزنة"))
                    Thread {
                        Vault.setup(this, p1)
                        main.post { d.dismiss(); offerBio() }
                    }.start()
                }
            }
        })

    private fun canBio(): Boolean {
        val km = getSystemService(KeyguardManager::class.java)
        val bm = getSystemService(BiometricManager::class.java)
        return km.isDeviceSecure && bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun offerBio() {
        if (!canBio()) { opened(); return }
        dialog().setTitle(t("Unlock with fingerprint?", "الفتح بالبصمة؟"))
            .setMessage(t("You can still use the PIN any time.", "يمكنك دائمًا استخدام الرمز أيضًا."))
            .setPositiveButton(t("Yes", "نعم")) { _, _ -> turnOnBio { opened() } }
            .setNegativeButton(t("Not now", "ليس الآن")) { _, _ -> opened() }
            .setCancelable(false).show()
    }

    private fun prompt(onOk: () -> Unit) {
        val p = BiometricPrompt.Builder(this)
            .setTitle(t("Unlock the Vault", "فتح الخزنة"))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
        openingOther = true
        p.authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { openingOther = false; onOk() }
            override fun onAuthenticationError(code: Int, msg: CharSequence?) { openingOther = false }
        })
    }

    private fun turnOnBio(then: () -> Unit) {
        if (!Vault.prepareBioKey()) { toast(t("Set a screen lock first", "فعّل قفل الشاشة أولًا")); then(); return }
        prompt {
            toast(if (Vault.enableBio(this)) t("Fingerprint unlock is on ✓", "تم تفعيل الفتح بالبصمة ✓") else t("Could not turn on fingerprint", "تعذّر تفعيل البصمة"))
            then()
        }
    }

    private fun lockedScreen() {
        val bio = Vault.bioEnabled(this) && canBio()
        val buttons = ArrayList<View>()
        if (bio) buttons.add(button(t("Unlock with fingerprint", "الفتح بالبصمة")) { bioUnlock() })
        buttons.add(button(t("Use PIN", "استخدام الرمز"), primary = !bio) { pinUnlock() })
        page("🔒", t("Vault is locked", "الخزنة مقفلة"), t("${pendingAdd.size.takeIf { it > 0 }?.let { "$it files are waiting to be added.\n" } ?: ""}Unlock to continue.",
            "${pendingAdd.size.takeIf { it > 0 }?.let { "$it ملف بانتظار الإضافة.\n" } ?: ""}افتح للمتابعة."), *buttons.toTypedArray())
        if (bio) bioUnlock()
    }

    private fun bioUnlock() = prompt {
        if (Vault.unlockBio(this)) opened() else toast(t("Fingerprint key changed — use the PIN", "تغيّر مفتاح البصمة — استخدم الرمز"))
    }

    private var wrong = 0
    private fun pinUnlock() = ask(t("Vault PIN", "رمز الخزنة"), password = true, numeric = true) { pin ->
        val (d, _) = progress(t("Unlocking", "جارٍ الفتح"))
        Thread {
            val ok = Vault.unlockPin(this, pin)
            main.post {
                d.dismiss()
                if (ok) { wrong = 0; opened() }
                else {
                    wrong++
                    toast(t("Wrong PIN", "رمز خاطئ"))
                    if (wrong >= 5) { toast(t("Too many tries — wait 30 seconds", "محاولات كثيرة — انتظر 30 ثانية")); finish() }
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- unlocked

    private lateinit var info: TextView
    private lateinit var listView: ListView

    private fun opened() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(topBar(t("Vault", "الخزنة"), true, "⚙" to { settings() }, "🔒" to { Vault.lock(this); finish() }))
        info = label("", 13f, C.SUB).apply { setPadding(dp(18), 0, dp(18), dp(6)) }
        root.addView(info)
        listView = ListView(this).apply {
            adapter = this@VaultActivity.adapter; divider = null
            setOnItemClickListener { _, _, p, _ -> itemMenu(entries[p]) }
        }
        root.addView(listView, lp(h = 0, weight = 1f))
        root.addView(button(t("＋  Add files", "＋  إضافة ملفات")) { pick() }, lp().margins(this, 16, 6, 16, 14))
        setContentView(root)
        reload()
        if (pendingAdd.isNotEmpty()) { val p = pendingAdd; pendingAdd = emptyList(); addPaths(p) }
    }

    private fun reload() {
        Thread {
            val e = Vault.entries(this)
            main.post {
                entries = e; adapter.notifyDataSetChanged()
                info.text = if (e.isEmpty()) t("Empty. Add files here, or select files in Sahl Files and tap 🔒 Vault.", "فارغة. أضف ملفات هنا، أو حدّد ملفات في سهل للملفات واضغط 🔒 الخزنة.")
                            else t("${e.size} files · ${e.sumOf { it.size }.human()} · encrypted", "${e.size} ملف · ${e.sumOf { it.size }.human()} · مشفّرة")
            }
        }.start()
    }

    /** Files chosen in Sahl Files: encrypt, then remove the original. */
    private fun addPaths(paths: List<String>) {
        val files = paths.map { File(it) }.filter { it.isFile }
        val (d, set) = progress(t("Encrypting", "جارٍ التشفير"))
        Thread {
            var bad = 0
            files.forEachIndexed { i, f ->
                val ok = Vault.add(this, f.name, f.parent ?: "", f.inputStream()) { done -> set("${i + 1}/${files.size} · ${f.name}", (done * 100 / f.length().coerceAtLeast(1)).toInt()) }
                if (ok) f.delete() else bad++
            }
            main.post {
                d.dismiss(); reload()
                toast(if (bad > 0) t("$bad files could not be added", "تعذّرت إضافة $bad ملف") else t("Moved into the Vault ✓", "تم النقل إلى الخزنة ✓"))
            }
        }.start()
    }

    private fun pick() {
        openingOther = true
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 7)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        openingOther = false
        if (req != 7 || res != RESULT_OK || data == null || !Vault.unlocked) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { c -> for (i in 0 until c.itemCount) uris.add(c.getItemAt(i).uri) } ?: data.data?.let { uris.add(it) }
        val (d, set) = progress(t("Encrypting", "جارٍ التشفير"))
        Thread {
            val added = ArrayList<Uri>()
            uris.forEachIndexed { i, u ->
                var name = "file"; var size = 1L
                contentResolver.query(u, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                        c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it).coerceAtLeast(1) }
                    }
                }
                val inp = try { contentResolver.openInputStream(u) } catch (_: Exception) { null } ?: return@forEachIndexed
                if (Vault.add(this, name, "", inp) { done -> set("${i + 1}/${uris.size} · $name", (done * 100 / size).toInt()) }) added.add(u)
            }
            main.post {
                d.dismiss(); reload()
                if (added.isEmpty()) return@post
                dialog().setTitle(t("Added ${added.size} files ✓", "تمت إضافة ${added.size} ملف ✓"))
                    .setMessage(t("Delete the originals so they are only in the Vault?", "حذف الملفات الأصلية لتبقى في الخزنة فقط؟"))
                    .setPositiveButton(t("Delete originals", "حذف الأصل")) { _, _ ->
                        var bad = 0
                        for (u in added) if (!(try { DocumentsContract.deleteDocument(contentResolver, u) } catch (_: Exception) { false })) bad++
                        if (bad > 0) toast(t("$bad originals could not be deleted — remove them yourself", "تعذّر حذف $bad ملف أصلي — احذفها بنفسك"))
                    }
                    .setNegativeButton(t("Keep them", "إبقاؤها"), null).show()
            }
        }.start()
    }

    private fun itemMenu(e: Vault.Entry) {
        dialog().setTitle(e.name).setItems(arrayOf(t("Open", "فتح"), t("Restore to phone", "استعادة إلى الهاتف"), t("Delete forever", "حذف نهائي"))) { _, w ->
            when (w) {
                0 -> decryptTo(e, File(Vault.openDir(this), e.id).apply { mkdirs() }.let { File(it, Fs.cleanName(e.name)) }) { f ->
                    openingOther = true; Fs.open(this, f)
                }
                1 -> {
                    val dest = File(e.from).takeIf { e.from.isNotEmpty() && it.isDirectory && it.canWrite() } ?: Fs.received.apply { mkdirs() }
                    decryptTo(e, Fs.freeName(dest, Fs.cleanName(e.name))) { f ->
                        Vault.remove(this, e); reload()
                        toast(t("Restored to ", "تمت الاستعادة إلى ") + f.parent?.removePrefix(Fs.root.absolutePath))
                    }
                }
                2 -> confirm(t("Delete forever?", "حذف نهائي؟"), e.name, t("Delete", "حذف")) { Vault.remove(this, e); reload() }
            }
        }.show()
    }

    private fun decryptTo(e: Vault.Entry, target: File, done: (File) -> Unit) {
        val (d, set) = progress(t("Decrypting", "جارٍ فك التشفير"))
        Thread {
            val ok = Vault.export(this, e, target.outputStream()) { n -> set(e.name, (n * 100 / e.size.coerceAtLeast(1)).toInt()) }
            main.post {
                d.dismiss()
                if (ok) done(target) else { target.delete(); toast(t("This file is damaged and could not be opened", "هذا الملف تالف ولا يمكن فتحه")) }
            }
        }.start()
    }

    private fun settings() {
        val bioOn = Vault.bioEnabled(this)
        val opts = arrayOf(
            t("Change PIN", "تغيير الرمز"),
            if (bioOn) t("Turn off fingerprint unlock", "إيقاف الفتح بالبصمة") else t("Turn on fingerprint unlock", "تفعيل الفتح بالبصمة"))
        dialog().setItems(opts) { _, w ->
            when (w) {
                0 -> ask(t("New PIN", "الرمز الجديد"), password = true, numeric = true) { p1 ->
                    if (p1.length < 4) { toast(t("Use at least 4 digits", "استخدم 4 أرقام على الأقل")); return@ask }
                    ask(t("Repeat the PIN", "أعد كتابة الرمز"), password = true, numeric = true) { p2 ->
                        if (p1 != p2) toast(t("The PINs don't match", "الرمزان غير متطابقين"))
                        else Thread { Vault.changePin(this, p1); main.post { toast(t("PIN changed ✓", "تم تغيير الرمز ✓")) } }.start()
                    }
                }
                1 -> if (bioOn) { Vault.disableBio(this); toast(t("Fingerprint unlock is off", "تم إيقاف الفتح بالبصمة")) }
                     else if (canBio()) turnOnBio {} else toast(t("Set a screen lock and fingerprint first", "فعّل قفل الشاشة والبصمة أولًا"))
            }
        }.show()
    }

    private inner class Adapter : BaseAdapter() {
        private val df = DateFormat.getDateInstance(DateFormat.MEDIUM)
        override fun getCount() = entries.size
        override fun getItem(p: Int) = entries[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val row = (convert as? LinearLayout) ?: LinearLayout(this@VaultActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
                addView(label("", 24f).apply { tag = "ic"; gravity = Gravity.CENTER; background = round(C.CARD, 12) }, LinearLayout.LayoutParams(dp(46), dp(46)))
                addView(LinearLayout(this@VaultActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0)
                    addView(label("", 15f).apply { tag = "name"; maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE })
                    addView(label("", 12f, C.SUB).apply { tag = "sub" })
                }, lp(0, WRAP_CONTENT, 1f))
            }
            val e = entries[p]
            row.findViewWithTag<TextView>("ic").text = Fs.icon(File(e.name))
            row.findViewWithTag<TextView>("name").text = e.name
            row.findViewWithTag<TextView>("sub").text = e.size.human() + " · " + df.format(Date(e.added))
            return row
        }
    }
}
