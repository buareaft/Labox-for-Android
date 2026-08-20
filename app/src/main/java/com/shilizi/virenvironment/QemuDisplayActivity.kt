package com.shilizi.virenvironment

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * QEMU 虚拟机显示界面。QEMU 11 以独立进程运行，画面经 QMP screendump 轮询渲染，
 * 触摸/键盘经 QMP input-send-event 注入。
 *
 * 交互设计（悬浮球集成）：
 *  - 画面占满全屏，右上悬浮球可拖动吸附、点击展开控制面板
 *  - 画面模式：等比(FIT)/拉伸(STRETCH)/原始(ORIGINAL)，双指捏合缩放，缩放后单指平移
 *  - 触摸=鼠标：单指左键、长按/双指右键
 */
class QemuDisplayActivity : ComponentActivity() {

    private lateinit var vncView: VncView
    private var screendumpThread: Thread? = null
    private var engine: Qemu11Engine? = null
    private var paused = false
    private var finished = false
    private var lastButtons = 0
    private var immersive = true

    /** 后台时暂停画面轮询，省电（QEMU 进程保持运行，回来继续渲染）。 */
    @Volatile
    private var screenVisible = true

    /** 停止确认对话框（面板按钮/返回键都先确认，防误触杀虚拟机）。 */
    private val confirmStop = mutableStateOf(false)

    private val statusText = mutableStateOf("正在启动 QEMU…")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QemuRuntime.display = this
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableImmersiveMode()

        val diskUri: Uri? = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DISK_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DISK_URI)
        } ?: intent.getStringExtra(EXTRA_DISK_URI)?.let { Uri.parse(it) }
        val memoryMb = intent.getIntExtra(EXTRA_MEMORY_MB, 2048)
        val cpuCores = intent.getIntExtra(EXTRA_CPU_CORES, 2)

        // am --esa 存 String[]，app 内 putStringArrayListExtra 存 ArrayList，两种都兼容
        val qemuArgs = intent.getStringArrayExtra(EXTRA_QEMU_ARGS)?.toList()
            ?: intent.getStringArrayListExtra(EXTRA_QEMU_ARGS) ?: emptyList()
        val planArgs = when (intent.getStringExtra(EXTRA_BOOT_PRESET)) {
            "win11_pe" -> win11PeArguments()
            else -> qemuArgs.ifEmpty { defaultBootArguments() }
        }
        Log.i(TAG, "intent keys=${intent.extras?.keySet()}, qemuArgs=$qemuArgs")

        vncView = VncView(this)

        val vncPort = findFreePort()
        val qmpPort = findFreePort()

        if (diskUri != null) {
            val eng = Qemu11Engine(
                context = this,
                diskUri = diskUri,
                memoryMb = memoryMb,
                cpuCores = cpuCores,
                vncPort = vncPort,
                qmpPort = qmpPort,
                qemuArgs = planArgs,
                onStateChanged = { msg -> runOnUiThread { statusText.value = msg } }
            )
            engine = eng
            // QEMU 进程异常退出（崩溃/被杀）时：界面显示提示并退出，避免停在死机画面
            eng.onProcessExit = { msg ->
                if (!finished) {
                    runOnUiThread {
                        statusText.value = "QEMU 已退出"
                        showVmExitedDialog("虚拟机已退出", "QEMU 进程已终止，虚拟机已关闭。")
                    }
                }
            }
            // 触摸 → QMP input-send-event 鼠标：usb-tablet 绝对坐标；无 tablet 时相对位移
            val useTablet = planArgs.any { it.contains("usb-tablet") }
            eng.pointerMode =
                if (useTablet) Qemu11Engine.PointerMode.ABS else Qemu11Engine.PointerMode.REL
            vncView.mouseCallback = { x, y, buttons ->
                eng.sendMouseMove(x, y)
                val changed = buttons xor lastButtons
                if (changed and 1 != 0) eng.sendMouseButton("left", buttons and 1 != 0)
                if (changed and 4 != 0) eng.sendMouseButton("right", buttons and 4 != 0)
                lastButtons = buttons
            }
            // 双指垂直滑动 = 滚轮滚动
            vncView.wheelCallback = { notches -> eng.sendWheel(notches) }
            // 解压 + 启动 + 轮询渲染（后台线程）
            Thread({
                try {
                    // 若 Activity 已销毁（用户退出/系统回收）直接放弃，否则 QEMU 进程会变成孤儿
                    if (finished) return@Thread
                    eng.prepare()
                    if (finished) return@Thread
                    runOnUiThread { statusText.value = "QEMU 启动中…" }
                    eng.start()
                    if (finished) {
                        eng.stop()
                        return@Thread
                    }
                    statusText.value = "QEMU 已启动"
                    // QEMU 11 的 VNC 在纯 TCG 下不推送帧，改用 QMP screendump 轮询渲染
                    startScreendumpLoop()
                } catch (error: Throwable) {
                    Log.e(TAG, "QEMU 启动失败", error)
                    runOnUiThread { statusText.value = "QEMU 启动失败: ${error.message}" }
                }
            }, "labox-qemu-launch").start()
        } else {
            // 无磁盘镜像（异常入口）：不能启动虚拟机，直接退出并提示
            android.widget.Toast.makeText(this, "未选择磁盘镜像，无法启动虚拟机", android.widget.Toast.LENGTH_SHORT).show()
            finish()
        }

        setContent {
            QemuScreen(
                status = statusText.value,
                vncView = vncView,
                paused = paused,
                confirmStop = confirmStop.value,
                onPauseClick = { togglePause() },
                onKeyboard = { text -> sendText(text) },
                onStopClick = { stopEmulator() },
                onRequestStop = { confirmStop.value = true },
                onDismissStop = { confirmStop.value = false },
                onScreenshot = { takeScreenshot() },
                onFullscreen = { toggleFullscreen() },
                onDisplayMode = { mode -> vncView.displayMode = mode },
                onKey = { key -> engine?.tapKey(key) },
                onCombo = { keys -> engine?.tapKeyCombo(*keys.toTypedArray()) }
            )
        }
    }

    /** 未传入 QEMU 参数时（独立入口/测试），用 CD 引导 + 经典设备兜底。 */
    private fun defaultBootArguments(): List<String> = listOf(
        "-machine", "pc",
        "-cpu", "qemu64",
        "-accel", "tcg,thread=multi",
        "-vga", "cirrus",
        "-boot", "order=d",
        "-net", "nic,model=ne2k_pci",
        "-net", "user",
        "-device", "usb-ehci",
        "-device", "usb-tablet"
    )

    /**
     * Win11 PE 验证预设：q35 + SATA + UEFI(OVMF pflash) + XHCI + usb-tablet。
     * 与生产入口共用 toQemuLaunchPlan，仅用于 adb 直连验证流程。
     */
    private fun win11PeArguments(): List<String> =
        recommendedQemuHardware(WindowsProfile.WINDOWS_10)
            .toQemuLaunchPlan(networkEnabled = true, audioEnabled = true).arguments

    /** 全屏界面：画面占满 + 悬浮球 + 控制面板。 */
    @Composable
    private fun QemuScreen(
        status: String,
        vncView: VncView,
        paused: Boolean,
        confirmStop: Boolean,
        onPauseClick: () -> Unit,
        onKeyboard: (String) -> Unit,
        onStopClick: () -> Unit,
        onRequestStop: () -> Unit,
        onDismissStop: () -> Unit,
        onScreenshot: () -> Unit,
        onFullscreen: () -> Unit,
        onDisplayMode: (DisplayMode) -> Unit,
        onKey: (String) -> Unit,
        onCombo: (List<String>) -> Unit
    ) {
        var menuOpen by remember { mutableStateOf(false) }
        Surface(color = ComposeColor(0xFF101214), modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // 虚拟机画面（占满全屏）
                AndroidView<SurfaceView>(factory = { vncView }, modifier = Modifier.fillMaxSize())

                // 半透明遮罩（菜单打开时拦截触摸，点空白关闭）
                if (menuOpen) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(ComposeColor(0x66000000))
                            .clickable { menuOpen = false }
                    )
                }

                // 悬浮球：可拖动、吸附边缘，点击展开/收起菜单
                val ballW = maxWidth
                val ballH = maxHeight
                FloatingBall(
                    containerW = with(LocalDensity.current) { ballW.toPx() },
                    containerH = with(LocalDensity.current) { ballH.toPx() },
                    panelOpen = menuOpen,
                    onTap = { menuOpen = !menuOpen }
                )

                // 控制面板（底部滑出，悬浮球点击展开）
                AnimatedVisibility(visible = menuOpen, modifier = Modifier.align(Alignment.BottomCenter)) {
                    ControlPanel(
                        status = status,
                        paused = paused,
                        onPauseClick = onPauseClick,
                        onStopClick = onRequestStop,
                        onScreenshot = onScreenshot,
                        onFullscreen = onFullscreen,
                        onDisplayMode = onDisplayMode,
                        onKeyboard = onKeyboard,
                        onKey = onKey,
                        onCombo = onCombo
                    )
                }
            }
        }

        // 停止确认对话框（面板停止按钮 / 返回键共用）
        if (confirmStop) {
            AlertDialog(
                onDismissRequest = onDismissStop,
                title = { Text("停止虚拟机") },
                text = { Text("确定要停止虚拟机吗？未保存的数据将丢失。") },
                confirmButton = {
                    TextButton(onClick = {
                        onDismissStop()
                        onStopClick()
                    }) { Text("停止", color = ComposeColor(0xFFFF5252)) }
                },
                dismissButton = {
                    TextButton(onClick = onDismissStop) { Text("取消") }
                }
            )
        }
    }

    /** 应用内悬浮球：半透明圆形，拖动跟随、松手吸附左右边缘，轻点触发 onTap。 */
    @Composable
    private fun FloatingBall(
        containerW: Float,
        containerH: Float,
        panelOpen: Boolean,
        onTap: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        val ballSize = with(LocalDensity.current) { 54.dp.toPx() }
        val edgeMargin = with(LocalDensity.current) { 8.dp.toPx() }
        var pos by remember {
            mutableStateOf(Offset(containerW - ballSize - edgeMargin, containerH * 0.4f))
        }
        var dragging by remember { mutableStateOf(false) }
        // 面板打开时球自动上移避让，不遮挡控制面板（面板约 260dp 高）
        val panelHeight = with(LocalDensity.current) { 260.dp.toPx() }
        val targetY = if (panelOpen) {
            (containerH - panelHeight - ballSize - edgeMargin * 2).coerceAtLeast(edgeMargin)
        } else {
            pos.y.coerceIn(edgeMargin, containerH - ballSize - edgeMargin)
        }
        // 尺寸变化（横竖屏/分辨率）时把球约束回屏幕内，避免初始 Y 或拖动出屏
        LaunchedEffect(containerW, containerH, panelOpen) {
            pos = Offset(
                pos.x.coerceIn(edgeMargin, (containerW - ballSize - edgeMargin).coerceAtLeast(edgeMargin)),
                if (panelOpen) targetY else pos.y.coerceIn(edgeMargin, (containerH - ballSize - edgeMargin).coerceAtLeast(edgeMargin))
            )
        }
        Box(
            modifier = modifier
                .offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }
                .size(54.dp)
                .graphicsLayer {
                    alpha = if (dragging) 0.95f else 0.72f
                    shadowElevation = 8f
                }
                .clip(CircleShape)
                .background(ComposeColor(0xCC1565C0))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var dragStarted = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (change.changedToUp()) break
                            if (!dragStarted &&
                                (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                            ) {
                                dragStarted = true
                            }
                            if (dragStarted) {
                                dragging = true
                                pos += change.positionChange()
                                change.consume()
                            }
                        }
                        if (!dragStarted) {
                            onTap()
                        } else {
                            // 松手吸附到较近的侧边，Y 限制在屏内
                            val x = if (pos.x < containerW / 2) edgeMargin
                            else containerW - ballSize - edgeMargin
                            pos = Offset(
                                x,
                                pos.y.coerceIn(edgeMargin, containerH - ballSize - edgeMargin)
                            )
                        }
                        dragging = false
                    }
                }
        ) {
            Box(Modifier.align(Alignment.Center)) {
                Text("⚙", color = ComposeColor.White, fontSize = 22.sp)
            }
        }
    }

    /** 悬浮球展开的控制面板：状态 + 功能按钮 + 特殊键 + 软键盘输入。 */
    @Composable
    private fun ControlPanel(
        status: String,
        paused: Boolean,
        onPauseClick: () -> Unit,
        onStopClick: () -> Unit,
        onScreenshot: () -> Unit,
        onFullscreen: () -> Unit,
        onDisplayMode: (DisplayMode) -> Unit,
        onKeyboard: (String) -> Unit,
        onKey: (String) -> Unit,
        onCombo: (List<String>) -> Unit
    ) {
        var pausedLocal by remember { mutableStateOf(paused) }
        var keyboardOpen by remember { mutableStateOf(false) }
        var keyboardText by remember { mutableStateOf("") }
        // 暂停状态由 Activity 持有，面板按钮点击后同步回显
        LaunchedEffect(paused) { pausedLocal = paused }

        Column(
            Modifier
                .fillMaxWidth()
                .background(ComposeColor(0xF21C1F23))
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 状态 + 功能按钮
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = status,
                    color = ComposeColor(0xFF9AA0A6),
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                panelButton(if (pausedLocal) "继续" else "暂停") {
                    pausedLocal = !pausedLocal
                    onPauseClick()
                }
                panelButton("停止", onStopClick)
                panelButton("截图", onScreenshot)
                panelButton(if (immersive) "退出全屏" else "全屏", onFullscreen)
                panelButton("键盘", { keyboardOpen = !keyboardOpen })
            }
            // 画面模式 + 复位
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("画面:", color = ComposeColor(0xFF9AA0A6), fontSize = 12.sp)
                DisplayMode.values().forEach { m ->
                    modeChip(m.label, vncView.displayMode == m) { onDisplayMode(m) }
                }
                modeChip("复位", false) { onDisplayMode(DisplayMode.FIT) }
            }
            // 特殊键（水平滚动）
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                keyChip("Win") { onKey("meta_l") }
                keyChip("Win+E") { onCombo(listOf("meta_l", "e")) }
                keyChip("Win+R") { onCombo(listOf("meta_l", "r")) }
                keyChip("Win+L") { onCombo(listOf("meta_l", "l")) }
                keyChip("Ctrl+Alt+Del") { onCombo(listOf("ctrl", "alt", "delete")) }
                keyChip("Alt+Tab") { onCombo(listOf("alt", "tab")) }
                keyChip("PrtSc") { onKey("print") }
                keyChip("F1") { onKey("f1") }
                keyChip("F5") { onKey("f5") }
                keyChip("F8") { onKey("f8") }
                keyChip("F10") { onKey("f10") }
                keyChip("F12") { onKey("f12") }
                keyChip("Esc") { onKey("esc") }
                keyChip("Tab") { onKey("tab") }
                keyChip("↵") { onKey("ret") }
                keyChip("空格") { onKey("spc") }
                keyChip("←") { onKey("left") }
                keyChip("↑") { onKey("up") }
                keyChip("→") { onKey("right") }
                keyChip("↓") { onKey("down") }
            }
            // 软键盘输入（点击「键盘」展开）
            AnimatedVisibility(visible = keyboardOpen) {
                OutlinedTextField(
                    value = keyboardText,
                    onValueChange = { newValue ->
                        if (newValue.length > keyboardText.length) {
                            onKeyboard(newValue.substring(keyboardText.length))
                        } else {
                            repeat(keyboardText.length - newValue.length) {
                                onKeyboard("\u0008")
                            }
                        }
                        keyboardText = newValue
                    },
                    placeholder = { Text("键盘输入…") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }

    /** 功能按钮（面板第一行）。 */
    @Composable
    private fun panelButton(label: String, onClick: () -> Unit) {
        TextButton(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            modifier = Modifier.height(32.dp)
        ) {
            Text(label, color = ComposeColor(0xFF36C98F), fontSize = 13.sp)
        }
    }

    /** 画面模式选择 chip。 */
    @Composable
    private fun modeChip(label: String, selected: Boolean, onClick: () -> Unit) {
        TextButton(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            modifier = Modifier.height(32.dp)
        ) {
            Text(
                label,
                color = if (selected) ComposeColor(0xFF36C98F) else ComposeColor(0xFF9AA0A6),
                fontSize = 13.sp
            )
        }
    }

    /** 特殊键快捷按钮。 */
    @Composable
    private fun keyChip(label: String, onClick: () -> Unit) {
        TextButton(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            modifier = Modifier.height(34.dp)
        ) {
            Text(label, color = ComposeColor(0xFF9AA0A6), fontSize = 13.sp)
        }
    }

    internal fun togglePause() {
        val eng = engine ?: return
        if (paused) {
            eng.resume()
            paused = false
        } else {
            eng.pause()
            paused = true
        }
    }

    private fun sendText(text: String) {
        engine?.tapText(text)
    }

    /** 全屏切换：显示/隐藏系统导航栏与状态栏。 */
    private fun toggleFullscreen() {
        immersive = !immersive
        val decor = window.decorView
        if (immersive) {
            enableImmersiveMode()
        } else {
            decor.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    /** 截取当前画面保存为 PNG（走引擎的 screendump，后台线程执行）。 */
    private fun takeScreenshot() {
        Thread({
            val bmp = engine?.screendumpBitmap()
            if (bmp == null) {
                runOnUiThread { statusText.value = "截图失败（画面未就绪）" }
                return@Thread
            }
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir, "screenshots")
            dir.mkdirs()
            val f = File(dir, "vm_${System.currentTimeMillis()}.png")
            val ok = runCatching {
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }.isSuccess
            runOnUiThread { statusText.value = if (ok) "截图已保存: ${f.name}" else "截图失败" }
        }, "labox-screenshot").start()
    }

    internal fun stopEmulator() {
        engine?.stop()
        finish()
    }

    /** 虚拟机进程异常退出时提示（与用户主动停止区分，主动停止不弹此框）。 */
    private fun showVmExitedDialog(title: String, message: String) {
        if (finished || isFinishing) return
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("确定") { _, _ -> finish() }
            .show()
    }

    override fun onBackPressed() {
        // 返回键先确认（防误触杀虚拟机），面板停止按钮也走同一入口
        confirmStop.value = true
    }

    /**
     * 实体键盘透传：Android KeyEvent -> QEMU qcode，转发给引擎。
     * 修饰键（Ctrl/Alt/Shift）按状态同步；Compose 文本框聚焦时按键由文本框
     * 消费、不会走到这里（不干扰软键盘输入框）。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val eng = engine ?: return super.onKeyDown(keyCode, event)
        val qcode = qcodeForKeyCode(keyCode) ?: return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) {
            // 按下前同步修饰键状态（只同步一次，重复 keydown 不再重复按）
            if (event.isCtrlPressed) eng.sendKey("ctrl", true)
            if (event.isAltPressed) eng.sendKey("alt", true)
            if (event.isShiftPressed) eng.sendKey("shift", true)
            eng.sendKey(qcode, true)
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val eng = engine ?: return super.onKeyUp(keyCode, event)
        val qcode = qcodeForKeyCode(keyCode) ?: return super.onKeyUp(keyCode, event)
        eng.sendKey(qcode, false)
        // 松开后按当前状态释放修饰键（若仍在按住则不释放）
        if (!event.isCtrlPressed) eng.sendKey("ctrl", false)
        if (!event.isAltPressed) eng.sendKey("alt", false)
        if (!event.isShiftPressed) eng.sendKey("shift", false)
        return true
    }

    override fun onDestroy() {
        finished = true
        if (QemuRuntime.display === this) QemuRuntime.display = null
        // 无论何种原因销毁都停掉引擎：QEMU 进程 + 输入线程 + QMP 连接必须随界面释放
        engine?.stop()
        engine = null
        // 释放大对象引用（帧缓冲 Bitmap / SurfaceView），确保可被 GC
        vncView.clearFrame()
        super.onDestroy()
    }

    /** 应用退到后台：暂停画面轮询省电（QEMU 进程保持运行）。 */
    override fun onPause() {
        super.onPause()
        screenVisible = false
    }

    /** 回到前台：恢复画面轮询。 */
    override fun onResume() {
        super.onResume()
        screenVisible = true
    }

    private fun enableImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
        window.decorView.setOnSystemUiVisibilityChangeListener {
            if (it and View.SYSTEM_UI_FLAG_FULLSCREEN == 0 && immersive) {
                window.decorView.postDelayed({ enableImmersiveMode() }, 1000)
            }
        }
    }

    private fun findFreePort(): Int =
        ServerSocket(0).use { it.localPort }

    /**
     * QMP screendump 轮询渲染循环。
     *
     * QEMU 11 的 VNC 在纯 TCG（无 KVM）下不推送帧（已验证），
     * 改用 QMP screendump 截 PNG + BitmapFactory 解码渲染。
     * 轮询间隔 250ms：FreeDOS 等低速系统足够流畅，避免 TCG 负载过高。
     */
    private fun startScreendumpLoop() {
        if (screendumpThread != null) return
        screendumpThread = Thread({
            var firstFrame = true
            var frameCount = 0
            while (!finished && engine?.isRunning() == true) {
                try {
                    if (!screenVisible) {
                        // 后台时暂停轮询省电（QEMU 仍运行，回前台恢复）
                        Thread.sleep(300)
                        continue
                    }
                    val bmp = engine!!.screendumpBitmap()
                    if (bmp == null) {
                        Log.w(TAG, "screendumpBitmap null")
                        Thread.sleep(300)
                        continue
                    }
                    if (firstFrame) {
                        firstFrame = false
                        Log.i(TAG, "screendump 首帧 ${bmp.width}x${bmp.height}")
                        runOnUiThread { statusText.value = "已连接画面 ${bmp.width}x${bmp.height}" }
                    }
                    vncView.drawFrame(bmp)
                    if (++frameCount % 20 == 0) Log.i(TAG, "screendump 已渲染 $frameCount 帧")
                    Thread.sleep(250)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "screendump 循环异常: ${e.message}")
                    Thread.sleep(300)
                }
            }
            // 退出循环：释放线程引用 + 清掉最后一帧，让 SurfaceView 可被 GC
            screendumpThread = null
            vncView.clearFrame()
        }, "labox-screendump").also { it.isDaemon = true }
        screendumpThread!!.start()
    }

    companion object {
        private const val TAG = "LaboxQemu"
        const val EXTRA_DISK_URI = "disk_uri"
        const val EXTRA_MEMORY_MB = "memory_mb"
        const val EXTRA_CPU_CORES = "cpu_cores"
        const val EXTRA_QEMU_ARGS = "qemu_args"
        const val EXTRA_BOOT_PRESET = "boot_preset"

        /** Android KeyEvent keyCode -> QEMU qcode（实体键盘透传用）。不支持的返回 null。 */
        fun qcodeForKeyCode(keyCode: Int): String? = when (keyCode) {
            android.view.KeyEvent.KEYCODE_A -> "a"
            android.view.KeyEvent.KEYCODE_B -> "b"
            android.view.KeyEvent.KEYCODE_C -> "c"
            android.view.KeyEvent.KEYCODE_D -> "d"
            android.view.KeyEvent.KEYCODE_E -> "e"
            android.view.KeyEvent.KEYCODE_F -> "f"
            android.view.KeyEvent.KEYCODE_G -> "g"
            android.view.KeyEvent.KEYCODE_H -> "h"
            android.view.KeyEvent.KEYCODE_I -> "i"
            android.view.KeyEvent.KEYCODE_J -> "j"
            android.view.KeyEvent.KEYCODE_K -> "k"
            android.view.KeyEvent.KEYCODE_L -> "l"
            android.view.KeyEvent.KEYCODE_M -> "m"
            android.view.KeyEvent.KEYCODE_N -> "n"
            android.view.KeyEvent.KEYCODE_O -> "o"
            android.view.KeyEvent.KEYCODE_P -> "p"
            android.view.KeyEvent.KEYCODE_Q -> "q"
            android.view.KeyEvent.KEYCODE_R -> "r"
            android.view.KeyEvent.KEYCODE_S -> "s"
            android.view.KeyEvent.KEYCODE_T -> "t"
            android.view.KeyEvent.KEYCODE_U -> "u"
            android.view.KeyEvent.KEYCODE_V -> "v"
            android.view.KeyEvent.KEYCODE_W -> "w"
            android.view.KeyEvent.KEYCODE_X -> "x"
            android.view.KeyEvent.KEYCODE_Y -> "y"
            android.view.KeyEvent.KEYCODE_Z -> "z"
            in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 ->
                (keyCode - android.view.KeyEvent.KEYCODE_0).toString()
            android.view.KeyEvent.KEYCODE_ENTER -> "ret"
            android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> "kp_enter"
            android.view.KeyEvent.KEYCODE_DEL -> "backspace"
            android.view.KeyEvent.KEYCODE_FORWARD_DEL -> "delete"
            android.view.KeyEvent.KEYCODE_TAB -> "tab"
            android.view.KeyEvent.KEYCODE_SPACE -> "spc"
            android.view.KeyEvent.KEYCODE_ESCAPE -> "esc"
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            android.view.KeyEvent.KEYCODE_DPAD_UP -> "up"
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            android.view.KeyEvent.KEYCODE_HOME -> "home"
            android.view.KeyEvent.KEYCODE_MOVE_END -> "end"
            android.view.KeyEvent.KEYCODE_PAGE_UP -> "pgup"
            android.view.KeyEvent.KEYCODE_PAGE_DOWN -> "pgdn"
            android.view.KeyEvent.KEYCODE_INSERT -> "insert"
            android.view.KeyEvent.KEYCODE_CAPS_LOCK -> "caps_lock"
            android.view.KeyEvent.KEYCODE_NUM_LOCK -> "num_lock"
            android.view.KeyEvent.KEYCODE_CTRL_LEFT, android.view.KeyEvent.KEYCODE_CTRL_RIGHT -> "ctrl"
            android.view.KeyEvent.KEYCODE_ALT_LEFT, android.view.KeyEvent.KEYCODE_ALT_RIGHT -> "alt"
            android.view.KeyEvent.KEYCODE_SHIFT_LEFT, android.view.KeyEvent.KEYCODE_SHIFT_RIGHT -> "shift"
            android.view.KeyEvent.KEYCODE_SEMICOLON -> "semicolon"
            android.view.KeyEvent.KEYCODE_APOSTROPHE -> "apostrophe"
            android.view.KeyEvent.KEYCODE_COMMA -> "comma"
            android.view.KeyEvent.KEYCODE_PERIOD -> "period"
            android.view.KeyEvent.KEYCODE_SLASH -> "slash"
            android.view.KeyEvent.KEYCODE_BACKSLASH -> "backslash"
            android.view.KeyEvent.KEYCODE_LEFT_BRACKET -> "bracket_left"
            android.view.KeyEvent.KEYCODE_RIGHT_BRACKET -> "bracket_right"
            android.view.KeyEvent.KEYCODE_MINUS -> "minus"
            android.view.KeyEvent.KEYCODE_EQUALS -> "equal"
            android.view.KeyEvent.KEYCODE_GRAVE -> "grave_accent"
            in android.view.KeyEvent.KEYCODE_F1..android.view.KeyEvent.KEYCODE_F12 ->
                "f${keyCode - android.view.KeyEvent.KEYCODE_F1 + 1}"
            else -> null
        }
    }
}

/** 画面显示模式。 */
enum class DisplayMode(val label: String) {
    FIT("等比"),
    STRETCH("拉伸"),
    ORIGINAL("原始")
}

/**
 * 渲染帧缓冲的自定义 SurfaceView：直接在 Surface 上自绘，
 * 绕过 hwui/RenderThread（模拟器上 postInvalidate 会触发图形栈崩溃）。
 *
 * 画面变换：
 *  - FIT 等比居中（黑边）；STRETCH 拉伸铺满；ORIGINAL 1:1 原始像素
 *  - 双指捏合在任意模式上额外缩放（1-8x），缩放后单指拖动平移画面
 *  - 触摸映射（鼠标）按当前显示变换反算坐标
 *
 * 触摸 = 鼠标：单指左键、双指轻点/长按右键；双指捏合 = 缩放。
 */
private class VncView(context: Context) : SurfaceView(context) {
    /** 鼠标事件回调：move(x, y, buttons)（帧缓冲坐标） */
    var mouseCallback: ((Int, Int, Int) -> Unit)? = null

    /** 滚轮事件回调：notches > 0 向上滚（内容上移），< 0 向下滚 */
    var wheelCallback: ((Int) -> Unit)? = null

    /** 显示模式，切换时复位缩放/平移。 */
    var displayMode: DisplayMode = DisplayMode.FIT
        set(value) {
            field = value
            zoom = 1f
            panX = 0f
            panY = 0f
            requestRender()
        }

    private var fbW = 720
    private var fbH = 400

    /** 双指捏合额外缩放倍率（1-8x） */
    private var zoom = 1f
    /** 画面平移偏移（像素） */
    private var panX = 0f
    private var panY = 0f

    // 触摸状态
    private var buttons = 0
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var longPressRight = false
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressMs = 500L
    private val slopPx = (6 * resources.displayMetrics.density).toInt()
    // 缩放/平移状态
    private var prevX = 0f
    private var prevY = 0f
    private var pinchMode = false
    private var panning = false
    private var twoFingerDown = false
    private var lastPinchDist = 0f
    // 双指垂直滑动（滚轮）累计
    private var twoFingerScrollY = 0f
    private var twoFingerScrollInit = false
    private var scrollFired = false

    private var lastBitmap: Bitmap? = null

    init {
        setZOrderOnTop(false)
        isClickable = true
    }

    /** 是否处于画面放大状态（此时单指拖动 = 平移画面而非移动鼠标）。 */
    private fun zoomed(): Boolean =
        zoom > 1.01f || (displayMode == DisplayMode.ORIGINAL && (fbW > width || fbH > height))

    /** 当前画面在视图中的显示区域（含缩放/平移/拉伸）。 */
    private fun displayRect(): Rect {
        val w = width.toFloat()
        val h = height.toFloat()
        if (fbW <= 0 || fbH <= 0 || w <= 0 || h <= 0) return Rect(0, 0, width, height)
        val baseScale = when (displayMode) {
            DisplayMode.FIT -> minOf(w / fbW, h / fbH)
            DisplayMode.STRETCH -> maxOf(w / fbW, h / fbH)
            DisplayMode.ORIGINAL -> 1f
        }
        val scale = baseScale * zoom
        val dw = fbW * scale
        val dh = fbH * scale
        val dx = (w - dw) / 2f + panX
        val dy = (h - dh) / 2f + panY
        return Rect(
            dx.toInt(), dy.toInt(),
            (dx + dw).toInt(), (dy + dh).toInt()
        )
    }

    /** 视图坐标 -> 帧缓冲坐标（按当前变换反算，越界收敛到边缘）。 */
    private fun toFbX(vx: Float): Int {
        val r = displayRect()
        return ((vx - r.left) * fbW / r.width().toFloat()).toInt().coerceIn(0, fbW - 1)
    }

    private fun toFbY(vy: Float): Int {
        val r = displayRect()
        return ((vy - r.top) * fbH / r.height().toFloat()).toInt().coerceIn(0, fbH - 1)
    }

    /** 由渲染线程调用：把新帧画到 Surface。 */
    @Synchronized
    fun drawFrame(bmp: Bitmap) {
        lastBitmap = bmp
        fbW = bmp.width
        fbH = bmp.height
        render()
    }

    /** 画面变换变化时（缩放/平移/模式）重新绘制上一帧。任意线程可调。 */
    @Synchronized
    fun requestRender() {
        render()
    }

    /** 释放帧缓冲引用并清屏（Activity 销毁时调用，确保 Bitmap/Surface 可被 GC）。 */
    @Synchronized
    fun clearFrame() {
        lastBitmap = null
        runCatching {
            holder.lockCanvas()?.let { canvas ->
                try {
                    canvas.drawColor(android.graphics.Color.BLACK)
                } finally {
                    holder.unlockCanvasAndPost(canvas)
                }
            }
        }
    }

    private fun render() {
        val bmp = lastBitmap ?: return
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawColor(android.graphics.Color.BLACK)
            val r = displayRect()
            canvas.drawBitmap(bmp, null, r, null)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun pinchDistance(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        return hypot(
            e.getX(0) - e.getX(1),
            e.getY(0) - e.getY(1)
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cb = mouseCallback
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                moved = false
                if (zoomed()) {
                    // 放大状态：单指拖动 = 平移画面；轻点 = 左键点击（UP 时处理）
                    prevX = event.x
                    prevY = event.y
                    panning = true
                } else {
                    // 正常状态：单指 = 鼠标左键
                    moved = false
                    downX = event.x
                    downY = event.y
                    longPressRight = false
                    buttons = buttons or 1
                    cb?.invoke(toFbX(event.x), toFbY(event.y), buttons)
                    // 单指长按（500ms 内未移动、未抬起）= 右键
                    longPressHandler.removeCallbacksAndMessages(null)
                    longPressHandler.postDelayed({
                        if (!moved && buttons and 1 != 0) {
                            buttons = buttons or 4
                            longPressRight = true
                            cb?.invoke(toFbX(event.x), toFbY(event.y), buttons)
                        }
                    }, longPressMs)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    longPressHandler.removeCallbacksAndMessages(null)
                    lastPinchDist = pinchDistance(event)
                    pinchMode = false
                    twoFingerDown = true
                    twoFingerScrollInit = false
                    twoFingerScrollY = 0f
                    scrollFired = false
                    panning = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                when {
                    event.pointerCount >= 2 -> {
                        // 双指手势三态：
                        //  1) 两指间距变化大 → 捏合缩放
                        //  2) 两指整体垂直滑动 → 滚轮滚动（双指上下滑浏览网页/列表）
                        //  3) 两者都不明显 → 轻点右键（UP 时处理）
                        val dist = pinchDistance(event)
                        if (!twoFingerScrollInit) {
                            twoFingerScrollInit = true
                            twoFingerScrollY = (event.getY(0) + event.getY(1)) / 2f
                            scrollFired = false
                        }
                        val centerY = (event.getY(0) + event.getY(1)) / 2f
                        val dy = centerY - twoFingerScrollY
                        val distChanged = abs(dist - lastPinchDist) > slopPx
                        val scrolled = abs(dy) > slopPx * 1.5f
                        if (distChanged && !scrollFired) {
                            // 捏合缩放
                            if (lastPinchDist > 20f) {
                                val newZoom = (zoom * dist / lastPinchDist).coerceIn(1f, 8f)
                                if (abs(newZoom - zoom) > 0.01f) {
                                    zoom = newZoom
                                    pinchMode = true
                                }
                            }
                            lastPinchDist = dist
                            requestRender()
                        } else if (scrolled && !pinchMode) {
                            // 垂直滚动 → 滚轮（每 slop 距离 = 1 格，向下滑 = 内容下移 = wheel 负）
                            scrollFired = true
                            val notches = (dy / (slopPx * 1.5f)).toInt()
                            if (notches != 0) {
                                wheelCallback?.invoke(-notches)
                                twoFingerScrollY = centerY
                            }
                        } else {
                            // 无明显手势（轻点双指）
                            lastPinchDist = dist
                        }
                    }
                    pinchMode || panning -> {
                        // 放大后单指平移画面（超过 touch slop 才算拖动）
                        if (!moved) {
                            val dist = hypot(event.x - prevX, event.y - prevY)
                            if (dist > slopPx) moved = true
                        }
                        if (moved) {
                            panX += event.x - prevX
                            panY += event.y - prevY
                            prevX = event.x
                            prevY = event.y
                            requestRender()
                        }
                    }
                    else -> {
                        // 鼠标移动
                        if (!moved) {
                            val dist = hypot(event.x - downX, event.y - downY)
                            if (dist > slopPx) {
                                moved = true
                                longPressHandler.removeCallbacksAndMessages(null)
                            }
                        }
                        cb?.invoke(toFbX(event.x), toFbY(event.y), buttons)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 双指轻点（未缩放）= 右键
                // 双指轻点（未缩放、未滚动）= 右键
                if (twoFingerDown && !pinchMode && !scrollFired && cb != null) {
                    buttons = buttons or 4
                    cb(toFbX(event.x), toFbY(event.y), buttons)
                    buttons = buttons and 4.inv()
                    cb(toFbX(event.x), toFbY(event.y), buttons)
                }
                twoFingerDown = false
                pinchMode = false
                lastPinchDist = 0f
                twoFingerScrollInit = false
                scrollFired = false
                panning = false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacksAndMessages(null)
                // 放大状态下的轻点（未拖动）= 左键点击
                if (panning && !moved) {
                    cb?.invoke(toFbX(event.x), toFbY(event.y), buttons or 1)
                    cb?.invoke(toFbX(event.x), toFbY(event.y), buttons)
                }
                buttons = 0
                cb?.invoke(toFbX(event.x), toFbY(event.y), buttons)
                longPressRight = false
                moved = false
                pinchMode = false
                panning = false
                twoFingerDown = false
                twoFingerScrollInit = false
                scrollFired = false
            }
        }
        return true
    }
}
