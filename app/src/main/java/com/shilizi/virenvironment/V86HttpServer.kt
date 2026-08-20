package com.shilizi.virenvironment

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.withLock
import java.util.concurrent.locks.ReentrantLock

/**
 * 极简本地 HTTP 服务器，为 WebView 提供 v86 运行时与磁盘镜像。
 *
 * 不用 WebViewAssetLoader 的原因：在部分系统/WebView 上 appassets 域会拦截
 * 内联脚本执行；本地 HTTP 服务是 v86 类应用的标准做法。服务器只监听
 * 127.0.0.1 回环地址，不暴露到局域网。
 *
 * 支持：
 * - GET /assets/v86/（静态文件：js/wasm/bin 带正确 MIME）
 * - GET /disk（流式提供磁盘镜像：SAF Uri 或本地文件路径）
 * - Range 请求（v86 读 ISO 需要，避免整体载入内存）
 */
internal class V86HttpServer(
    private val context: Context,
    private val diskUri: Uri?,
    private val diskPath: String?
) {
    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(true)
    private val lock = ReentrantLock()

    val port: Int get() = serverSocket.localPort

    fun start() {
        executor.execute {
            while (running.get()) {
                try {
                    val socket = serverSocket.accept()
                    executor.execute { handle(socket) }
                } catch (_: IOException) {
                    if (running.get()) break
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore('?')

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val index = line.indexOf(':')
                if (index > 0) {
                    headers[line.substring(0, index).trim().lowercase(Locale.ROOT)] =
                        line.substring(index + 1).trim()
                }
            }

            when {
                method == "GET" && path == "/disk" -> serveDisk(output, headers)
                method == "GET" && path.startsWith("/assets/") -> serveAsset(output, path.removePrefix("/assets/"))
                else -> respond(output, 404, "text/plain", "Not Found", null)
            }
        } catch (_: IOException) {
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun serveDisk(output: OutputStream, headers: Map<String, String>) {
        var file: File? = null
        var input: InputStream? = null

        diskPath?.let {
            val f = File(it)
            if (f.isFile) file = f
        }
        if (file == null && diskUri != null) {
            val stream = runCatching { context.contentResolver.openInputStream(diskUri!!) }.getOrNull()
            if (stream != null) input = stream
        }
        if (file == null && input == null) {
            respond(output, 404, "text/plain", "No disk image", null)
            return
        }

        val length = file?.length() ?: input?.let { (it.available()).toLong() } ?: 0L
        var start: Long = 0
        var end: Long = length - 1
        var contentRange: String? = null
        var status = "200 OK"

        val rangeHeader = headers["range"]
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val spec = rangeHeader.removePrefix("bytes=").trim()
            val dash = spec.indexOf('-')
            if (dash > 0) {
                start = spec.substring(0, dash).toLongOrNull() ?: 0
                val suffix = spec.substring(dash + 1)
                end = if (suffix.isEmpty()) length - 1 else suffix.toLongOrNull()?.coerceAtMost(length - 1) ?: (length - 1)
            } else if (dash == 0) {
                val suffix = spec.substring(1).toLongOrNull() ?: 0
                start = (length - suffix).coerceAtLeast(0)
                end = length - 1
            }
            if (start > end || start >= length) {
                respond(output, 416, "text/plain", "Range Not Satisfiable", null)
                return
            }
            status = "206 Partial Content"
            contentRange = "bytes $start-$end/$length"
        }

        val responseHead = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: ${end - start + 1}\r\n")
            if (contentRange != null) append("Content-Range: $contentRange\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(responseHead.toByteArray(Charsets.UTF_8))
        output.flush()

        try {
            if (file != null) {
                FileInputStream(file).use { fileInput ->
                    fileInput.skip(start)
                    copyRange(fileInput, output, end - start + 1)
                }
            } else if (input != null) {
                val buffered = BufferedInputStream(input)
                buffered.skip(start)
                copyRange(buffered, output, end - start + 1)
            }
        } finally {
            output.flush()
            runCatching { input?.close() }
        }
    }

    private fun copyRange(input: InputStream, output: OutputStream, count: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = count
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun serveAsset(output: OutputStream, path: String) {
        val mime = when {
            path.endsWith(".html") -> "text/html; charset=utf-8"
            path.endsWith(".js") -> "application/javascript; charset=utf-8"
            path.endsWith(".wasm") -> "application/wasm"
            path.endsWith(".bin") -> "application/octet-stream"
            else -> "application/octet-stream"
        }
        val input = runCatching { context.assets.open(path) }.getOrNull()
        if (input == null) {
            respond(output, 404, "text/plain", "Not Found", null)
            return
        }
        respond(output, 200, mime, null, input)
    }

    private fun respond(
        output: OutputStream,
        status: Int,
        contentType: String,
        message: String?,
        body: InputStream?
    ) {
        val statusText = when (status) {
            200 -> "OK"; 206 -> "Partial Content"; 404 -> "Not Found"; 416 -> "Range Not Satisfiable"
            else -> "Error"
        }
        val bytes: ByteArray = if (body != null) {
            body.readBytes()
        } else {
            message?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        }
        val head = buildString {
            append("HTTP/1.1 $status $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else bytes.toString("ISO-8859-1")
            if (b == '\n'.code) {
                val line = bytes.toString("ISO-8859-1").trimEnd('\r')
                return line
            }
            bytes.write(b)
        }
    }

    fun stop() {
        running.set(false)
        lock.withLock {
            runCatching { serverSocket.close() }
        }
        executor.shutdownNow()
    }
}
