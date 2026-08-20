package com.shilizi.virenvironment

import androidx.annotation.StringRes

interface QemuOption {
    val qemuValue: String
    val labelRes: Int
}

enum class QemuCpu(override val qemuValue: String, @StringRes override val labelRes: Int) : QemuOption {
    I486("486", R.string.qemu_cpu_486),
    PENTIUM("pentium", R.string.qemu_cpu_pentium),
    PENTIUM2("pentium2", R.string.qemu_cpu_pentium2),
    PENTIUM3("pentium3", R.string.qemu_cpu_pentium3),
    QEMU32("qemu32", R.string.qemu_cpu_qemu32),
    QEMU64("qemu64", R.string.qemu_cpu_qemu64),
    MAX("max", R.string.qemu_cpu_max)
}

enum class QemuChipset(override val qemuValue: String, @StringRes override val labelRes: Int) : QemuOption {
    I440FX("pc-i440fx", R.string.qemu_chipset_i440fx),
    Q35("q35", R.string.qemu_chipset_q35)
}

enum class QemuDisk(override val qemuValue: String, @StringRes override val labelRes: Int) : QemuOption {
    IDE("ide-hd", R.string.qemu_disk_ide),
    SATA("sata", R.string.qemu_disk_sata),
    SCSI("scsi-hd", R.string.qemu_disk_scsi),
    VIRTIO("virtio-blk-pci", R.string.qemu_disk_virtio)
}

enum class QemuVideo(override val qemuValue: String, @StringRes override val labelRes: Int) : QemuOption {
    CIRRUS("cirrus", R.string.qemu_video_cirrus),
    STANDARD("std", R.string.qemu_video_standard),
    VMWARE("vmware", R.string.qemu_video_vmware),
    QXL("qxl", R.string.qemu_video_qxl),
    VIRTIO("virtio", R.string.qemu_video_virtio)
}

enum class QemuAudio(override val qemuValue: String, @StringRes override val labelRes: Int) : QemuOption {
    NONE("none", R.string.qemu_none),
    SB16("sb16", R.string.qemu_audio_sb16),
    AC97("AC97", R.string.qemu_audio_ac97),
    ES1370("es1370", R.string.qemu_audio_es1370),
    HDA("hda-duplex", R.string.qemu_audio_hda)
}

enum class QemuNetwork(override val qemuValue: String, @StringRes override val labelRes: Int) : QemuOption {
    NONE("none", R.string.qemu_none),
    NE2000("ne2k_pci", R.string.qemu_network_ne2000),
    RTL8139("rtl8139", R.string.qemu_network_rtl8139),
    E1000("e1000", R.string.qemu_network_e1000),
    VIRTIO("virtio-net-pci", R.string.qemu_network_virtio)
}

enum class QemuFirmware(override val qemuValue: String, @StringRes override val labelRes: Int) : QemuOption {
    BIOS("bios", R.string.qemu_firmware_bios),
    UEFI("uefi", R.string.qemu_firmware_uefi)
}

enum class QemuUsb(override val qemuValue: String, @StringRes override val labelRes: Int) : QemuOption {
    NONE("none", R.string.qemu_none),
    UHCI("usb-uhci", R.string.qemu_usb_uhci),
    EHCI("usb-ehci", R.string.qemu_usb_ehci),
    XHCI("qemu-xhci", R.string.qemu_usb_xhci)
}

enum class QemuPointer(override val qemuValue: String, @StringRes override val labelRes: Int) : QemuOption {
    PS2("ps2", R.string.qemu_pointer_ps2),
    USB_TABLET("usb-tablet", R.string.qemu_pointer_tablet)
}

enum class QemuBootOrder(override val qemuValue: String, @StringRes override val labelRes: Int) : QemuOption {
    DISK_FIRST("c", R.string.qemu_boot_disk),
    CD_FIRST("dc", R.string.qemu_boot_cd)
}

data class QemuHardwareConfig(
    val cpu: QemuCpu = QemuCpu.PENTIUM3,
    val chipset: QemuChipset = QemuChipset.I440FX,
    val disk: QemuDisk = QemuDisk.IDE,
    val video: QemuVideo = QemuVideo.STANDARD,
    val audio: QemuAudio = QemuAudio.AC97,
    val network: QemuNetwork = QemuNetwork.RTL8139,
    val firmware: QemuFirmware = QemuFirmware.BIOS,
    val usb: QemuUsb = QemuUsb.EHCI,
    val pointer: QemuPointer = QemuPointer.USB_TABLET,
    val bootOrder: QemuBootOrder = QemuBootOrder.CD_FIRST,
    val rtcLocalTime: Boolean = true,
    val tpm2Enabled: Boolean = false
)

enum class QemuValidationIssue(val blocksLaunch: Boolean) {
    SATA_REQUIRES_Q35(true),
    USB_TABLET_REQUIRES_USB(true),
    TPM_REQUIRES_Q35_UEFI(true),
    WINDOWS_11_REQUIRES_64_BIT_CPU(true),
    LEGACY_WINDOWS_WITH_UEFI(false),
    LEGACY_WINDOWS_WITH_MODERN_DEVICES(false),
    VIRTIO_DRIVER_REQUIRED(false)
}

data class QemuLaunchPlan(
    val arguments: List<String>,
    val requiredFiles: Set<String>
)

fun recommendedQemuHardware(profile: WindowsProfile): QemuHardwareConfig = when (profile) {
    WindowsProfile.WINDOWS_98 -> QemuHardwareConfig(
        cpu = QemuCpu.PENTIUM2,
        video = QemuVideo.CIRRUS,
        audio = QemuAudio.SB16,
        network = QemuNetwork.NE2000,
        usb = QemuUsb.UHCI,
        pointer = QemuPointer.PS2
    )
    WindowsProfile.WINDOWS_XP -> QemuHardwareConfig()
    WindowsProfile.WINDOWS_7 -> QemuHardwareConfig(
        cpu = QemuCpu.QEMU64,
        chipset = QemuChipset.Q35,
        disk = QemuDisk.SATA,
        audio = QemuAudio.HDA,
        network = QemuNetwork.E1000
    )
    WindowsProfile.WINDOWS_10 -> QemuHardwareConfig(
        cpu = QemuCpu.MAX,
        chipset = QemuChipset.Q35,
        disk = QemuDisk.SATA,
        video = QemuVideo.STANDARD,
        audio = QemuAudio.HDA,
        network = QemuNetwork.E1000,
        firmware = QemuFirmware.UEFI,
        usb = QemuUsb.XHCI
    )
    WindowsProfile.WINDOWS_11 -> QemuHardwareConfig(
        cpu = QemuCpu.MAX,
        chipset = QemuChipset.Q35,
        disk = QemuDisk.SATA,
        video = QemuVideo.STANDARD,
        audio = QemuAudio.HDA,
        network = QemuNetwork.E1000,
        firmware = QemuFirmware.UEFI,
        usb = QemuUsb.XHCI,
        tpm2Enabled = true
    )
}

fun QemuHardwareConfig.validate(profile: WindowsProfile): List<QemuValidationIssue> = buildList {
    if (disk == QemuDisk.SATA && chipset != QemuChipset.Q35) add(QemuValidationIssue.SATA_REQUIRES_Q35)
    if (pointer == QemuPointer.USB_TABLET && usb == QemuUsb.NONE) add(QemuValidationIssue.USB_TABLET_REQUIRES_USB)
    if (tpm2Enabled && (chipset != QemuChipset.Q35 || firmware != QemuFirmware.UEFI)) {
        add(QemuValidationIssue.TPM_REQUIRES_Q35_UEFI)
    }
    if (profile == WindowsProfile.WINDOWS_11 && cpu !in setOf(QemuCpu.QEMU64, QemuCpu.MAX)) {
        add(QemuValidationIssue.WINDOWS_11_REQUIRES_64_BIT_CPU)
    }
    if (profile in setOf(WindowsProfile.WINDOWS_98, WindowsProfile.WINDOWS_XP) && firmware == QemuFirmware.UEFI) {
        add(QemuValidationIssue.LEGACY_WINDOWS_WITH_UEFI)
    }
    if (profile == WindowsProfile.WINDOWS_98 &&
        (chipset == QemuChipset.Q35 || usb == QemuUsb.XHCI || video == QemuVideo.VIRTIO)
    ) {
        add(QemuValidationIssue.LEGACY_WINDOWS_WITH_MODERN_DEVICES)
    }
    if (disk == QemuDisk.VIRTIO || video in setOf(QemuVideo.QXL, QemuVideo.VMWARE, QemuVideo.VIRTIO) ||
        network == QemuNetwork.VIRTIO
    ) {
        add(QemuValidationIssue.VIRTIO_DRIVER_REQUIRED)
    }
}

/** Builds documented QEMU arguments. Native code replaces @DISK@ and firmware/TPM placeholders. */
fun QemuHardwareConfig.toQemuLaunchPlan(networkEnabled: Boolean, audioEnabled: Boolean): QemuLaunchPlan {
    val requiredFiles = mutableSetOf<String>()
    // Termux QEMU 11 的机型名必须带版本号（pc-i440fx-9.2 / pc-q35-9.2），
    // 不带版本号的 "pc-i440fx" / "q35" 会报 "unsupported machine type" 直接崩溃。
    // 固定用 9.2 版本，避免跟随默认版本变动导致行为漂移。
    val machineArg = when (chipset) {
        QemuChipset.I440FX -> "pc-i440fx-9.2"
        QemuChipset.Q35 -> "pc-q35-9.2"
    }
    val arguments = buildList {
    addAll(listOf("-machine", machineArg, "-cpu", cpu.qemuValue))
    addAll(listOf("-vga", video.qemuValue, "-boot", "order=${bootOrder.qemuValue}"))
    addAll(listOf("-rtc", "base=${if (rtcLocalTime) "localtime" else "utc"}"))
    // 磁盘：@DISK@ 由引擎替换。ISO 装光驱，IMG/QCOW2/VHD 装硬盘。
    // format 用 raw（引擎已把镜像复制成 raw 格式）
    addAll(listOf("-drive", "file=@DISK@,if=none,id=disk0,format=raw"))
    when (disk) {
        QemuDisk.IDE -> addAll(listOf("-device", "ide-hd,drive=disk0"))
        QemuDisk.SATA -> addAll(listOf("-device", "ide-hd,drive=disk0")) // q35 下 ide-hd 自动挂 SATA
        QemuDisk.SCSI -> addAll(listOf("-device", "lsi53c895a,id=scsi0", "-device", "scsi-hd,drive=disk0,bus=scsi0.0"))
        QemuDisk.VIRTIO -> addAll(listOf("-device", "virtio-blk-pci,drive=disk0"))
    }
    if (firmware == QemuFirmware.UEFI) {
        requiredFiles += "OVMF_CODE.fd"
        requiredFiles += "OVMF_VARS.fd"
        addAll(listOf(
            "-drive", "if=pflash,format=raw,readonly=on,file=@OVMF_CODE@",
            "-drive", "if=pflash,format=raw,file=@OVMF_VARS@"
        ))
    }
    if (tpm2Enabled) {
        // Termux QEMU 11 只有 passthrough/emulator 两种 TPM 后端，都依赖外部 swtpm
        // 进程（Android 上无法打包运行）。生成 TPM 参数会让 QEMU 启动即失败
        // （chardev socket 连接不上 swtpm.sock）。因此实际不生成 TPM 参数，
        // 仅保留 requiredFiles 提示；Win11 在 PE/安装场景可跳过 TPM 检查。
        // 占位符 @TPM_SOCKET@ 由引擎兜底替换，保证即使残留也不崩。
        requiredFiles += "swtpm"
        // 不再 addAll TPM 参数（swtpm 不可用）
    }
    if (audioEnabled && audio != QemuAudio.NONE) {
        if (audio == QemuAudio.HDA) {
            addAll(listOf("-device", "intel-hda", "-device", "hda-duplex"))
        } else {
            addAll(listOf("-device", audio.qemuValue))
        }
    } else {
        addAll(listOf("-audio", "none"))
    }
    if (networkEnabled && network != QemuNetwork.NONE) {
        addAll(listOf("-netdev", "user,id=net0", "-device", "${network.qemuValue},netdev=net0"))
    } else {
        addAll(listOf("-nic", "none"))
    }
    if (usb != QemuUsb.NONE) addAll(listOf("-device", usb.qemuValue))
    if (pointer == QemuPointer.USB_TABLET) addAll(listOf("-device", pointer.qemuValue))
    }
    return QemuLaunchPlan(arguments, requiredFiles)
}

fun QemuHardwareConfig.toQemuArguments(networkEnabled: Boolean, audioEnabled: Boolean): List<String> =
    toQemuLaunchPlan(networkEnabled, audioEnabled).arguments
