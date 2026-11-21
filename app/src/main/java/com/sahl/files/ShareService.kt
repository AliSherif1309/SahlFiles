package com.sahl.files

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/** Keeps Sahl Share visible and receiving on the Wi-Fi while it runs (shown as a notification). */
class ShareService : Service(), Lan.Events {
    companion object {
        @Volatile var inst: ShareService? = null
        @Volatile var ui: Lan.Events? = null                   // ShareActivity, while it is on screen
        val waiting = ConcurrentLinkedQueue<Lan.Request>()      // requests nobody answered yet

        fun start(c: Context) { if (inst == null) c.startForegroundService(Intent(c, ShareService::class.java)) }
        fun stop(c: Context) { c.stopService(Intent(c, ShareService::class.java)) }
        fun quickSave(c: Context) = c.getSharedPreferences("share", 0).getBoolean("quick", false)
        private const val CH = "share"; private const val CH_IN = "incoming_chime"
    }

    private val main = Handler(Looper.getMainLooper())
    lateinit var server: Lan.Server
    lateinit var discovery: Lan.Discovery
    private var lock: WifiManager.MulticastLock? = null
    private var lastNote = 0L

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, t("Sahl Share running", "سهل للمشاركة يعمل"), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(SahlCore.chimeChannel(this, CH_IN, t("Incoming files", "الملفات الواردة")))
        try { nm.deleteNotificationChannel("incoming") } catch (e: Exception) { }
        startForeground(1, note(t("Visible on Wi-Fi as ${Lan.alias(this)}", "ظاهر على الواي فاي باسم ${Lan.alias(this)}"), Lan.ip()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        lock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createMulticastLock("sahl-share").apply { setReferenceCounted(false); acquire() }
        server = Lan.Server(this, this).also { it.start() }
        discovery = Lan.Discovery(this).also { it.start() }
        inst = this
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int) = START_NOT_STICKY

    override fun onDestroy() {
        inst = null
        waiting.forEach { it.answer(false) }; waiting.clear()
        try { server.stop(); discovery.stop() } catch (_: Exception) {}
        lock?.release()
        super.onDestroy()
    }

    private fun openApp(target: Class<*>, extra: (Intent) -> Unit = {}) = PendingIntent.getActivity(this, target.hashCode(),
        Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).also(extra),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun note(title: String, text: String, progress: Int = -1): Notification =
        Notification.Builder(this, CH).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title).setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(openApp(ShareActivity::class.java))
            .apply { if (progress >= 0) setProgress(100, progress, false) }
            .addAction(Notification.Action.Builder(null, t("Stop", "إيقاف"),
                PendingIntent.getActivity(this@ShareService, 9, Intent(this@ShareService, ShareActivity::class.java).putExtra("stop", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)).build())
            .build()

    private fun alert(id: Int, title: String, text: String, open: PendingIntent) {
        getSystemService(NotificationManager::class.java).notify(id,
            Notification.Builder(this, CH_IN).setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true).setContentIntent(open).build())
    }

    // ---------------------------------------------------------------- events from the server

    override fun ask(r: Lan.Request) {
        if (quickSave(this)) { r.answer(true); return }
        waiting.add(r)
        main.post {
            val u = ui
            if (u != null) u.ask(r)
            else alert(2, t("${r.from} wants to send ${r.names.size} files", "${r.from} يريد إرسال ${r.names.size} ملف"),
                t("${r.total.human()} · tap to accept or decline", "${r.total.human()} · اضغط للقبول أو الرفض"), openApp(ShareActivity::class.java))
        }
    }

    override fun progress(name: String, done: Long, total: Long) {
        ui?.progress(name, done, total)
        val now = System.currentTimeMillis()
        if (now - lastNote > 700) {
            lastNote = now
            getSystemService(NotificationManager::class.java).notify(1, note(t("Receiving…", "جارٍ الاستلام…"), "$name · ${done.human()}",
                if (total > 0) (done * 100 / total).toInt() else 0))
        }
    }

    @Volatile private var last: File? = null
    override fun received(f: File) { last = f; ui?.received(f) }

    override fun finished(count: Int, from: String) {
        getSystemService(NotificationManager::class.java).notify(1, note(t("Visible on Wi-Fi as ${Lan.alias(this)}", "ظاهر على الواي فاي باسم ${Lan.alias(this)}"), Lan.ip()))
        main.post { ui?.finished(count, from) }
        alert(3, t("Received $count files from $from", "تم استلام $count ملف من $from"), t("Saved in Download/Sahl Files", "حُفظت في التنزيلات/Sahl Files"),
            openApp(MainActivity::class.java) { val l = last; it.putExtra("path", (l?.parentFile ?: Fs.received).absolutePath).putExtra("select", l?.absolutePath) })
    }

    override fun message(from: String, text: String) {
        main.post {
            val u = ui
            if (u != null) u.message(from, text)
            else alert(4, t("Message from $from", "رسالة من $from"), text, openApp(ShareActivity::class.java))
        }
    }
}
