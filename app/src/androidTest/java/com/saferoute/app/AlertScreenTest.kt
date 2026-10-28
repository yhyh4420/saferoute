package com.saferoute.app

import android.Manifest
import android.content.Intent
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Offline alert path on a device/emulator. Run with airplane mode on:
 *   adb shell cmd connectivity airplane-mode enable && ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class AlertScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    // connectedAndroidTest reinstalls the app, so the runtime permission dialog would cover the screen.
    @get:Rule
    val location: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun SemanticsNodeInteraction.text(): String =
        fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }

    @Test
    fun earthquakeAndFloodGiveDifferentDestinationsOffline() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(ctx, MainActivity::class.java).putExtra("hazard", "earthquake").putExtra("place", 0)
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag("shelter_name", useUnmergedTree = true).text() }.isSuccess }
            val quake = compose.onNodeWithTag("shelter_name", useUnmergedTree = true).text()

            compose.onNodeWithTag("hazard_flood").performClick()
            compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag("shelter_name", useUnmergedTree = true).text() != quake }.getOrDefault(false) }
            val flood = compose.onNodeWithTag("shelter_name", useUnmergedTree = true).text()
            assertNotEquals(quake, flood)

            val ms = Regex("""([\d.]+) ms""").find(compose.onNodeWithTag("compute_ms", useUnmergedTree = true).text())!!.groupValues[1].toDouble()
            assertTrue("offline guidance took $ms ms", ms < 200.0)
        }
    }
}
