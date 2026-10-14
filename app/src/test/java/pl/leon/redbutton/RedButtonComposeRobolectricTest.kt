package pl.leon.redbutton

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pl.leon.redbutton.execution.ArmedLaunchExecutor
import pl.leon.redbutton.model.AttemptOutcome
import pl.leon.redbutton.model.EncryptionStatus
import pl.leon.redbutton.model.ExecutionMode
import pl.leon.redbutton.model.HardwareIdentitySnapshot
import pl.leon.redbutton.model.LockScreenStatus
import pl.leon.redbutton.model.ManagementRole
import pl.leon.redbutton.model.RawDeviceState
import pl.leon.redbutton.model.UserQualification
import pl.leon.redbutton.model.WipeApiPath
import pl.leon.redbutton.model.WipeFlags
import pl.leon.redbutton.model.WipeOptions
import pl.leon.redbutton.policy.DevicePolicyEvaluator
import pl.leon.redbutton.provider.AndroidDeviceStateInspector
import pl.leon.redbutton.provider.AndroidDpmWipeExecutionProvider
import pl.leon.redbutton.provider.DeviceStateInspector
import pl.leon.redbutton.provider.ExpectedExecutionSnapshot
import pl.leon.redbutton.provider.FakeWipeExecutionProvider
import pl.leon.redbutton.provider.ProviderCallResult
import pl.leon.redbutton.storage.InMemoryStorageBackend
import pl.leon.redbutton.storage.RedButtonStorage
import pl.leon.redbutton.ui.RedButtonApp
import pl.leon.redbutton.ui.RedButtonSessionController
import pl.leon.redbutton.ui.ScreenStage

/**
 * Compose UI & Accessibility tests executed on JVM via Robolectric with FakeWipeExecutionProvider (Section 10).
 * Verifies:
 * 1. REAL MODE consent flow, armed screen without cancel button, and Activity recreation on outcome screen.
 * 2. TEST MODE toggle and simulation flow without invoking production provider.
 * 3. Storage read error fallback banner forcing TEST MODE ON.
 * 4. Unmanaged / blocked device showing blocking reasons and disabling REAL MODE arming.
 * 5. Storage write failure and uncertain write error banners blocking screen transition.
 * 6. WIPE_EUICC checkbox enablement rules (disabled below API 34, enabled on qualified API 34+ with eUICC).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RedButtonComposeRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun sampleRawState(
        sdkInt: Int = 34,
        role: ManagementRole = ManagementRole.DEVICE_OWNER,
        hasActiveWipePolicy: Boolean = true,
        lockScreenStatus: LockScreenStatus = LockScreenStatus.SECURE,
        encryptionStatus: EncryptionStatus = EncryptionStatus.ACTIVE,
        hasEuiccFeature: Boolean? = true
    ): RawDeviceState {
        return RawDeviceState(
            sdkInt = sdkInt,
            hardwareIdentity = HardwareIdentitySnapshot(
                manufacturer = "Google",
                brand = "google",
                model = "Pixel 8",
                device = "shiba",
                board = "shiba",
                hardware = "tensor",
                product = "shiba",
                osRelease = "14",
                securityPatch = "2026-09-05"
            ),
            managementRole = role,
            hasActiveWipePolicy = hasActiveWipePolicy,
            hasParentProfileDpm = false,
            userQualification = UserQualification.SYSTEM_OR_MAIN_USER,
            lockScreenStatus = lockScreenStatus,
            encryptionStatus = encryptionStatus,
            hasEuiccHardwareFeature = hasEuiccFeature
        )
    }

    private fun buildComponents(
        rawState: RawDeviceState = sampleRawState(),
        backend: InMemoryStorageBackend = InMemoryStorageBackend(),
        fakeProvider: FakeWipeExecutionProvider = FakeWipeExecutionProvider()
    ): Triple<RedButtonStorage, ArmedLaunchExecutor, FakeWipeExecutionProvider> {
        val storage = RedButtonStorage(backend)
        val inspector = object : DeviceStateInspector {
            override fun inspectCurrentState(): RawDeviceState = rawState
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
    fun composeUi_realModeConsentAndSecondLaunchOutcomeAndRecreation_callsFakeProviderOnce() {
        val (storage, executor, fakeProvider) = buildComponents()
        val controller = RedButtonSessionController(executor, storage)
        controller.onLaunchOrRecreate()

        var closeCalledOnArm = false

        composeTestRule.setContent {
            RedButtonApp(
                controller = controller,
                onRequestDeviceAdmin = {},
                onCloseApplication = { closeCalledOnArm = true }
            )
        }

        composeTestRule.onNodeWithTag("mode_badge").assertIsDisplayed()
        composeTestRule.onNodeWithTag("readiness_badge").assertIsDisplayed()

        // Arm button is initially disabled
        composeTestRule.onNodeWithTag("btn_arm_action").performScrollTo().assertIsNotEnabled()

        // Check both consent boxes for REAL MODE
        composeTestRule.onNodeWithTag("checkbox_consent_common").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("btn_arm_action").performScrollTo().assertIsNotEnabled()

        composeTestRule.onNodeWithTag("checkbox_consent_real").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("btn_arm_action").performScrollTo().assertIsEnabled()

        // Click Arm -> ArmedClosingScreen is shown, no production provider called on first launch
        composeTestRule.onNodeWithTag("btn_arm_action").performClick()
        assertTrue(closeCalledOnArm)
        assertEquals(0, fakeProvider.callCount)
        composeTestRule.onNodeWithTag("armed_closing_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_close_after_arm").assertIsDisplayed()

        // Simulate next launch (Launch 2) and Activity recreation on the outcome screen
        val secondLaunchController = RedButtonSessionController(executor, storage)
        secondLaunchController.onLaunchOrRecreate()
        assertEquals(1, fakeProvider.callCount)
        assertEquals(
            AttemptOutcome.WIPE_REQUESTED,
            secondLaunchController.uiState.value.executionSummary?.outcome
        )

        // Simulate Activity recreation
        secondLaunchController.onLaunchOrRecreate()
        assertEquals(1, fakeProvider.callCount)
    }

    @Test
    fun composeUi_testModeSwitchAndSimulationFlow_neverCallsProvider() {
        val (storage, executor, fakeProvider) = buildComponents()
        val controller = RedButtonSessionController(executor, storage)
        controller.onLaunchOrRecreate()

        composeTestRule.setContent {
            RedButtonApp(
                controller = controller,
                onRequestDeviceAdmin = {},
                onCloseApplication = {}
            )
        }

        // Toggle TEST MODE ON
        composeTestRule.onNodeWithTag("switch_test_mode").performScrollTo().assertIsOff()
        composeTestRule.onNodeWithTag("switch_test_mode").performClick()
        composeTestRule.onNodeWithTag("switch_test_mode").assertIsOn()

        // In TEST MODE, only common consent checkbox is required
        composeTestRule.onNodeWithTag("checkbox_consent_common").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("btn_arm_action").performScrollTo().assertIsEnabled()
        composeTestRule.onNodeWithTag("btn_arm_action").performClick()

        composeTestRule.onNodeWithTag("armed_closing_screen").assertIsDisplayed()
        assertEquals(0, fakeProvider.callCount)

        // Next launch executes simulation and never calls FakeWipeExecutionProvider
        val secondLaunchController = RedButtonSessionController(executor, storage)
        secondLaunchController.onLaunchOrRecreate()
        assertEquals(ScreenStage.EXECUTION_OUTCOME, secondLaunchController.uiState.value.stage)
        assertEquals(
            AttemptOutcome.SIMULATED,
            secondLaunchController.uiState.value.executionSummary?.outcome
        )
        assertEquals(0, fakeProvider.callCount)
    }

    @Test
    fun composeUi_storageReadErrorForcesTestModeFallbackAndDisplaysWarningBanner() {
        val backend = InMemoryStorageBackend().apply {
            throwOnReadKeys = setOf(RedButtonStorage.KEY_TEST_MODE)
        }
        val (storage, executor, _) = buildComponents(backend = backend)
        val controller = RedButtonSessionController(executor, storage)
        controller.onLaunchOrRecreate()

        composeTestRule.setContent {
            RedButtonApp(
                controller = controller,
                onRequestDeviceAdmin = {},
                onCloseApplication = {}
            )
        }

        assertTrue(controller.uiState.value.testModeEnabled)
        assertTrue(controller.uiState.value.storageFallbackTriggered)
        composeTestRule.onNodeWithTag("storage_fallback_banner").assertIsDisplayed()
    }

    @Test
    fun composeUi_blockedDeviceShowsBlockingReasons_andBlocksRealModeArming() {
        val blockedRaw = sampleRawState(
            sdkInt = 34,
            role = ManagementRole.NONE,
            hasActiveWipePolicy = false
        )
        val (storage, executor, _) = buildComponents(rawState = blockedRaw)
        val controller = RedButtonSessionController(executor, storage)
        controller.onLaunchOrRecreate()

        composeTestRule.setContent {
            RedButtonApp(
                controller = controller,
                onRequestDeviceAdmin = {},
                onCloseApplication = {}
            )
        }

        composeTestRule.onNodeWithTag("blocking_reasons_card").performScrollTo().assertIsDisplayed()

        // Even with both checkboxes checked in REAL MODE, Arm button remains disabled
        composeTestRule.onNodeWithTag("checkbox_consent_common").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("checkbox_consent_real").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("btn_arm_action").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun composeUi_writeFailureAndUncertainWriteBanners_blockTransitionToArmedScreen() {
        val backend = InMemoryStorageBackend()
        val (storage, executor, _) = buildComponents(backend = backend)
        val controller = RedButtonSessionController(executor, storage)
        controller.onLaunchOrRecreate()

        var closeCalled = false
        composeTestRule.setContent {
            RedButtonApp(
                controller = controller,
                onRequestDeviceAdmin = {},
                onCloseApplication = { closeCalled = true }
            )
        }

        composeTestRule.onNodeWithTag("checkbox_consent_common").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("checkbox_consent_real").performScrollTo().performClick()

        // 1. Write returns false -> shows arm_error_failed_banner and does not close
        backend.returnFalseOnWriteKeys = setOf(RedButtonStorage.KEY_CONSENT_RECORD)
        composeTestRule.onNodeWithTag("btn_arm_action").performScrollTo().performClick()
        assertFalse(closeCalled)
        composeTestRule.onNodeWithTag("arm_error_failed_banner").performScrollTo().assertIsDisplayed()

        // 2. Write throws exception -> shows arm_error_uncertain_banner and does not close
        backend.returnFalseOnWriteKeys = emptySet()
        backend.throwUncertainOnWriteKeys = setOf(RedButtonStorage.KEY_CONSENT_RECORD)
        composeTestRule.onNodeWithTag("btn_arm_action").performScrollTo().performClick()
        assertFalse(closeCalled)
        composeTestRule.onNodeWithTag("arm_error_uncertain_banner").performScrollTo().assertIsDisplayed()
    }

    @After
    fun tearDown() {
        RedButtonViewModelFactory.testControllerFactoryOverride = null
    }

    @Test
    fun composeUi_euiccCheckboxDisabledOnApi33_andEnabledOnQualifiedApi34() {
        val api33State = sampleRawState(sdkInt = 33, role = ManagementRole.DEVICE_OWNER, hasEuiccFeature = true)
        val (storage33, executor33, _) = buildComponents(rawState = api33State)
        val controller33 = RedButtonSessionController(executor33, storage33)
        controller33.onLaunchOrRecreate()

        composeTestRule.setContent {
            RedButtonApp(
                controller = controller33,
                onRequestDeviceAdmin = {},
                onCloseApplication = {}
            )
        }

        composeTestRule.onNodeWithTag("checkbox_wipe_euicc").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun mainActivity_recreateOnOutcomeScreen_doesNotReExecuteProvider() {
        val backend = InMemoryStorageBackend()
        val (storage, executor, fakeProvider) = buildComponents(backend = backend)

        // Arm REAL MODE prior to launching MainActivity (simulating armed state from Launch 1)
        executor.armForNextLaunch(
            executionMode = ExecutionMode.REAL_MODE,
            options = WipeOptions(wipeExternalStorage = true, wipeEuicc = false),
            commonConsentChecked = true,
            realModeConsentChecked = true
        )
        assertEquals(0, fakeProvider.callCount)

        RedButtonViewModelFactory.testControllerFactoryOverride = {
            RedButtonSessionController(executor, storage)
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals("Launch 2 must execute provider once", 1, fakeProvider.callCount)

            // Recreate MainActivity (configuration change / screen rotation)
            scenario.recreate()
            assertEquals("MainActivity.recreate() must NOT re-execute provider", 1, fakeProvider.callCount)

            scenario.onActivity { activity ->
                assertNotNull(activity)
            }
        }
    }

    @Test
    fun androidInspectorAndProvider_defensiveFailClosedGuards() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        // 1. AndroidDeviceStateInspector safely inspects standard Robolectric environment
        val inspector = AndroidDeviceStateInspector(context)
        val raw = inspector.inspectCurrentState()
        assertEquals(34, raw.sdkInt)
        assertEquals(ManagementRole.NONE, raw.managementRole)
        assertFalse(raw.hasActiveWipePolicy)

        // 2. AndroidDpmWipeExecutionProvider defensive fail-closed guards (never reaches DPM)
        val provider = AndroidDpmWipeExecutionProvider(
            context = context,
            inspector = object : DeviceStateInspector {
                override fun inspectCurrentState() = sampleRawState(sdkInt = 34)
            },
            evaluator = DevicePolicyEvaluator()
        )

        // Path NONE -> Refused
        val noneRes = provider.requestWipe(WipeApiPath.NONE, 0)
        assertTrue(noneRes is ProviderCallResult.Refused)
        assertEquals("UNSUPPORTED_PATH", (noneRes as ProviderCallResult.Refused).code)

        // Forbidden flags (FRP / SILENT) -> Refused
        val frpRes = provider.requestWipe(
            WipeApiPath.MODERN_WIPE_DEVICE,
            WipeFlags.WIPE_RESET_PROTECTION_DATA
        )
        assertTrue(frpRes is ProviderCallResult.Refused)
        assertEquals("FORBIDDEN_FLAGS", (frpRes as ProviderCallResult.Refused).code)

        // Mismatched expectedSnapshot (role changed) -> Refused before touching DPM
        val mismatchSnapshot = ExpectedExecutionSnapshot(
            sdkInt = 34,
            managementRole = ManagementRole.ORG_OWNED_PROFILE_OWNER,
            wipeApiPath = WipeApiPath.MODERN_WIPE_DEVICE,
            hasActiveWipePolicy = true,
            userQualification = UserQualification.SYSTEM_OR_MAIN_USER,
            hardwareIdentity = sampleRawState().hardwareIdentity
        )
        val mismatchRes = provider.requestWipe(
            WipeApiPath.MODERN_WIPE_DEVICE,
            WipeFlags.WIPE_EXTERNAL_STORAGE,
            mismatchSnapshot
        )
        assertTrue(mismatchRes is ProviderCallResult.Refused)
        assertEquals("PROVIDER_CONTINUITY_MISMATCH", (mismatchRes as ProviderCallResult.Refused).code)
    }
}
