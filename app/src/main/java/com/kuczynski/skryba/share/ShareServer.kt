package com.kuczynski.skryba.share

import com.kuczynski.skryba.R
import com.kuczynski.skryba.data.Library
import com.kuczynski.skryba.data.ScanDocument
import com.kuczynski.skryba.data.Settings
import com.kuczynski.skryba.i18n.str
import com.kuczynski.skryba.pdf.PdfExport
import com.kuczynski.skryba.pdf.PdfOptions
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Small HTTP server on the local network. The recipient scans a QR code or opens the link
 * in a browser and downloads files directly from the phone - no cloud and no internet.
 * Each share has a random token, and the server runs only while something is being shared.
 */
object ShareServer {

    class Share(
        val title: String,
        val files: List<File> = emptyList(),
        val docIds: List<String> = emptyList(),
        /** Whole library (access from a computer), the list is refreshed on every visit. */
        val library: Boolean = false,
        val allowUpload: Boolean = false,
    )

    private var server: ServerSocket? = null
    private var pool = Executors.newCachedThreadPool()
    private val shares = ConcurrentHashMap<String, Share>()
    private val random = SecureRandom()
    var port: Int = 0
        private set

    /** Called after a file is uploaded from the browser. */
    var onUpload: ((File) -> Unit)? = null

    @Synchronized
    fun share(share: Share): String {
        ensureStarted()
        val alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val token = (1..10).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        shares[token] = share
        return token
    }

    @Synchronized
    fun unshare(token: String) {
        shares.remove(token)
        if (shares.isEmpty()) stop()
    }

    fun url(token: String): String? = localIp()?.let { "http://$it:$port/s/$token" }

    @Synchronized
    private fun ensureStarted() {
        if (server?.isClosed == false) return
        if (pool.isShutdown) pool = Executors.newCachedThreadPool()
        val preferred = Settings.value.sharePort
        val s = ServerSocket()
        s.reuseAddress = true
        try {
            s.bind(InetSocketAddress(preferred))
        } catch (e: Exception) {
            s.bind(InetSocketAddress(0))
        }
        server = s
        port = s.localPort
        pool.execute {
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                pool.execute { handle(client) }
            }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        pool.shutdownNow()
    }

    /** IPv4 address on the local network (Wi-Fi or the hotspot shared by the phone). */
    fun localIp(): String? {
        val candidates = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { ni.name to it } }
            .filter { it.second.isSiteLocalAddress }
        val preferred = candidates.firstOrNull { (name, _) -> name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan") }
        return (preferred ?: candidates.firstOrNull())?.second?.hostAddress
    }

    // ---------------- Request handling ----------------

    private fun handle(socket: Socket) {
        socket.use { s ->
            runCatching {
                s.soTimeout = 30000
                val input = BufferedInputStream(s.getInputStream())
                val out = s.getOutputStream()
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0]
                val target = parts[1]
                val headers = HashMap<String, String>()
                while (true) {
                    val l = readLine(input) ?: break
                    if (l.isEmpty()) break
                    val i = l.indexOf(':')
                    if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
                route(method, target, headers, input, out)
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun route(method: String, target: String, headers: Map<String, String>, input: InputStream, out: OutputStream) {
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        val seg = path.trim('/').split('/')
        if (seg.size < 2 || seg[0] != "s") return send(out, 404, "text/html; charset=utf-8", page("Skryba", "<p>${str(R.string.web_not_found)}</p>"))
        val token = seg[1]
        val share = shares[token] ?: return send(out, 404, "text/html; charset=utf-8", page("Skryba", "<p>${str(R.string.web_expired)}</p>"))
        when {
            seg.size == 2 -> send(out, 200, "text/html; charset=utf-8", index(token, share))
            seg.size >= 4 && seg[2] == "f" -> {
                val f = files(share).getOrNull(seg[3].toIntOrNull() ?: -1)
                    ?: return send(out, 404, "text/plain; charset=utf-8", str(R.string.web_no_file).toByteArray())
                sendFile(out, f, f.name, query["dl"] == "1")
            }
            seg.size >= 4 && seg[2] == "d" -> {
                val doc = docs(share).firstOrNull { it.id == seg[3] }
                    ?: return send(out, 404, "text/plain; charset=utf-8", str(R.string.web_no_file).toByteArray())
                val pdf = docPdf(doc)
                sendFile(out, pdf, Library.sanitize(doc.name) + ".pdf", query["dl"] == "1")
            }
            seg.size >= 3 && seg[2] == "upload" && method == "POST" && share.allowUpload -> {
                val name = Library.sanitize(query["name"] ?: "plik")
                val len = headers["content-length"]?.toLongOrNull() ?: -1
                if (len < 0) return send(out, 411, "text/plain", "Length required".toByteArray())
                val tmp = Library.tempFile("upload_${System.nanoTime()}")
                tmp.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var left = len
                    while (left > 0) {
                        val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (r < 0) break
                        o.write(buf, 0, r)
                        left -= r
                    }
                }
                val saved = Library.addFile(tmp, name, move = true)
                onUpload?.invoke(saved)
                send(out, 200, "text/plain; charset=utf-8", "OK".toByteArray())
            }
            else -> send(out, 404, "text/plain; charset=utf-8", str(R.string.web_not_found).toByteArray())
        }
    }

    private fun files(s: Share): List<File> = if (s.library) Library.files.value else s.files
    private fun docs(s: Share): List<ScanDocument> =
        if (s.library) Library.docs.value else s.docIds.mapNotNull { Library.get(it) }

    private fun docPdf(doc: ScanDocument): File {
        val f = File(Library.tempDir, "srv_${doc.id}_${doc.modified}.pdf")
        if (!f.exists()) {
            val st = Settings.value
            PdfExport.imagesToPdf(
                Library.appContext,
                doc.pages.map { Library.pageFile(doc.id, it.processed) },
                f,
                PdfOptions(st.pageSize, st.jpegQuality),
            )
        }
        return f
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val status = when (code) { 200 -> "OK"; 404 -> "Not Found"; 411 -> "Length Required"; else -> "Error" }
        out.write(
            ("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(),
        )
        out.write(body)
        out.flush()
    }

    private fun sendFile(out: OutputStream, f: File, name: String, download: Boolean) {
        val enc = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val disp = if (download) "attachment" else "inline"
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: ${Sharing.mime(f)}\r\nContent-Length: ${f.length()}\r\n" +
                "Content-Disposition: $disp; filename*=UTF-8''$enc\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(),
        )
        f.inputStream().use { it.copyTo(out, 64 * 1024) }
        out.flush()
    }

    private fun js(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun size(bytes: Long): String = when {
        bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
        bytes >= 1 shl 10 -> "%.0f kB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun index(token: String, s: Share): ByteArray {
        val sb = StringBuilder()
        val docs = docs(s)
        val files = files(s)
        if (docs.isEmpty() && files.isEmpty()) sb.append("<p class=\"muted\">${str(R.string.web_no_files)}</p>")
        if (docs.isNotEmpty()) {
            sb.append("<h2>${str(R.string.web_documents)}</h2><ul>")
            for (d in docs) {
                sb.append("<li><a href=\"/s/$token/d/${d.id}\">${esc(d.name)}.pdf</a>")
                sb.append("<span class=\"muted\">${str(R.string.web_pages_short, d.pages.size)}</span>")
                sb.append("<a class=\"btn\" href=\"/s/$token/d/${d.id}?dl=1\">${str(R.string.web_download)}</a></li>")
            }
            sb.append("</ul>")
        }
        if (files.isNotEmpty()) {
            sb.append("<h2>${str(R.string.web_files)}</h2><ul>")
            files.forEachIndexed { i, f ->
                sb.append("<li><a href=\"/s/$token/f/$i\">${esc(f.name)}</a>")
                sb.append("<span class=\"muted\">${size(f.length())}</span>")
                sb.append("<a class=\"btn\" href=\"/s/$token/f/$i?dl=1\">${str(R.string.web_download)}</a></li>")
            }
            sb.append("</ul>")
        }
        if (s.allowUpload) {
            sb.append(
                """<h2>${str(R.string.web_upload_title)}</h2>
                <input type="file" id="up" multiple><p id="st" class="muted"></p>
                <script>
                document.getElementById('up').onchange = async (e) => {
                  const st = document.getElementById('st');
                  for (const f of e.target.files) {
                    st.textContent = '${js(str(R.string.web_uploading))}' + f.name + '...';
                    const r = await fetch('/s/$token/upload?name=' + encodeURIComponent(f.name), {method: 'POST', body: f});
                    st.textContent = r.ok ? '${js(str(R.string.web_uploaded))}' + f.name : '${js(str(R.string.web_upload_error))}';
                  }
                  setTimeout(() => location.reload(), 800);
                };
                </script>""",
            )
        }
        return page(s.title, sb.toString())
    }

    private fun page(title: String, body: String): ByteArray = """<!doctype html>
<html lang="${str(R.string.web_lang)}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)}</title>
<style>
:root { --bg:#f6f8f7; --fg:#1b1f1e; --muted:#66706d; --card:#fff; --brand:#0f7b6c; --line:#dde3e1; }
@media (prefers-color-scheme: dark) { :root { --bg:#121615; --fg:#e7ecea; --muted:#9aa5a2; --card:#1b2120; --brand:#4fc1ad; --line:#2c3533; } }
body { margin:0; font-family:system-ui, sans-serif; background:var(--bg); color:var(--fg); }
main { max-width:720px; margin:0 auto; padding:16px; }
header { display:flex; align-items:center; gap:10px; margin:8px 0 16px; }
.logo { width:36px; height:36px; border-radius:9px; background:var(--brand); color:#fff; display:grid; place-items:center; font-weight:700; }
h1 { font-size:20px; margin:0; } h2 { font-size:15px; color:var(--muted); margin:20px 0 8px; font-weight:600; }
ul { list-style:none; padding:0; margin:0; background:var(--card); border:1px solid var(--line); border-radius:12px; overflow:hidden; }
li { display:flex; align-items:center; gap:10px; padding:12px 14px; border-top:1px solid var(--line); }
li:first-child { border-top:none; }
li a:first-child { flex:1; color:var(--fg); text-decoration:none; word-break:break-word; font-weight:500; }
.muted { color:var(--muted); font-size:13px; }
.btn { background:var(--brand); color:#fff; padding:6px 12px; border-radius:8px; text-decoration:none; font-size:14px; white-space:nowrap; }
input[type=file] { margin-top:4px; }
footer { margin-top:24px; font-size:12px; color:var(--muted); }
</style></head><body><main>
<header><div class="logo">S</div><h1>${esc(title)}</h1></header>
$body
<footer>${str(R.string.web_footer)}</footer>
</main></body></html>""".toByteArray()
}
