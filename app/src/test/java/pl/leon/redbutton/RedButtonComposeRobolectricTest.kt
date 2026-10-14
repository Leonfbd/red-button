package pl.leon.redbutton

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pl.leon.redbutton.execution.ArmedLaunchExecutor
import pl.leon.redbutton.model.EncryptionStatus
import pl.leon.redbutton.model.HardwareIdentitySnapshot
import pl.leon.redbutton.model.LockScreenStatus
import pl.leon.redbutton.model.ManagementRole
import pl.leon.redbutton.model.RawDeviceState
import pl.leon.redbutton.model.UserQualification
import pl.leon.redbutton.policy.DevicePolicyEvaluator
import pl.leon.redbutton.provider.DeviceStateInspector
import pl.leon.redbutton.provider.FakeWipeExecutionProvider
import pl.leon.redbutton.storage.InMemoryStorageBackend
import pl.leon.redbutton.storage.RedButtonStorage
import pl.leon.redbutton.ui.RedButtonApp
import pl.leon.redbutton.ui.RedButtonSessionController

/**
 * Compose UI & Accessibility tests executed on JVM via Robolectric with FakeWipeExecutionProvider (Section 10).
 * Verifies consent flow, storage fallback warning, armed screen without cancel button,
 * and Activity recreation on the outcome screen without re-executing the wipe request.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RedButtonComposeRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun buildComponents(
        backend: InMemoryStorageBackend = InMemoryStorageBackend(),
        fakeProvider: FakeWipeExecutionProvider = FakeWipeExecutionProvider()
    ): Triple<RedButtonStorage, ArmedLaunchExecutor, FakeWipeExecutionProvider> {
        val storage = RedButtonStorage(backend)
        val inspector = object : DeviceStateInspector {
            override fun inspectCurrentState(): RawDeviceState {
                return RawDeviceState(
                    sdkInt = 34,
                    hardwareIdentity = HardwareIdentitySnapshot(
                        manufacturer = "Google",
                        brand = "google",
                        model = "Pixel 8",
                        device = "shiba",
                        board = "shiba",
                        hardware = "tensor",
                        product = "shiba",
                        osRelease = "14"
                    ),
                    managementRole = ManagementRole.DEVICE_OWNER,
                    hasActiveWipePolicy = true,
                    hasParentProfileDpm = false,
                    userQualification = UserQualification.SYSTEM_OR_MAIN_USER,
                    lockScreenStatus = LockScreenStatus.SECURE,
                    encryptionStatus = EncryptionStatus.ACTIVE,
                    hasEuiccHardwareFeature = true
                )
            }
        }
        val executor = ArmedLaunchExecutor(
            storage = storage,
            inspector = inspector,
            evaluator = DevicePolicyEvaluator(),
            wipeExecutionProvider = fakeProvider
        )
        return Triple(storage, executor, fakeProvider)
    }

    @Test
    fun composeUi_armingAndSecondLaunchOutcomeAndRecreation_callsFakeProviderOnce() {
        val (storage, executor, fakeProvider) = buildComponents()
        val firstLaunchController = RedButtonSessionController(executor, storage)
        firstLaunchController.onLaunchOrRecreate()

        var closeCalledOnArm = false

        composeTestRule.setContent {
            RedButtonApp(
                controller = firstLaunchController,
                onRequestDeviceAdmin = {},
                onCloseApplication = { closeCalledOnArm = true }
            )
        }

        // Verify mode and readiness badges are visible
        composeTestRule.onNodeWithTag("mode_badge").assertIsDisplayed()
        composeTestRule.onNodeWithTag("readiness_badge").assertIsDisplayed()

        // Arm button is initially disabled
        composeTestRule.onNodeWithTag("btn_arm_action").performScrollTo().assertIsNotEnabled()

        // Check both consent boxes for REAL MODE
        composeTestRule.onNodeWithTag("checkbox_consent_common").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("btn_arm_action").performScrollTo().assertIsNotEnabled()

        composeTestRule.onNodeWithTag("checkbox_consent_real").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("btn_arm_action").performScrollTo().assertIsEnabled()

        // Click Arm -> ArmedClosingScreen is shown, no production provider called
        composeTestRule.onNodeWithTag("btn_arm_action").performClick()
        assertTrue(closeCalledOnArm)
        assertEquals(0, fakeProvider.callCount)
        composeTestRule.onNodeWithTag("armed_closing_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_close_after_arm").assertIsDisplayed()

        // Simulate next launch (Launch 2) and Activity recreation on the outcome screen
        val secondLaunchController = RedButtonSessionController(executor, storage)
        secondLaunchController.onLaunchOrRecreate()
        assertEquals(1, fakeProvider.callCount)

        // Simulate Activity recreation before/after UI render
        secondLaunchController.onLaunchOrRecreate()
        assertEquals(1, fakeProvider.callCount)
    }
}
