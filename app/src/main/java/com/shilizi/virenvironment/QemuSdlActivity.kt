package com.shilizi.virenvironment

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.max2idea.android.limbo.jni.VMExecutor
import org.libsdl.app.SDLActivity
import kotlin.math.abs
import kotlin.math.roundToInt

/** 原生 SDL 显示页。QEMU 直接绘制 Surface，不经过 VNC。 */
class QemuSdlActivity : SDLActivity() {
    private lateinit var engine: QemuSdlEngine
    private lateinit var keyboardView: SdlKeyboardInputView
    private var status by mutableStateOf("正在初始化原生显示…")
    private var paused by mutableStateOf(false)

    override fun loadLibraries() {
        System.loadLibrary("compat-limbo")
        System.loadLibrary("compat-musl")
        System.loadLibrary("glib-2.0")
        System.loadLibrary("pixman-1")
        if (android.os.Build.VERSION.SDK_INT >= 26) System.loadLibrary("compat-SDL2-addons")
        System.loadLibrary("SDL2")
        System.loadLibrary("compat-SDL2-ext")
        System.loadLibrary("limbo")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val media = readMedia()
        if (media.isEmpty()) {
            android.widget.Toast.makeText(this, "未选择磁盘镜像，无法启动虚拟机", android.widget.Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        engine = QemuSdlEngine(
            context = this,
            mediaList = media,
            virtualDisks = readVirtualDisks(),
            memoryMb = intent.getIntExtra(QemuDisplayActivity.EXTRA_MEMORY_MB, 2048),
            cpuCores = intent.getIntExtra(QemuDisplayActivity.EXTRA_CPU_CORES, 2),
            qemuArgs = readStringListExtra(QemuDisplayActivity.EXTRA_QEMU_ARGS),
            diskController = when (intent.getStringExtra(QemuDisplayActivity.EXTRA_DISK_CONTROLLER)) {
                "sata" -> "sata"
                "scsi-hd" -> "scsi"
                "virtio-blk-pci" -> "virtio"
                else -> "ide"
            },
            onStateChanged = { message -> runOnUiThread { status = message } }
        )

        keyboardView = SdlKeyboardInputView(this)
        val root = FrameLayout(this)
        createSurfaceIn(root)
        root.addView(keyboardView, FrameLayout.LayoutParams(1, 1))
        root.addView(createOverlay(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        QemuRuntime.sdlDisplay = this
        VMExecutor.resolutionListener = { width, height ->
            Log.i(TAG, "原生画面分辨率 ${width}x$height")
            runOnUiThread {
                resizeSurfaceBuffer(width, height)
                status = "原生 SDL 画面 ${width}x$height"
            }
        }
    }

    private fun createOverlay() = ComposeView(this).apply {
        setBackgroundColor(Color.TRANSPARENT)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { MaterialTheme { NativeControls() } }
    }

    override fun runSDLMain() {
        runCatching { engine.start() }
            .onFailure {
                Log.e(TAG, "QEMU SDL 启动失败", it)
                runOnUiThread {
                    status = "原生 QEMU 启动失败: ${it.message}"
                    android.widget.Toast.makeText(this, status, android.widget.Toast.LENGTH_LONG).show()
                }
            }
    }

    fun stopEmulator() {
        engine.stop()
        finish()
    }

    fun togglePause() {
        if (paused) engine.resume() else engine.pause()
        paused = !paused
    }

    private fun showKeyboard() {
        keyboardView.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(keyboardView, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onDestroy() {
        if (::engine.isInitialized) engine.stop()
        VMExecutor.resolutionListener = null
        if (QemuRuntime.sdlDisplay === this) QemuRuntime.sdlDisplay = null
        super.onDestroy()
    }

    @Composable
    private fun NativeControls() {
        var touchpadVisible by androidx.compose.runtime.remember { mutableStateOf(true) }
        Box(Modifier.fillMaxSize()) {
            Surface(
                color = ComposeColor(0xBB15191D),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
            ) {
                Text(status, color = ComposeColor.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
            }

            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(ComposeColor(0xCC15191D), RoundedCornerShape(6.dp)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = ::showKeyboard, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Default.Keyboard, "系统键盘", tint = ComposeColor.White)
                }
                IconButton(onClick = ::togglePause, modifier = Modifier.size(42.dp)) {
                    Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, if (paused) "继续" else "暂停", tint = ComposeColor.White)
                }
                IconButton(onClick = ::stopEmulator, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Default.Close, "关闭虚拟机", tint = ComposeColor(0xFFFF6B6B))
                }
            }

            AnimatedVisibility(
                visible = touchpadVisible,
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
            ) {
                NativeTouchpad(onHide = { touchpadVisible = false })
            }
            if (!touchpadVisible) {
                IconButton(
                    onClick = { touchpadVisible = true },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).background(ComposeColor(0xCC15191D), RoundedCornerShape(6.dp))
                ) {
                    Icon(Icons.Default.TouchApp, "显示触摸板", tint = ComposeColor.White)
                }
            }
        }
    }

    @Composable
    private fun NativeTouchpad(onHide: () -> Unit) {
        Surface(color = ComposeColor(0xE61B2025), shape = RoundedCornerShape(6.dp), shadowElevation = 4.dp) {
            Column(Modifier.width(286.dp).padding(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("虚拟触摸板", color = ComposeColor.White, fontSize = 12.sp)
                    IconButton(onClick = onHide, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.VisibilityOff, "隐藏触摸板", tint = ComposeColor(0xFFB8C0C7), modifier = Modifier.size(18.dp))
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(132.dp)
                        .background(ComposeColor(0xFF2A3036), RoundedCornerShape(4.dp))
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitPointerEvent().changes.firstOrNull { it.pressed }
                                    ?: return@awaitEachGesture
                                var dragged = false
                                var distance = 0f
                                while (true) {
                                    val change = awaitPointerEvent().changes
                                        .firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    val delta = change.positionChange()
                                    if (abs(delta.x) >= 0.5f || abs(delta.y) >= 0.5f) {
                                        distance += abs(delta.x) + abs(delta.y)
                                        if (distance >= viewConfiguration.touchSlop) dragged = true
                                        change.consume()
                                        engine.mouseMove(delta.x.roundToInt(), delta.y.roundToInt())
                                    }
                                }
                                if (!dragged) engine.mouseClick(MOUSE_LEFT)
                            }
                        }
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MouseButton("左键", MOUSE_LEFT, Modifier.weight(1f))
                    MouseButton("中键", MOUSE_MIDDLE, Modifier.weight(1f))
                    MouseButton("右键", MOUSE_RIGHT, Modifier.weight(1f))
                }
            }
        }
    }

    @Composable
    private fun MouseButton(label: String, button: Int, modifier: Modifier) {
        Box(
            modifier
                .height(38.dp)
                .background(ComposeColor(0xFF343B42), RoundedCornerShape(4.dp))
                .pointerInput(button) {
                    awaitEachGesture {
                        val down = awaitPointerEvent().changes.firstOrNull { it.pressed }
                            ?: return@awaitEachGesture
                        engine.mouseButton(button, true)
                        try {
                            while (awaitPointerEvent().changes.any { it.id == down.id && it.pressed }) {
                                // Keep the guest button pressed until the finger is released.
                            }
                        } finally {
                            engine.mouseButton(button, false)
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = ComposeColor.White, fontSize = 12.sp)
        }
    }

    private fun readMedia(): List<VmMedia> {
        val uris = readStringListExtra(QemuDisplayActivity.EXTRA_MEDIA_URIS)
        val names = readStringListExtra(QemuDisplayActivity.EXTRA_MEDIA_NAMES)
        val types = readStringListExtra(QemuDisplayActivity.EXTRA_MEDIA_TYPES)
        return uris.mapIndexedNotNull { index, value ->
            val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return@mapIndexedNotNull null
            VmMedia(
                uri,
                names.getOrNull(index) ?: uri.lastPathSegment.orEmpty(),
                runCatching { MediaType.valueOf(types.getOrNull(index) ?: "DISK") }.getOrDefault(MediaType.DISK)
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun readStringListExtra(key: String): List<String> = when (val value = intent.extras?.get(key)) {
        is ArrayList<*> -> value.filterIsInstance<String>()
        is Array<*> -> value.filterIsInstance<String>()
        else -> emptyList()
    }

    private fun readVirtualDisks(): List<VirtualDisk> =
        readStringListExtra(QemuDisplayActivity.EXTRA_VIRTUAL_DISKS).mapNotNull { id ->
            val file = java.io.File(filesDir, "labox-disks/$id.raw")
            if (file.isFile) VirtualDisk(id, id, (file.length() / 1048576L).toInt().coerceAtLeast(1)) else null
        }

    companion object {
        private const val TAG = "LaboxQemuSDL"
        private const val MOUSE_LEFT = 1
        private const val MOUSE_MIDDLE = 2
        private const val MOUSE_RIGHT = 3
    }
}

private class SdlKeyboardInputView(context: Context) : View(context) {
    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                text?.takeIf { it.isNotEmpty() }?.let { SDLActivity.commitText(it.toString()) }
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                // 组合文本由系统输入法维护，只在 commitText 时发送最终内容，避免拼音候选重复输入。
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtMost(32)) { sendKey(KeyEvent.KEYCODE_DEL) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) SDLActivity.onNativeKeyDown(event.keyCode)
                else if (event.action == KeyEvent.ACTION_UP) SDLActivity.onNativeKeyUp(event.keyCode)
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                sendKey(KeyEvent.KEYCODE_ENTER)
                return true
            }

            private fun sendKey(keyCode: Int) {
                SDLActivity.onNativeKeyDown(keyCode)
                SDLActivity.onNativeKeyUp(keyCode)
            }
        }
    }
}
