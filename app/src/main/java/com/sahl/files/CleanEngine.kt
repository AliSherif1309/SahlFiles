package com.sahl.files

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * The cleaner's brain, shared by the Cleaner screen and the scheduled auto-clean.
 * Finds: leftovers of uninstalled apps, junk files, your own rules, duplicates, large files,
 * installers, old downloads and empty folders. Never touches excluded paths.
 */
object CleanEngine {
    const val LEFTOVER = 1; const val JUNK = 2; const val RULES = 3; const val DUP = 4
    const val LARGE = 5; const val APK = 6; const val OLD = 7; const val EMPTY = 8

    fun title(sec: Int) = when (sec) {
        LEFTOVER -> t("Leftovers of uninstalled apps", "بقايا تطبيقات محذوفة")
        JUNK -> t("Junk files (thumbnails, logs, temp)", "ملفات غير مفيدة (مصغّرات، سجلات، مؤقتة)")
        RULES -> t("Your rules", "قواعدك")
        DUP -> t("Duplicate files", "ملفات مكررة")
        LARGE -> t("Large files (over 100 MB)", "ملفات كبيرة (أكثر من 100 م.ب)")
        APK -> t("Installer files (APK)", "ملفات التثبيت (APK)")
        OLD -> t("Downloads older than 6 months", "تنزيلات أقدم من 6 أشهر")
        else -> t("Empty folders", "مجلدات فارغة")
    }

    /** One thing found. [shell] = must be deleted through Shizuku (Android/data, obb). */
    class Found(val section: Int, val path: String, val size: Long, var checked: Boolean, val note: String = "", val shell: Boolean = false) {
        val file get() = File(path)
    }

    // ---------------------------------------------------------------- settings

    private fun prefs(c: Context) = c.getSharedPreferences("cleaner", 0)

    fun exclusions(c: Context): Set<String> = prefs(c).getStringSet("exclude", emptySet())!!.toSet()
    fun exclude(c: Context, path: String) = prefs(c).edit().putStringSet("exclude", exclusions(c) + path).apply()
    fun unexclude(c: Context, path: String) = prefs(c).edit().putStringSet("exclude", exclusions(c) - path).apply()

    /** A user rule. Every filled field must match. */
    class Rule(var name: String, var nameHas: String = "", var exts: String = "", var pathHas: String = "",
               var olderDays: Int = 0, var minMb: Int = 0, var on: Boolean = true, var auto: Boolean = false) {
        fun json() = JSONObject().put("name", name).put("nameHas", nameHas).put("exts", exts).put("pathHas", pathHas)
            .put("olderDays", olderDays).put("minMb", minMb).put("on", on).put("auto", auto)
        private val extSet get() = exts.split(',', ' ', ';').map { it.trim().trimStart('.').lowercase() }.filter { it.isNotEmpty() }.toSet()
        fun empty() = nameHas.isBlank() && exts.isBlank() && pathHas.isBlank()
        fun matches(f: File, size: Long, mod: Long, now: Long): Boolean {
            if (!on || empty()) return false
            if (nameHas.isNotBlank() && !f.name.contains(nameHas.trim(), true)) return false
            if (exts.isNotBlank() && f.extension.lowercase() !in extSet) return false
            if (pathHas.isNotBlank() && !f.absolutePath.contains(pathHas.trim(), true)) return false
            if (olderDays > 0 && now - mod < olderDays * 86_400_000L) return false
            if (minMb > 0 && size < minMb * 1024L * 1024) return false
            return true
        }
        companion object {
            fun from(j: JSONObject) = Rule(j.optString("name"), j.optString("nameHas"), j.optString("exts"), j.optString("pathHas"),
                j.optInt("olderDays"), j.optInt("minMb"), j.optBoolean("on", true), j.optBoolean("auto"))
        }
    }

    fun rules(c: Context): MutableList<Rule> = try {
        val a = JSONArray(prefs(c).getString("rules", "[]")); (0 until a.length()).map { Rule.from(a.getJSONObject(it)) }.toMutableList()
    } catch (_: Exception) { mutableListOf() }

    fun saveRules(c: Context, list: List<Rule>) = prefs(c).edit().putString("rules", JSONArray().apply { list.forEach { put(it.json()) } }.toString()).apply()

    // ---------------------------------------------------------------- scanning

    private val junkNames = setOf("thumbs.db", ".ds_store", "desktop.ini", ".thumbdata", "ehthumbs.db")
    private val junkExts = setOf("log", "tmp", "temp", "dmp", "crdownload", "part", "partial")

    /**
     * [quick] = the safe part only (used by auto-clean): leftovers, junk, rules marked "auto", empty folders.
     */
    fun scan(c: Context, quick: Boolean = false, stop: () -> Boolean = { false }, status: (String) -> Unit = {}): List<Found> {
        val root = Fs.root; val r = root.absolutePath
        val excl = exclusions(c)
        fun excluded(p: String) = excl.any { p == it || p.startsWith("$it/") }
        val rules = rules(c).filter { it.on && (!quick || it.auto) }
        val now = System.currentTimeMillis()
        val out = ArrayList<Found>()

        // walk once
        val files = ArrayList<Pair<File, Long>>(); val empties = ArrayList<File>()
        var n = 0
        fun walk(d: File): Boolean {
            if (stop()) return false
            val kids = d.listFiles() ?: return false
            var emptyHere = true
            for (k in kids) {
                val p = k.absolutePath
                if (excluded(p)) { emptyHere = false; continue }
                if (k.isDirectory) {
                    if (p == "$r/Android/data" || p == "$r/Android/obb" || p.startsWith("$r/.SahlFiles")) { emptyHere = false; continue }
                    if (java.nio.file.Files.isSymbolicLink(k.toPath())) { emptyHere = false; continue }
                    if (!walk(k)) emptyHere = false
                } else {
                    emptyHere = false; files.add(k to k.length())
                    if (++n % 2000 == 0) status(t("Scanning… $n files", "جارٍ الفحص… $n ملف"))
                }
            }
            if (emptyHere && d != root && !d.name.startsWith(".") && d.parentFile?.absolutePath != r) empties.add(d)
            return emptyHere
        }
        walk(root)
        if (stop()) return emptyList()

        // 1. leftovers of uninstalled apps
        status(t("Looking for app leftovers…", "البحث عن بقايا التطبيقات…"))
        val installed = Sh.installedPackages(c)
        fun pkgLike(s: String) = s.count { it == '.' } >= 1 && s.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))
        File(root, "Android/media").listFiles()?.forEach { d ->
            if (d.isDirectory && pkgLike(d.name) && d.name !in installed && !excluded(d.absolutePath))
                out.add(Found(LEFTOVER, d.absolutePath, Fs.sizeOf(d), true, d.name))
        }
        if (Sh.granted()) for (area in listOf("data", "obb")) {
            val base = "$r/Android/$area"
            val names = Sh.run("ls -1 ${Sh.q(base)}").second.lines().map { it.trim() }.filter { it.isNotEmpty() }
            for (name in names) if (pkgLike(name) && name !in installed && !excluded("$base/$name")) {
                val kb = Sh.run("du -sk ${Sh.q("$base/$name")}").second.trim().substringBefore('\t').substringBefore(' ').toLongOrNull() ?: 0L
                out.add(Found(LEFTOVER, "$base/$name", kb * 1024, true, "$name · Android/$area", shell = true))
            }
        }

        // 2. junk + 3. rules
        status(t("Checking junk files…", "فحص الملفات غير المفيدة…"))
        for ((f, size) in files) {
            val p = f.absolutePath; val name = f.name.lowercase(); val ext = f.extension.lowercase()
            val junk = when {
                p.contains("/.thumbnails/") -> t("thumbnail cache", "مصغّرات مؤقتة")
                name in junkNames -> t("system junk", "ملف نظام غير مفيد")
                ext in junkExts && !p.startsWith("$r/Android/media/com.whatsapp") -> t("log / temporary", "سجل / مؤقت")
                name.startsWith("~$") -> t("Office temp file", "ملف أوفيس مؤقت")
                p.startsWith("$r/LOST.DIR/") -> "LOST.DIR"
                size == 0L && !name.startsWith(".") -> t("empty file", "ملف فارغ")
                name.startsWith(".trashed-") -> t("in gallery trash", "في سلة المعرض")
                else -> null
            }
            if (junk != null) { out.add(Found(JUNK, p, size, !name.startsWith(".trashed-"), junk)); continue }
            if (rules.isNotEmpty()) {
                val mod = f.lastModified()
                rules.firstOrNull { it.matches(f, size, mod, now) }?.let { out.add(Found(RULES, p, size, true, it.name)) }
            }
        }

        if (!quick) {
            // 4. duplicates
            status(t("Looking for duplicates…", "البحث عن الملفات المكررة…"))
            val taken = out.map { it.path }.toHashSet()
            val bySize = files.filter { it.second >= 64 * 1024 && !it.first.name.startsWith(".") && it.first.absolutePath !in taken }
                .groupBy { it.second }.values.filter { it.size > 1 }
            for (group in bySize) {
                if (stop()) break
                for (g in group.map { it.first }.groupBy { quickHash(it) }.values.filter { it.size > 1 })
                    for (same in g.groupBy { fullHash(it) }.values.filter { it.size > 1 }) {
                        val sorted = same.sortedBy { it.lastModified() }
                        sorted.forEachIndexed { i, f -> out.add(Found(DUP, f.absolutePath, f.length(), i > 0,
                            if (i == 0) t("original · kept", "الأصل · يُحتفظ به") else t("copy", "نسخة"))) }
                    }
            }
            // 5–7. large, installers, old downloads
            files.filter { it.second >= 100L * 1024 * 1024 }.sortedByDescending { it.second }.forEach { out.add(Found(LARGE, it.first.absolutePath, it.second, false)) }
            files.filter { it.first.extension.lowercase() in setOf("apk", "apks", "xapk") }.sortedByDescending { it.second }.forEach { out.add(Found(APK, it.first.absolutePath, it.second, true)) }
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath + "/"
            val old = now - 180L * 86_400_000L
            files.filter { it.first.absolutePath.startsWith(dl) && it.first.lastModified() < old }.sortedByDescending { it.second }
                .forEach { out.add(Found(OLD, it.first.absolutePath, it.second, false)) }
        }

        // 8. empty folders (top-most only)
        empties.filter { e -> empties.none { o -> o != e && e.absolutePath.startsWith(o.absolutePath + "/") } }
            .forEach { out.add(Found(EMPTY, it.absolutePath, 0, true)) }
        return out
    }

    private fun quickHash(f: File): String = try {
        val md = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(64 * 1024)
        java.io.RandomAccessFile(f, "r").use { r ->
            var n = r.read(buf); if (n > 0) md.update(buf, 0, n)
            if (r.length() > buf.size * 2) { r.seek(r.length() - buf.size); n = r.read(buf); if (n > 0) md.update(buf, 0, n) }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { f.absolutePath }

    private fun fullHash(f: File): String = try {
        val md = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(512 * 1024)
        f.inputStream().use { i -> while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { f.absolutePath }

    // ---------------------------------------------------------------- cleaning

    /** Removes what is picked. Junk and empty folders are deleted; Android/data leftovers via Shizuku; the rest go to the Recycle bin unless [forever]. */
    fun clean(list: List<Found>, forever: Boolean, tick: (String, Int) -> Unit = { _, _ -> }): Pair<Long, Int> {
        var freed = 0L; var bad = 0
        list.forEachIndexed { i, x ->
            tick(x.file.name, i * 100 / list.size.coerceAtLeast(1))
            val f = x.file
            val ok = when {
                x.shell -> Sh.run("rm -rf ${Sh.q(x.path)}").first == 0
                x.section == EMPTY -> f.list()?.isEmpty() != false && f.deleteRecursively()
                x.section == JUNK -> f.deleteRecursively()
                forever -> f.deleteRecursively()
                else -> Trash.put(f)
            }
            if (ok) freed += x.size else bad++
        }
        return freed to bad
    }
}
