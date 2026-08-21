package com.shilizi.virenvironment

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * v86 虚拟机显示界面。通过本地 HTTP 服务器（仅监听 127.0.0.1）提供 v86 运行时，
 * 并把用户选择的磁盘镜像以流 + Range 方式挂载给虚拟机。
 */
class V86Activity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var keyboardButton: TextView
    private lateinit var pauseButton: TextView
    private lateinit var statusText: TextView
    private var server: V86HttpServer? = null
    private var imageUri: Uri? = null
    private var diskPath: String? = null
    private var hdaPath: String? = null
    private var paused = false
    private var keyboardVisible = false
    private var finished = false
    private var stoppedDialogShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        enableImmersiveMode()

        val imageType = intent.getStringExtra(EXTRA_IMAGE_TYPE) ?: "iso"
        // v86 上限 512MB；软盘引导的轻量系统（DOS/FreeDOS）128MB 足够，
        // 避免为小系统分配 512MB WASM 内存拖慢启动
        val memoryMb = when (imageType) {
            "floppy" -> intent.getIntExtra(EXTRA_MEMORY_MB, 256).coerceIn(64, 128)
            else -> intent.getIntExtra(EXTRA_MEMORY_MB, 256).coerceIn(64, 512)
        }
        imageUri = intent.getParcelableExtra(EXTRA_DISK_URI)
        diskPath = intent.getStringExtra(EXTRA_DISK_PATH)
        hdaPath = intent.getStringExtra(EXTRA_HDA_PATH)

        V86Runtime.display = this
        buildUi()
        startWebView(memoryMb, imageType)
    }

    private fun enableImmersiveMode() {
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
        window.decorView.setOnSystemUiVisibilityChangeListener {
            if (it and android.view.View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                window.decorView.postDelayed({ enableImmersiveMode() }, 1000)
            }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101214"))
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.parseColor("#191C1F"))
        }

        pauseButton = toolbarButton("暂停") {
            togglePause()
        }
        keyboardButton = toolbarButton("键盘") {
            setKeyboardVisible(!keyboardVisible)
        }
        val stopButton = toolbarButton("停止") {
            stopEmulator()
        }
        statusText = TextView(this).apply {
            text = "启动中…"
            textSize = 13f
            setTextColor(Color.parseColor("#9AA2A9"))
            setPadding(dp(12), 0, dp(12), 0)
        }
        toolbar.addView(statusText, LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.MATCH_PARENT,
            1f
        ))
        toolbar.addView(pauseButton)
        toolbar.addView(keyboardButton)
        toolbar.addView(stopButton)

        // 特殊键面板：第一行功能键，第二行编辑键与组合键（均横向滚动）
        // 扫描码为 PS/2 Set 1（v86 keyboard_send_scancodes 直接透传），
        // 方向键/Win/PrtSc 等扩展键带 0xE0 前缀，组合键为各键按下码顺序排列
        val fnRow = specialKeyRow(
            "F1" to intArrayOf(0x3B), "F2" to intArrayOf(0x3C), "F3" to intArrayOf(0x3D),
            "F4" to intArrayOf(0x3E), "F5" to intArrayOf(0x3F), "F6" to intArrayOf(0x40),
            "F7" to intArrayOf(0x41), "F8" to intArrayOf(0x42), "F9" to intArrayOf(0x43),
            "F10" to intArrayOf(0x44), "F11" to intArrayOf(0x57), "F12" to intArrayOf(0x58)
        )
        val editRow = specialKeyRow(
            "Esc" to intArrayOf(0x01),
            "Tab" to intArrayOf(0x0F),
            "↵" to intArrayOf(0x1C),
            "空格" to intArrayOf(0x39),
            "←" to intArrayOf(0xE0, 0x4B),
            "↑" to intArrayOf(0xE0, 0x48),
            "→" to intArrayOf(0xE0, 0x4D),
            "↓" to intArrayOf(0xE0, 0x50),
            "Ctrl+Alt+Del" to intArrayOf(0x1D, 0x38, 0xE0, 0x53),
            "Alt+Tab" to intArrayOf(0x38, 0x0F),
            "Win" to intArrayOf(0xE0, 0x5B),
            "Win+E" to intArrayOf(0xE0, 0x5B, 0x12),
            "Win+R" to intArrayOf(0xE0, 0x5B, 0x13),
            "Win+L" to intArrayOf(0xE0, 0x5B, 0x26),
            "PrtSc" to intArrayOf(0xE0, 0x2A, 0xE0, 0x37)
        )

        webView = WebView(this)
        val container = FrameLayout(this)
        container.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        root.addView(toolbar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(fnRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(editRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(container, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        setContentView(root)
    }

    /** 横向滚动的特殊键行。 */
    private fun specialKeyRow(vararg keys: Pair<String, IntArray>): HorizontalScrollView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(3), dp(8), dp(3))
        }
        keys.forEach { (label, codes) ->
            row.addView(keyChip(label) { sendKey(*codes) })
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun keyChip(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(Color.parseColor("#D8DEE4"))
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(Color.parseColor("#2A2E33"))
            }
            setOnClickListener { onClick() }
        }

    /** 向 v86 发送按键：单键或组合键按下+释放（laboxKey 在页面内处理释放序列）。 */
    private fun sendKey(vararg codes: Int) {
        val hex = codes.joinToString(",") { "0x%02X".format(it) }
        Log.i(TAG, "sendKey: [$hex]")
        evaluate("window.laboxKey && laboxKey([$hex]);")
    }

    private fun toolbarButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.parseColor("#36C98F"))
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { onClick() }
        }

    private fun startWebView(memoryMb: Int, imageType: String) {
        val server = V86HttpServer(this, imageUri, diskPath, hdaPath)
        this.server = server
        server.start()
        val baseUrl = "http://127.0.0.1:${server.port}"

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            // 允许 HTTP 缓存：v86.wasm 响应带 immutable 缓存头，Chromium 的 WASM
            // 磁盘编译缓存可跨启动命中，避免每次启动重新编译（这是 v86 慢的主因）
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = null

            override fun onPageFinished(view: WebView, url: String?) {
                Log.i(TAG, "page finished: $url")
                // 立即探针：v86 是否已创建、wasm 是否加载成功（title 反映初始化阶段）
                webView.evaluateJavascript(
                    "JSON.stringify({title: document.title, v86: typeof V86Starter, emu: typeof window.laboxEmulator, err: document.getElementById('err').style.display})",
                    { v -> Log.i(TAG, "probe: $v") }
                )
                pollStatus()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: String, lineNumber: Int, sourceId: String) {
                Log.d(TAG, "v86 console: $message ($sourceId:$lineNumber)")
            }
        }

        val autostopMs = intent.getIntExtra(EXTRA_AUTOSTOP_MS, 0)
        val hdaParam = if (!hdaPath.isNullOrBlank()) "&hda=$baseUrl/hda" else ""
        val url = "$baseUrl/assets/v86/index.html?memory=$memoryMb&image=$baseUrl/disk&type=$imageType$hdaParam" +
            (if (autostopMs > 0) "&autostop_ms=$autostopMs" else "")
        webView.loadUrl(url)
    }

    /** 轮询页面读取 v86 完成状态（emulator-stopped / running / loading），更新工具栏显示。 */
    private fun pollStatus() {
        webView.postDelayed({
            if (finished) return@postDelayed
            webView.evaluateJavascript(
                "JSON.stringify({status: window.laboxVmStatus || 'n/a', title: document.title, v86: typeof V86Starter, emu: typeof window.laboxEmulator, canvas: (window.laboxCanvasInfo ? laboxCanvasInfo() : 'na')})",
                { value ->
                    val status = parseStatus(value)
                    val title = Regex("\"title\":\"([^\"]*)\"").find(value ?: "")?.groupValues?.get(1)
                    if (title != null && !title.contains("labox-v86-loading")) {
                        Log.i(TAG, "page title: $title")
                    }
                    val canvasInfo = Regex("\"canvas\":\"([^\"]*)\"").find(value ?: "")?.groupValues?.get(1)
                    if (canvasInfo != null && canvasInfo != "none" && canvasInfo != "na") {
                        Log.i(TAG, "canvas: $canvasInfo")
                    }
                    if (status != null) {
                        Log.i(TAG, "v86 status: $status")
                        when (status) {
                            "stopped" -> {
                                runOnUiThread {
                                    statusText.text = "已停止"
                                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                    showVmStoppedDialog("虚拟机已停止", "v86 模拟器已停止运行。")
                                }
                                return@evaluateJavascript
                            }
                            "error" -> {
                                runOnUiThread {
                                    statusText.text = "启动失败"
                                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                    showVmStoppedDialog("虚拟机启动失败", "v86 加载磁盘镜像失败或运行出错。")
                                }
                                return@evaluateJavascript
                            }
                            "running" -> {
                                if (statusText.text == "启动中…") {
                                    runOnUiThread { statusText.text = "运行中" }
                                }
                            }
                            else -> {
                                if (statusText.text == "启动中…") {
                                    runOnUiThread { statusText.text = "加载中…" }
                                }
                            }
                        }
                    }
                    // 未停止则继续轮询
                    if (!finished) pollStatus()
                }
            )
        }, 3000)
    }

    private fun parseStatus(value: String?): String? {
        if (value.isNullOrBlank() || value == "null") return null
        // evaluateJavascript 返回 JSON 字符串（可能带引号包裹）
        val json = value.trim().removePrefix("\"").removeSuffix("\"").replace("\\\"", "\"")
        return Regex("\"status\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
    }

    /** 虚拟机自行停止/启动失败时弹出提示，避免画面冻结但用户不知道如何退出。 */
    private fun showVmStoppedDialog(title: String, message: String) {
        if (finished || isFinishing || stoppedDialogShown) return
        stoppedDialogShown = true
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("退出") { _, _ -> finish() }
            .show()
    }

    private fun evaluate(script: String) {
        if (!::webView.isInitialized) return
        webView.post {
            if (!finished) {
                runCatching { webView.evaluateJavascript(script, null) }
            }
        }
    }

    internal fun togglePause() {
        if (paused) {
            evaluate("window.laboxResume && laboxResume();")
            pauseButton.text = "暂停"
            paused = false
        } else {
            evaluate("window.laboxPause && laboxPause();")
            pauseButton.text = "继续"
            paused = true
        }
    }

    private fun setKeyboardVisible(visible: Boolean) {
        keyboardVisible = visible
        evaluate("window.laboxKeyboard && laboxKeyboard($visible);")
        keyboardButton.text = if (visible) "键盘开" else "键盘"
    }

    internal fun stopEmulator() {
        evaluate("window.laboxStop && laboxStop();")
        finish()
    }

    override fun onBackPressed() {
        stopEmulator()
    }

    override fun onDestroy() {
        finished = true
        if (V86Runtime.display === this) V86Runtime.display = null
        server?.stop()
        server = null
        // WebView 释放：先从父视图移除再 destroy，避免 WebView 持有 Activity/渲染线程泄漏
        runCatching { (webView.parent as? android.view.ViewGroup)?.removeView(webView) }
        runCatching { webView.stopLoading() }
        runCatching { webView.loadUrl("about:blank") }
        runCatching { webView.removeAllViews() }
        runCatching { webView.destroy() }
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "LaboxV86"
        const val EXTRA_DISK_URI = "disk_uri"
        const val EXTRA_DISK_PATH = "disk_path"
        const val EXTRA_HDA_PATH = "hda_path"
        const val EXTRA_MEMORY_MB = "memory_mb"
        const val EXTRA_IMAGE_TYPE = "image_type"
        const val EXTRA_AUTOSTOP_MS = "autostop_ms"
    }
}
