package com.shilizi.virenvironment

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 最近打开的磁盘镜像记录（SharedPreferences 持久化）。 */
data class RecentImage(val uri: String, val name: String)

enum class VmEngine(val label: String) {
    V86("v86"),
    QEMU("QEMU")
}

enum class WindowsProfile(
    val recommendedEngine: VmEngine,
    val defaultMemoryMb: Int,
    val defaultCpuCores: Int
) {
    WINDOWS_98(VmEngine.V86, 512, 1),
    WINDOWS_XP(VmEngine.V86, 1024, 1),
    WINDOWS_7(VmEngine.QEMU, 2048, 2),
    WINDOWS_10(VmEngine.QEMU, 4096, 4),
    WINDOWS_11(VmEngine.QEMU, 8192, 4)
}

enum class VmStatus {
    STOPPED,
    RUNNING,
    PAUSED
}

enum class VmMessage {
    SELECT_DISK,
    ENGINE_SELECTED,
    DISK_READY,
    CHOOSE_DISK_FIRST,
    RESUMED,
    STARTING,
    PAUSED,
    STOPPED,
    PROFILE_APPLIED,
    INVALID_CONFIGURATION,
    ENGINE_UNAVAILABLE,
    START_FAILED
}

data class VmUiState(
    val engine: VmEngine = VmEngine.V86,
    val windowsProfile: WindowsProfile = WindowsProfile.WINDOWS_XP,
    val qemuHardware: QemuHardwareConfig = recommendedQemuHardware(WindowsProfile.WINDOWS_XP),
    val status: VmStatus = VmStatus.STOPPED,
    val diskUri: Uri? = null,
    val diskName: String? = null,
    val memoryMb: Int = 1024,
    val cpuCores: Int = 1,
    val networkEnabled: Boolean = true,
    val audioEnabled: Boolean = true,
    val message: VmMessage = VmMessage.SELECT_DISK
)

class VmViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(VmUiState())
    val uiState: StateFlow<VmUiState> = _uiState.asStateFlow()
    private val runtimes: Map<VmEngine, VirtualMachineRuntime> = listOf(
        V86Runtime(),
        QemuRuntime()
    ).associateBy { it.engine }

    /** 最近镜像列表（SharedPreferences 持久化，最多 5 条，最近在前）。 */
    private val prefs = application.getSharedPreferences("labox_recent", Context.MODE_PRIVATE)
    private val _recentImages = MutableStateFlow<List<RecentImage>>(loadRecentImages())
    val recentImages: StateFlow<List<RecentImage>> = _recentImages.asStateFlow()

    init {
        // 重启后自动恢复最近选中的磁盘：上次选过的镜像重新设为当前磁盘，
        // 避免「列表有但没选中、启动按钮灰掉」的困惑体验。
        val last = prefs.getString("recent_last_uri", null)
        val lastName = prefs.getString("recent_last_name", null)
        if (last != null && lastName != null) {
            val uri = runCatching { Uri.parse(last) }.getOrNull()
            if (uri != null) {
                _uiState.update { it.copy(diskUri = uri, diskName = lastName) }
            }
        }
    }

    private fun loadRecentImages(): List<RecentImage> {
        val count = prefs.getInt("recent_count", 0)
        return (0 until count).mapNotNull { i ->
            val uri = prefs.getString("recent_${i}_uri", null) ?: return@mapNotNull null
            val name = prefs.getString("recent_${i}_name", uri.substringAfterLast('/').substringAfterLast(':')) ?: uri
            RecentImage(uri, name)
        }
    }

    private fun saveRecentImages() {
        prefs.edit().apply {
            clear()
            putInt("recent_count", _recentImages.value.size)
            _recentImages.value.forEachIndexed { i, r ->
                putString("recent_${i}_uri", r.uri)
                putString("recent_${i}_name", r.name)
            }
            apply()
        }
    }

    /** 记录最近镜像（选中或从最近列表点击时调用）。 */
    fun rememberRecentImage(uri: Uri, name: String) {
        val uriStr = uri.toString()
        val updated = (listOf(RecentImage(uriStr, name)) + _recentImages.value.filter { it.uri != uriStr })
            .take(5)
        _recentImages.value = updated
        saveRecentImages()
    }

    /** 从最近镜像 URI 恢复磁盘（同名源，VM 停止时允许）。 */
    fun selectRecentImage(uri: Uri, name: String) {
        rememberRecentImage(uri, name)
        selectDisk(uri, name)
    }

    fun selectEngine(engine: VmEngine) {
        if (_uiState.value.status == VmStatus.STOPPED) {
            _uiState.update { it.copy(engine = engine, message = VmMessage.ENGINE_SELECTED) }
        }
    }

    fun selectWindowsProfile(profile: WindowsProfile) {
        if (_uiState.value.status == VmStatus.STOPPED) {
            _uiState.update {
                it.copy(
                    windowsProfile = profile,
                    engine = profile.recommendedEngine,
                    qemuHardware = recommendedQemuHardware(profile),
                    memoryMb = profile.defaultMemoryMb,
                    cpuCores = profile.defaultCpuCores,
                    message = VmMessage.PROFILE_APPLIED
                )
            }
        }
    }

    fun selectDisk(uri: Uri, name: String) {
        rememberRecentImage(uri, name)
        prefs.edit().putString("recent_last_uri", uri.toString()).putString("recent_last_name", name).apply()
        _uiState.update { it.copy(diskUri = uri, diskName = name, message = VmMessage.DISK_READY) }
    }

    fun setMemory(value: Int) {
        _uiState.update { it.copy(memoryMb = value) }
    }

    fun setCpuCores(value: Int) {
        _uiState.update { it.copy(cpuCores = value) }
    }

    fun setNetworkEnabled(enabled: Boolean) {
        if (_uiState.value.status == VmStatus.STOPPED) {
            _uiState.update { it.copy(networkEnabled = enabled) }
        }
    }

    fun setAudioEnabled(enabled: Boolean) {
        if (_uiState.value.status == VmStatus.STOPPED) {
            _uiState.update { it.copy(audioEnabled = enabled) }
        }
    }

    fun setQemuHardware(config: QemuHardwareConfig) {
        if (_uiState.value.status == VmStatus.STOPPED) {
            _uiState.update { it.copy(qemuHardware = config) }
        }
    }

    fun startOrResume() {
        _uiState.update { state ->
            val invalidQemuConfig = state.engine == VmEngine.QEMU &&
                state.qemuHardware.validate(state.windowsProfile).any { it.blocksLaunch }
            if (invalidQemuConfig) {
                state.copy(message = VmMessage.INVALID_CONFIGURATION)
            } else if (state.diskUri == null) {
                state.copy(message = VmMessage.CHOOSE_DISK_FIRST)
            } else {
                val runtime = requireNotNull(runtimes[state.engine])
                if (!runtime.isAvailable(getApplication())) {
                    state.copy(message = VmMessage.ENGINE_UNAVAILABLE)
                } else {
                    runCatching {
                        if (state.status == VmStatus.PAUSED) {
                            runtime.resume()
                        } else {
                            runtime.start(
                                getApplication(),
                                VmLaunchConfig(
                                    diskUri = state.diskUri,
                                    memoryMb = state.memoryMb,
                                    cpuCores = state.cpuCores,
                                    qemuHardware = state.qemuHardware,
                                    networkEnabled = state.networkEnabled,
                                    audioEnabled = state.audioEnabled
                                )
                            )
                        }
                    }.fold(
                        onSuccess = {
                            state.copy(
                                status = VmStatus.RUNNING,
                                message = if (state.status == VmStatus.PAUSED) VmMessage.RESUMED else VmMessage.STARTING
                            )
                        },
                        onFailure = { state.copy(message = VmMessage.START_FAILED) }
                    )
                }
            }
        }
    }

    fun pause() {
        _uiState.update { state ->
            if (state.status == VmStatus.RUNNING) {
                runCatching { runtimes[state.engine]?.pause() }.fold(
                    onSuccess = { state.copy(status = VmStatus.PAUSED, message = VmMessage.PAUSED) },
                    onFailure = { state.copy(message = VmMessage.START_FAILED) }
                )
            } else state
        }
    }

    fun stop() {
        _uiState.update { state ->
            runCatching { runtimes[state.engine]?.stop() }
            state.copy(status = VmStatus.STOPPED, message = VmMessage.STOPPED)
        }
    }

    /**
     * 回到主界面时同步 VM 显示状态：虚拟机的显示 Activity（QemuDisplayActivity /
     * V86Activity）在 onDestroy 里清空各自 runtime 的 display 引用。若回到本页时
     * display 已清空（用户在虚拟机界面停止/退出），但本页状态仍是运行/暂停，
     * 说明状态未同步，重置为停止，避免主界面误显示"运行中"且启动按钮不可用。
     * @return true 表示状态已是停止或本次已同步完成；false 表示仍显示运行中且
     * 显示页引用还在（调用方可稍后重试，因 onDestroy 可能在 onResume 之后才执行）。
     */
    fun syncDisplayState(): Boolean {
        val s = _uiState.value
        if (s.status == VmStatus.STOPPED) return true
        val runtime = runtimes[s.engine] ?: return true
        val displayClosed = when (s.engine) {
            VmEngine.QEMU -> QemuRuntime.display == null
            VmEngine.V86 -> V86Runtime.display == null
        }
        android.util.Log.i("LaboxQemu", "syncDisplayState: engine=${s.engine} status=${s.status} displayClosed=$displayClosed")
        if (displayClosed) {
            // 界面已关 = VM 已停止/退出；引擎若还在跑（异常路径）兜底停掉
            runCatching { runtime.stop() }
            _uiState.update { it.copy(status = VmStatus.STOPPED, message = VmMessage.STOPPED) }
            return true
        }
        return false
    }

    override fun onCleared() {
        runtimes.values.forEach { runCatching { it.stop() } }
        super.onCleared()
    }
}
