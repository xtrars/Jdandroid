package com.jdandroid.container

import com.jdandroid.core.AppLog
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A request received via Click'n'Load: links plus the extra data the protocol
 * carries (package name, extraction passwords, origin).
 */
data class CnlRequest(
    val urls: List<String>,
    val packageName: String? = null,
    val passwords: List<String> = emptyList(),
    val source: String? = null
)

/**
 * Local Click'n'Load 2 server on port 9666 (tests: [port] 0 = free port, the
 * real one is in [listeningPort]). Browser pages with a "Click'n'Load" button
 * send their (encrypted) links here; they are decrypted locally and queued
 * via [onRequest].
 *
 * Minimal HTTP server on a [ServerSocket]: one acceptor thread takes
 * connections, a small thread pool answers them. Every response closes the
 * connection (no keep-alive), which is enough for the few requests of a CnL
 * page. The server binds to loopback only, so it works with browsers on the
 * same device, like JDownloader on the desktop.
 */
class ClickNLoadServer(
    private val hostname: String = LOOPBACK,
    private val port: Int = PORT,
    private val onRequest: (CnlRequest) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var acceptor: Thread? = null
    private var workers: ExecutorService? = null

    /** Actually bound port (with [port] 0 the one chosen by the system), -1 without a socket. */
    val listeningPort: Int
        get() = serverSocket?.localPort ?: -1

    /** True while the acceptor is listening. */
    val isAlive: Boolean
        get() = acceptor?.isAlive == true && serverSocket?.isClosed == false

    /** Binds the socket and starts acceptor and pool; throws if the port is taken. */
    @Synchronized
    fun start() {
        check(serverSocket == null) { "server already running" }
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(hostname, port), BACKLOG)
        serverSocket = socket
        val pool = Executors.newFixedThreadPool(WORKER_THREADS) { runnable ->
            Thread(runnable, "cnl-worker").apply { isDaemon = true }
        }
        workers = pool
        acceptor = thread(name = "cnl-acceptor", isDaemon = true) { acceptLoop(socket, pool) }
    }

    /** Closes the socket; running requests are aborted. May be called repeatedly. */
    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        workers?.shutdownNow()
        runCatching { workers?.awaitTermination(1, TimeUnit.SECONDS) }
        runCatching { acceptor?.join(1000) }
        serverSocket = null
        workers = null
        acceptor = null
    }

    private fun acceptLoop(socket: ServerSocket, pool: ExecutorService) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                // Socket closed (stop) or transient error
                if (socket.isClosed) return
                continue
            }
            try {
                pool.execute { handleConnection(client) }
            } catch (e: RejectedExecutionException) {
                runCatching { client.close() }
            }
        }
    }

    private fun handleConnection(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = SOCKET_READ_TIMEOUT_MS
                val input = BufferedInputStream(socket.getInputStream())
                val response = try {
                    serve(Request.parse(input), input)
                } catch (e: HttpError) {
                    Response(e.status, MIME_PLAINTEXT, e.text)
                }
                val out = socket.getOutputStream()
                out.write(response.toBytes())
                out.flush()
            } catch (e: IOException) {
                // Client aborted or read timeout: nothing to do
            }
        }
    }

    private fun serve(request: Request, input: InputStream): Response {
        var outcome = ContainerTexts.t("service_cnl_result_ok")
        val response = try {
            when {
                // CORS preflight: recent Chrome versions (Local Network Access)
                // ask via OPTIONS before fetch/XHR to localhost.
                request.method == "OPTIONS" -> {
                    outcome = ContainerTexts.t("service_cnl_result_preflight")
                    Response(Status.NO_CONTENT, MIME_PLAINTEXT, "")
                }
                request.path == "/jdcheck.js" -> Response(
                    // Must be served as JavaScript: with text/html browsers
                    // refuse to execute it and the page assumes there is no
                    // download manager.
                    Status.OK, "text/javascript",
                    "jdownloader=true; var jd_version='JDAndroid';"
                )
                request.path == "/crossdomain.xml" -> Response(
                    Status.OK, "application/xml",
                    """<?xml version="1.0"?><cross-domain-policy>""" +
                        """<allow-access-from domain="*"/></cross-domain-policy>"""
                )
                request.path in ADD_PATHS -> {
                    val (resp, note) = handleAdd(request, input)
                    outcome = note
                    resp
                }
                // Root as a liveness check; everything else is unknown
                request.path == "/" -> Response(Status.OK, MIME_HTML, "JDAndroid")
                else -> {
                    outcome = ContainerTexts.t("service_cnl_result_unknown_path")
                    Response(Status.NOT_FOUND, MIME_PLAINTEXT, "not found\r\n")
                }
            }
        } catch (e: ContainerDecrypter.ContainerException) {
            AppLog.w(TAG, "Abgelehnt bei ${request.path}: ${e.message}")
            outcome = ContainerTexts.t("service_cnl_result_rejected", e.message.orEmpty())
            Response(Status.BAD_REQUEST, MIME_PLAINTEXT, "failed\r\n")
        } catch (e: Exception) {
            AppLog.w(TAG, "Fehler bei ${request.path}: ${e.message}")
            outcome = ContainerTexts.t("service_cnl_result_error", e.message ?: e.javaClass.simpleName)
            Response(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "failed\r\n")
        }
        CnlStatus.record(request.method, request.path, outcome)
        return response.withCors(request)
    }

    private fun Response.withCors(request: Request): Response {
        // Mirror the Origin, otherwise the browser rejects responses with
        // credentials; without an Origin (form POST) "*" remains.
        headers["Access-Control-Allow-Origin"] = request.headers["origin"] ?: "*"
        headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
        // Mirror the requested headers, otherwise the preflight fails on a
        // header the page sends in addition
        headers["Access-Control-Allow-Headers"] =
            request.headers["access-control-request-headers"]?.ifBlank { null }
                ?: "Content-Type, X-Requested-With"
        // Chrome: older name (Private Network Access) and newer name (Local
        // Network Access, Chrome 138+); set both
        headers["Access-Control-Allow-Private-Network"] = "true"
        headers["Access-Control-Allow-Local-Network"] = "true"
        headers["Access-Control-Max-Age"] = "86400"
        return this
    }

    /** Response plus a short description of the outcome for the status line. */
    private fun handleAdd(request: Request, input: InputStream): Pair<Response, String> {
        // Any page open in the browser may post here: limit the size,
        // otherwise a 50 MB body crashes the app with an OOM.
        val length = request.headers["content-length"]?.trim()?.toLongOrNull()
        if (length != null && length > MAX_BODY_BYTES) {
            return Response(Status.PAYLOAD_TOO_LARGE, MIME_PLAINTEXT, "failed\r\n") to
                ContainerTexts.t("service_cnl_result_too_large")
        }
        val params = LinkedHashMap(request.query)
        if (request.method == "POST" || request.method == "PUT") {
            val body = readBody(input, length ?: 0L)
            val contentType = request.headers["content-type"].orEmpty().lowercase(Locale.ROOT)
            if (contentType.startsWith(MIME_FORM)) {
                // GET parameters take precedence over form fields of the same name
                decodeParams(body).forEach { (k, v) -> params.putIfAbsent(k, v) }
            }
        }

        val crypted = params["crypted"]
        val jk = params["jk"]
        val plainUrls = params["urls"] ?: params["links"]

        val links = when {
            // Click'n'Load 2: AES-encrypted link list, key in jk
            !crypted.isNullOrBlank() && !jk.isNullOrBlank() ->
                ContainerDecrypter.decryptClickNLoad(crypted, jk)
            // Older variant (/flash/addcrypted): a complete DLC container
            !crypted.isNullOrBlank() ->
                ContainerDecrypter.decryptDlc(crypted)
            !plainUrls.isNullOrBlank() ->
                plainUrls.split('\n', '\r', ' ').filter { it.startsWith("http") }
            else -> emptyList()
        }

        if (links.isEmpty()) {
            // Like JDownloader: the page then shows "failed" instead of
            // wrongly reporting success.
            val note = if (crypted.isNullOrBlank() && plainUrls.isNullOrBlank()) {
                val fields = params.keys.joinToString(",")
                    .ifBlank { ContainerTexts.t("service_cnl_result_no_fields") }
                ContainerTexts.t("service_cnl_result_no_links_in_form", fields)
            } else ContainerTexts.t("service_cnl_result_nothing_decrypted")
            return Response(Status.BAD_REQUEST, MIME_PLAINTEXT, "failed\r\n") to note
        }

        // Cap package name and passwords: a page must not fill the password
        // list without limit (every extraction tries all entries)
        val passwords = params["passwords"].orEmpty()
            .split('\n', '\r')
            .map { it.trim().take(MAX_PASSWORD_LENGTH) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_PASSWORDS)
        onRequest(
            CnlRequest(
                urls = links.take(MAX_LINKS),
                packageName = params["package"]?.trim()?.take(MAX_PACKAGE_LENGTH)?.ifBlank { null },
                passwords = passwords,
                source = params["source"]?.trim()?.ifBlank { null }
                    ?: request.headers["referer"]?.takeIf { it.isNotBlank() }
            )
        )
        return Response(Status.OK, MIME_HTML, "success\r\n") to ContainerTexts.quantity(
            "service_cnl_result_links_taken_one", "service_cnl_result_links_taken_other", links.size
        )
    }

    /** Reads exactly [length] bytes (nothing without Content-Length); stops on a short stream. */
    private fun readBody(input: InputStream, length: Long): String {
        if (length <= 0) return ""
        val buffer = ByteArray(length.toInt())
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        return String(buffer, 0, read, Charsets.UTF_8)
    }

    /** HTTP status with the text used in the status line. */
    enum class Status(val code: Int, val text: String) {
        OK(200, "OK"),
        NO_CONTENT(204, "No Content"),
        BAD_REQUEST(400, "Bad Request"),
        NOT_FOUND(404, "Not Found"),
        PAYLOAD_TOO_LARGE(413, "Payload Too Large"),
        HEADERS_TOO_LARGE(431, "Request Header Fields Too Large"),
        INTERNAL_ERROR(500, "Internal Server Error")
    }

    /** Parse error that goes straight back to the client as the response. */
    private class HttpError(val status: Status, val text: String) : Exception(text)

    /**
     * Parsed request: method, path without query, GET parameters and headers
     * (names lower-cased, first occurrence wins).
     */
    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>
    ) {
        companion object {
            fun parse(input: InputStream): Request {
                val requestLine = readLine(input) ?: throw badRequest()
                val parts = requestLine.split(' ')
                if (parts.size != 3 || parts[1].isEmpty() || !parts[2].startsWith("HTTP/")) {
                    throw badRequest()
                }
                val method = parts[0].uppercase(Locale.ROOT)
                if (method.isEmpty() || method.any { !it.isLetter() }) throw badRequest()
                val target = parts[1]
                val path = target.substringBefore('?')
                val query = if ('?' in target) decodeParams(target.substringAfter('?')) else emptyMap()

                val headers = HashMap<String, String>()
                var total = 0
                while (true) {
                    val line = readLine(input) ?: throw badRequest()
                    if (line.isEmpty()) break
                    total += line.length
                    if (headers.size >= MAX_HEADERS || total > MAX_HEADER_BYTES) throw headersTooLarge()
                    val colon = line.indexOf(':')
                    if (colon <= 0) throw badRequest()
                    val name = line.substring(0, colon).trim().lowercase(Locale.ROOT)
                    val value = line.substring(colon + 1).trim()
                    headers.putIfAbsent(name, value)
                }
                return Request(method, path, query, headers)
            }

            private fun badRequest() = HttpError(Status.BAD_REQUEST, "bad request\r\n")
            private fun headersTooLarge() = HttpError(Status.HEADERS_TOO_LARGE, "headers too large\r\n")

            /**
             * Reads a CRLF-terminated line as ISO-8859-1 without reading past
             * the line end (the body follows directly). Null at end of stream,
             * [HttpError] for an overlong line.
             */
            private fun readLine(input: InputStream): String? {
                val bytes = ByteArrayOutputStream()
                while (true) {
                    val b = input.read()
                    if (b < 0) return if (bytes.size() == 0) null else bytes.toString("ISO-8859-1")
                    if (b == '\n'.code) break
                    if (b != '\r'.code) bytes.write(b)
                    if (bytes.size() > MAX_LINE_BYTES) throw headersTooLarge()
                }
                return bytes.toString("ISO-8859-1")
            }
        }
    }

    /** Response with a fixed length; the connection is closed afterwards. */
    private class Response(val status: Status, val mimeType: String, body: String) {
        val headers = LinkedHashMap<String, String>()
        private val bodyBytes = body.toByteArray(Charsets.UTF_8)

        fun toBytes(): ByteArray {
            val head = StringBuilder()
            head.append("HTTP/1.1 ").append(status.code).append(' ').append(status.text).append("\r\n")
            head.append("Content-Type: ").append(mimeType).append("\r\n")
            head.append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            head.append("Connection: close\r\n")
            headers.forEach { (name, value) -> head.append(name).append(": ").append(value).append("\r\n") }
            head.append("\r\n")
            return head.toString().toByteArray(Charsets.ISO_8859_1) + bodyBytes
        }
    }

    companion object {
        const val PORT = 9666
        const val LOOPBACK = "127.0.0.1"
        const val MAX_BODY_BYTES = 2L * 1024 * 1024
        const val MAX_PACKAGE_LENGTH = 200
        const val MAX_PASSWORDS = 50
        const val MAX_PASSWORD_LENGTH = 200
        const val MAX_LINKS = 5000
        private const val TAG = "ClickNLoad"
        private const val BACKLOG = 16
        private const val WORKER_THREADS = 4
        private const val SOCKET_READ_TIMEOUT_MS = 5000
        private const val MAX_LINE_BYTES = 8 * 1024
        private const val MAX_HEADERS = 64
        private const val MAX_HEADER_BYTES = 32 * 1024
        private const val MIME_PLAINTEXT = "text/plain"
        private const val MIME_HTML = "text/html"
        private const val MIME_FORM = "application/x-www-form-urlencoded"
        private val ADD_PATHS = setOf(
            "/flashgot", "/flash/add", "/flash/addcrypted", "/flash/addcrypted2"
        )

        /**
         * Splits "a=1&b=2" into a map (first occurrence wins). "+" becomes a
         * space as in forms; pages that send unencoded Base64 are repaired by
         * the decrypter.
         */
        private fun decodeParams(encoded: String): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            encoded.split('&').forEach { pair ->
                if (pair.isEmpty()) return@forEach
                val name = decode(pair.substringBefore('='))
                val value = if ('=' in pair) decode(pair.substringAfter('=')) else ""
                if (name.isNotEmpty()) result.putIfAbsent(name, value)
            }
            return result
        }

        private fun decode(value: String): String =
            runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

        /**
         * Self test from the settings: requests /jdcheck.js on loopback like a
         * browser and returns a message for display. Blocks (network), so do
         * not call it on the main thread.
         */
        fun selfTest(port: Int = PORT): String = runCatching {
            val connection = URL("http://$LOOPBACK:$port/jdcheck.js").openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            try {
                val code = connection.responseCode
                if (code in 200..299) ContainerTexts.t("service_cnl_selftest_ok", code)
                else ContainerTexts.t("service_cnl_selftest_http", code)
            } finally {
                connection.disconnect()
            }
        }.getOrElse { ContainerTexts.t("service_cnl_selftest_unreachable", it.message ?: it.javaClass.simpleName) }
    }
}
