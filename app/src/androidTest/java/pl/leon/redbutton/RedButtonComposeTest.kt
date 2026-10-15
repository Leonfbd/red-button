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
 * Compose UI and accessibility tests using FakeWipeExecutionProvider only (Section 10).
 * Production wipeData() / wipeDevice() are never invoked.
 */
class RedButtonComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun buildTestController(
        backend: InMemoryStorageBackend = InMemoryStorageBackend(),
        fakeProvider: FakeWipeExecutionProvider = FakeWipeExecutionProvider()
    ): Pair<RedButtonSessionController, FakeWipeExecutionProvider> {
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
        val controller = RedButtonSessionController(executor, storage)
        return controller to fakeProvider
    }

    @Test
    fun consentFlow_requiresBothCheckboxesForRealMode_andShowsArmedScreenWithoutCancel() {
        val (controller, fakeProvider) = buildTestController()
        var closeCalled = false
        controller.onLaunchOrRecreate()

        composeTestRule.setContent {
            RedButtonApp(
                controller = controller,
                onRequestDeviceAdmin = {},
                onCloseApplication = { closeCalled = true }
            )
        }

        composeTestRule.onNodeWithTag("mode_badge").assertIsDisplayed()
        composeTestRule.onNodeWithTag("readiness_badge").assertIsDisplayed()

        // Arm button is disabled before checking informed consent checkboxes
        composeTestRule.onNodeWithTag("btn_arm_action").performScrollTo().assertIsNotEnabled()

        // Check common consent -> still disabled in REAL MODE until realModeConsentChecked is checked
        composeTestRule.onNodeWithTag("checkbox_consent_common").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("btn_arm_action").performScrollTo().assertIsNotEnabled()

        // Check real mode consent -> arm button becomes enabled
        composeTestRule.onNodeWithTag("checkbox_consent_real").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("btn_arm_action").performScrollTo().assertIsEnabled()

        // Click Arm -> transitions to ArmedClosingScreen and calls onCloseApplication
        composeTestRule.onNodeWithTag("btn_arm_action").performClick()
        assertTrue(closeCalled)
        assertEquals(0, fakeProvider.callCount)
        composeTestRule.onNodeWithTag("armed_closing_screen").assertIsDisplayed()
    }
}
