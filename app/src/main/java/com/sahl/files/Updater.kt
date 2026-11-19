package com.sahl.files

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates from GitHub Releases: checks for a newer signed APK,
 * downloads it inside the app and hands it to Android's installer.
 */
object Updater {
    private const val REPO = "AliSherif1309/SahlFiles"
    data class Release(val code: Int, val tag: String, val apkUrl: String, val notes: String)

    /** Blocking — call off the main thread. Returns a release only if it is newer than this build. */
    fun fetchNewer(): Release? = try {
        val con = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection)
            .apply { connectTimeout = 8000; readTimeout = 8000; setRequestProperty("Accept", "application/vnd.github+json") }
        val j = JSONObject(con.inputStream.bufferedReader().readText())
        val tag = j.getString("tag_name")                       // e.g. v1.7
        val code = tag.substringAfterLast('.').toIntOrNull() ?: 0
        val assets = j.getJSONArray("assets")
        var url = ""
        for (i in 0 until assets.length()) assets.getJSONObject(i).let {
            if (it.getString("name").endsWith(".apk")) url = it.getString("browser_download_url")
        }
        if (code > BuildConfig.VERSION_CODE && url.isNotEmpty()) Release(code, tag, url, j.optString("body")) else null
    } catch (e: Exception) { null }

    /** Blocking — streams the APK straight into an install session, reporting 0–100 progress. */
    fun install(c: Context, r: Release, progress: (Int) -> Unit): Boolean = try {
        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            var con = URL(r.apkUrl).openConnection() as HttpURLConnection
            con.instanceFollowRedirects = true
            con.connectTimeout = 10000; con.readTimeout = 20000
            // GitHub redirects to its file host; follow manually if Android doesn't
            var hops = 0
            while (con.responseCode in 301..308 && hops++ < 5) {
                val loc = con.getHeaderField("Location"); con.disconnect()
                con = URL(loc).openConnection() as HttpURLConnection
            }
            val total = con.contentLengthLong
            con.inputStream.use { inp ->
                s.openWrite("app.apk", 0, if (total > 0) total else -1).use { out ->
                    val buf = ByteArray(256 * 1024); var done = 0L
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) progress((done * 100 / total).toInt())
                    }
                    s.fsync(out)
                }
            }
            val intent = Intent(c, UpdateReceiver::class.java)
            val pending = PendingIntent.getBroadcast(c, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            s.commit(pending.intentSender)
        }
        true
    } catch (e: Exception) { false }

    /** Shows "update available" once per app start, if there is one. */
    private var checked = false
    fun checkOnStart(a: Activity) {
        if (checked) return; checked = true
        Thread { fetchNewer()?.let { r -> a.runOnUiThread { if (!a.isFinishing) offer(a, r) } } }.start()
    }

    /** Manual check from the menu. */
    fun checkNow(a: Activity) {
        a.toast(t("Checking for updates…", "جارٍ البحث عن تحديث…"))
        Thread {
            val r = fetchNewer()
            a.runOnUiThread {
                if (r == null) a.toast(t("You have the latest version (v${BuildConfig.VERSION_NAME})", "لديك أحدث إصدار (v${BuildConfig.VERSION_NAME})"))
                else offer(a, r)
            }
        }.start()
    }

    private fun offer(a: Activity, r: Release) {
        a.dialog().setTitle(t("Update available: ${r.tag}", "تحديث جديد: ${r.tag}"))
            .setMessage(r.notes.ifBlank { t("A new version of Sahl Files is ready.", "إصدار جديد من سهل للملفات جاهز.") })
            .setPositiveButton(t("Update now", "حدّث الآن")) { _, _ ->
                val (d, set) = a.progress(t("Downloading update", "تنزيل التحديث"))
                Thread {
                    val ok = install(a, r) { p -> set("${r.tag} · $p%", p) }
                    a.runOnUiThread { d.dismiss(); if (!ok) a.toast(t("Download failed. Check the internet.", "فشل التنزيل. تحقق من الإنترنت.")) }
                }.start()
            }
            .setNegativeButton(t("Later", "لاحقًا"), null).show()
    }
}

/** Android asks the user to confirm the install; this opens that screen. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> c.toast(t("Update was not installed", "لم يتم تثبيت التحديث"))
        }
    }
}
