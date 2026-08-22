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
    private val mediaList: List<VmMedia>,
    private val virtualDisks: List<VirtualDisk>,
    private val memoryMb: Int,
    private val cpuCores: Int,
    private val vncPort: Int,
    private val qmpPort: Int,
    private val qemuArgs: List<String>,
    private val onStateChanged: (String) -> Unit,
    /** 硬盘控制器类型（QemuHardwareConfig.disk）：ide/sata/scsi/virtio。 */
    private val diskController: String = "ide"
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

    /** 挂载介质目录：每个介质复制为 media<i>.img（i 为介质序号），虚拟硬盘为 vd<id>.raw */
    val mediaDir: File get() = File(rootDir, "media")

    /** 虚拟硬盘目录（app 私有，与 ViewModel 的 virtualDiskDir 一致）。 */
    val vdDir: File get() = File(appContext.filesDir, "labox-disks")

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
        // 校验本次 APK 中当前 ABI 的依赖库是否全部解压。依赖可按版本裁剪，
        // 因此不能用固定数量判断完整性。
        val libCount = File(rootDir, "lib").list()?.size ?: 0
        if (libEntries.isEmpty() || libCount != libEntries.size) {
            throw IOException("QEMU 依赖库不完整 (expected=${libEntries.size}, actual=$libCount)")
        }
        marker.writeText("ok")
        onStateChanged("QEMU 解压完成")
    }

    /** 从 URI 复制磁盘到私有文件（QEMU 需要真实文件路径）。
     *  file:// 直接读取（app 私有目录有权限）；content:// 走 ContentResolver（SAF）。
     *  每个介质一个槽位（media<i>.img），用 .src 标记文件记录来源 URI：
     *  来源变化或大小不符时重新复制，避免旧镜像被误当本次磁盘。 */
    fun prepareMedia(): List<Pair<VmMedia, File>> {
        mediaDir.mkdirs()
        return mediaList.mapIndexed { i, media ->
            val dest = File(mediaDir, "media$i.img")
            val marker = File(mediaDir, "media$i.src")
            var sourceSize = -1L
            if (media.uri.scheme == "file") {
                sourceSize = File(media.uri.path!!).length()
            } else {
                runCatching {
                    appContext.contentResolver.query(media.uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                            if (idx >= 0 && !c.isNull(idx)) sourceSize = c.getLong(idx)
                        }
                    }
                }
            }
            val sameSource = marker.exists() && marker.readText() == media.uri.toString()
            val sameSize = sourceSize <= 0 || (dest.exists() && dest.length() == sourceSize)
            if (!(dest.exists() && sameSource && sameSize)) {
                dest.delete()
                marker.writeText(media.uri.toString())
                if (media.uri.scheme == "file") {
                    File(media.uri.path!!).inputStream().use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                } else {
                    appContext.contentResolver.openInputStream(media.uri)?.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IOException("无法打开磁盘镜像: ${media.uri}")
                }
            }
            media to dest
        }
    }

    /** 磁盘来源扩展名（小写，无点）：prepareMedia 时记录，media<i>.img 复制后仍能判断原始介质。 */
    private val sourceExts: MutableList<String> = mutableListOf()

    /** 复制 assets 固件到目标文件（用于 pflash 可写副本等）。 */
    private fun copyFirmware(assetName: String, dest: File) {
        appContext.assets.open("qemu11/firmware/$assetName").use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }

    /** 启动 QEMU 进程。
     *  外部传入的 qemuArgs 不含磁盘参数（QemuHardware 只生成非磁盘参数），
     *  所有介质（ISO 光驱 / 硬盘镜像 / 软盘 + 虚拟硬盘）由本引擎统一挂载：
     *   - ISO → ide-cd 光驱（可多个）
     *   - 硬盘镜像/虚拟硬盘 → 所选硬盘控制器（IDE/SATA/SCSI/VirtIO），多块分配不同槽位
     *   - 软盘（<3MB）→ if=floppy 软驱
     *  @OVMF_CODE@ / @OVMF_VARS@ / @TPM_SOCKET@ 占位符在此替换 */
    fun start() {
        val prepared = prepareMedia()
        sourceExts.clear()
        sourceExts.addAll(prepared.map { it.first.name.substringAfterLast('.', "").lowercase() })
        val disk = prepared.firstOrNull()?.second
        // 介质判断优先用来源扩展名（prepareMedia 记录）：生产路径把 ISO 复制成 media<i>.img，
        // 只看文件名会漏掉 .iso。来源不可知（旧标记/直接入口）时按大小兜底。
        val isIso = disk != null && sourceExts.first() == "iso"
        // 按镜像大小判断介质：<3MB 视为软盘（如 FreeDOS 引导盘），否则按扩展名
        val isFloppy = disk != null && !isIso && disk.length() < 3L * 1024 * 1024
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
        // Android 无 KVM 时统一启用多线程 TCG，并扩大翻译块缓存。生产硬件参数原先
        // 没有 -accel，会退回 QEMU 默认值；多核 Windows 下会明显拖慢客体执行和显卡刷新。
        if (qemuArgs.none { it == "-accel" || it.startsWith("-accel ") }) {
            cmd += listOf("-accel", "tcg,thread=multi,tb-size=256")
        }
        // 挂载所有介质（ISO 光驱 + 硬盘镜像 + 软盘）与虚拟硬盘。
        // QemuHardware 不再生成磁盘参数，这里统一生成；多条 -drive/-device 显式分配槽位。
        cmd.addAll(buildDiskArguments(prepared))
        cmd.addAll(qemuArgs.map { arg ->
            var a = arg.replace("@OVMF_CODE@", ovmfCode.absolutePath)
                .replace("@OVMF_VARS@", ovmfVars.absolutePath)
                .replace("@TPM_SOCKET@", File(rootDir, "swtpm.sock").absolutePath)
            a
        })
        // 软盘镜像（<3MB，如 FreeDOS 引导盘）必须挂为软驱并从 A 盘引导。
        // buildDiskArguments 已把软盘挂为 if=floppy；追加 -boot order=a 确保软盘优先。
        if (isFloppy) {
            // 移除 QemuHardware 可能残留的磁盘设备行：参数数组里 "-device" 与设备值
            // 是两个独立元素，需按索引把标志和值成对删除。
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
            // 软盘优先引导
            cmd.addAll(listOf("-boot", "order=a"))
        }
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

    /**
     * 为所有介质与虚拟硬盘生成 QEMU 挂载参数。
     *
     * 槽位策略（保证多盘共存且 Windows 能识别）：
     *  - IDE/i440FX（PIIX3）：bus ide.0（primary）/ ide.1（secondary），每通道 unit 0/1。
     *    光驱优先 primary master，硬盘副通道，共 4 槽。
     *  - SATA/Q35（ich9-ahci）：总线 ide.0~ide.5（6 个 AHCI 端口）。
     *  - SCSI：lsi53c895a 总线，scsi-hd/scsi-cd 依次分配 unit。
     *  - VirtIO：virtio-blk-pci 每块一个 PCI 设备。
     *  - 软盘：if=floppy（fda），只能挂一个。
     *  ISO 一律挂光驱（ide-cd/scsi-cd），硬盘镜像/虚拟硬盘挂所选控制器。
     *  @return 追加到 cmd 的参数列表（-drive/-device 成对）
     */
    private fun buildDiskArguments(prepared: List<Pair<VmMedia, File>>): List<String> {
        val args = mutableListOf<String>()
        // Q35（SATA）走 ich9-ahci，总线名仍是 ide.N；i440FX 走 PIIX3 的 ide.0/ide.1
        val isQ35 = qemuArgs.any { it.contains("q35") }
        val ideBusCount = if (isQ35) 6 else 2

        // ---- 虚拟硬盘：挂到所选控制器 ----
        val vdDrives = mutableListOf<String>()
        virtualDisks.forEachIndexed { vdIdx, vd ->
            val file = File(vdDir, "${vd.id}.raw")
            if (!file.isFile) return@forEachIndexed
            val id = "vd$vdIdx"
            args += listOf("-drive", "file=${file.absolutePath},format=raw,if=none,id=$id")
            vdDrives += id
        }

        // ---- 外部镜像：ISO→光驱，其余→硬盘 ----
        val isoDrives = mutableListOf<String>()
        val hdDrives = mutableListOf<String>()
        prepared.forEachIndexed { i, (media, file) ->
            val type = media.type
            val id = "disk$i"
            when (type) {
                MediaType.ISO -> {
                    args += listOf("-drive", "file=${file.absolutePath},format=raw,if=none,id=$id,media=cdrom")
                    isoDrives += id
                }
                MediaType.FLOPPY -> {
                    // 软盘挂 fda（QEMU 只支持一个软驱）
                    args += listOf("-drive", "file=${file.absolutePath},format=raw,if=floppy")
                }
                MediaType.DISK -> {
                    args += listOf("-drive", "file=${file.absolutePath},format=raw,if=none,id=$id")
                    hdDrives += id
                }
            }
        }

        // IDE 系（IDE/SATA）：光驱和硬盘共用 ide.N 总线，槽位顺序分配。
        // 光驱优先低槽位（primary master），保证 Windows 从 CD 引导安装。
        val ideLike = diskController == "ide" || diskController == "sata"
        if (ideLike) {
            var bus = 0
            var unit = 0
            fun nextSlot(): String {
                val slot = "ide.$bus,unit=$unit"
                unit++
                if (unit >= 2) { unit = 0; bus++ }
                if (bus >= ideBusCount) bus = ideBusCount - 1
                return slot
            }
            isoDrives.forEach { id -> args += listOf("-device", "ide-cd,drive=$id,bus=${nextSlot()}") }
            (vdDrives + hdDrives).forEach { id -> args += listOf("-device", "ide-hd,drive=$id,bus=${nextSlot()}") }
            return args
        }
        when (diskController) {
            "scsi" -> {
                args += listOf("-device", "lsi53c895a,id=scsi0")
                isoDrives.forEachIndexed { idx, id ->
                    args += listOf("-device", "scsi-cd,drive=$id,bus=scsi0.0,unit=$idx")
                }
                (vdDrives + hdDrives).forEachIndexed { idx, id ->
                    args += listOf("-device", "scsi-hd,drive=$id,bus=scsi0.0,unit=$idx")
                }
            }
            else -> { // virtio
                isoDrives.forEach { id -> args += listOf("-device", "virtio-blk-pci,drive=$id") }
                (vdDrives + hdDrives).forEach { id -> args += listOf("-device", "virtio-blk-pci,drive=$id") }
            }
        }
        return args
    }

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

    /** 虚拟触摸板直接产生相对位移。累加而不是覆盖，快速滑动时不会丢失行程。 */
    private val pendingDeltaX = java.util.concurrent.atomic.AtomicInteger(0)
    private val pendingDeltaY = java.util.concurrent.atomic.AtomicInteger(0)
    private val deltaQueued = java.util.concurrent.atomic.AtomicBoolean(false)

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

    /**
     * 发送虚拟触摸板的相对位移，与当前是否配置 usb-tablet 无关。
     * 高频移动先合并，再逐批投递到输入线程，保证按键事件仍能及时执行。
     */
    fun sendMouseDelta(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        pendingDeltaX.addAndGet(dx)
        pendingDeltaY.addAndGet(dy)
        queueMouseDelta()
    }

    private fun queueMouseDelta() {
        if (!deltaQueued.compareAndSet(false, true)) return
        postInput {
            val dx = pendingDeltaX.getAndSet(0)
            val dy = pendingDeltaY.getAndSet(0)
            val q = qmp
            if (q != null && (dx != 0 || dy != 0)) {
                val events = buildList {
                    if (dx != 0) add(
                        mapOf("type" to "rel", "data" to mapOf("axis" to "x", "value" to dx))
                    )
                    if (dy != 0) add(
                        mapOf("type" to "rel", "data" to mapOf("axis" to "y", "value" to dy))
                    )
                }
                runCatching { q.execute("input-send-event", mapOf("events" to events)) }
            }
            deltaQueued.set(false)
            if (pendingDeltaX.get() != 0 || pendingDeltaY.get() != 0) queueMouseDelta()
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
