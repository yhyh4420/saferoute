package com.saferoute.app

import android.Manifest
import android.content.Intent
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertFalse
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

    private fun shelterAt(place: Int, hour: Int, block: () -> Unit) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(ctx, MainActivity::class.java).putExtra("hazard", "earthquake")
            .putExtra("place", place).putExtra("hour", hour)
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag("shelter_name", useUnmergedTree = true).text() }.isSuccess }
            block()
        }
    }

    @Test
    fun schoolYardAtNightShowsLockWarningWithNonSchoolAlternative() {
        // 광화문 demo place: nearest earthquake destination is a school yard (덕수초등학교 운동장).
        shelterAt(place = 3, hour = 23) {
            assertTrue(compose.onNodeWithTag("shelter_name", useUnmergedTree = true).text().contains("운동장"))
            val alt = compose.onNodeWithTag("lock_alternative", useUnmergedTree = true).text()
            assertFalse("alternative must not be a school: $alt", alt.contains("학교") || alt.contains("운동장"))
        }
        val weekday = LocalDate.now().dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        if (weekday) shelterAt(place = 3, hour = 12) {
            assertTrue(compose.onAllNodesWithTag("lock_warning", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        }
    }
}
