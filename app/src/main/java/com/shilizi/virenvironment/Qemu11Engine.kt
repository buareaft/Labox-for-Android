package com.shilizi.virenvironment

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * QEMU 11 独立进程引擎。
 *
 * QEMU 以原生可执行文件（Termux bionic 编译）运行在独立进程：
 *  - 二进制 + 依赖 .so + 固件从 assets/qemu11 首次解压到 filesDir/qemu11
 *  - LD_LIBRARY_PATH 指向解压后的 lib 目录
 *  - 显示输出走 VNC（127.0.0.1:port），控制走 QMP over TCP
 *  - 与 v86 引擎并列，QemuDisplayActivity 只依赖本类 + VncClient
 */
class Qemu11Engine(
    context: Context,
    private val diskUri: android.net.Uri,
    private val memoryMb: Int,
    private val cpuCores: Int,
    private val vncPort: Int,
    private val qmpPort: Int,
    private val qemuArgs: List<String>,
    private val onStateChanged: (String) -> Unit
) {
    /** 引擎只做文件/资产/ContentResolver 访问，一律用 Application 上下文，
     *  避免引擎及其后台线程把 Activity 引用拖住（泄漏根源）。 */
    private val appContext: Context = context.applicationContext

    private var process: Process? = null
    private var qmp: QmpClient? = null
    private var frameIndex = 0

    /** 进程退出监听器：QEMU 进程自行退出（崩溃/正常退出）时回调（任意线程）。 */
    @Volatile
    var onProcessExit: ((String) -> Unit)? = null

    /** 当前 ABI（决定用哪个 QEMU 二进制和依赖库集）。
     *  SUPPORTED_ABIS[0] 是主 ABI（如 x86_64,arm64-v8a 时选 x86_64）。
     *  x86_64 模拟器可能同时声明 arm64-v8a，必须按主 ABI 决定。 */
    private val abi: String = when (android.os.Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "arm64-v8a"
        else -> "x86_64"
    }

    /** QEMU 根目录（按 ABI 隔离，避免两套库互相覆盖） */
    val rootDir: File get() = File(appContext.filesDir, "qemu11/$abi")

    /** 二进制：从 nativeLibraryDir 读（安装时由包管理器按 ABI 解压并授予 exec 权限）。 */
    val binary: File get() = File(appContext.applicationInfo.nativeLibraryDir, "libqemu-system-x86_64.so")

    /** 依赖库目录（-L 指向 firmware，LD_LIBRARY_PATH 指向本目录） */
    val libDir: File get() = File(rootDir, "lib")

    /** 固件目录（-L 指向） */
    val firmwareDir: File get() = File(rootDir, "firmware")

    /** 磁盘文件（从 URI 复制到私有目录，QEMU 直接读文件路径） */
    private val diskFile: File get() = File(rootDir, "disk.img")

    /** 解压 assets/qemu11 到私有目录（首次运行）。二进制在 nativeLibraryDir，已由系统按 ABI 解压。 */
    fun prepare() {
        val marker = File(rootDir, ".installed")
        if (marker.isFile) return
        rootDir.mkdirs()
        firmwareDir.mkdirs()
        File(firmwareDir, "keymaps").mkdirs()
        try {
            doPrepare(marker)
        } catch (e: Exception) {
            Log.e(TAG, "QEMU 解压失败，清理半成品目录（下次启动重试）", e)
            runCatching { rootDir.deleteRecursively() }
            throw e
        }
    }

    /** prepare() 的实际解压逻辑：失败时由 prepare() 统一清理并重抛。 */
    private fun doPrepare(marker: File) {
        fun copyAsset(path: String, dest: File) {
            appContext.assets.open(path).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }

        // 记录失败文件，全部完成后校验
        val failures = mutableListOf<String>()

        // assets/qemu11/lib/<abi>/ 是本 ABI 的依赖库，firmware/ 架构无关
        val libSrc = "qemu11/lib/$abi"
        val libEntries = appContext.assets.list(libSrc) ?: emptyArray()
        val fwEntries = appContext.assets.list("qemu11/firmware") ?: emptyArray()
        val total = libEntries.size + fwEntries.size + 1
        var done = 0

        // 解压当前 ABI 的依赖库
        File(rootDir, "lib").mkdirs()
        for (entry in libEntries) {
            try {
                copyAsset("$libSrc/$entry", File(libDir, entry))
            } catch (e: Exception) {
                failures += "$libSrc/$entry: ${e.message}"
            }
            done++
            if (done % 50 == 0) onStateChanged("解压 QEMU… $done/$total")
        }
        // 解压固件（firmware/ 所有条目；keymaps 是子目录需逐文件）
        for (entry in fwEntries) {
            try {
                if (entry == "keymaps") {
                    appContext.assets.list("qemu11/firmware/keymaps")?.forEach { km ->
                        copyAsset("qemu11/firmware/keymaps/$km", File(firmwareDir, "keymaps/$km"))
                    }
                } else {
                    copyAsset("qemu11/firmware/$entry", File(firmwareDir, entry))
                }
            } catch (e: Exception) {
                failures += "qemu11/firmware/$entry: ${e.message}"
            }
            done++
            if (done % 50 == 0) onStateChanged("解压 QEMU… $done/$total")
        }
        if (failures.isNotEmpty()) {
            android.util.Log.w(TAG, "解压失败 ${failures.size} 个文件: ${failures.take(5)}")
            throw IOException("QEMU 解压失败: ${failures.first()}")
        }
        // 校验 lib 目录完整性
        val libCount = File(rootDir, "lib").list()?.size ?: 0
        if (libCount < 100) throw IOException("QEMU 依赖库不完整 (lib=$libCount)")
        marker.writeText("ok")
        onStateChanged("QEMU 解压完成")
    }

    /** 从 URI 复制磁盘到私有文件（QEMU 需要真实文件路径）。
     *  file:// 直接读取（app 私有目录有权限）；content:// 走 ContentResolver（SAF）。
     *  用 .src 标记文件记录来源 URI + 源大小：来源变化或大小不符时重新复制，
     *  避免上一次运行的旧镜像（如 FreeDOS 软盘）被误当本次磁盘。 */
    fun prepareDisk(): File {
        diskFile.parentFile?.mkdirs()
        val marker = File(diskFile.parentFile, "${diskFile.name}.src")
        var sourceSize = -1L
        if (diskUri.scheme == "file") {
            sourceSize = File(diskUri.path!!).length()
        } else {
            runCatching {
                appContext.contentResolver.query(diskUri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (idx >= 0 && !c.isNull(idx)) sourceSize = c.getLong(idx)
                    }
                }
            }
        }
        val sameSource = marker.exists() && marker.readText() == diskUri.toString()
        val sameSize = sourceSize <= 0 || (diskFile.exists() && diskFile.length() == sourceSize)
        // 记录来源扩展名（.iso 判断用）：disk.img 复制后无法从文件名得知原始介质。
        // 在任何返回路径前设置，保证 fast path（已存在）也有值。
        sourceExt = diskUri.lastPathSegment?.substringAfterLast('.', "")?.lowercase() ?: ""
        if (diskFile.exists() && sameSource && sameSize) return diskFile
        diskFile.delete()
        marker.writeText(diskUri.toString())
        if (diskUri.scheme == "file") {            // app 私有目录或外部存储的 file:// 直接复制
            File(diskUri.path!!).inputStream().use { input ->
                diskFile.outputStream().use { output -> input.copyTo(output) }
            }
        } else {
            appContext.contentResolver.openInputStream(diskUri)?.use { input ->
                diskFile.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("无法打开磁盘镜像: $diskUri")
        }
        return diskFile
    }

    /** 磁盘来源扩展名（小写，无点）：prepareDisk 时记录，disk.img 复制后仍能判断原始介质。 */
    private var sourceExt: String = ""

    /** 复制 assets 固件到目标文件（用于 pflash 可写副本等）。 */
    private fun copyFirmware(assetName: String, dest: File) {
        appContext.assets.open("qemu11/firmware/$assetName").use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }

    /** 启动 QEMU 进程。
     *  外部传入的 qemuArgs 可能含占位符（见 QemuHardwareConfig.toQemuLaunchPlan）：
     *   - @DISK@：磁盘镜像路径（引擎按来源扩展名决定介质：iso→光驱，img/qcow2/vhd→硬盘，<3MB→软盘）
     *   - @OVMF_CODE@ / @OVMF_VARS@：UEFI 固件（复制为可写副本，变量存储需可写）
     *   - @TPM_SOCKET@：TPM 模拟器 chardev socket 路径（rootDir 下） */
    fun start() {
        val disk = prepareDisk()
        // 介质判断优先用来源扩展名（prepareDisk 记录）：生产路径把 ISO 复制成 disk.img，
        // 只看 disk.name 会漏掉 .iso。来源不可知（旧标记/直接入口）时按大小兜底。
        val isIso = sourceExt == "iso"
        // 按镜像大小判断介质：<3MB 视为软盘（如 FreeDOS 引导盘），否则按扩展名
        val isFloppy = !isIso && disk.length() < 3L * 1024 * 1024
        val mediaArg = when {
            isFloppy -> "if=floppy"
            isIso -> "media=cdrom,if=ide"
            else -> "media=cdrom,if=ide" // 直接入口默认当光驱（兼容旧测试路径）
        }

        // UEFI 固件：edk2-x86_64-code.fd 只读代码段 + edk2-i386-vars.fd 可写变量段
        val ovmfCode = File(firmwareDir, "OVMF_CODE.fd")
        val ovmfVars = File(firmwareDir, "OVMF_VARS.fd")
        val uefiRequested = qemuArgs.any { it.contains("@OVMF_CODE@") || it.contains("@OVMF_VARS@") }
        if (uefiRequested) {
            if (!ovmfCode.isFile) copyFirmware("edk2-x86_64-code.fd", ovmfCode)
            // 变量段必须是可写副本（QEMU pflash 写入 NVRAM 变量）
            if (!ovmfVars.isFile) copyFirmware("edk2-i386-vars.fd", ovmfVars)
        }

        val cmd = mutableListOf(
            binary.absolutePath,
            "-L", firmwareDir.absolutePath,
            "-m", memoryMb.toString(),
            "-smp", cpuCores.toString(),
            // QEMU 11 的 -vnc host:N 把 N 当显示编号（X11 惯例），实际监听 5900+N。
            // 传入 vncPort-5900，QEMU 就监听 5900+(vncPort-5900)=vncPort，与客户端一致。
            "-vnc", "127.0.0.1:${(vncPort - 5900).coerceAtLeast(0)}",
            "-qmp", "tcp:127.0.0.1:$qmpPort,server=on,wait=off",
            "-D", File(rootDir, "qemu.log").absolutePath
        )
        // 外部参数含 @DISK@（toQemuLaunchPlan 生成的 -drive if=none 挂载）时不重复挂盘
        val diskInArgs = qemuArgs.any { it.contains("@DISK@") }
        if (!diskInArgs) {
            cmd.add("-drive")
            cmd.add("file=${disk.absolutePath},format=raw,$mediaArg")
        }
        cmd.addAll(qemuArgs.map { arg ->
            var a = arg.replace("@DISK@", disk.absolutePath)
                .replace("@OVMF_CODE@", ovmfCode.absolutePath)
                .replace("@OVMF_VARS@", ovmfVars.absolutePath)
                .replace("@TPM_SOCKET@", File(rootDir, "swtpm.sock").absolutePath)
            // ISO 安装镜像必须挂光驱设备（ide-cd），否则 UEFI 无法引导安装盘
            if (isIso && a.contains("drive=disk0") && a.startsWith("-device")) {
                a = a.replace("ide-hd", "ide-cd")
            }
            a
        })
        // TPM 兜底：Termux QEMU 无 swtpm 进程，TPM 参数必然启动失败。
        // 若外部参数仍带 TPM（旧缓存/直接构造），过滤掉相关参数保持可启动。
        if (cmd.any { it.contains("chrtpm") || it.contains("tpm-tis") || it.contains("@TPM_SOCKET@") }) {
            Log.w(TAG, "检测到 TPM 参数但 swtpm 不可用，已移除")
            val tpmKeywords = setOf("chrtpm", "tpm0", "tpm-tis")
            cmd.removeAll { arg ->
                arg.startsWith("-") && tpmKeywords.any { arg.contains(it) }
            }
            // 移除紧随的 -chardev/-tpmdev 值行
            cmd.removeAll { arg -> arg.contains("swtpm.sock") }
        }
        // 软盘镜像（<3MB，如 FreeDOS 引导盘）必须挂为软驱并从 A 盘引导。
        // 生产路径 QemuHardware 默认把 @DISK@ 挂成 IDE 硬盘（-device ide-hd,drive=disk0），
        // 若不改成软驱，QEMU 从 A 盘找不到可引导设备 → 黑屏 / "No bootable device"。
        // QEMU 后出现的 -boot 覆盖前面的，追加在末尾确保 order=a 生效。
        if (isFloppy) {
            // 1) 移除 QemuHardware 生成的磁盘设备行：QEMU 参数数组里 "-device" 与设备值
            //    （ide-hd,drive=disk0 / scsi-hd,drive=disk0 / virtio-blk-pci,drive=disk0）
            //    是两个独立元素，需按索引把标志和值成对删除，否则残留的
            //    "-device ide-hd,drive=disk0" 会因找不到 drive=disk0 让 QEMU 直接退出。
            val filtered = mutableListOf<String>()
            var i = 0
            while (i < cmd.size) {
                if (cmd[i] == "-device" && i + 1 < cmd.size && cmd[i + 1].contains("drive=disk0")) {
                    i += 2
                    continue
                }
                filtered += cmd[i]
                i++
            }
            cmd.clear()
            cmd.addAll(filtered)
            // 2) 把 -drive 值行改为挂软驱（if=floppy）；找不到（直接入口未生成 @DISK@）则追加
            val driveValueIdx = cmd.indexOfFirst { it.startsWith("file=${disk.absolutePath}") }
            if (driveValueIdx >= 0) {
                cmd[driveValueIdx] = "file=${disk.absolutePath},format=raw,if=floppy"
            } else {
                cmd.addAll(listOf("-drive", "file=${disk.absolutePath},format=raw,if=floppy"))
            }
            // 3) 软盘优先引导
            cmd.addAll(listOf("-boot", "order=a"))
        }
        Log.i(TAG, "QEMU 11 启动 (abi=$abi, uefi=$uefiRequested): $cmd")

        val pb = ProcessBuilder(cmd)
        pb.environment()["LD_LIBRARY_PATH"] = libDir.absolutePath
        pb.directory(rootDir)
        pb.redirectErrorStream(true)
        try {
            process = pb.start()
        } catch (e: IOException) {
            Log.e(TAG, "QEMU 进程启动失败", e)
            onStateChanged("QEMU 启动失败: ${e.message}")
            return
        }

        // 读取进程输出（避免阻塞）
        val output = process!!.inputStream.bufferedReader()
        Thread({
            try {
                val sb = StringBuilder()
                while (true) {
                    val line = output.readLine() ?: break
                    if (sb.length < 4000) sb.append(line).append('\n')
                }
                Log.i(TAG, "QEMU 进程结束: $sb")
            } catch (_: IOException) {
            } finally {
                onStateChanged("QEMU 已退出")
                // 通知监听者：QEMU 进程已终止（可能是崩溃或正常退出）。
                // 若进程是引擎 stop() 主动终止的（process 已被清空），不再重复通知。
                if (process != null && process?.isAlive == false) {
                    onProcessExit?.invoke("QEMU 进程已退出（exit）")
                }
            }
        }, "labox-qemu-log").start()

        // 等待 QMP 就绪并连接
        Thread({
            var attempt = 0
            while (attempt++ < 50) {
                if (!isRunning()) return@Thread
                try {
                    qmp = QmpClient("127.0.0.1", qmpPort)
                    Log.i(TAG, "QMP 已连接")
                    return@Thread
                } catch (_: IOException) {
                    Thread.sleep(200)
                }
            }
        }, "labox-qmp-connect").start()
    }

    fun isRunning(): Boolean = process?.isAlive == true

    // ---------- 输入（QMP input-send-event）----------
    // 所有输入统一投递到后台 input 线程：QMP 是网络 I/O，绝不上主线程
    // （StrictMode NetworkOnMainThreadException）；同线程串行保证事件顺序。
    //
    // 关键：组合键/文本必须「一次 execute 发多个事件」（QMP events 数组按序发送）。
    // TCG 下每次 QMP 往返 100-500ms，若逐个 execute，键间延迟会超过 Windows
    // 组合键窗口（如 Win 键按下后 r 太晚到 = 弹出开始菜单而非运行框）。

    /** 构造一个按键事件（QMP event 对象）。 */
    private fun keyEvent(key: String, down: Boolean): Map<String, Any> = mapOf(
        "type" to "key",
        "data" to mapOf(
            "down" to down,
            "key" to mapOf("type" to "qcode", "data" to key)
        )
    )

    /** 一次 execute 发送多个事件（调用方必须已在 input 线程）。 */
    private fun sendEventsNow(events: List<Map<String, Any>>) {
        val q = qmp ?: return
        runCatching { q.execute("input-send-event", mapOf("events" to events)) }
    }

    /** 发送单个按键事件。 */
    fun sendKey(key: String, down: Boolean) {
        postInput { sendEventsNow(listOf(keyEvent(key, down))) }
    }

    /** 单键一次点击（按下+释放），一个 execute 完成。 */
    fun tapKey(key: String) {
        postInput {
            sendEventsNow(listOf(keyEvent(key, true), keyEvent(key, false)))
        }
    }

    /** 组合键：一次 execute 内按下全部再逆序释放（如 Ctrl+Alt+Del、Win+R）。
     *  单次往返保证键间零延迟，TCG 慢速下组合键也能被 Windows 正确识别。 */
    fun tapKeyCombo(vararg keys: String) {
        postInput {
            val events = keys.map { keyEvent(it, true) } + keys.reversed().map { keyEvent(it, false) }
            sendEventsNow(events)
        }
    }

    /** 输入一段文本：整段在一次 execute 内按序发送，Shift 只切换一次。
     *  长文本按段发送（每段 ≤ [TEXT_BATCH_CHARS] 字符），避免单条 QMP 命令过大。 */
    fun tapText(text: String) {
        postInput {
            val events = mutableListOf<Map<String, Any>>()
            var shiftDown = false
            fun setShift(on: Boolean) {
                if (shiftDown != on) {
                    events += keyEvent("shift", on)
                    shiftDown = on
                }
            }
            fun flush() {
                if (events.isNotEmpty()) {
                    sendEventsNow(events)
                    events.clear()
                }
            }
            var charsInBatch = 0
            for (c in text) {
                if (c == '\b') {
                    setShift(false)
                    events += keyEvent("backspace", true)
                    events += keyEvent("backspace", false)
                    charsInBatch++
                } else {
                    val (code, needShift) = qcodeFor(c) ?: continue
                    setShift(needShift)
                    events += keyEvent(code, true)
                    events += keyEvent(code, false)
                    charsInBatch++
                }
                if (charsInBatch >= TEXT_BATCH_CHARS) { flush(); charsInBatch = 0 }
            }
            setShift(false)
            flush()
        }
    }

    /** 指针输入模式：ABS=usb-tablet 绝对坐标（默认），REL=ps2 鼠标相对移动。 */
    enum class PointerMode { ABS, REL }

    /** 当前指针模式（由启动参数里是否含 usb-tablet 决定）。 */
    var pointerMode: PointerMode = PointerMode.ABS

    /** 鼠标合并发送状态：触摸事件频率远高于 QMP 往返速度，拖动时只保留最新坐标，
     *  input 线程同时最多排队一个移动任务，队列不会随拖动越来越深。 */
    @Volatile
    private var pendingMoveX = Int.MIN_VALUE
    @Volatile
    private var pendingMoveY = 0
    private val moveQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    /** REL 模式基准：以上次「已发送」坐标算位移——合并丢帧也不会累积漂移。 */
    private var lastSentX = 0
    private var lastSentY = 0
    private var relInitialized = false

    /** 请求移动鼠标。高频回调会被合并：最新坐标胜出，input 线程每轮最多发一帧。
     *  ABS：发绝对坐标（需 usb-tablet）；REL：发与上次已发送坐标的位移。 */
    fun sendMouseMove(x: Int, y: Int) {
        pendingMoveX = x
        pendingMoveY = y
        if (moveQueued.compareAndSet(false, true)) {
            postInput {
                moveQueued.set(false)
                val q = qmp ?: return@postInput
                val tx = pendingMoveX
                if (tx == Int.MIN_VALUE) return@postInput
                pendingMoveX = Int.MIN_VALUE
                val ty = pendingMoveY
                val events = if (pointerMode == PointerMode.ABS) {
                    listOf(
                        mapOf("type" to "abs", "data" to mapOf("axis" to "x", "value" to tx)),
                        mapOf("type" to "abs", "data" to mapOf("axis" to "y", "value" to ty))
                    )
                } else {
                    // 首次移动只建立基准不发位移，避免从 (0,0) 的跳变
                    val dx = if (relInitialized) tx - lastSentX else 0
                    val dy = if (relInitialized) ty - lastSentY else 0
                    relInitialized = true
                    lastSentX = tx
                    lastSentY = ty
                    if (dx == 0 && dy == 0) return@postInput
                    listOf(
                        mapOf("type" to "rel", "data" to mapOf("axis" to "x", "value" to dx)),
                        mapOf("type" to "rel", "data" to mapOf("axis" to "y", "value" to dy))
                    )
                }
                runCatching { q.execute("input-send-event", mapOf("events" to events)) }
            }
        }
    }

    /** 发送鼠标按键（button: "left"/"right"/"middle"）。在后台 input 线程执行。 */
    fun sendMouseButton(button: String, down: Boolean) {
        postInput {
            val q = qmp ?: return@postInput
            val event = mapOf(
                "type" to "btn",
                "data" to mapOf("down" to down, "button" to button)
            )
            runCatching { q.execute("input-send-event", mapOf("events" to listOf(event))) }
        }
    }

    /**
     * 滚轮滚动。notches > 0 向上滚（内容上移），< 0 向下滚。
     * 一次批量发送多格事件，避免 TCG 下逐格往返造成延迟。
     */
    fun sendWheel(notches: Int) {
        if (notches == 0) return
        postInput {
            val q = qmp ?: return@postInput
            val events = List(kotlin.math.abs(notches)) {
                mapOf(
                    "type" to "rel",
                    "data" to mapOf("axis" to "wheel", "value" to if (notches > 0) 1 else -1)
                )
            }
            runCatching { q.execute("input-send-event", mapOf("events" to events)) }
        }
    }

    /** 后台输入线程：QMP input-send-event 全部投递到这里执行。
     *  保证：1) 网络 I/O 不进主线程；2) 按键/鼠标事件按序发送。 */
    private val inputThread = HandlerThread("labox-input").apply { start() }
    private val inputHandler by lazy { Handler(inputThread.looper) }

    /** 已停止标志：stop() 幂等，防止重复 quit 后 lazy 重建 HandlerThread（线程泄漏）。 */
    @Volatile
    private var stopped = false

    private fun postInput(block: () -> Unit) {
        if (stopped) return
        if (Looper.myLooper() == inputThread.looper) {
            runCatching(block)
        } else {
            inputHandler.post { runCatching(block) }
        }
    }

    fun pause() {
        postInput { runCatching { qmp?.execute("stop") } }
    }

    fun resume() {
        postInput { runCatching { qmp?.execute("cont") } }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        // 全程在后台线程执行，避免主线程（onDestroy）阻塞过久触发 ANR。
        // 关闭顺序：QMP quit → 等进程退出 → SIGTERM → SIGKILL 兜底。
        // 关键：quit 必须直接投递到 input handler——postInput() 在 stopped 后会直接
        // return，旧代码因此导致 quit 永远发不出去，QEMU 只能裸靠 SIGTERM 退出。
        val p = process
        Thread({
            if (p != null) {
                inputHandler.post { runCatching { qmp?.execute("quit") } }
                // QMP quit 需 QEMU 主循环响应，TCG 慢速下可能延迟，分阶段等待
                try { p.waitFor(3000, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
                if (p.isAlive) {
                    Log.w(TAG, "QEMU 3s 内未自行退出，发送 SIGTERM")
                    p.destroy()
                    try { p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
                }
                if (p.isAlive) {
                    Log.e(TAG, "QEMU SIGTERM 后仍存活，SIGKILL 强杀")
                    p.destroyForcibly()
                    try { p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
                }
                if (p.isAlive) {
                    Log.e(TAG, "QEMU 进程无法终止（仍存活）——guest 内存无法回收！")
                } else {
                    Log.i(TAG, "QEMU 进程已退出，guest 内存已释放")
                }
            }
            process = null
            // 关闭 QMP socket，退出输入线程：HandlerThread 是 GC root，
            // 不 quit 的话消息队列里捕获 engine/context 的 lambda 会让整个 Activity 活下来（泄漏）。
            runCatching { qmp?.close() }
            qmp = null
            inputThread.quitSafely()
        }, "labox-stop").start()
    }

    /** 截图输出文件（QEMU screendump 写入）。双缓冲避免读到写一半的文件。 */
    private val screenshotFiles: List<File> get() = listOf(
        File(rootDir, "screen0.ppm"),
        File(rootDir, "screen1.ppm")
    )

    /**
     * 通过 QMP screendump 截取当前画面，解析为 ARGB Bitmap。
     *
     * QEMU 11 的 VNC 在纯 TCG（无 KVM）下不推送帧，改用 screendump 轮询：
     *  - QMP 阻塞等 screendump 写文件完成（QEMU 端同步写）
     *  - 双缓冲文件：每次写不同文件，读「上一轮」已完成的文件，避免读半截
     *
     * 注意：Termux 版 QEMU 把文本换行写成 CRLF（\r\n），PNG 二进制会被破坏，
     * 因此用 PPM（ASCII 头 + RGB 像素），像素区不含换行不受影响。
     * @return ARGB Bitmap，失败返回 null
     */
    @Synchronized
    fun screendumpBitmap(): Bitmap? {
        val q = qmp ?: run { Log.w(TAG, "screendump: qmp 未连接"); return null }
        val files = screenshotFiles
        // QEMU 的 screendump 异步落盘：execute 返回时文件可能还在写。
        // 每次写一个槽位，读「上一轮」的槽位（此时必然已写完），首轮读不到返回 null。
        val target = files[frameIndex % files.size]
        try {
            val resp = q.execute("screendump", mapOf(
                "filename" to target.absolutePath,
                "format" to "ppm"
            ))
            if (resp == null) { Log.w(TAG, "screendump: execute 返回 null"); return null }
            if (resp.contains("\"error\"")) {
                Log.w(TAG, "screendump 失败: $resp")
                return null
            }
            frameIndex++
            val readFile = files[frameIndex % files.size]
            if (!readFile.isFile) return null
            val data = readFile.readBytes()
            val bmp = parsePpm(data)
            if (bmp == null) Log.w(TAG, "screendump: parsePpm 失败 (${data.size} 字节)")
            return bmp
        } catch (e: Exception) {
            Log.w(TAG, "screendump 异常: ${e.message}")
            return null
        }
    }

    /** 解析 PPM P6 到 ARGB Bitmap。容错 CRLF 换行。
     *  注意：Kotlin Byte 有符号，像素字节 >= 0x80 为负值，空白判断必须按无符号。 */
    private fun parsePpm(data: ByteArray): Bitmap? {
        if (data.size < 15) return null
        var pos = 0
        fun isWs(b: Byte): Boolean = (b.toInt() and 0xFF) <= 32
        fun nextToken(): String {
            while (pos < data.size && isWs(data[pos])) pos++
            val sb = StringBuilder()
            while (pos < data.size && !isWs(data[pos])) { sb.append(data[pos].toInt().toChar()); pos++ }
            return sb.toString()
        }
        val magic = nextToken()
        if (magic != "P6") { Log.w(TAG, "parsePpm: 格式错误 magic=$magic pos=$pos"); return null }
        val w = nextToken().toIntOrNull() ?: run { Log.w(TAG, "parsePpm: w 解析失败 pos=$pos"); return null }
        val h = nextToken().toIntOrNull() ?: run { Log.w(TAG, "parsePpm: h 解析失败 pos=$pos"); return null }
        val maxval = nextToken()
        if (w <= 0 || h <= 0 || w > 4096 || h > 4096) { Log.w(TAG, "parsePpm: 尺寸异常 ${w}x$h"); return null }
        // 头部结束：只跳过恰好一个行尾（LF，Termux 版可能 CRLF），
        // 像素区不能再做任何空白跳过——像素字节本身可能 <=32（如黑色 0x00）。
        if (pos < data.size && data[pos] == '\r'.code.toByte()) pos++
        if (pos < data.size && data[pos] == '\n'.code.toByte()) pos++
        val pixelCount = w * h
        val have = data.size - pos
        if (have < pixelCount * 3) {
            Log.w(TAG, "parsePpm: 像素不足 maxval=$maxval pos=$pos need=${pixelCount * 3} have=$have size=${data.size}")
            return null
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(pixelCount)
        var p = pos
        for (i in 0 until pixelCount) {
            val r = data[p].toInt() and 0xFF
            val g = data[p + 1].toInt() and 0xFF
            val b = data[p + 2].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            p += 3
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    companion object {
        private const val TAG = "LaboxQemu11"

        /** 单条 input-send-event 最多包含的字符数（每字符 2 个键事件）。 */
        const val TEXT_BATCH_CHARS = 32

        /** 字符 -> (QEMU qcode, 是否需要 Shift)。不支持的字符返回 null。 */
        fun qcodeFor(c: Char): Pair<String, Boolean>? = when (c) {
            in 'a'..'z' -> c.toString() to false
            in 'A'..'Z' -> c.lowercaseChar().toString() to true
            in '0'..'9' -> c.toString() to false
            ' ' -> "spc" to false
            '\n', '\r' -> "ret" to false
            '\t' -> "tab" to false
            '-' -> "minus" to false
            '=' -> "equal" to false
            '[' -> "bracket_left" to false
            ']' -> "bracket_right" to false
            '\\' -> "backslash" to false
            ';' -> "semicolon" to false
            '\'' -> "apostrophe" to false
            '`' -> "grave_accent" to false
            ',' -> "comma" to false
            '.' -> "period" to false
            '/' -> "slash" to false
            '!' -> "1" to true
            '@' -> "2" to true
            '#' -> "3" to true
            '$' -> "4" to true
            '%' -> "5" to true
            '^' -> "6" to true
            '&' -> "7" to true
            '*' -> "8" to true
            '(' -> "9" to true
            ')' -> "0" to true
            '_' -> "minus" to true
            '+' -> "equal" to true
            '{' -> "bracket_left" to true
            '}' -> "bracket_right" to true
            '|' -> "backslash" to true
            ':' -> "semicolon" to true
            '"' -> "apostrophe" to true
            '~' -> "grave_accent" to true
            '<' -> "comma" to true
            '>' -> "period" to true
            '?' -> "slash" to true
            else -> null
        }
    }
}
