package com.shilizi.virenvironment

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

data class VmLaunchConfig(
    val mediaList: List<VmMedia> = emptyList(),
    val virtualDisks: List<VirtualDisk> = emptyList(),
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
        // v86 一次只能挂一个主介质（fda/cdrom/hda 三选一），取列表第一个；
        // 虚拟硬盘（raw 文件）额外挂为 hda，让 XP 安装/运行有目标磁盘
        val media = config.mediaList.firstOrNull()
        if (media == null) {
            android.widget.Toast.makeText(context, "v86 需要至少一个镜像介质", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val hda = config.virtualDisks.firstOrNull()?.let { vd ->
            val file = java.io.File(context.filesDir, "labox-disks/${vd.id}.raw")
            if (file.isFile) file.absolutePath else null
        }
        val intent = Intent(context, V86Activity::class.java).apply {
            putExtra(V86Activity.EXTRA_DISK_URI, media.uri)
            putExtra(V86Activity.EXTRA_MEMORY_MB, config.memoryMb)
            putExtra(V86Activity.EXTRA_IMAGE_TYPE, v86ImageType(media.type))
            hda?.let { putExtra(V86Activity.EXTRA_HDA_PATH, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** v86 的 imageType 值：iso / floppy / img。 */
    private fun v86ImageType(type: MediaType): String = when (type) {
        MediaType.ISO -> "iso"
        MediaType.FLOPPY -> "floppy"
        MediaType.DISK -> "img"
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
        /** 当前显示的 v86 界面，由 V86Activity 生命周期维护。 */
        var display: V86Activity? = null
    }
}

/** QEMU 运行时：优先使用 Limbo SDL 原生 Surface，库不可用时回退 QEMU 11/VNC。 */
class QemuRuntime : VirtualMachineRuntime {
    override val engine = VmEngine.QEMU

    override fun isAvailable(context: Context): Boolean =
        // 检查 assets 里是否打包了 QEMU 11 依赖库（二进制在 nativeLibraryDir，运行时由系统按 ABI 解压）。
        // 用标准 soname（libglib-2.0.so.0）——无版本软链 libglib-2.0.so 已被裁剪掉。
        runCatching { context.assets.open("qemu11/lib/arm64-v8a/libglib-2.0.so.0").close() }.isSuccess ||
            runCatching { context.assets.open("qemu11/lib/x86_64/libglib-2.0.so.0").close() }.isSuccess

    override fun start(context: Context, config: VmLaunchConfig) {
        val plan = config.qemuHardware.toQemuLaunchPlan(config.networkEnabled, config.audioEnabled)
        val nativeCore = File(context.applicationInfo.nativeLibraryDir, "libqemu-system-x86_64-sdl.so")
        val displayClass = if (nativeCore.isFile) QemuSdlActivity::class.java else QemuDisplayActivity::class.java
        val intent = Intent(context, displayClass).apply {
            putExtra(QemuDisplayActivity.EXTRA_MEDIA_URIS, ArrayList(config.mediaList.map { it.uri.toString() }))
            putExtra(QemuDisplayActivity.EXTRA_MEDIA_NAMES, ArrayList(config.mediaList.map { it.name }))
            putExtra(QemuDisplayActivity.EXTRA_MEDIA_TYPES, ArrayList(config.mediaList.map { it.type.name }))
            putExtra(QemuDisplayActivity.EXTRA_VIRTUAL_DISKS, ArrayList(config.virtualDisks.map { it.id }))
            putExtra(QemuDisplayActivity.EXTRA_DISK_CONTROLLER, config.qemuHardware.disk.qemuValue)
            putExtra(QemuDisplayActivity.EXTRA_MEMORY_MB, config.memoryMb)
            putExtra(QemuDisplayActivity.EXTRA_CPU_CORES, config.cpuCores)
            putStringArrayListExtra(QemuDisplayActivity.EXTRA_QEMU_ARGS, ArrayList(plan.arguments))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    override fun pause() {
        sdlDisplay?.togglePause() ?: display?.togglePause()
    }

    override fun resume() {
        sdlDisplay?.togglePause() ?: display?.togglePause()
    }

    override fun stop() {
        sdlDisplay?.stopEmulator() ?: display?.stopEmulator()
    }

    companion object {
        /** 当前显示的 QEMU 界面，由 QemuDisplayActivity 生命周期维护。 */
        var display: QemuDisplayActivity? = null

        /** 当前原生 SDL 显示页。 */
        var sdlDisplay: QemuSdlActivity? = null
    }
}
