package com.rstar.printerbridge

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.Charset
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LocalHttpServer {
    private val running = AtomicBoolean(false)
    private val active = AtomicBoolean(false)
    private val enabled = AtomicBoolean(true)
    private val workers = Executors.newCachedThreadPool()
    @Volatile private var serverSocket: ServerSocket? = null

    private val allowedOrigins = setOf(
        "https://rstar.rangerlive.com",
        "http://localhost:4200",
        "http://127.0.0.1:4200"
    )

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread {
            try {
                val ss = ServerSocket()
                serverSocket = ss
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("127.0.0.1", 3001))
                while (running.get()) {
                    val client = ss.accept()
                    workers.execute { handleClient(client) }
                }
            } catch (e: Exception) {
                if (running.get()) android.util.Log.e("RStarBridge", "HTTP server error", e)
            } finally {
                running.set(false)
            }
        }.start()
    }

    fun stopServer() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        workers.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 20000
                val input = client.getInputStream()
                val headerBytes = readHeaders(input)
                val headerText = String(headerBytes, Charset.forName("ISO-8859-1"))
                val lines = headerText.split("\r\n")
                if (lines.isEmpty() || lines[0].isBlank()) {
                    writeResponse(client, 400, JSONObject().put("error", "Invalid HTTP request."))
                    return
                }

                val requestParts = lines[0].split(" ")
                if (requestParts.size < 2) {
                    writeResponse(client, 400, JSONObject().put("error", "Invalid HTTP request."))
                    return
                }
                val method = requestParts[0].uppercase()
                val path = requestParts[1].substringBefore("?")
                val headers = mutableMapOf<String, String>()
                for (line in lines.drop(1)) {
                    val index = line.indexOf(':')
                    if (index > 0) headers[line.substring(0, index).trim().lowercase()] =
                        line.substring(index + 1).trim()
                }

                val origin = headers["origin"]
                if (origin != null && origin !in allowedOrigins) {
                    writeResponse(client, 403, JSONObject().put("error", "Origin is not allowed."))
                    return
                }

                if (method == "OPTIONS") {
                    if (origin == null) {
                        writeResponse(client, 403, JSONObject().put("error", "A trusted application origin is required."))
                    } else {
                        writeResponse(client, 204, JSONObject(), origin, headers["access-control-request-private-network"] == "true")
                    }
                    return
                }

                if (path == "/api/bridge/status" && method == "GET") {
                    writeResponse(client, 200, JSONObject()
                        .put("enabled", enabled.get())
                        .put("active", active.get()), origin)
                    return
                }

                if (path == "/api/bridge/start" || path == "/api/bridge/stop") {
                    if (method != "POST") {
                        writeResponse(client, 405, JSONObject().put("error", "Use POST to change bridge state."), origin)
                        return
                    }
                    if (origin == null || origin !in allowedOrigins) {
                        writeResponse(client, 403, JSONObject().put("error", "A trusted application origin is required."))
                        return
                    }
                    if (!isJson(headers)) {
                        writeResponse(client, 415, JSONObject().put("error", "Expected application/json."), origin)
                        return
                    }
                    readBody(input, headers, 1024)
                    if (path.endsWith("/start")) {
                        enabled.set(true)
                        writeResponse(client, 200, JSONObject().put("enabled", true).put("active", active.get()), origin)
                    } else if (active.get()) {
                        writeResponse(client, 409, JSONObject().put("error", "A print job is in progress; wait before stopping the bridge."), origin)
                    } else {
                        enabled.set(false)
                        writeResponse(client, 200, JSONObject().put("enabled", false).put("active", active.get()), origin)
                    }
                    return
                }

                if (path != "/api/print") {
                    writeResponse(client, 404, JSONObject().put("error", "Not found"), origin)
                    return
                }
                if (method != "POST") {
                    writeResponse(client, 405, JSONObject().put("error", "Use POST to print."), origin)
                    return
                }
                if (!isJson(headers)) {
                    writeResponse(client, 415, JSONObject().put("error", "Expected application/json."), origin)
                    return
                }
                if (!enabled.get()) {
                    writeResponse(client, 503, JSONObject().put("error", "The print bridge is stopped. Start it in Settings & testing."), origin)
                    return
                }

                val body = readBody(input, headers, 2 * 1024 * 1024)
                val json = JSONObject(String(body, Charsets.UTF_8))
                val ip = json.optString("ip", "")
                val port = json.optInt("port", -1)
                val bytesJson = json.optJSONArray("bytes")

                if (!validIpv4(ip) || port !in 1..65535 || bytesJson == null ||
                    bytesJson.length() == 0 || bytesJson.length() > 524288) {
                    writeResponse(client, 400, JSONObject().put("error", "Invalid IP, port, or byte array."), origin)
                    return
                }

                val bytes = ByteArray(bytesJson.length())
                for (i in 0 until bytesJson.length()) {
                    val value = bytesJson.optInt(i, -1)
                    if (value !in 0..255) {
                        writeResponse(client, 400, JSONObject().put("error", "Invalid IP, port, or byte array."), origin)
                        return
                    }
                    bytes[i] = value.toByte()
                }

                if (!active.compareAndSet(false, true)) {
                    writeResponse(client, 409, JSONObject().put("error", "A job is already active. Wait before retrying."), origin)
                    return
                }

                try {
                    android.util.Log.i("RStarBridge", "Connecting to printer $ip:$port")
                    Socket().use { printer ->
                        printer.connect(InetSocketAddress(ip, port), 15000)
                        printer.soTimeout = 15000
                        printer.getOutputStream().use { output ->
                            output.write(bytes)
                            output.flush()
                        }
                    }
                    writeResponse(client, 200, JSONObject().put(
                        "message",
                        "Receipt bytes sent to the printer. Physical print completion is not confirmed."
                    ), origin)
                } catch (e: Exception) {
                    android.util.Log.e("RStarBridge", "TCP print failed", e)
                    writeResponse(client, 502, JSONObject().put("error", "TCP print failed (${e.message}). Check the printer before retrying."), origin)
                } finally {
                    active.set(false)
                }
            } catch (e: BodyTooLargeException) {
                writeResponse(socket, 413, JSONObject().put("error", e.message ?: "Request too large."))
            } catch (e: Exception) {
                android.util.Log.e("RStarBridge", "Request error", e)
                try {
                    writeResponse(socket, 400, JSONObject().put("error", "Invalid or interrupted JSON request."))
                } catch (_: Exception) {}
            }
        }
    }

    private fun readHeaders(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        var matched = 0
        val marker = byteArrayOf(13, 10, 13, 10)
        while (out.size() < 32768) {
            val b = input.read()
            if (b == -1) break
            out.write(b)
            if (b.toByte() == marker[matched]) {
                matched++
                if (matched == marker.size) return out.toByteArray()
            } else {
                matched = if (b == 13) 1 else 0
            }
        }
        throw IllegalArgumentException("HTTP headers are missing or too large.")
    }

    private fun readBody(input: InputStream, headers: Map<String, String>, maxBytes: Int): ByteArray {
        val length = headers["content-length"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Content-Length is required.")
        if (length < 0 || length > maxBytes) throw BodyTooLargeException("Request payload too large.")
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(out, offset, length - offset)
            if (count <= 0) throw IllegalArgumentException("Incomplete request body.")
            offset += count
        }
        return out
    }

    private fun isJson(headers: Map<String, String>) =
        headers["content-type"]?.startsWith("application/json", ignoreCase = true) == true

    private fun validIpv4(ip: String): Boolean {
        val parts = ip.split(".")
        return parts.size == 4 && parts.all {
            val n = it.toIntOrNull()
            n != null && n in 0..255 && it.isNotEmpty()
        }
    }

    private fun writeResponse(
        socket: Socket,
        status: Int,
        body: JSONObject,
        origin: String? = null,
        allowPrivateNetwork: Boolean = false
    ) {
        val reason = when (status) {
            200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 403 -> "Forbidden"
            404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"
            413 -> "Payload Too Large"; 415 -> "Unsupported Media Type"; 502 -> "Bad Gateway"
            503 -> "Service Unavailable"; else -> "Error"
        }
        val bodyBytes = if (status == 204) ByteArray(0) else body.toString().toByteArray(Charsets.UTF_8)
        val headers = StringBuilder()
        headers.append("HTTP/1.1 $status $reason\r\n")
        headers.append("Content-Type: application/json; charset=utf-8\r\n")
        headers.append("Cache-Control: no-store\r\n")
        headers.append("Connection: close\r\n")
        if (origin != null) {
            headers.append("Access-Control-Allow-Origin: $origin\r\n")
            headers.append("Vary: Origin\r\n")
            headers.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            headers.append("Access-Control-Allow-Headers: Content-Type\r\n")
        }
        if (allowPrivateNetwork) headers.append("Access-Control-Allow-Private-Network: true\r\n")
        headers.append("Content-Length: ${bodyBytes.size}\r\n\r\n")
        socket.getOutputStream().apply {
            write(headers.toString().toByteArray(Charsets.ISO_8859_1))
            write(bodyBytes)
            flush()
        }
    }

    private class BodyTooLargeException(message: String) : Exception(message)
}
