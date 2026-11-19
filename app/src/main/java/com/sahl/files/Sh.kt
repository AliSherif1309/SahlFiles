package com.sahl.files

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserHandle
import android.os.storage.StorageManager
import rikka.shizuku.Shizuku

/**
 * Shizuku = "shell" power without root. With it Sahl Files can clear every app's cache
 * and reach Android/data + Android/obb (needed for leftovers of uninstalled apps).
 */
object Sh {
    const val PKG = "moe.shizuku.privileged.api"

    fun installed(c: Context) = try { c.packageManager.getPackageInfo(PKG, 0); true } catch (_: Exception) { false }
    fun running() = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
    fun granted() = try { running() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (_: Throwable) { false }
    fun request() = try { Shizuku.requestPermission(42) } catch (_: Throwable) {}

    /** Runs a shell command as the "shell" user. Returns exit code and output. Blocking. */
    fun run(cmd: String): Pair<Int, String> = try {
        val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as java.lang.Process
        val out = p.inputStream.bufferedReader().readText()
        p.errorStream.bufferedReader().readText()
        p.waitFor() to out
    } catch (e: Throwable) { -1 to "" }

    fun q(path: String) = "'" + path.replace("'", "'\\''") + "'"

    // ---------------------------------------------------------------- app caches

    fun usageAccess(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
    }

    class AppCache(val pkg: String, val label: String, val cache: Long)

    /** Cache size of every installed app (needs Usage access). Blocking. */
    fun appCaches(c: Context): List<AppCache> {
        val ssm = c.getSystemService(StorageStatsManager::class.java)
        val pm = c.packageManager
        val me = UserHandle.getUserHandleForUid(Process.myUid())
        return pm.getInstalledApplications(0).mapNotNull { ai ->
            try {
                val s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, me)
                if (s.cacheBytes < 64 * 1024) null else AppCache(ai.packageName, pm.getApplicationLabel(ai).toString(), s.cacheBytes)
            } catch (_: Exception) { null }
        }.sortedByDescending { it.cache }
    }

    /** Clears the cache of every app at once (Android's own "free up space" call). */
    fun clearAllCaches(): Boolean = run("pm trim-caches 999999999999").first == 0

    // ---------------------------------------------------------------- installed apps

    fun installedPackages(c: Context): Set<String> =
        c.packageManager.getInstalledPackages(PackageManager.MATCH_UNINSTALLED_PACKAGES).map { it.packageName }.toSet()
}
