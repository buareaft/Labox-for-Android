package com.shilizi.virenvironment

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

data class VmLaunchConfig(
    val diskUri: Uri,
    val memoryMb: Int,
    val cpuCores: Int,
    val qemuHardware: QemuHardwareConfig = QemuHardwareConfig(),
    val networkEnabled: Boolean = true,
    val audioEnabled: Boolean = true
)

interface VirtualMachineRuntime {
    val engine: VmEngine
    fun isAvailable(context: Context): Boolean
    fun start(context: Context, config: VmLaunchConfig)
    fun pause()
    fun resume()
    fun stop()
}

/** Web runtime entry point. Bundle v86 under app/src/main/assets/v86. */
class V86Runtime : VirtualMachineRuntime {
    override val engine = VmEngine.V86

    override fun isAvailable(context: Context): Boolean = runCatching {
        context.assets.open("v86/index.html").close()
    }.isSuccess

    override fun start(context: Context, config: VmLaunchConfig) {
        val intent = Intent(context, V86Activity::class.java).apply {
            putExtra(V86Activity.EXTRA_DISK_URI, config.diskUri)
            putExtra(V86Activity.EXTRA_MEMORY_MB, config.memoryMb)
            putExtra(V86Activity.EXTRA_IMAGE_TYPE, imageTypeOf(context, config.diskUri))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    override fun pause() {
        display?.togglePause()
    }

    override fun resume() {
        display?.togglePause()
    }

    override fun stop() {
        display?.stopEmulator()
    }

    /**
     * 判断镜像介质类型：
     *  - .iso → 光驱（v86 cdrom）
     *  - <3MB → 软盘（v86 fda），如 FreeDOS 引导盘——必须挂软驱才能引导，
     *    否则 v86 当硬盘（hda）挂载时 BIOS 找不到引导扇区，输出几行文本后黑屏
     *  - 其他 → 硬盘（v86 hda）
     */
    private fun imageTypeOf(context: Context, uri: Uri): String {
        val name = uri.lastPathSegment.orEmpty()
        if (name.substringAfterLast('.', "").equals("iso", ignoreCase = true)) return "iso"
        val size = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else -1L }
        }.getOrDefault(-1L)
        if (size in 1 until 3L * 1024 * 1024) return "floppy"
        return "img"
    }

    companion object {
        /** 当前显示的 v86 界面，由 V86Activity 生命周期维护。 */
        var display: V86Activity? = null
    }
}

/**
 * QEMU 11 独立进程运行时。
 *
 * QEMU 11 以 Termux bionic 原生可执行文件打包进 assets，运行时解压到私有目录，
 * 通过 ProcessBuilder 启动为独立进程（天然隔离，不污染 UI 进程）。
 * 显示输出走 VNC，控制走 QMP（暂停/继续/退出），均由 [QemuDisplayActivity] 接管。
 */
class QemuRuntime : VirtualMachineRuntime {
    override val engine = VmEngine.QEMU

    override fun isAvailable(context: Context): Boolean =
        // 检查 assets 里是否打包了 QEMU 11 依赖库（二进制在 nativeLibraryDir，运行时由系统按 ABI 解压）
        runCatching { context.assets.open("qemu11/lib/arm64-v8a/libglib-2.0.so").close() }.isSuccess ||
            runCatching { context.assets.open("qemu11/lib/x86_64/libglib-2.0.so").close() }.isSuccess

    override fun start(context: Context, config: VmLaunchConfig) {
        val plan = config.qemuHardware.toQemuLaunchPlan(config.networkEnabled, config.audioEnabled)
        val intent = Intent(context, QemuDisplayActivity::class.java).apply {
            putExtra(QemuDisplayActivity.EXTRA_DISK_URI, config.diskUri)
            putExtra(QemuDisplayActivity.EXTRA_MEMORY_MB, config.memoryMb)
            putExtra(QemuDisplayActivity.EXTRA_CPU_CORES, config.cpuCores)
            putStringArrayListExtra(QemuDisplayActivity.EXTRA_QEMU_ARGS, ArrayList(plan.arguments))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    override fun pause() {
        display?.togglePause()
    }

    override fun resume() {
        display?.togglePause()
    }

    override fun stop() {
        display?.stopEmulator()
    }

    companion object {
        /** 当前显示的 QEMU 界面，由 QemuDisplayActivity 生命周期维护。 */
        var display: QemuDisplayActivity? = null
    }
}
