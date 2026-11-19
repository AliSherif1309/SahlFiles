package com.sahl.files

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Wi-Fi sharing that speaks the LocalSend v2 protocol, so Sahl Files can send to and receive from
 * the LocalSend app on Windows, Mac, Linux, iPhone and Android — plus a simple browser page
 * (address + 6-digit code) for any device without an app.
 */
object Lan {
    const val PORT = 53317
    private const val GROUP = "224.0.0.167"

    // ---------------------------------------------------------------- who am I

    fun alias(c: Context): String = c.getSharedPreferences("share", 0).getString("alias", null) ?: "Sahl · ${Build.MODEL}"
    fun setAlias(c: Context, a: String) = c.getSharedPreferences("share", 0).edit().putString("alias", a).apply()
    private fun fingerprint(c: Context): String {
        val p = c.getSharedPreferences("share", 0)
        return p.getString("fp", null) ?: UUID.randomUUID().toString().replace("-", "").also { p.edit().putString("fp", it).apply() }
    }

    fun info(c: Context, announce: Boolean? = null): JSONObject = JSONObject()
        .put("alias", alias(c)).put("version", "2.0")
        .put("deviceModel", Build.MODEL).put("deviceType", "mobile")
        .put("fingerprint", fingerprint(c)).put("port", PORT).put("protocol", "http").put("download", false)
        .apply { if (announce != null) { put("announce", announce); put("announcement", announce) } }

    fun ip(): String = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress ?: "?"
    } catch (_: Exception) { "?" }

    // ---------------------------------------------------------------- nearby devices

    class Peer(val alias: String, val ip: String, val port: Int, val https: Boolean, val type: String, val fp: String, var seen: Long = System.currentTimeMillis()) {
        val icon get() = when (type) { "desktop" -> "💻"; "web" -> "🌐"; "headless", "server" -> "🖥"; else -> "📱" }
        fun url(path: String) = "${if (https) "https" else "http"}://$ip:$port$path"
    }

    val peers = ConcurrentHashMap<String, Peer>()
    @Volatile var onPeers: (() -> Unit)? = null

    private fun addPeer(c: Context, j: JSONObject, ip: String): Peer? {
        val fp = j.optString("fingerprint"); if (fp.isEmpty() || fp == fingerprint(c)) return null
        val p = Peer(j.optString("alias", ip), ip, j.optInt("port", PORT), j.optString("protocol", "https") == "https", j.optString("deviceType", "mobile"), fp)
        peers[fp] = p; onPeers?.invoke(); return p
    }

    // ---------------------------------------------------------------- trust (LocalSend uses self-signed certificates on the LAN)

    private val lanSsl by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }), SecureRandom())
        }.socketFactory
    }

    /** Only used for private-network (192.168 / 10 / 172.16) addresses of LocalSend devices. */
    private fun connect(url: String, method: String, timeout: Int = 5000): HttpURLConnection {
        val con = URL(url).openConnection() as HttpURLConnection
        if (con is HttpsURLConnection) { con.sslSocketFactory = lanSsl; con.setHostnameVerifier { _, _ -> true } }
        con.requestMethod = method; con.connectTimeout = timeout; con.readTimeout = timeout
        return con
    }

    private fun postJson(url: String, body: JSONObject, timeout: Int = 5000): Pair<Int, String> {
        val con = connect(url, "POST", timeout)
        con.doOutput = true; con.setRequestProperty("Content-Type", "application/json")
        con.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = con.responseCode
        val text = try { (if (code < 400) con.inputStream else con.errorStream)?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
        con.disconnect()
        return code to text
    }

    /** Asks a device at [ip] who it is (https first, like LocalSend, then http). */
    fun probe(c: Context, ip: String, timeout: Int = 1500): Peer? {
        for (https in listOf(true, false)) try {
            val (code, text) = postJson("${if (https) "https" else "http"}://$ip:$PORT/api/localsend/v2/register", info(c), timeout)
            if (code == 200) return addPeer(c, JSONObject(text), ip)
        } catch (_: Exception) {}
        return null
    }

    /** Checks every address on this Wi-Fi (x.x.x.1–254). Blocking, ~5 s. */
    fun scan(c: Context) {
        val me = ip(); if (me == "?") return
        val base = me.substringBeforeLast('.')
        val pool = Executors.newFixedThreadPool(48)
        for (i in 1..254) { val a = "$base.$i"; if (a != me) pool.execute { probe(c, a, 700) } }
        pool.shutdown(); pool.awaitTermination(20, TimeUnit.SECONDS)
    }

    // ---------------------------------------------------------------- multicast discovery

    class Discovery(private val c: Context) {
        @Volatile private var sock: MulticastSocket? = null
        private val pool = Executors.newCachedThreadPool()

        fun start() {
            Thread {
                try {
                    val s = MulticastSocket(InetSocketAddress(PORT)); sock = s
                    s.joinGroup(InetAddress.getByName(GROUP))
                    announce()
                    val buf = ByteArray(8192)
                    while (!s.isClosed) {
                        val pk = DatagramPacket(buf, buf.size); s.receive(pk)
                        val j = runCatching { JSONObject(String(pk.data, 0, pk.length)) }.getOrNull() ?: continue
                        val ip = pk.address.hostAddress ?: continue
                        val peer = addPeer(c, j, ip) ?: continue
                        if (j.optBoolean("announce", j.optBoolean("announcement", false))) pool.execute {
                            // answer over HTTP (LocalSend v2); fall back to a multicast reply
                            val ok = try { postJson(peer.url("/api/localsend/v2/register"), info(c), 3000).first == 200 } catch (_: Exception) { false }
                            if (!ok) send(info(c, false))
                        }
                    }
                } catch (_: Exception) {}
            }.start()
        }

        fun announce() = pool.execute { repeat(3) { send(info(c, true)); Thread.sleep(500) } }

        private fun send(j: JSONObject) {
            try {
                val d = j.toString().toByteArray()
                sock?.send(DatagramPacket(d, d.size, InetAddress.getByName(GROUP), PORT))
            } catch (_: Exception) {}
        }

        fun stop() { try { sock?.close() } catch (_: Exception) {}; pool.shutdownNow() }
    }

    // ---------------------------------------------------------------- sending

    class Item(val name: String, val size: Long, val mime: String, val open: () -> InputStream)

    fun fileItems(paths: List<String>): List<Item> = paths.map { File(it) }.flatMap { f ->
        if (f.isDirectory) Fs.walkFiles(f).map { (ff, rel) -> Item(rel, ff.length(), Fs.mime(ff)) { ff.inputStream() } }
        else listOf(Item(f.name, f.length(), Fs.mime(f)) { f.inputStream() })
    }

    fun uriItem(c: Context, u: Uri): Item? = try {
        var name = u.lastPathSegment ?: "file"; var size = -1L
        c.contentResolver.query(u, null, null, null, null)?.use { cur ->
            if (cur.moveToFirst()) {
                cur.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cur.getString(it) ?: name }
                cur.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { if (!cur.isNull(it)) size = cur.getLong(it) }
            }
        }
        if (size < 0) size = c.contentResolver.openAssetFileDescriptor(u, "r")?.use { it.length } ?: -1
        if (size < 0) null else Item(name, size, c.contentResolver.getType(u) ?: Fs.mime(name)) { c.contentResolver.openInputStream(u)!! }
    } catch (_: Exception) { null }

    /** Sends to a LocalSend-compatible device. Returns a message for the user. Blocking. */
    fun send(c: Context, peer: Peer, items: List<Item>, tick: (String, Long, Long) -> Unit): String {
        val files = JSONObject(); val ids = items.mapIndexed { i, _ -> "f$i-" + UUID.randomUUID().toString().take(8) }
        items.forEachIndexed { i, it ->
            files.put(ids[i], JSONObject().put("id", ids[i]).put("fileName", it.name).put("size", it.size).put("fileType", it.mime))
        }
        tick(t("Waiting for ${peer.alias} to accept…", "بانتظار موافقة ${peer.alias}…"), 0, 1)
        val (code, text) = try { postJson(peer.url("/api/localsend/v2/prepare-upload"), JSONObject().put("info", info(c)).put("files", files), 120_000) }
                           catch (e: Exception) { return t("Could not reach ${peer.alias}", "تعذّر الوصول إلى ${peer.alias}") }
        when (code) {
            200 -> {}
            204 -> return t("Nothing needed to be sent", "لا يوجد ما يلزم إرساله")
            401, 403 -> return t("${peer.alias} declined", "رفض ${peer.alias}")
            409 -> return t("${peer.alias} is busy with another transfer", "${peer.alias} مشغول بنقل آخر")
            else -> return t("Error from ${peer.alias} ($code)", "خطأ من ${peer.alias} ($code)")
        }
        val res = JSONObject(text); val session = res.getString("sessionId"); val tokens = res.optJSONObject("files") ?: JSONObject()
        val total = items.filterIndexed { i, _ -> tokens.has(ids[i]) }.sumOf { it.size }.coerceAtLeast(1)
        var done = 0L; var sent = 0
        for ((i, it) in items.withIndex()) {
            val token = tokens.optString(ids[i]); if (token.isEmpty()) continue
            val url = peer.url("/api/localsend/v2/upload?sessionId=${enc(session)}&fileId=${enc(ids[i])}&token=${enc(token)}")
            try {
                val con = connect(url, "POST", 30_000)
                con.doOutput = true; con.setFixedLengthStreamingMode(it.size)
                con.setRequestProperty("Content-Type", "application/octet-stream")
                it.open().use { inp -> con.outputStream.use { o ->
                    val buf = ByteArray(256 * 1024)
                    while (true) { val n = inp.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n; tick(it.name, done, total) }
                } }
                if (con.responseCode == 200) sent++
                con.disconnect()
            } catch (_: Exception) {
                try { connect(peer.url("/api/localsend/v2/cancel?sessionId=${enc(session)}"), "POST").apply { doOutput = true; outputStream.close(); responseCode } } catch (_: Exception) {}
                return t("Stopped after $sent of ${items.size} files", "توقف بعد $sent من ${items.size} ملف")
            }
        }
        return t("Sent $sent files to ${peer.alias} ✓", "تم إرسال $sent ملف إلى ${peer.alias} ✓")
    }

    /** A text message, shown on the other device (LocalSend "message"). */
    fun sendText(c: Context, peer: Peer, text: String): String {
        val bytes = text.toByteArray()
        val files = JSONObject().put("t1", JSONObject().put("id", "t1").put("fileName", "message.txt").put("size", bytes.size).put("fileType", "text/plain").put("preview", text))
        return try {
            val (code, body) = postJson(peer.url("/api/localsend/v2/prepare-upload"), JSONObject().put("info", info(c)).put("files", files), 60_000)
            if (code == 200) {      // some versions still want the file itself
                val j = JSONObject(body); val tok = j.optJSONObject("files")?.optString("t1").orEmpty()
                if (tok.isNotEmpty()) {
                    val con = connect(peer.url("/api/localsend/v2/upload?sessionId=${enc(j.getString("sessionId"))}&fileId=t1&token=${enc(tok)}"), "POST", 10_000)
                    con.doOutput = true; con.outputStream.use { it.write(bytes) }; con.responseCode; con.disconnect()
                }
            }
            if (code == 200 || code == 204) t("Message sent ✓", "تم إرسال الرسالة ✓") else t("Not delivered ($code)", "لم تُسلَّم ($code)")
        } catch (_: Exception) { t("Could not reach ${peer.alias}", "تعذّر الوصول إلى ${peer.alias}") }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    // ---------------------------------------------------------------- receiving: tiny HTTP server

    /** An incoming request waiting for the user to accept. */
    class Request(val from: String, val names: List<String>, val total: Long, val text: String?) {
        val latch = CountDownLatch(1)
        @Volatile var accepted = false
        fun answer(ok: Boolean) { accepted = ok; latch.countDown() }
    }

    interface Events {
        fun ask(r: Request)                                   // show accept / decline
        fun progress(name: String, done: Long, total: Long)
        fun received(f: File)
        fun finished(count: Int, from: String)
        fun message(from: String, text: String)
    }

    class Server(private val c: Context, private val ev: Events) {
        private var ss: ServerSocket? = null
        private val pool = Executors.newCachedThreadPool()
        val code: String = String.format("%06d", SecureRandom().nextInt(1_000_000))
        @Volatile private var wrongCodes = 0
        @Volatile var webShare: List<Item> = emptyList()       // files offered to the browser page

        private class Session(val id: String, val from: String, val files: Map<String, Pair<String, String>>, var total: Long) { // fileId -> (name, token)
            var done = 0L; var count = 0; var left = files.size
            @Volatile var last = System.currentTimeMillis()
        }
        @Volatile private var session: Session? = null

        fun start() {
            Thread {
                try {
                    val s = ServerSocket(); s.reuseAddress = true; s.bind(InetSocketAddress(PORT)); ss = s
                    while (!s.isClosed) { val sock = s.accept(); pool.execute { handle(sock) } }
                } catch (_: Exception) {}
            }.start()
        }

        fun stop() { try { ss?.close() } catch (_: Exception) {}; pool.shutdownNow() }

        // ---- HTTP plumbing
        private class Req(val method: String, val path: String, val q: Map<String, String>, val headers: Map<String, String>, val body: InputStream)

        private fun readLine(i: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = i.read(); if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar()); if (sb.length > 8192) return null
            }
        }

        private fun parse(i: InputStream): Req? {
            val line = readLine(i) ?: return null
            val p = line.split(' '); if (p.size < 2) return null
            val h = HashMap<String, String>()
            while (true) { val l = readLine(i) ?: break; if (l.isEmpty()) break; val k = l.substringBefore(':').trim().lowercase(); h[k] = l.substringAfter(':').trim() }
            val path = p[1].substringBefore('?')
            val q = p[1].substringAfter('?', "").split('&').filter { it.contains('=') }
                .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            val body: InputStream = when {
                h["transfer-encoding"]?.contains("chunked", true) == true -> Chunked(i)
                else -> Limited(i, h["content-length"]?.toLongOrNull() ?: 0L)
            }
            return Req(p[0], path, q, h, body)
        }

        private fun reply(o: OutputStream, code: Int, type: String = "application/json", body: ByteArray = ByteArray(0)) {
            val status = mapOf(200 to "OK", 204 to "No Content", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found", 409 to "Conflict", 429 to "Too Many Requests", 500 to "Error")[code] ?: "OK"
            o.write("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
            o.write(body); o.flush()
        }
        private fun json(o: OutputStream, code: Int, j: JSONObject) = reply(o, code, "application/json", j.toString().toByteArray())

        private fun handle(sock: Socket) {
            try {
                sock.soTimeout = 60_000
                val inp = BufferedInputStream(sock.getInputStream(), 64 * 1024); val out = sock.getOutputStream()
                val r = parse(inp) ?: return
                val ip = sock.inetAddress.hostAddress ?: ""
                when {
                    r.path.endsWith("/info") && r.path.startsWith("/api/localsend") -> json(out, 200, info(c))
                    r.path == "/api/localsend/v2/register" || r.path == "/api/localsend/v1/register" -> {
                        try { addPeer(c, JSONObject(r.body.readBytes().toString(Charsets.UTF_8)), ip) } catch (_: Exception) {}
                        json(out, 200, info(c))
                    }
                    r.path == "/api/localsend/v2/prepare-upload" -> prepare(r, out, ip)
                    r.path == "/api/localsend/v2/upload" -> upload(r, out)
                    r.path == "/api/localsend/v2/cancel" -> { session = null; reply(out, 200) }
                    r.path == "/" || r.path == "/index.html" -> reply(out, 200, "text/html; charset=utf-8", WebPage.html(alias(c)).toByteArray())
                    r.path == "/web/list" -> webList(r, out)
                    r.path == "/web/dl" -> webDownload(r, out)
                    r.path == "/web/up" -> webUpload(r, out)
                    r.path == "/fs/info" -> fsInfo(r, out)
                    r.path == "/fs/ls" -> fsList(r, out)
                    r.path == "/fs/get" -> fsGet(r, out)
                    r.path == "/fs/thumb" -> fsThumb(r, out)
                    r.path == "/fs/zip" -> fsZip(r, out)
                    r.path == "/fs/put" -> fsPut(r, out)
                    r.path == "/fs/mkdir" -> fsOp(r, out) { d, _ -> File(d, Fs.cleanName(r.q["n"] ?: "")).let { it.exists() || it.mkdirs() } }
                    r.path == "/fs/rename" -> fsOp(r, out) { f, _ -> val to = File(f.parentFile, Fs.cleanName(r.q["n"] ?: "")); !to.exists() && f.renameTo(to) }
                    r.path == "/fs/delete" -> fsOp(r, out) { f, base -> f.absolutePath != base.absolutePath && Trash.put(f) }
                    else -> reply(out, 404)
                }
            } catch (_: Exception) {
            } finally { try { sock.close() } catch (_: Exception) {} }
        }

        // ---- LocalSend
        private fun prepare(r: Req, out: OutputStream, ip: String) {
            session?.let { if (System.currentTimeMillis() - it.last > 5 * 60_000) session = null }   // forget a stalled sender
            if (session != null) { json(out, 409, JSONObject().put("message", "busy")); return }
            val j = JSONObject(r.body.readBytes().toString(Charsets.UTF_8))
            val inf = j.optJSONObject("info") ?: JSONObject(); val from = inf.optString("alias", ip)
            addPeer(c, inf, ip)
            val fj = j.getJSONObject("files")
            val metas = fj.keys().asSequence().map { fj.getJSONObject(it) }.toList()
            // a text message: show it, nothing to upload
            if (metas.size == 1 && metas[0].has("preview") && metas[0].optString("fileType").startsWith("text")) {
                ev.message(from, metas[0].optString("preview")); reply(out, 204); return
            }
            val total = metas.sumOf { it.optLong("size") }
            val req = Request(from, metas.map { it.optString("fileName") }, total, null)
            ev.ask(req)
            if (!req.latch.await(90, TimeUnit.SECONDS) || !req.accepted) { json(out, 403, JSONObject().put("message", "declined")); return }
            val tokens = JSONObject(); val map = HashMap<String, Pair<String, String>>()
            for (m in metas) { val tok = UUID.randomUUID().toString(); map[m.getString("id")] = m.optString("fileName", "file") to tok; tokens.put(m.getString("id"), tok) }
            val s = Session(UUID.randomUUID().toString(), from, map, total); session = s
            json(out, 200, JSONObject().put("sessionId", s.id).put("files", tokens))
        }

        private fun upload(r: Req, out: OutputStream) {
            val s = session
            val f = s?.files?.get(r.q["fileId"])
            if (s == null || s.id != r.q["sessionId"] || f == null || f.second != r.q["token"]) { json(out, 403, JSONObject().put("message", "invalid")); return }
            val target = save(f.first, r.body) { n -> s.done += n; s.last = System.currentTimeMillis(); ev.progress(f.first, s.done, s.total) }
            if (target == null) { reply(out, 500); return }
            s.count++; s.left--
            ev.received(target)
            reply(out, 200)
            if (s.left <= 0) { session = null; ev.finished(s.count, s.from) }
        }

        /** Saves under Download/Sahl Files, keeping sub-folders of a sent folder. Never overwrites. */
        private fun save(rawName: String, body: InputStream, tick: (Int) -> Unit): File? = try {
            val parts = rawName.replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." && it != ".." }.map { Fs.cleanName(it) }
            var dir = Fs.received; dir.mkdirs()
            for (p in parts.dropLast(1)) { dir = File(dir, p); dir.mkdirs() }
            val target = Fs.freeName(dir, parts.lastOrNull() ?: "file")
            target.outputStream().use { o ->
                val buf = ByteArray(256 * 1024)
                while (true) { val n = body.read(buf); if (n < 0) break; o.write(buf, 0, n); tick(n) }
            }
            target
        } catch (_: Exception) { null }

        fun c() = c

        // ---- browser file manager
        private fun rel(f: File) = f.absolutePath.removePrefix(base().absolutePath).ifEmpty { "/" }

        private fun fsInfo(r: Req, out: OutputStream) {
            if (!codeOk(r, out)) return
            val st = android.os.StatFs(Fs.root.absolutePath)
            json(out, 200, JSONObject().put("device", alias(c)).put("full", webFull(c))
                .put("free", st.availableBytes).put("total", st.totalBytes))
        }

        private fun fsList(r: Req, out: OutputStream) {
            if (!codeOk(r, out)) return
            val d = resolve(r.q["p"])?.takeIf { it.isDirectory } ?: run { reply(out, 404); return }
            val hidden = r.q["h"] == "1"
            val a = org.json.JSONArray()
            (d.listFiles() ?: emptyArray()).filter { hidden || !it.name.startsWith(".") }
                .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                .forEach { f -> a.put(JSONObject().put("n", f.name).put("d", f.isDirectory).put("s", if (f.isFile) f.length() else (f.list()?.size ?: 0)).put("m", f.lastModified())) }
            json(out, 200, JSONObject().put("path", rel(d)).put("items", a))
        }

        private fun fsGet(r: Req, out: OutputStream) {
            if (!codeOk(r, out)) return
            val f = resolve(r.q["p"])?.takeIf { it.isFile } ?: run { reply(out, 404); return }
            val len = f.length(); var start = 0L; var end = len - 1
            val range = r.headers["range"]
            if (range != null && range.startsWith("bytes=")) {
                val (a, b) = range.removePrefix("bytes=").substringBefore(',').split('-').let { it[0] to it.getOrElse(1) { "" } }
                if (a.isEmpty()) { start = (len - (b.toLongOrNull() ?: 0)).coerceAtLeast(0) } else { start = a.toLongOrNull() ?: 0; if (b.isNotEmpty()) end = minOf(b.toLong(), len - 1) }
            }
            val partial = range != null && start <= end
            val name = java.net.URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")
            val disp = if (r.q["dl"] == "1") "attachment" else "inline"
            out.write(("HTTP/1.1 ${if (partial) "206 Partial Content" else "200 OK"}\r\nContent-Type: ${Fs.mime(f)}\r\n" +
                "Content-Length: ${if (len == 0L) 0 else end - start + 1}\r\nAccept-Ranges: bytes\r\n" +
                (if (partial) "Content-Range: bytes $start-$end/$len\r\n" else "") +
                "Content-Disposition: $disp; filename*=UTF-8''$name\r\nConnection: close\r\n\r\n").toByteArray())
            if (len > 0) java.io.RandomAccessFile(f, "r").use { raf ->
                raf.seek(start); var left = end - start + 1; val buf = ByteArray(256 * 1024)
                while (left > 0) { val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; out.write(buf, 0, n); left -= n }
            }
            out.flush()
        }

        private fun fsThumb(r: Req, out: OutputStream) {
            if (!codeOk(r, out)) return
            val f = resolve(r.q["p"])?.takeIf { it.isFile } ?: run { reply(out, 404); return }
            val bmp = try {
                if (Fs.isVideo(f)) android.media.ThumbnailUtils.createVideoThumbnail(f, android.util.Size(240, 240), null)
                else android.media.ThumbnailUtils.createImageThumbnail(f, android.util.Size(240, 240), null)
            } catch (_: Exception) { null } ?: run { reply(out, 404); return }
            val bo = java.io.ByteArrayOutputStream(); bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bo)
            reply(out, 200, "image/jpeg", bo.toByteArray())
        }

        /** A folder as a zip, made on the fly. */
        private fun fsZip(r: Req, out: OutputStream) {
            if (!codeOk(r, out)) return
            val d = resolve(r.q["p"])?.takeIf { it.isDirectory } ?: run { reply(out, 404); return }
            val name = java.net.URLEncoder.encode((if (d.name.isEmpty()) "files" else d.name) + ".zip", "UTF-8").replace("+", "%20")
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Disposition: attachment; filename*=UTF-8''$name\r\nConnection: close\r\n\r\n").toByteArray())
            java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(out, 256 * 1024), Charsets.UTF_8).use { z ->
                val baseLen = d.absolutePath.length + 1; val buf = ByteArray(256 * 1024)
                d.walkTopDown().filter { it.isFile }.forEach { f ->
                    z.putNextEntry(java.util.zip.ZipEntry(f.absolutePath.substring(baseLen)).apply { time = f.lastModified() })
                    f.inputStream().use { i -> while (true) { val n = i.read(buf); if (n < 0) break; z.write(buf, 0, n) } }
                    z.closeEntry()
                }
            }
        }

        private fun fsPut(r: Req, out: OutputStream) {
            if (!codeOk(r, out)) return
            val d = resolve(r.q["p"])?.takeIf { it.isDirectory } ?: run { reply(out, 404); return }
            val name = Fs.cleanName(r.q["n"] ?: "file"); val size = r.headers["content-length"]?.toLongOrNull() ?: 0L
            val target = Fs.freeName(d, name); var done = 0L
            try {
                target.outputStream().use { o -> val buf = ByteArray(256 * 1024)
                    while (true) { val n = r.body.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n; ev.progress(name, done, size) } }
            } catch (e: Exception) { target.delete(); reply(out, 500); return }
            ev.received(target); ev.finished(1, t("Browser", "المتصفح"))
            reply(out, 200, "text/plain", "ok".toByteArray())
        }

        private fun fsOp(r: Req, out: OutputStream, op: (File, File) -> Boolean) {
            if (!codeOk(r, out)) return
            val f = resolve(r.q["p"]) ?: run { reply(out, 404); return }
            reply(out, if (try { op(f, base()) } catch (_: Exception) { false }) 200 else 409)
        }

        // ---- browser page
        private fun codeOk(r: Req, out: OutputStream): Boolean {
            if (wrongCodes >= 10) { reply(out, 429); return false }
            if (r.q["c"] != code) { wrongCodes++; reply(out, 401); return false }
            return true
        }

        private fun webList(r: Req, out: OutputStream) {
            if (!codeOk(r, out)) return
            val a = org.json.JSONArray(); webShare.forEachIndexed { i, it -> a.put(JSONObject().put("i", i).put("name", it.name).put("size", it.size)) }
            json(out, 200, JSONObject().put("device", alias(c)).put("files", a))
        }

        private fun webDownload(r: Req, out: OutputStream) {
            if (!codeOk(r, out)) return
            val it = webShare.getOrNull(r.q["i"]?.toIntOrNull() ?: -1) ?: run { reply(out, 404); return }
            val name = java.net.URLEncoder.encode(it.name.substringAfterLast('/'), "UTF-8").replace("+", "%20")
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: ${it.mime}\r\nContent-Length: ${it.size}\r\n" +
                "Content-Disposition: attachment; filename*=UTF-8''$name\r\nConnection: close\r\n\r\n").toByteArray())
            it.open().use { i -> i.copyTo(out, 256 * 1024) }; out.flush()
        }

        private fun webUpload(r: Req, out: OutputStream) {
            if (!codeOk(r, out)) return
            val name = r.q["n"] ?: "file"; val size = r.headers["content-length"]?.toLongOrNull() ?: 0L
            var done = 0L
            val f = save(name, r.body) { n -> done += n; ev.progress(name, done, size) } ?: run { reply(out, 500); return }
            ev.received(f); ev.finished(1, t("Browser", "المتصفح"))
            reply(out, 200, "text/plain", "ok".toByteArray())
        }
    }

    // ---------------------------------------------------------------- browser file manager (like PlainApp)

    /** Whole phone, or only the "Received" folder (default, safer). */
    private fun Server.base(): File = if (webFull(c())) Fs.root else Fs.received.apply { mkdirs() }
    fun webFull(c: Context) = c.getSharedPreferences("share", 0).getBoolean("webfull", false)

    private fun Server.resolve(p: String?): File? {
        val base = base(); val bc = base.canonicalPath
        val f = File(base, (p ?: "").trimStart('/'))
        val cp = f.canonicalPath
        return if (cp == bc || cp.startsWith("$bc/")) f else null       // never outside the shared area
    }

    private class Limited(i: InputStream, private var left: Long) : FilterInputStream(i) {
        override fun read(): Int { if (left <= 0) return -1; val b = super.read(); if (b >= 0) left--; return b }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), left).toInt()); if (n > 0) left -= n; return n
        }
    }

    private class Chunked(private val i: InputStream) : InputStream() {
        private var left = 0L; private var done = false
        private fun next() {
            if (left == 0L && !done) {
                val sb = StringBuilder()
                while (true) { val b = i.read(); if (b < 0) { done = true; return }; if (b == '\n'.code) break; sb.append(b.toChar()) }
                val line = sb.toString().trim()
                if (line.isEmpty()) { next(); return }
                left = line.substringBefore(';').toLong(16)
                if (left == 0L) done = true
            }
        }
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            next(); if (done) return -1
            val n = i.read(b, off, minOf(len.toLong(), left).toInt()); if (n < 0) { done = true; return -1 }
            left -= n
            if (left == 0L) { i.read(); i.read() }          // CRLF after each chunk
            return n
        }
    }
}
