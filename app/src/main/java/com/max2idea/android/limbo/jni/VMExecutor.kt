package com.max2idea.android.limbo.jni

import android.os.ParcelFileDescriptor
import java.io.File

/**
 * JNI 名称必须与 Limbo 6.0.1 的 liblimbo.so 保持一致。
 * 这里只暴露 Labox 原生 SDL 显示需要的最小接口。
 */
class VMExecutor {
    external fun start(
        storageDir: String,
        baseDir: String,
        libraryName: String,
        libraryPath: String,
        sdlScaleHint: Int,
        params: Array<Any>
    ): String?

    external fun stop(restart: Int): String?
    external fun setSDLRefreshRateDefault(value: Int)
    external fun setSDLRefreshRateIdle(value: Int)
    external fun nativeMouseEvent(button: Int, action: Int, relative: Int, x: Int, y: Int)
    external fun nativeMouseBounds(xMin: Int, xMax: Int, yMin: Int, yMax: Int)

    /** Limbo 的 Android 文件系统兼容层按固定 JNI 名称回调这两个方法。 */
    fun get_fd(path: String): Int = runCatching {
        val normalized = path.removePrefix("/content/")
        ParcelFileDescriptor.open(File(normalized), ParcelFileDescriptor.MODE_READ_WRITE).detachFd()
    }.getOrElse { -1 }

    fun close_fd(fd: Int): Int = runCatching {
        ParcelFileDescriptor.adoptFd(fd).close()
        0
    }.getOrElse { -1 }

    companion object {
        @Volatile
        var resolutionListener: ((Int, Int) -> Unit)? = null

        @JvmStatic
        fun onVMResolutionChanged(width: Int, height: Int) {
            resolutionListener?.invoke(width, height)
        }
    }
}
