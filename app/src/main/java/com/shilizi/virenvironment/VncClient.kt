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
import java.util.Arrays
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
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
    private val onFrame: (Bitmap) -> Unit,
    private val onUnavailable: () -> Unit = {}
) {
    private val running = AtomicBoolean(true)
    private val destroyed = AtomicBoolean(false)
    private val inputWriteConfirmed = AtomicBoolean(false)
    private val socketLock = Object()
    private val inputExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "labox-vnc-input").apply { isDaemon = true }
    }
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var thread: Thread? = null
    private var rawBytes = ByteArray(0)
    private var rawPixels = IntArray(0)
    private var hextilePixels = IntArray(0)

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
        if (!connected) {
            Log.e(TAG, "VNC 30 秒内未连接成功")
            onUnavailable()
            return
        }
        try {
            messageLoop()
        } catch (e: Exception) {
            Log.w(TAG, "VNC 画面连接中断: ${e.message}")
        } finally {
            closeSocket()
            if (running.get() && !destroyed.get()) onUnavailable()
        }
    }

    private fun handshake(socket: Socket) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream(), NETWORK_BUFFER_SIZE))
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        this.input = input

        // 1. 版本协商：RFB 由服务端先发送版本，客户端回显选定版本。
        val version = ByteArray(12)
        input.readFully(version)
        val versionStr = String(version, Charsets.US_ASCII)
        Log.i(TAG, "VNC 版本: ${versionStr.trim()}")
        val minor = when {
            versionStr.startsWith("RFB 003.008") -> 8
            versionStr.startsWith("RFB 003.007") -> 7
            else -> 3 // 3.3 或更老
        }
        val clientVersion = if (minor >= 8) "RFB 003.008\n" else if (minor >= 7) "RFB 003.007\n" else "RFB 003.003\n"
        out.write(clientVersion.toByteArray(Charsets.US_ASCII))
        out.flush()

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
        fmt[0] = 32   // bits-per-pixel
        fmt[1] = 24   // depth
        fmt[2] = 0    // big-endian-flag（0 = 小端）
        fmt[3] = 1    // true-colour-flag
        fmt[4] = 0; fmt[5] = (255).toByte()  // red-max
        fmt[6] = 0; fmt[7] = (255).toByte()  // green-max
        fmt[8] = 0; fmt[9] = (255).toByte()  // blue-max
        fmt[10] = 16  // red-shift
        fmt[11] = 8   // green-shift
        fmt[12] = 0   // blue-shift
        fmt[13] = 0; fmt[14] = 0; fmt[15] = 0
        out.writeByte(0) // SetPixelFormat
        out.writeByte(0)
        out.writeByte(0)
        out.writeByte(0) // padding
        out.write(fmt)
        Log.i(TAG, "VNC 已发送 SetPixelFormat (32bpp)")

        // 6. SetEncodings: CopyRect(1), Hextile(5), Raw(0), DesktopSize(-223)
        // RFB 3.8: type(1) + padding(1) + count(2) + encoding(4)*n
        out.writeByte(2)
        out.writeByte(0) // padding
        out.writeShort(4)
        out.writeInt(1) // CopyRect
        out.writeInt(5) // Hextile
        out.writeInt(0) // Raw
        out.writeInt(-223) // DesktopSize
        out.flush()

        synchronized(socketLock) {
            this.output = out
        }

        framebuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        onConnected(width, height)

        // 7. 请求全屏更新
        requestUpdate(false)
        Log.i(TAG, "VNC 握手完成，请求全屏更新")
    }

    private fun messageLoop() {
        val input = input ?: throw IOException("VNC 输入流未初始化")
        var frameCount = 0
        var statsStartedAt = System.nanoTime()
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
                    if (handleFramebufferUpdate(input)) frameCount++
                    val now = System.nanoTime()
                    val elapsed = now - statsStartedAt
                    if (elapsed >= STATS_INTERVAL_NS) {
                        val fps = frameCount * 1_000_000_000.0 / elapsed
                        Log.i(TAG, "VNC 解码帧率 %.1f FPS".format(java.util.Locale.US, fps))
                        frameCount = 0
                        statsStartedAt = now
                    }
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

    /** 返回本次更新是否真正改变了画面。 */
    private fun handleFramebufferUpdate(input: DataInputStream): Boolean {
        input.readUnsignedByte() // padding
        val rectCount = input.readUnsignedShort()
        var bmp = framebuffer ?: return false
        var changed = false

        repeat(rectCount) {
            val x = input.readUnsignedShort()
            val y = input.readUnsignedShort()
            val w = input.readUnsignedShort()
            val h = input.readUnsignedShort()
            val encoding = input.readInt()
            when (encoding) {
                0 -> {
                    readRaw(input, bmp, x, y, w, h)
                    changed = true
                }
                1 -> {
                    readCopyRect(input, bmp, x, y, w, h)
                    changed = true
                }
                5 -> {
                    readHextile(input, bmp, x, y, w, h)
                    changed = true
                }
                -223 -> {
                    if (w <= 0 || h <= 0 || w > 4096 || h > 4096) {
                        throw IOException("VNC 返回无效画面尺寸: ${w}x$h")
                    }
                    width = w
                    height = h
                    bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    framebuffer = bmp
                    onConnected(w, h)
                    changed = true
                }
                else -> {
                    Log.e(TAG, "不支持的 VNC 编码: $encoding")
                    throw IOException("不支持的 VNC 编码: $encoding")
                }
            }
        }
        // 先请求下一帧，让 QEMU 在显示层绘制当前帧时并行准备后续增量更新。
        requestUpdate(true)
        if (changed) onFrame(bmp)
        return changed
    }

    private fun readRaw(input: DataInputStream, bmp: Bitmap, x: Int, y: Int, w: Int, h: Int) {
        val pixelCount = w * h
        val byteCount = pixelCount * 4
        if (rawBytes.size < byteCount) rawBytes = ByteArray(byteCount)
        if (rawPixels.size < pixelCount) rawPixels = IntArray(pixelCount)
        input.readFully(rawBytes, 0, byteCount)
        val ints = ByteBuffer.wrap(rawBytes, 0, byteCount).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        ints.get(rawPixels, 0, pixelCount)
        // VNC 小端 32bpp: [B,G,R,0] -> ARGB int。整块一次提交，避免逐行 JNI 调用。
        for (i in 0 until pixelCount) {
            rawPixels[i] = rawPixels[i] or -0x1000000
        }
        bmp.setPixels(rawPixels, 0, w, x, y, w, h)
    }

    private fun readCopyRect(input: DataInputStream, bmp: Bitmap, x: Int, y: Int, w: Int, h: Int) {
        val srcX = input.readUnsignedShort()
        val srcY = input.readUnsignedShort()
        val src = IntArray(w * h)
        bmp.getPixels(src, 0, w, srcX, srcY, w, h)
        bmp.setPixels(src, 0, w, x, y, w, h)
    }

    /** Hextile：把画面拆成 16x16 小块，纯色桌面和窗口区域无需传输每个像素。 */
    private fun readHextile(input: DataInputStream, bmp: Bitmap, x: Int, y: Int, w: Int, h: Int) {
        val pixelCount = w * h
        if (hextilePixels.size < pixelCount) hextilePixels = IntArray(pixelCount)
        val pixels = hextilePixels
        var background = -0x1000000
        var foreground = -0x1000000
        var tileY = 0
        while (tileY < h) {
            val tileH = minOf(16, h - tileY)
            var tileX = 0
            while (tileX < w) {
                val tileW = minOf(16, w - tileX)
                val subencoding = input.readUnsignedByte()
                if (subencoding and HEXTILE_RAW != 0) {
                    for (row in 0 until tileH) {
                        var offset = (tileY + row) * w + tileX
                        repeat(tileW) { pixels[offset++] = readPixel(input) }
                    }
                    tileX += 16
                    continue
                }

                if (subencoding and HEXTILE_BACKGROUND_SPECIFIED != 0) {
                    background = readPixel(input)
                }
                for (row in 0 until tileH) {
                    val start = (tileY + row) * w + tileX
                    Arrays.fill(pixels, start, start + tileW, background)
                }
                if (subencoding and HEXTILE_FOREGROUND_SPECIFIED != 0) {
                    foreground = readPixel(input)
                }
                if (subencoding and HEXTILE_ANY_SUBRECTS != 0) {
                    val count = input.readUnsignedByte()
                    repeat(count) {
                        val color = if (subencoding and HEXTILE_SUBRECTS_COLOURED != 0) {
                            readPixel(input)
                        } else {
                            foreground
                        }
                        val xy = input.readUnsignedByte()
                        val wh = input.readUnsignedByte()
                        val sx = xy ushr 4
                        val sy = xy and 0x0F
                        val sw = (wh ushr 4) + 1
                        val sh = (wh and 0x0F) + 1
                        for (row in sy until minOf(sy + sh, tileH)) {
                            val start = (tileY + row) * w + tileX + sx
                            val end = (tileY + row) * w + tileX + minOf(sx + sw, tileW)
                            if (start < end) Arrays.fill(pixels, start, end, color)
                        }
                    }
                }
                tileX += 16
            }
            tileY += 16
        }
        bmp.setPixels(pixels, 0, w, x, y, w, h)
    }

    /** 当前协商格式为 little-endian 32bpp，线上字节顺序是 B,G,R,0。 */
    private fun readPixel(input: DataInputStream): Int {
        val blue = input.readUnsignedByte()
        val green = input.readUnsignedByte()
        val red = input.readUnsignedByte()
        input.readUnsignedByte()
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    /** 发送鼠标事件。buttons: bit0=左, bit1=中, bit2=右。 */
    fun sendPointer(buttons: Int, x: Int, y: Int) {
        enqueueInput { out ->
                out.writeByte(5)
                out.writeByte(buttons)
                out.writeShort(x.coerceIn(0, width - 1))
                out.writeShort(y.coerceIn(0, height - 1))
        }
    }

    /** 发送键盘事件。keysym 为 X11 keysym。 */
    fun sendKey(down: Boolean, keysym: Int) {
        enqueueInput { out ->
                out.writeByte(4)
                out.writeByte(if (down) 1 else 0)
                out.writeShort(0)
                out.writeInt(keysym)
        }
    }

    /** UI 输入统一在后台串行写入，保证按键顺序且不触发主线程网络异常。 */
    private fun enqueueInput(write: (DataOutputStream) -> Unit) {
        if (destroyed.get()) return
        try {
            inputExecutor.execute {
                synchronized(socketLock) {
                    val out = output ?: return@synchronized
                    try {
                        write(out)
                        out.flush()
                        if (inputWriteConfirmed.compareAndSet(false, true)) {
                            Log.i(TAG, "VNC 输入通道工作正常")
                        }
                    } catch (error: IOException) {
                        Log.w(TAG, "VNC 输入发送失败: ${error.message}")
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
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
        inputExecutor.shutdownNow()
        synchronized(socketLock) {
            try { socket?.close() } catch (_: IOException) {}
            socket = null
            input = null
            output = null
        }
        thread?.interrupt()
    }

    private fun closeSocket() {
        synchronized(socketLock) {
            try { socket?.close() } catch (_: IOException) {}
            socket = null
            input = null
            output = null
        }
    }

    companion object {
        private const val TAG = "LaboxVnc"
        private const val NETWORK_BUFFER_SIZE = 256 * 1024
        private const val STATS_INTERVAL_NS = 2_000_000_000L
        private const val HEXTILE_RAW = 1
        private const val HEXTILE_BACKGROUND_SPECIFIED = 2
        private const val HEXTILE_FOREGROUND_SPECIFIED = 4
        private const val HEXTILE_ANY_SUBRECTS = 8
        private const val HEXTILE_SUBRECTS_COLOURED = 16

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
