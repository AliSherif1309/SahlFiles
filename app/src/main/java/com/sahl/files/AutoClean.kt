package com.sahl.files

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Scheduled cleaning: runs on its own (daily or weekly, only while charging) and reports what it freed. */
class AutoClean : JobService() {
    companion object {
        private const val JOB = 77
        fun prefs(c: Context) = c.getSharedPreferences("cleaner", 0)
        /** 0 off, 1 daily, 7 weekly */
        fun every(c: Context) = prefs(c).getInt("autoDays", 0)
        fun caches(c: Context) = prefs(c).getBoolean("autoCaches", true)

        fun schedule(c: Context, days: Int, withCaches: Boolean) {
            prefs(c).edit().putInt("autoDays", days).putBoolean("autoCaches", withCaches).apply()
            val js = c.getSystemService(JobScheduler::class.java)
            js.cancel(JOB)
            if (days <= 0) return
            js.schedule(JobInfo.Builder(JOB, ComponentName(c, AutoClean::class.java))
                .setPeriodic(days * 24L * 60 * 60 * 1000)
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .setPersisted(true)
                .build())
        }
    }

    override fun onStartJob(p: JobParameters): Boolean {
        Thread {
            var freed = 0L; var cache = 0L
            try {
                if (Fs.hasAccess()) {
                    val found = CleanEngine.scan(this, quick = true).filter { it.checked }
                    freed = CleanEngine.clean(found, forever = false).first
                    Trash.autoClean()
                }
                if (caches(this) && Sh.granted()) {
                    cache = if (Sh.usageAccess(this)) Sh.appCaches(this).sumOf { it.cache } else 0L
                    if (!Sh.clearAllCaches()) cache = 0
                }
            } catch (_: Exception) {}
            val total = freed + cache
            if (total > 1024 * 1024) notify(total)
            jobFinished(p, false)
        }.start()
        return true
    }

    override fun onStopJob(p: JobParameters) = true

    private fun notify(bytes: Long) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("autoclean", t("Auto clean", "التنظيف التلقائي"), NotificationManager.IMPORTANCE_LOW))
        nm.notify(5, SahlNotif.apply(this, Notification.Builder(this, "autoclean"))
            .setContentTitle(t("Sahl Files cleaned ${bytes.human()}", "نظّف سهل للملفات ${bytes.human()}"))
            .setContentText(t("Junk, app leftovers and caches. Moved files are in the Recycle bin.", "ملفات غير مفيدة وبقايا تطبيقات وذاكرة مؤقتة. الملفات المنقولة في المحذوفات."))
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(this, 5, Intent(this, CleanerActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build())
    }
}
