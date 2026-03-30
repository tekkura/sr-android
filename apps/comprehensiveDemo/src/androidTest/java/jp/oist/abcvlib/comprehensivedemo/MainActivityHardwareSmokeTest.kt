package jp.oist.abcvlib.comprehensivedemo

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class MainActivityHardwareSmokeTest {

    @After
    fun tearDown() {
        ComprehensiveDemoControllerProvider.overrideForTesting = null
    }

    @Test
    fun cyclesAllBehaviorsOnHardwareWithoutCrashing() {
        val seenBehaviors = Collections.synchronizedSet(mutableSetOf<Behavior>())
        val hardwareReadySeen = AtomicBoolean(false)
        ComprehensiveDemoControllerProvider.overrideForTesting = {
            CyclingSmokeTestController(
                intervalMs = BEHAVIOR_INTERVAL_MS,
                startupDelayMs = STARTUP_DELAY_MS,
                hardwareReadySeen = hardwareReadySeen,
                seenBehaviors = seenBehaviors
            )
        }

        launchActivity().use { scenario ->
            waitForSmokeCycle(hardwareReadySeen, seenBehaviors)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertTrue(
                "Hardware smoke test never reached ready state. Permission flow or publisher startup likely blocked.",
                hardwareReadySeen.get()
            )
            assertEquals(Behavior.entries.toSet(), seenBehaviors.toSet())
            scenario.onActivity {
                // If the activity is still alive here, the hardware smoke cycle completed.
            }
        }
    }

    private fun launchActivity(): ActivityScenario<MainActivity> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return ActivityScenario.launch(intent)
    }

    private fun waitForSmokeCycle(
        hardwareReadySeen: AtomicBoolean,
        seenBehaviors: Set<Behavior>
    ) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val deadlineMs = System.currentTimeMillis() + TEST_TIMEOUT_MS

        while (System.currentTimeMillis() < deadlineMs) {
            maybeAcceptPermissionDialogs(device)
            if (hardwareReadySeen.get() && seenBehaviors.containsAll(Behavior.entries)) {
                return
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    private fun maybeAcceptPermissionDialogs(device: UiDevice) {
        repeat(MAX_PERMISSION_DIALOGS) {
            val positiveButton = device.wait(
                Until.findObject(By.res("android", "button1")),
                DIALOG_WAIT_MS
            ) ?: device.wait(
                Until.findObject(By.text(PERMISSION_ALLOW_TEXT)),
                DIALOG_WAIT_MS
            ) ?: return
            positiveButton.click()
            device.waitForIdle()
        }
    }

    private class CyclingSmokeTestController(
        private val intervalMs: Long,
        private val startupDelayMs: Long,
        private val hardwareReadySeen: AtomicBoolean,
        private val seenBehaviors: MutableSet<Behavior>,
        private val delegate: ComprehensiveDemoController = ComprehensiveDemoController()
    ) : DemoController {
        private var readyAtMs: Long? = null

        override val currentBehavior: Behavior
            get() = delegate.currentBehavior

        override val qrVisible: Boolean
            get() = delegate.qrVisible

        override fun update(state: ControllerState, now: Long) {
            if (state.hardwareReady && readyAtMs == null) {
                readyAtMs = now
                hardwareReadySeen.set(true)
            }

            val startedAtMs = readyAtMs
            val forcedBehavior = if (startedAtMs == null) {
                Behavior.REST_ON_TAIL
            } else {
                val elapsedMs = (now - startedAtMs).coerceAtLeast(0L)
                if (elapsedMs < startupDelayMs) {
                    Behavior.REST_ON_TAIL
                } else {
                    val cycleElapsed = elapsedMs - startupDelayMs
                    val index = ((cycleElapsed / intervalMs) % Behavior.entries.size).toInt()
                    Behavior.entries[index]
                }
            }
            if (startedAtMs != null) {
                seenBehaviors += forcedBehavior
            }
            delegate.update(state, now, forcedBehavior = forcedBehavior)
        }

        override fun wheelCommand(state: ControllerState, now: Long): WheelCommand {
            return delegate.wheelCommand(state, now)
        }
    }

    companion object {
        private const val STARTUP_DELAY_MS = 5_000L
        private const val BEHAVIOR_INTERVAL_MS = 2_000L
        private const val DIALOG_WAIT_MS = 500L
        private const val POLL_INTERVAL_MS = 250L
        private const val MAX_PERMISSION_DIALOGS = 4
        private val TEST_TIMEOUT_MS =
            30_000L + STARTUP_DELAY_MS + (Behavior.entries.size * BEHAVIOR_INTERVAL_MS)
        private const val PERMISSION_ALLOW_TEXT = "Allow"
    }
}
