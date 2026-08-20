package com.shilizi.virenvironment

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlin.math.roundToInt

private val Background = Color(0xFF101214)
private val Panel = Color(0xFF191C1F)
private val Border = Color(0xFF34393E)
private val Accent = Color(0xFF36C98F)
private val Warning = Color(0xFFFFB86B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 诊断：QEMU 引擎可用性（assets 是否打包了 QEMU 11 二进制）
        android.util.Log.i("LaboxQemu", "qemu11 available=${QemuRuntime().isAvailable(this)}")
        setContent {
            LaboxTheme {
                val vmViewModel: VmViewModel = viewModel()
                val state by vmViewModel.uiState.collectAsStateWithLifecycle()
                val imagePicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    uri?.let {
                        runCatching {
                            contentResolver.takePersistableUriPermission(
                                it,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            )
                        }
                        val name = contentResolver.query(
                            it,
                            arrayOf(OpenableColumns.DISPLAY_NAME),
                            null,
                            null,
                            null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        } ?: getString(R.string.default_disk_name)
                        vmViewModel.addMedia(it, name)
                    }
                }

                LaboxScreen(
                    state = state,
                    recentImages = vmViewModel.recentImages.collectAsStateWithLifecycle().value,
                    onSelectProfile = vmViewModel::selectWindowsProfile,
                    onSelectEngine = vmViewModel::selectEngine,
                    onQemuHardwareChange = vmViewModel::setQemuHardware,
                    onSelectDisk = { imagePicker.launch(arrayOf("application/octet-stream", "application/x-iso9660-image", "*/*")) },
                    onRemoveMedia = vmViewModel::removeMedia,
                    onCreateVirtualDisk = { name, sizeMb -> vmViewModel.createVirtualDisk(name, sizeMb) },
                    onDeleteVirtualDisk = vmViewModel::deleteVirtualDisk,
                    onSelectRecentImage = { recent ->
                        val uri = Uri.parse(recent.uri)
                        runCatching {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            )
                        }
                        vmViewModel.selectRecentImage(uri, recent.name)
                    },
                    onMemoryChange = vmViewModel::setMemory,
                    onCpuChange = vmViewModel::setCpuCores,
                    onNetworkChange = vmViewModel::setNetworkEnabled,
                    onAudioChange = vmViewModel::setAudioEnabled,
                    onStart = vmViewModel::startOrResume,
                    onPause = vmViewModel::pause,
                    onStop = vmViewModel::stop
                )
                // 从虚拟机显示页（QemuDisplayActivity / V86Activity）返回时同步状态：
                // 旧 Activity 的 onDestroy（清空 display 引用）在本页 onResume 之后才执行，
                // 因此轮询几次，直到 display 清空或超时（最多 ~2.4s）。
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            var attempts = 0
                            val check = object : Runnable {
                                override fun run() {
                                    if (++attempts > 8) return
                                    if (!vmViewModel.syncDisplayState()) {
                                        window.decorView.postDelayed(this, 300)
                                    }
                                }
                            }
                            window.decorView.postDelayed(check, 200)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LaboxScreen(
    state: VmUiState,
    recentImages: List<RecentImage>,
    onSelectProfile: (WindowsProfile) -> Unit,
    onSelectEngine: (VmEngine) -> Unit,
    onQemuHardwareChange: (QemuHardwareConfig) -> Unit,
    onSelectDisk: () -> Unit,
    onRemoveMedia: (Uri) -> Unit,
    onCreateVirtualDisk: (String, Int) -> Unit,
    onDeleteVirtualDisk: (String) -> Unit,
    onSelectRecentImage: (RecentImage) -> Unit,
    onMemoryChange: (Int) -> Unit,
    onCpuChange: (Int) -> Unit,
    onNetworkChange: (Boolean) -> Unit,
    onAudioChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit
) {
    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("LABOX", fontWeight = FontWeight.Black, letterSpacing = 0.sp)
                        Text(stringResource(R.string.windows_virtual_machine), style = MaterialTheme.typography.labelSmall, color = Color(0xFF9AA2A9))
                    }
                },
                actions = {
                    StatusPill(state.status)
                    Spacer(Modifier.width(12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Panel)
            )
        }
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            if (maxWidth >= 760.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ConfigurationPanel(
                        state,
                        recentImages,
                        onSelectProfile,
                        onSelectEngine,
                        onQemuHardwareChange,
                        onSelectDisk,
                        onRemoveMedia,
                        onCreateVirtualDisk,
                        onDeleteVirtualDisk,
                        onSelectRecentImage,
                        onMemoryChange,
                        onCpuChange,
                        onNetworkChange,
                        onAudioChange,
                        Modifier.width(320.dp).fillMaxHeight()
                    )
                    ConsolePanel(state, onStart, onPause, onStop, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ConsolePanel(state, onStart, onPause, onStop, Modifier.fillMaxWidth().weight(0.8f))
                    ConfigurationPanel(
                        state,
                        recentImages,
                        onSelectProfile,
                        onSelectEngine,
                        onQemuHardwareChange,
                        onSelectDisk,
                        onRemoveMedia,
                        onCreateVirtualDisk,
                        onDeleteVirtualDisk,
                        onSelectRecentImage,
                        onMemoryChange,
                        onCpuChange,
                        onNetworkChange,
                        onAudioChange,
                        Modifier.fillMaxWidth().weight(1.2f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfigurationPanel(
    state: VmUiState,
    recentImages: List<RecentImage>,
    onSelectProfile: (WindowsProfile) -> Unit,
    onSelectEngine: (VmEngine) -> Unit,
    onQemuHardwareChange: (QemuHardwareConfig) -> Unit,
    onSelectDisk: () -> Unit,
    onRemoveMedia: (Uri) -> Unit,
    onCreateVirtualDisk: (String, Int) -> Unit,
    onDeleteVirtualDisk: (String) -> Unit,
    onSelectRecentImage: (RecentImage) -> Unit,
    onMemoryChange: (Int) -> Unit,
    onCpuChange: (Int) -> Unit,
    onNetworkChange: (Boolean) -> Unit,
    onAudioChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier, color = Panel, shape = RoundedCornerShape(8.dp), tonalElevation = 0.dp) {
        Column(
            Modifier.padding(18.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(stringResource(R.string.machine), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            WindowsProfileSelector(
                selected = state.windowsProfile,
                enabled = state.status == VmStatus.STOPPED,
                onSelected = onSelectProfile
            )
            Text(stringResource(R.string.engine), style = MaterialTheme.typography.labelSmall, color = Color(0xFF8B949C))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VmEngine.entries.forEach { engine ->
                    FilterChip(
                        selected = state.engine == engine,
                        enabled = state.status == VmStatus.STOPPED,
                        onClick = { onSelectEngine(engine) },
                        label = { Text(engine.label) }
                    )
                }
            }
            EngineCompatibilityHint(state)

            if (state.engine == VmEngine.QEMU) {
                QemuHardwarePanel(
                    profile = state.windowsProfile,
                    hardware = state.qemuHardware,
                    enabled = state.status == VmStatus.STOPPED,
                    onHardwareChange = onQemuHardwareChange
                )
            }

            Text(stringResource(R.string.boot_image), style = MaterialTheme.typography.labelSmall, color = Color(0xFF8B949C))
            // 已挂载介质列表（ISO/硬盘镜像/软盘，可多个）
            state.mediaList.forEach { media ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = mediaTypeLabel(media.type),
                        color = Accent,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text(
                        text = media.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFC8D0D6),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (state.status == VmStatus.STOPPED) {
                        IconButton(onClick = { onRemoveMedia(media.uri) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.remove_media), tint = Color(0xFF8B949C), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            Button(
                onClick = onSelectDisk,
                enabled = state.status == VmStatus.STOPPED && state.mediaList.size < 4,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3035))
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.add_media), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            // 虚拟硬盘（VMware 风格：可创建/删除，QEMU 挂为硬盘）
            Text(stringResource(R.string.virtual_disks), style = MaterialTheme.typography.labelSmall, color = Color(0xFF8B949C))
            state.virtualDisks.forEach { vd ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = vd.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFC8D0D6),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = stringResource(R.string.disk_size_value, vd.sizeMb),
                        color = Accent,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    if (state.status == VmStatus.STOPPED) {
                        IconButton(onClick = { onDeleteVirtualDisk(vd.id) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete_virtual_disk), tint = Color(0xFF8B949C), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            VirtualDiskCreator(
                enabled = state.status == VmStatus.STOPPED,
                onCreate = onCreateVirtualDisk
            )

            // 最近镜像快捷列表（SharedPreferences 记忆，点击直接选中）
            if (recentImages.isNotEmpty()) {
                Text(stringResource(R.string.recent_images), style = MaterialTheme.typography.labelSmall, color = Color(0xFF8B949C))
                recentImages.forEach { recent ->
                    val selected = state.mediaList.any { it.uri.toString() == recent.uri }
                    TextButton(
                        onClick = { onSelectRecentImage(recent) },
                        enabled = state.status == VmStatus.STOPPED,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = recent.name,
                            color = if (selected) Accent else Color(0xFF9AA2A9),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (selected) {
                            Text("✓", color = Accent, fontSize = 13.sp)
                        }
                    }
                }
            }

            SettingSlider(
                title = stringResource(R.string.memory),
                valueLabel = stringResource(R.string.memory_value, state.memoryMb),
                value = state.memoryMb.toFloat(),
                range = 512f..8192f,
                steps = 14,
                enabled = state.status == VmStatus.STOPPED,
                onValueChange = { onMemoryChange((it / 512).roundToInt() * 512) }
            )
            SettingSlider(
                title = stringResource(R.string.cpu_cores),
                valueLabel = stringResource(R.string.cpu_value, state.cpuCores),
                value = state.cpuCores.toFloat(),
                range = 1f..8f,
                steps = 6,
                enabled = state.status == VmStatus.STOPPED,
                onValueChange = { onCpuChange(it.roundToInt()) }
            )

            Text(stringResource(R.string.devices), style = MaterialTheme.typography.labelSmall, color = Color(0xFF8B949C))
            ToggleSetting(
                title = stringResource(R.string.network),
                description = stringResource(R.string.network_description),
                checked = state.networkEnabled,
                enabled = state.status == VmStatus.STOPPED,
                onCheckedChange = onNetworkChange
            )
            ToggleSetting(
                title = stringResource(R.string.audio),
                description = stringResource(R.string.audio_description),
                checked = state.audioEnabled,
                enabled = state.status == VmStatus.STOPPED,
                onCheckedChange = onAudioChange
            )

            Text(
                text = stringResource(if (state.engine == VmEngine.V86) R.string.v86_architecture else R.string.qemu_architecture),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8B949C)
            )
        }
    }
}

@Composable
private fun ConsolePanel(
    state: VmUiState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 未选磁盘或 QEMU 配置有阻断性错误时禁止启动（避免无效操作，错误由配置面板的红字提示）
    val invalidQemuConfig = state.engine == VmEngine.QEMU &&
        state.qemuHardware.validate(state.windowsProfile).any { it.blocksLaunch }
    val hasMedia = state.mediaList.isNotEmpty() || state.virtualDisks.isNotEmpty()
    val canStart = state.status == VmStatus.STOPPED && hasMedia && !invalidQemuConfig
    Surface(modifier = modifier, color = Panel, shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.display), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (state.status != VmStatus.RUNNING) {
                    IconButton(onClick = onStart, enabled = canStart) {
                        Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.start), tint = if (canStart) Accent else Color(0xFF4A5055))
                    }
                }
                if (state.status == VmStatus.RUNNING) {
                    IconButton(onClick = onPause) {
                        Icon(Icons.Default.Pause, contentDescription = stringResource(R.string.pause))
                    }
                }
                IconButton(onClick = onStop, enabled = state.status != VmStatus.STOPPED && hasMedia) {
                    Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.stop))
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                    .background(Color(0xFF050607), RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center
            ) {
                ConsoleContent(state)
            }
            Row(
                Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(7.dp).background(statusColor(state.status), RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(8.dp))
                Text(statusMessage(state), style = MaterialTheme.typography.bodySmall, color = Color(0xFFADB5BD), maxLines = 1)
            }
        }
    }
}

@Composable
private fun ConsoleContent(state: VmUiState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = when (state.status) {
                VmStatus.STOPPED -> stringResource(R.string.no_signal)
                VmStatus.RUNNING -> stringResource(R.string.engine_display, state.engine.label.uppercase())
                VmStatus.PAUSED -> stringResource(R.string.paused)
            },
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (state.status == VmStatus.STOPPED) Color(0xFF626A70) else Accent
        )
        if (state.status != VmStatus.STOPPED) {
            Text(
                "${state.cpuCores} CPU  |  ${state.memoryMb} MB",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF798188)
            )
        }
    }
}

@Composable
private fun mediaTypeLabel(type: MediaType): String = when (type) {
    MediaType.ISO -> "ISO"
    MediaType.DISK -> "HDD"
    MediaType.FLOPPY -> "FDD"
}

/** 虚拟硬盘创建器：输入名称和大小，VMware 风格创建空 raw 磁盘。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VirtualDiskCreator(
    enabled: Boolean,
    onCreate: (String, Int) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var sizeMb by remember { mutableStateOf(4096) }
    Button(
        onClick = { showDialog = true },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3035))
    ) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.create_virtual_disk))
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.create_virtual_disk)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.disk_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Column {
                        Row(Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.disk_size))
                            Spacer(Modifier.weight(1f))
                            Text(stringResource(R.string.disk_size_value, sizeMb), color = Accent)
                        }
                        Slider(
                            value = sizeMb.toFloat(),
                            onValueChange = { sizeMb = (it / 512).roundToInt() * 512 },
                            valueRange = 512f..65536f,
                            steps = 126
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val finalName = name.ifBlank { "Virtual Disk" }
                        onCreate(finalName, sizeMb)
                        showDialog = false
                        name = ""
                        sizeMb = 4096
                    }
                ) { Text(stringResource(R.string.create)) }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun SettingSlider(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = Accent)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range, steps = steps, enabled = enabled)
    }
}

@Composable
private fun QemuHardwarePanel(
    profile: WindowsProfile,
    hardware: QemuHardwareConfig,
    enabled: Boolean,
    onHardwareChange: (QemuHardwareConfig) -> Unit
) {
    var showArguments by remember { mutableStateOf(false) }
    val issues = hardware.validate(profile)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.qemu_hardware), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.qemu_hardware_summary), style = MaterialTheme.typography.bodySmall, color = Color(0xFF9AA2A9))
        QemuSelectRow(stringResource(R.string.qemu_cpu), hardware.cpu, QemuCpu.entries, enabled) {
            onHardwareChange(hardware.copy(cpu = it as QemuCpu))
        }
        QemuSelectRow(stringResource(R.string.qemu_chipset), hardware.chipset, QemuChipset.entries, enabled) {
            onHardwareChange(hardware.copy(chipset = it as QemuChipset))
        }
        QemuSelectRow(stringResource(R.string.qemu_disk), hardware.disk, QemuDisk.entries, enabled) {
            onHardwareChange(hardware.copy(disk = it as QemuDisk))
        }
        QemuSelectRow(stringResource(R.string.qemu_video), hardware.video, QemuVideo.entries, enabled) {
            onHardwareChange(hardware.copy(video = it as QemuVideo))
        }
        QemuSelectRow(stringResource(R.string.qemu_audio), hardware.audio, QemuAudio.entries, enabled) {
            onHardwareChange(hardware.copy(audio = it as QemuAudio))
        }
        QemuSelectRow(stringResource(R.string.qemu_network), hardware.network, QemuNetwork.entries, enabled) {
            onHardwareChange(hardware.copy(network = it as QemuNetwork))
        }
        QemuSelectRow(stringResource(R.string.qemu_firmware), hardware.firmware, QemuFirmware.entries, enabled) {
            onHardwareChange(hardware.copy(firmware = it as QemuFirmware))
        }
        QemuSelectRow(stringResource(R.string.qemu_usb), hardware.usb, QemuUsb.entries, enabled) {
            onHardwareChange(hardware.copy(usb = it as QemuUsb))
        }
        QemuSelectRow(stringResource(R.string.qemu_pointer), hardware.pointer, QemuPointer.entries, enabled) {
            onHardwareChange(hardware.copy(pointer = it as QemuPointer))
        }
        QemuSelectRow(stringResource(R.string.qemu_boot_order), hardware.bootOrder, QemuBootOrder.entries, enabled) {
            onHardwareChange(hardware.copy(bootOrder = it as QemuBootOrder))
        }
        ToggleSetting(
            title = stringResource(R.string.qemu_rtc_local),
            description = stringResource(R.string.qemu_rtc_description),
            checked = hardware.rtcLocalTime,
            enabled = enabled,
            onCheckedChange = { onHardwareChange(hardware.copy(rtcLocalTime = it)) }
        )
        ToggleSetting(
            title = stringResource(R.string.qemu_tpm2),
            description = stringResource(R.string.qemu_tpm2_description),
            checked = hardware.tpm2Enabled,
            enabled = enabled,
            onCheckedChange = { onHardwareChange(hardware.copy(tpm2Enabled = it)) }
        )
        if (issues.isNotEmpty()) {
            Text(stringResource(R.string.qemu_configuration_errors), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            issues.forEach { issue ->
                Text(
                    text = "• ${qemuIssueText(issue)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (issue.blocksLaunch) MaterialTheme.colorScheme.error else Warning
                )
            }
            if (issues.any { it.blocksLaunch }) {
                Text(stringResource(R.string.qemu_error_blocks_launch), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        TextButton(onClick = { showArguments = !showArguments }) {
            Text(stringResource(if (showArguments) R.string.qemu_hide_arguments else R.string.qemu_show_arguments))
        }
        if (showArguments) {
            val plan = hardware.toQemuLaunchPlan(networkEnabled = true, audioEnabled = true)
            Text(stringResource(R.string.qemu_arguments), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            SelectionContainer {
                Text(
                    plan.arguments.joinToString(" ") { argument -> if (argument.contains(' ')) "\"$argument\"" else argument },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFB8C1C8)
                )
            }
            if (plan.requiredFiles.isNotEmpty()) {
                Text(stringResource(R.string.qemu_placeholder_note), style = MaterialTheme.typography.bodySmall, color = Color(0xFF8B949C))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QemuSelectRow(
    title: String,
    selected: QemuOption,
    options: List<QemuOption>,
    enabled: Boolean,
    onSelected: (QemuOption) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = !expanded }) {
        OutlinedTextField(
            value = stringResource(selected.labelRes),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(title) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes)) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    }
                )
            }
        }
    }
}

@Composable
private fun qemuIssueText(issue: QemuValidationIssue): String = stringResource(
    when (issue) {
        QemuValidationIssue.SATA_REQUIRES_Q35 -> R.string.qemu_issue_sata_q35
        QemuValidationIssue.USB_TABLET_REQUIRES_USB -> R.string.qemu_issue_usb_tablet
        QemuValidationIssue.TPM_REQUIRES_Q35_UEFI -> R.string.qemu_issue_tpm
        QemuValidationIssue.WINDOWS_11_REQUIRES_64_BIT_CPU -> R.string.qemu_issue_windows11_cpu
        QemuValidationIssue.LEGACY_WINDOWS_WITH_UEFI -> R.string.qemu_issue_legacy_uefi
        QemuValidationIssue.LEGACY_WINDOWS_WITH_MODERN_DEVICES -> R.string.qemu_issue_legacy_devices
        QemuValidationIssue.VIRTIO_DRIVER_REQUIRED -> R.string.qemu_issue_driver
    }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WindowsProfileSelector(
    selected: WindowsProfile,
    enabled: Boolean,
    onSelected: (WindowsProfile) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.system_version), style = MaterialTheme.typography.labelSmall, color = Color(0xFF8B949C))
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = !expanded }) {
            OutlinedTextField(
                value = windowsProfileLabel(selected),
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                WindowsProfile.entries.forEach { profile ->
                    DropdownMenuItem(
                        text = { Text(windowsProfileLabel(profile)) },
                        onClick = {
                            expanded = false
                            onSelected(profile)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EngineCompatibilityHint(state: VmUiState) {
    val recommended = state.windowsProfile.recommendedEngine
    val matches = state.engine == recommended
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            tint = if (matches) Accent else Warning,
            modifier = Modifier.size(18.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (matches) {
                    stringResource(R.string.engine_recommended, recommended.label)
                } else {
                    stringResource(R.string.engine_not_recommended, recommended.label, state.engine.label)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (matches) Accent else Warning
            )
            Text(
                stringResource(if (state.engine == VmEngine.V86) R.string.v86_recommendation else R.string.qemu_recommendation),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF9AA2A9)
            )
        }
    }
}

@Composable
private fun ToggleSetting(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = Color(0xFF8B949C))
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun windowsProfileLabel(profile: WindowsProfile): String = stringResource(
    when (profile) {
        WindowsProfile.WINDOWS_98 -> R.string.windows_98
        WindowsProfile.WINDOWS_XP -> R.string.windows_xp
        WindowsProfile.WINDOWS_7 -> R.string.windows_7
        WindowsProfile.WINDOWS_10 -> R.string.windows_10
        WindowsProfile.WINDOWS_11 -> R.string.windows_11
    }
)

@Composable
private fun StatusPill(status: VmStatus) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(statusColor(status), RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(7.dp))
        Text(
            stringResource(
                when (status) {
                    VmStatus.RUNNING -> R.string.status_running
                    VmStatus.PAUSED -> R.string.status_paused
                    VmStatus.STOPPED -> R.string.status_stopped
                }
            ),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

private fun statusColor(status: VmStatus): Color = when (status) {
    VmStatus.RUNNING -> Accent
    VmStatus.PAUSED -> Warning
    VmStatus.STOPPED -> Color(0xFF697178)
}

@Composable
private fun statusMessage(state: VmUiState): String = when (state.message) {
    VmMessage.SELECT_DISK -> stringResource(R.string.message_select_disk)
    VmMessage.ENGINE_SELECTED -> stringResource(R.string.message_engine_selected, state.engine.label)
    VmMessage.DISK_READY -> stringResource(R.string.message_disk_ready)
    VmMessage.CHOOSE_DISK_FIRST -> stringResource(R.string.message_choose_disk_first)
    VmMessage.RESUMED -> stringResource(R.string.message_resumed)
    VmMessage.STARTING -> stringResource(R.string.message_starting, state.engine.label)
    VmMessage.PAUSED -> stringResource(R.string.message_paused)
    VmMessage.STOPPED -> stringResource(R.string.message_stopped)
    VmMessage.PROFILE_APPLIED -> stringResource(R.string.message_profile_applied)
    VmMessage.INVALID_CONFIGURATION -> stringResource(R.string.message_invalid_configuration)
    VmMessage.ENGINE_UNAVAILABLE -> stringResource(
        if (state.engine == VmEngine.V86) R.string.message_engine_unavailable_v86 else R.string.message_engine_unavailable_qemu
    )
    VmMessage.START_FAILED -> stringResource(R.string.message_start_failed)
}

@Composable
private fun LaboxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = Accent,
            secondary = Color(0xFF61A8FF),
            background = Background,
            surface = Panel,
            outline = Border
        ),
        content = content
    )
}

@Preview(widthDp = 900, heightDp = 600)
@Composable
private fun LaboxPreview() {
    LaboxTheme {
        LaboxScreen(
            VmUiState(), emptyList(),
            {}, {}, {}, {}, {}, { _: String, _: Int -> }, {},
            {}, {}, {}, {}, {}, {}, {}, {}
        )
    }
}
