package dev.openstream.app

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatorSurfaceLayoutPolicyTest {

    @Test
    fun `expert camera controls stay hidden from the operator surface`() {
        val mainLayout = layoutText("activity_main.xml")

        listOf(
            "btnExposurePanel",
            "btnFocusPanel",
            "btnColorPanel",
            "btnArmRemote",
            "btnTorch",
            "btnScreenOff",
            "hudFps",
            "hudShutter",
            "hudIso",
            "hudWb",
            "hudFocus",
            "hudNetwork",
        ).forEach { id ->
            val viewStart = mainLayout.indexOf("@+id/$id")
            assertTrue("Missing expected compatibility view: $id", viewStart >= 0)
            val viewEnd = mainLayout.indexOf('>', viewStart)
            assertTrue(
                "$id must remain hidden from the operator surface",
                mainLayout.substring(viewStart, viewEnd).contains("android:visibility=\"gone\""),
            )
        }
    }

    @Test
    fun `settings layout has no advanced section or toggle`() {
        val settingsLayout = layoutText("activity_settings.xml")

        assertTrue(settingsLayout.contains("@+id/connectionSettingsPanel"))
        assertFalse(settingsLayout.contains("btnToggleAdvanced"))
        assertFalse(settingsLayout.contains("advancedSettingsPanel"))
        assertFalse(settingsLayout.contains("settings_section_advanced"))
    }

    @Test
    fun `main operator actions avoid the expert camera palette`() {
        val activitySource = sourceText("MainActivity.kt")

        assertTrue(activitySource.contains("btnStart.setOnClickListener"))
        assertTrue(activitySource.contains("btnLensPanel.setOnClickListener { selectNextLens() }"))
        assertFalse(activitySource.contains("btnLensPanel.setOnClickListener { showCameraPalette"))
    }

    private fun layoutText(name: String): String {
        val relativePath = Path.of("src", "main", "res", "layout", name)
        assertTrue("Layout source was not found: $relativePath", Files.exists(relativePath))
        return String(Files.readAllBytes(relativePath))
    }

    private fun sourceText(name: String): String {
        val relativePath = Path.of("src", "main", "java", "dev", "openstream", "app", name)
        assertTrue("Source was not found: $relativePath", Files.exists(relativePath))
        return String(Files.readAllBytes(relativePath))
    }
}
