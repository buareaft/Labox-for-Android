package com.shilizi.virenvironment

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * QMP（QEMU Machine Protocol）客户端。
 *
 * QMP 是 QEMU 现代控制协议：JSON-RPC over TCP。
 * 支持 stop/cont/quit 以及（新版）截图、注入键鼠等。
 * 用法：连接后先读 greeting + qmp_capabilities 握手，再发命令。
 */
class QmpClient(host: String, port: Int) {
    private val socket = Socket()
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null
    private var connected = false

    init {
        try {
            socket.connect(InetSocketAddress(host, port), 5000)
            socket.soTimeout = 5000
            writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
            reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

            // 读 greeting（QMP 首条消息，{"QMP":{...}}，不含 return/error）
            readGreeting()
            // 握手
            sendRaw("""{"execute":"qmp_capabilities"}""")
            readResponse()
            connected = true
        } catch (e: IOException) {
            try { socket.close() } catch (_: IOException) {}
            throw e
        }
    }

    /** 读 QMP greeting（不跳过 event，greeting 本身不含 return/error）。 */
    private fun readGreeting(): String {
        val r = reader ?: throw IOException("QMP 未连接")
        val line = r.readLine() ?: throw IOException("QMP 连接关闭")
        return line
    }

    private fun sendRaw(message: String) {
        val w = writer ?: throw IOException("QMP 未连接")
        w.write(message + "\n")
        w.flush()
    }

    private fun readResponse(): String {
        val r = reader ?: throw IOException("QMP 未连接")
        // QEMU 会异步发 event（如 RESET/POWERDOWN），必须跳过 event 行，
        // 只认命令响应（含 "return" 或 "error"），否则协议错位。
        while (true) {
            val line = r.readLine() ?: throw IOException("QMP 连接关闭")
            if (line.contains("\"return\"") || line.contains("\"error\"")) return line
            // 忽略 event 行（{"event":"..."}）
        }
    }

    /** 执行 QMP 命令，返回响应（或 null 超时）。 */
    @Synchronized
    fun execute(command: String): String? = execute(command, null)

    /** 执行带参数的 QMP 命令，返回响应（或 null 超时/失败）。 */
    @Synchronized
    fun execute(command: String, arguments: Map<String, Any>?): String? {
        if (!connected) return null
        return try {
            val json = if (arguments == null || arguments.isEmpty()) {
                """{"execute":"$command"}"""
            } else {
                val argsJson = arguments.entries.joinToString(",") { (k, v) ->
                    "\"$k\":${toJson(v)}"
                }
                """{"execute":"$command","arguments":{$argsJson}}"""
            }
            sendRaw(json)
            readResponse()
        } catch (e: java.net.SocketTimeoutException) {
            // 读响应超时：QEMU 在 TCG 下繁忙（如 Win11 引导）时 screendump 可能 >5s。
            // 超时不代表连接断开——置 connected=false 会让一次慢截图永久杀掉 QMP，
            // 后续所有输入/控制命令全部失效。这里保留连接，交给调用方按 null 重试。
            null
        } catch (e: IOException) {
            connected = false
            null
        }
    }

    /** 递归序列化 JSON 值（支持 String/Int/Long/Boolean/List/Map）。 */
    private fun toJson(v: Any): String = when (v) {
        is String -> "\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        is Int -> v.toString()
        is Long -> v.toString()
        is Boolean -> v.toString()
        is List<*> -> v.joinToString(",", "[", "]") { toJson(it!!) }
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { "\"${it.key}\":${toJson(it.value!!)}" }
        else -> "\"$v\""
    }

    fun close() {
        connected = false
        try { socket.close() } catch (_: IOException) {}
    }
}
