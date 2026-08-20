package com.shilizi.virenvironment

import android.graphics.Bitmap
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 极简 RFB (VNC) 客户端，连接 QEMU 的 VNC 输出并把画面渲染到 Bitmap。
 *
 * 支持 RFB 3.8/3.7/3.3 握手、无认证（QEMU 默认）、32 位真彩像素格式、
 * Raw 与 CopyRect 编码、增量更新。回环连接下 Raw 编码带宽足够。
 */
internal class VncClient(
    private val port: Int,
    private val onConnected: (width: Int, height: Int) -> Unit,
    private val onFrame: (Bitmap) -> Unit
) {
    private val running = AtomicBoolean(true)
    private val destroyed = AtomicBoolean(false)
    private val socketLock = Object()
    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private var thread: Thread? = null

    var framebuffer: Bitmap? = null
        private set
    var width: Int = 0
        private set
    var height: Int = 0
        private set

    fun start() {
        thread = Thread({ run() }, "labox-vnc").also { it.start() }
    }

    private fun run() {
        var connected = false
        // QEMU 异步监听 VNC 端口，重试连接
        for (attempt in 0 until 150) {
            if (!running.get() || destroyed.get()) return
            try {
                Log.i(TAG, "VNC 连接尝试 #$attempt -> 127.0.0.1:$port")
                val s = Socket()
                s.connect(java.net.InetSocketAddress("127.0.0.1", port), 1000)
                s.tcpNoDelay = true
                s.soTimeout = 8000
                socket = s
                handshake(s)
                Log.i(TAG, "VNC 连接成功, ${width}x${height}")
                connected = true
                break
            } catch (e: IOException) {
                Log.w(TAG, "VNC 连接失败 #$attempt: ${e.message}")
                closeSocket()
                Thread.sleep(200)
            } catch (_: Exception) {
                closeSocket()
                Thread.sleep(200)
            }
        }
        if (!connected) { Log.e(TAG, "VNC 30 秒内未连接成功"); return }
        try {
            messageLoop()
        } catch (_: Exception) {
        } finally {
            closeSocket()
        }
    }

    private fun handshake(socket: Socket) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

        // 1. 版本协商
        out.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
        out.flush()
        val version = ByteArray(12)
        input.readFully(version)
        val versionStr = String(version, Charsets.US_ASCII)
        Log.i(TAG, "VNC 版本: ${versionStr.trim()}")
        val minor = when {
            versionStr.startsWith("RFB 003.008") -> 8
            versionStr.startsWith("RFB 003.007") -> 7
            else -> 3 // 3.3 或更老
        }

        // 2. 安全类型协商（3.7+）
        if (minor >= 7) {
            val count = input.readUnsignedByte()
            if (count == 0) {
                // 服务器直接拒绝
                val reasonLen = input.readInt()
                val reason = ByteArray(reasonLen)
                input.readFully(reason)
                throw IOException("VNC 服务器拒绝连接: ${String(reason)}")
            }
            val types = ByteArray(count)
            input.readFully(types)
            val none = types.any { it == 1.toByte() }
            if (!none) {
                throw IOException("VNC 需要认证，当前仅支持无认证连接")
            }
            out.writeByte(1) // 选择 None
            out.flush()
            // 3.8 有 SecurityResult
            if (minor >= 8) {
                val result = input.readInt()
                if (result != 0) throw IOException("VNC 安全握手失败")
            }
        } else {
            // 3.3: 服务器直接发 SecurityResult
            val result = input.readInt()
            if (result != 0) throw IOException("VNC 认证失败")
        }

        // 3. ClientInit
        out.writeByte(1) // shared
        out.flush()

        // 4. ServerInit
        width = input.readUnsignedShort()
        height = input.readUnsignedShort()
        val serverPixelFormat = ByteArray(16)
        input.readFully(serverPixelFormat)
        val nameLen = input.readInt()
        val name = ByteArray(nameLen)
        input.readFully(name)

        // 5. 请求 32 位真彩像素格式
        val fmt = ByteArray(16)
        fmt[0] = 0
        fmt[1] = 32   // bits-per-pixel
        fmt[2] = 24   // depth
        fmt[3] = 0    // big-endian (小端)
        fmt[4] = 1    // true-colour
        fmt[5] = 0; fmt[6] = (255).toByte()  // red-max
        fmt[7] = 0; fmt[8] = (255).toByte()  // green-max
        fmt[9] = 0; fmt[10] = (255).toByte() // blue-max
        fmt[11] = 16  // red-shift
        fmt[12] = 8   // green-shift
        fmt[13] = 0   // blue-shift
        fmt[14] = 0; fmt[15] = 0
        out.writeByte(0) // SetPixelFormat
        out.write(fmt)
        Log.i(TAG, "VNC 已发送 SetPixelFormat (32bpp)")

        // 6. SetEncodings: Raw(0), CopyRect(1)
        // RFB 3.8: type(1) + padding(1) + count(2) + encoding(4)*n
        out.writeByte(2)
        out.writeByte(0) // padding
        out.writeShort(2)
        out.writeInt(0) // Raw
        out.writeInt(1) // CopyRect
        out.flush()

        this.output = out

        framebuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        onConnected(width, height)

        // 7. 请求全屏更新
        requestUpdate(false)
        Log.i(TAG, "VNC 握手完成，请求全屏更新")
    }

    private fun messageLoop() {
        val input = DataInputStream(BufferedInputStream(socket!!.getInputStream()))
        var frameCount = 0
        while (running.get() && !destroyed.get()) {
            val type = try {
                input.readUnsignedByte()
            } catch (_: EOFException) {
                Log.e(TAG, "VNC 连接被对端关闭 (EOF)")
                break
            } catch (_: IOException) {
                Log.e(TAG, "VNC 读取失败")
                break
            }
            when (type) {
                0 -> {
                    handleFramebufferUpdate(input)
                    if (++frameCount % 20 == 0) Log.i(TAG, "VNC 已接收 $frameCount 帧")
                }
                2 -> { /* Bell: 忽略 */ }
                3 -> {
                    // ServerCutText
                    input.skipBytes(3)
                    val len = input.readInt()
                    input.skipBytes(len)
                }
                255 -> break
                else -> {
                    // 未知消息：无法确定长度，断开避免死循环
                    break
                }
            }
        }
    }

    private fun handleFramebufferUpdate(input: DataInputStream) {
        input.readUnsignedByte() // padding
        val rectCount = input.readUnsignedShort()
        val bmp = framebuffer ?: return

        repeat(rectCount) {
            val x = input.readUnsignedShort()
            val y = input.readUnsignedShort()
            val w = input.readUnsignedShort()
            val h = input.readUnsignedShort()
            val encoding = input.readInt()
            when (encoding) {
                0 -> readRaw(input, bmp, x, y, w, h)
                1 -> readCopyRect(input, bmp, x, y, w, h)
                else -> {
                    Log.e(TAG, "不支持的 VNC 编码: $encoding")
                    throw IOException("不支持的 VNC 编码: $encoding")
                }
            }
        }
        onFrame(bmp)
        requestUpdate(true)
    }

    private fun readRaw(input: DataInputStream, bmp: Bitmap, x: Int, y: Int, w: Int, h: Int) {
        val pixelBytes = w * h * 4
        val data = ByteArray(pixelBytes)
        input.readFully(data)
        val ints = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        val row = IntArray(w)
        for (rowIndex in 0 until h) {
            ints.get(row)
            // VNC 小端 32bpp: [B,G,R,0] -> ARGB int
            for (i in 0 until w) row[i] = row[i] or -0x1000000
            bmp.setPixels(row, 0, w, x, y + rowIndex, w, 1)
        }
    }

    private fun readCopyRect(input: DataInputStream, bmp: Bitmap, x: Int, y: Int, w: Int, h: Int) {
        val srcX = input.readUnsignedShort()
        val srcY = input.readUnsignedShort()
        val src = IntArray(w * h)
        bmp.getPixels(src, 0, w, srcX, srcY, w, h)
        bmp.setPixels(src, 0, w, x, y, w, h)
    }

    /** 发送鼠标事件。buttons: bit0=左, bit1=中, bit2=右。 */
    fun sendPointer(buttons: Int, x: Int, y: Int) {
        synchronized(socketLock) {
            val out = output ?: return
            try {
                out.writeByte(5)
                out.writeByte(buttons)
                out.writeShort(x.coerceIn(0, width - 1))
                out.writeShort(y.coerceIn(0, height - 1))
                out.flush()
            } catch (_: IOException) {
            }
        }
    }

    /** 发送键盘事件。keysym 为 X11 keysym。 */
    fun sendKey(down: Boolean, keysym: Int) {
        synchronized(socketLock) {
            val out = output ?: return
            try {
                out.writeByte(4)
                out.writeByte(if (down) 1 else 0)
                out.writeShort(0)
                out.writeInt(keysym)
                out.flush()
            } catch (_: IOException) {
            }
        }
    }

    /** 请求增量帧缓冲更新（incremental=true 表示只要变化区域）。 */
    fun requestUpdate(incremental: Boolean) {
        synchronized(socketLock) {
            val out = output ?: return
            try {
                out.writeByte(3) // FramebufferUpdateRequest
                out.writeByte(if (incremental) 1 else 0)
                out.writeShort(0)
                out.writeShort(0)
                out.writeShort(width)
                out.writeShort(height)
                out.flush()
            } catch (_: IOException) {
            }
        }
    }

    fun close() {
        running.set(false)
        destroyed.set(true)
        synchronized(socketLock) {
            try { socket?.close() } catch (_: IOException) {}
            socket = null
            output = null
        }
        thread?.interrupt()
    }

    private fun closeSocket() {
        synchronized(socketLock) {
            try { socket?.close() } catch (_: IOException) {}
            socket = null
            output = null
        }
    }

    companion object {
        private const val TAG = "LaboxVnc"

        /** 把字符转成 X11 keysym。 */
        fun keysymFor(char: Char): Int = when (char) {
            '\n', '\r' -> 0xFF0D
            '\t' -> 0xFF09
            '\b' -> 0xFF08
            '\u001b' -> 0xFF1B
            ' ' -> 0x20
            else -> if (char.code in 0x21..0x7E) char.code else 0x01000000 + char.code
        }
    }
}
