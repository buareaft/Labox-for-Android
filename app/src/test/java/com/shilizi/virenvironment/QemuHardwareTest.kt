package com.shilizi.virenvironment

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QemuHardwareTest {
    @Test
    fun invalidHardwareCombinationsBlockLaunch() {
        val config = QemuHardwareConfig(
            chipset = QemuChipset.I440FX,
            disk = QemuDisk.SATA,
            firmware = QemuFirmware.BIOS,
            usb = QemuUsb.NONE,
            pointer = QemuPointer.USB_TABLET,
            tpm2Enabled = true
        )

        val issues = config.validate(WindowsProfile.WINDOWS_11)

        assertTrue(QemuValidationIssue.SATA_REQUIRES_Q35 in issues)
        assertTrue(QemuValidationIssue.USB_TABLET_REQUIRES_USB in issues)
        assertTrue(QemuValidationIssue.TPM_REQUIRES_Q35_UEFI in issues)
        assertTrue(issues.any { it.blocksLaunch })
    }

    @Test
    fun windows11PresetBuildsCompleteLaunchPlan() {
        val config = recommendedQemuHardware(WindowsProfile.WINDOWS_11)

        val plan = config.toQemuLaunchPlan(networkEnabled = true, audioEnabled = true)
        val arguments = plan.arguments.joinToString(" ")

        assertFalse(config.validate(WindowsProfile.WINDOWS_11).any { it.blocksLaunch })
        assertTrue(arguments.contains("-machine pc-q35-9.2"))
        assertFalse(arguments.contains("@DISK@"))
        assertTrue(arguments.contains("file=@OVMF_CODE@"))
        assertFalse(arguments.contains("-tpmdev"))
        assertTrue(arguments.contains("-netdev user,id=net0"))
        assertTrue("OVMF_CODE.fd" in plan.requiredFiles)
        assertTrue("swtpm" in plan.requiredFiles)
    }

    @Test
    fun disabledNetworkAndAudioGenerateExplicitNoneOptions() {
        val plan = QemuHardwareConfig().toQemuLaunchPlan(
            networkEnabled = false,
            audioEnabled = false
        )
        val arguments = plan.arguments.joinToString(" ")

        assertTrue(arguments.contains("-nic none"))
        assertTrue(arguments.contains("-audio none"))
    }
}
