package com.shilizi.virenvironment

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.MotionEvent
import com.max2idea.android.limbo.jni.VMExecutor
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import kotlin.concurrent.thread

/** QEMU 5.1 Limbo 补丁版：同进程运行并由 SDL 直接绘制 Android Surface。 */
class QemuSdlEngine(
    context: Context,
    private val mediaList: List<VmMedia>,
    private val virtualDisks: List<VirtualDisk>,
    private val memoryMb: Int,
    private val cpuCores: Int,
    private val qemuArgs: List<String>,
    private val diskController: String,
    private val onStateChanged: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val executor = VMExecutor()
    private val rootDir = File(appContext.filesDir, "qemu-sdl")
    private val firmwareDir = File(rootDir, "firmware")
    private val mediaDir = File(rootDir, "media")
    private val virtualDiskDir = File(appContext.filesDir, "labox-disks")
    private val qmpPort = findFreePort()

    @Volatile private var qmp: QmpClient? = null
    @Volatile private var running = false

    fun start(): String? {
        onStateChanged("正在准备原生 SDL 显示…")
        prepareFirmware()
        val preparedMedia = prepareMedia()
        val coreName = "libqemu-system-x86_64-sdl.so"
        val corePath = File(appContext.applicationInfo.nativeLibraryDir, coreName)
        check(corePath.isFile) { "原生 SDL QEMU 核心不存在" }

        val args = buildArguments(coreName, preparedMedia)
        Log.i(TAG, "QEMU SDL 启动参数: $args")
        running = true
        connectQmpAsync()
        tuneRefreshRateAsync()
        onStateChanged("QEMU 原生画面启动中…")
        return try {
            executor.start(
                appContext.filesDir.absolutePath,
                rootDir.absolutePath,
                coreName,
                corePath.absolutePath,
                1,
                args.toTypedArray<Any>()
            )
        } finally {
            running = false
            runCatching { qmp?.close() }
            qmp = null
        }
    }

    fun stop() {
        if (running) runCatching { executor.stop(0) }
    }

    fun pause() {
        runCatching { qmp?.execute("stop") }
    }

    fun resume() {
        runCatching { qmp?.execute("cont") }
    }

    fun mouseMove(dx: Int, dy: Int) {
        if (running && (dx != 0 || dy != 0)) {
            executor.nativeMouseEvent(0, MotionEvent.ACTION_MOVE, 1, dx, dy)
        }
    }

    fun mouseButton(button: Int, down: Boolean) {
        if (running) {
            executor.nativeMouseEvent(
                button,
                if (down) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP,
                1,
                0,
                0
            )
        }
    }

    fun mouseClick(button: Int) {
        mouseButton(button, true)
        mouseButton(button, false)
    }

    fun mouseWheel(delta: Int) {
        if (running && delta != 0) {
            executor.nativeMouseEvent(0, MotionEvent.ACTION_SCROLL, 1, 0, delta)
        }
    }

    private fun tuneRefreshRateAsync() = thread(name = "labox-sdl-refresh", isDaemon = true) {
        repeat(20) {
            if (!running) return@thread
            Thread.sleep(100L)
            if (runCatching {
                    executor.setSDLRefreshRateDefault(16)
                    executor.setSDLRefreshRateIdle(16)
                }.isSuccess
            ) {
                Log.i(TAG, "SDL 刷新周期已设为 16ms")
                return@thread
            }
        }
    }

    private fun connectQmpAsync() = thread(name = "labox-sdl-qmp", isDaemon = true) {
        repeat(100) {
            if (!running) return@thread
            runCatching { QmpClient("127.0.0.1", qmpPort) }.onSuccess {
                qmp = it
                return@thread
            }
            Thread.sleep(100L)
        }
    }

    private fun prepareFirmware() {
        val marker = File(firmwareDir, ".installed-v1")
        if (marker.isFile) return
        firmwareDir.mkdirs()
        copyAssetTree("qemu11/firmware", firmwareDir)
        marker.writeText("ok")
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val entries = appContext.assets.list(assetPath).orEmpty()
        if (entries.isEmpty()) {
            destination.parentFile?.mkdirs()
            appContext.assets.open(assetPath).use { input ->
                destination.outputStream().use(input::copyTo)
            }
            return
        }
        destination.mkdirs()
        entries.forEach { entry -> copyAssetTree("$assetPath/$entry", File(destination, entry)) }
    }

    private fun prepareMedia(): List<Pair<VmMedia, File>> {
        mediaDir.mkdirs()
        return mediaList.mapIndexed { index, media ->
            val extension = media.name.substringAfterLast('.', "img").lowercase()
            val destination = File(mediaDir, "media$index.$extension")
            val sourceMarker = File(mediaDir, "media$index.src")
            val source = media.uri.toString()
            if (!destination.isFile || sourceMarker.readTextOrNull() != source) {
                destination.delete()
                openUri(media.uri).use { input -> destination.outputStream().use(input::copyTo) }
                sourceMarker.writeText(source)
            }
            media to destination
        }
    }

    private fun openUri(uri: Uri) = if (uri.scheme == "file") {
        File(requireNotNull(uri.path)).inputStream()
    } else {
        appContext.contentResolver.openInputStream(uri)
            ?: throw IOException("无法打开磁盘镜像: $uri")
    }

    private fun buildArguments(coreName: String, media: List<Pair<VmMedia, File>>): List<String> {
        val filtered = mutableListOf<String>()
        var index = 0
        while (index < qemuArgs.size) {
            val flag = qemuArgs[index]
            if (flag in INCOMPATIBLE_FLAGS && index + 1 < qemuArgs.size) {
                index += 2
                continue
            }
            // The on-screen touchpad emits relative SDL mouse events. A usb-tablet is an
            // absolute pointer and QEMU routes SDL input to it preferentially, making the
            // relative touchpad appear unresponsive. Keep the implicit PS/2 mouse for SDL.
            if (flag == "-device" && qemuArgs.getOrNull(index + 1) == "usb-tablet") {
                index += 2
                continue
            }
            filtered += when (flag) {
                "pc-i440fx-9.2" -> "pc-i440fx-5.1"
                "pc-q35-9.2" -> "pc-q35-5.1"
                else -> flag.replace("@OVMF_CODE@", File(firmwareDir, "OVMF_CODE.fd").absolutePath)
                    .replace("@OVMF_VARS@", File(firmwareDir, "OVMF_VARS.fd").absolutePath)
            }
            index++
        }

        prepareOvmf(filtered)
        return buildList {
            add(coreName)
            addAll(listOf("-L", firmwareDir.absolutePath))
            addAll(listOf("-m", memoryMb.toString(), "-smp", cpuCores.toString()))
            addAll(listOf("-accel", "tcg,thread=multi"))
            addAll(listOf("-qmp", "tcp:127.0.0.1:$qmpPort,server,nowait"))
            addAll(listOf("-monitor", "none", "-serial", "none", "-parallel", "none"))
            addAll(filtered)
            addAll(buildDiskArguments(media))
        }
    }

    private fun prepareOvmf(args: List<String>) {
        if (args.none { it.contains("OVMF_") }) return
        val code = File(firmwareDir, "OVMF_CODE.fd")
        val vars = File(firmwareDir, "OVMF_VARS.fd")
        if (!code.isFile) File(firmwareDir, "edk2-x86_64-code.fd").copyTo(code, overwrite = true)
        if (!vars.isFile) File(firmwareDir, "edk2-i386-vars.fd").copyTo(vars, overwrite = true)
    }

    private fun buildDiskArguments(prepared: List<Pair<VmMedia, File>>): List<String> {
        val args = mutableListOf<String>()
        val disks = mutableListOf<String>()
        val optical = mutableListOf<String>()

        virtualDisks.forEachIndexed { i, disk ->
            val file = File(virtualDiskDir, "${disk.id}.raw")
            if (file.isFile) {
                val id = "vd$i"
                args += listOf("-drive", "file=${file.absolutePath},format=raw,if=none,id=$id")
                disks += id
            }
        }
        prepared.forEachIndexed { i, (medium, file) ->
            val id = "media$i"
            when (medium.type) {
                MediaType.FLOPPY -> args += listOf("-drive", "file=${file.absolutePath},format=raw,if=floppy")
                MediaType.ISO -> {
                    args += listOf("-drive", "file=${file.absolutePath},format=raw,if=none,id=$id,media=cdrom")
                    optical += id
                }
                MediaType.DISK -> {
                    args += listOf("-drive", "file=${file.absolutePath},format=raw,if=none,id=$id")
                    disks += id
                }
            }
        }

        when (diskController) {
            "scsi" -> {
                args += listOf("-device", "lsi53c895a,id=scsi0")
                optical.forEachIndexed { i, id -> args += listOf("-device", "scsi-cd,drive=$id,bus=scsi0.0,unit=$i") }
                disks.forEachIndexed { i, id -> args += listOf("-device", "scsi-hd,drive=$id,bus=scsi0.0,unit=$i") }
            }
            "virtio" -> {
                optical.forEach { id -> args += listOf("-device", "virtio-blk-pci,drive=$id") }
                disks.forEach { id -> args += listOf("-device", "virtio-blk-pci,drive=$id") }
            }
            else -> {
                val q35 = qemuArgs.any { it.contains("q35") }
                var slot = 0
                fun nextBus(): String {
                    val bus = if (q35) "ide.${slot.coerceAtMost(5)},unit=0" else "ide.${slot / 2},unit=${slot % 2}"
                    slot++
                    return bus
                }
                optical.forEach { id -> args += listOf("-device", "ide-cd,drive=$id,bus=${nextBus()}") }
                disks.forEach { id -> args += listOf("-device", "ide-hd,drive=$id,bus=${nextBus()}") }
            }
        }
        return args
    }

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

    companion object {
        private const val TAG = "LaboxQemuSDL"
        private val INCOMPATIBLE_FLAGS = setOf(
            "-accel", "-vnc", "-display", "-qmp", "-D",
            // QEMU 5.1 lacks QEMU 11's explicit disable forms. Omitting these options
            // already means no audio or network device is created.
            "-audio", "-nic"
        )

        private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }
    }
}
