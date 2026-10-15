package pl.leon.redbutton

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.leon.redbutton.execution.ArmedLaunchExecutor
import pl.leon.redbutton.execution.LaunchStepResult
import pl.leon.redbutton.model.AttemptOutcome
import pl.leon.redbutton.model.BlockingReason
import pl.leon.redbutton.model.EncryptionStatus
import pl.leon.redbutton.model.EuiccFeatureStatus
import pl.leon.redbutton.model.ExecutionMode
import pl.leon.redbutton.model.HardwareIdentitySnapshot
import pl.leon.redbutton.model.LockScreenStatus
import pl.leon.redbutton.model.ManagementRole
import pl.leon.redbutton.model.RawDeviceState
import pl.leon.redbutton.model.SanitizationLevel
import pl.leon.redbutton.model.UserQualification
import pl.leon.redbutton.model.WipeApiPath
import pl.leon.redbutton.model.WipeFlags
import pl.leon.redbutton.model.WipeOptions
import pl.leon.redbutton.policy.DevicePolicyEvaluator
import pl.leon.redbutton.policy.DiagnosticReportFormatter
import pl.leon.redbutton.provider.DeviceStateInspector
import pl.leon.redbutton.provider.FakeWipeExecutionProvider
import pl.leon.redbutton.provider.ProviderCallResult
import pl.leon.redbutton.storage.ArmConsentResult
import pl.leon.redbutton.storage.InMemoryStorageBackend
import pl.leon.redbutton.storage.RedButtonStorage
import pl.leon.redbutton.ui.ArmingErrorState
import pl.leon.redbutton.ui.RedButtonSessionController
import pl.leon.redbutton.ui.ScreenStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RedButtonUnitTest {

    private val evaluator = DevicePolicyEvaluator()

    private fun sampleHardware(
        manufacturer: String = "Google",
        brand: String = "google",
        model: String = "Pixel 8",
        device: String = "shiba",
        board: String = "shiba",
        hardware: String = "tensor",
        product: String = "shiba",
        fingerprint: String = "google/shiba/shiba:14/UQ1A.240205.004/112233:user/release-keys",
        securityPatch: String = "2026-09-05",
        osRelease: String = "14"
    ) = HardwareIdentitySnapshot(
        manufacturer = manufacturer,
        brand = brand,
        model = model,
        device = device,
        board = board,
        hardware = hardware,
        product = product,
        buildFingerprint = fingerprint,
        securityPatch = securityPatch,
        osRelease = osRelease
    )

    private fun qualifiedRawState(
        sdkInt: Int = 34,
        role: ManagementRole = ManagementRole.DEVICE_OWNER,
        hasActiveWipePolicy: Boolean = true,
        hasParentProfileDpm: Boolean = false,
        userQualification: UserQualification = UserQualification.SYSTEM_OR_MAIN_USER,
        lockScreenStatus: LockScreenStatus = LockScreenStatus.SECURE,
        encryptionStatus: EncryptionStatus = EncryptionStatus.ACTIVE,
        hasEuiccFeature: Boolean? = true,
        hardware: HardwareIdentitySnapshot = sampleHardware()
    ) = RawDeviceState(
        sdkInt = sdkInt,
        hardwareIdentity = hardware,
        managementRole = role,
        hasActiveWipePolicy = hasActiveWipePolicy,
        hasParentProfileDpm = hasParentProfileDpm,
        userQualification = userQualification,
        lockScreenStatus = lockScreenStatus,
        encryptionStatus = encryptionStatus,
        hasEuiccHardwareFeature = hasEuiccFeature
    )

    // -------------------------------------------------------------------------
    // 1. Non-invasive API Matrix Without Owner Role in TEST MODE (Section 10)
    // -------------------------------------------------------------------------

    @Test
    fun nonInvasiveMatrix_api23_24_27_28_33_34_35_36_unmanagedInTestMode() {
        val matrixApis = listOf(23, 24, 27, 28, 33, 34, 35, 36)
        for (sdk in matrixApis) {
            val backend = InMemoryStorageBackend()
            val storage = RedButtonStorage(backend)
            val fakeProvider = FakeWipeExecutionProvider()
            val unmanagedState = qualifiedRawState(
                sdkInt = sdk,
                role = ManagementRole.NONE,
                hasActiveWipePolicy = false,
                userQualification = if (sdk == 23) {
                    UserQualification.UNCONFIRMED_API23_USER
                } else {
                    UserQualification.SYSTEM_OR_MAIN_USER
                },
                hasEuiccFeature = if (sdk < 28) null else true
            )
            val executor = ArmedLaunchExecutor(
                storage = storage,
                inspector = object : DeviceStateInspector {
                    override fun inspectCurrentState() = unmanagedState
                },
                evaluator = evaluator,
                wipeExecutionProvider = fakeProvider
            )

            // First launch: unarmed inspection
            val firstLaunch = executor.handleLaunch()
            assertTrue(firstLaunch is LaunchStepResult.OpenUnarmedInspection)
            val eval = (firstLaunch as LaunchStepResult.OpenUnarmedInspection).evaluation
            assertFalse("Unmanaged API $sdk must not be ready for REAL MODE", eval.isReadyForRealMode)
            assertEquals(WipeApiPath.NONE, eval.wipeApiPath)
            assertEquals(
                SanitizationLevel.LEVEL_0_UNSUPPORTED,
                eval.sanitizationLevelFor(WipeOptions())
            )
            if (sdk < 28) {
                assertEquals(EuiccFeatureStatus.UNKNOWN_PRE_API_28, eval.euiccFeatureStatus)
            } else {
                assertEquals(EuiccFeatureStatus.PRESENT, eval.euiccFeatureStatus)
            }

            // Arm in TEST MODE and execute simulation on next launch
            val armResult = executor.armForNextLaunch(
                executionMode = ExecutionMode.TEST_MODE,
                options = WipeOptions(wipeExternalStorage = true, wipeEuicc = true),
                commonConsentChecked = true,
                realModeConsentChecked = false
            )
            assertTrue("Should arm TEST MODE on API $sdk", armResult is ArmConsentResult.Armed)

            val secondLaunch = executor.handleLaunch()
            assertTrue(secondLaunch is LaunchStepResult.ExecutedArmedLaunch)
            val summary = (secondLaunch as LaunchStepResult.ExecutedArmedLaunch).summary
            assertEquals(AttemptOutcome.SIMULATED, summary.outcome)
            assertTrue(summary.detailCode.startsWith("SIMULATED_WOULD_BLOCK:"))
            assertEquals("TEST MODE on API $sdk must never call provider", 0, fakeProvider.callCount)
        }
    }

    // -------------------------------------------------------------------------
    // 2. Management Role Matrix Across API 23, 24–33, and 34–36 (FR-07, Section 10)
    // -------------------------------------------------------------------------

    @Test
    fun apiMatrix_belowApi23_isBlocked() {
        val eval = evaluator.evaluate(qualifiedRawState(sdkInt = 22))
        assertFalse(eval.isReadyForRealMode)
        assertEquals(WipeApiPath.NONE, eval.wipeApiPath)
        assertTrue(eval.blockingReasons.contains(BlockingReason.API_BELOW_23))
        assertEquals(
            SanitizationLevel.LEVEL_0_UNSUPPORTED,
            eval.sanitizationLevelFor(WipeOptions())
        )
    }

    @Test
    fun api23_deviceOwnerAccepted_ordinaryAdminRejected() {
        val doEval = evaluator.evaluate(
            qualifiedRawState(
                sdkInt = 23,
                role = ManagementRole.DEVICE_OWNER,
                userQualification = UserQualification.UNCONFIRMED_API23_USER,
                hasEuiccFeature = null
            )
        )
        assertTrue(doEval.isReadyForRealMode)
        assertEquals(WipeApiPath.LEGACY_WIPE_DATA, doEval.wipeApiPath)
        assertEquals(EuiccFeatureStatus.UNKNOWN_PRE_API_28, doEval.euiccFeatureStatus)

        val adminEval = evaluator.evaluate(
            qualifiedRawState(
                sdkInt = 23,
                role = ManagementRole.DEVICE_ADMIN,
                userQualification = UserQualification.UNCONFIRMED_API23_USER
            )
        )
        assertFalse(adminEval.isReadyForRealMode)
        assertEquals(WipeApiPath.NONE, adminEval.wipeApiPath)
        assertTrue(adminEval.blockingReasons.contains(BlockingReason.API23_REQUIRES_DEVICE_OWNER))
    }

    @Test
    fun api24To33_systemAdminAndDeviceOwnerAccepted_profileOwnerAndSecondaryUserRejected() {
        for (sdk in listOf(24, 27, 28, 33)) {
            val sysAdmin = evaluator.evaluate(
                qualifiedRawState(
                    sdkInt = sdk,
                    role = ManagementRole.DEVICE_ADMIN,
                    userQualification = UserQualification.SYSTEM_OR_MAIN_USER
                )
            )
            assertTrue("Expected API $sdk system admin to be ready", sysAdmin.isReadyForRealMode)
            assertEquals(WipeApiPath.LEGACY_WIPE_DATA, sysAdmin.wipeApiPath)

            val secondaryAdmin = evaluator.evaluate(
                qualifiedRawState(
                    sdkInt = sdk,
                    role = ManagementRole.DEVICE_ADMIN,
                    userQualification = UserQualification.SECONDARY_OR_PROFILE_USER
                )
            )
            assertFalse(secondaryAdmin.isReadyForRealMode)
            assertTrue(secondaryAdmin.blockingReasons.contains(BlockingReason.SECONDARY_USER_NOT_ALLOWED))

            val profileOwner = evaluator.evaluate(
                qualifiedRawState(
                    sdkInt = sdk,
                    role = ManagementRole.PROFILE_OWNER,
                    userQualification = UserQualification.SYSTEM_OR_MAIN_USER
                )
            )
            assertFalse(profileOwner.isReadyForRealMode)
            assertTrue(profileOwner.blockingReasons.contains(BlockingReason.PROFILE_OWNER_NOT_ALLOWED_API24_33))
        }
    }

    @Test
    fun api34To36_requiresDeviceOwnerOrOrgOwnedProfileWithParentDpm() {
        for (sdk in listOf(34, 35, 36)) {
            val privateAdmin = evaluator.evaluate(
                qualifiedRawState(sdkInt = sdk, role = ManagementRole.DEVICE_ADMIN)
            )
            assertFalse(privateAdmin.isReadyForRealMode)
            assertEquals(WipeApiPath.NONE, privateAdmin.wipeApiPath)
            assertTrue(privateAdmin.blockingReasons.contains(BlockingReason.API34_PRIVATE_DEVICE_NO_OWNER_ROLE))

            val deviceOwner = evaluator.evaluate(
                qualifiedRawState(sdkInt = sdk, role = ManagementRole.DEVICE_OWNER)
            )
            assertTrue(deviceOwner.isReadyForRealMode)
            assertEquals(WipeApiPath.MODERN_WIPE_DEVICE, deviceOwner.wipeApiPath)

            val orgOwnerOk = evaluator.evaluate(
                qualifiedRawState(
                    sdkInt = sdk,
                    role = ManagementRole.ORG_OWNED_PROFILE_OWNER,
                    hasParentProfileDpm = true
                )
            )
            assertTrue(orgOwnerOk.isReadyForRealMode)
            assertEquals(WipeApiPath.MODERN_PARENT_PROFILE_WIPE_DEVICE, orgOwnerOk.wipeApiPath)

            val orgOwnerNoParent = evaluator.evaluate(
                qualifiedRawState(
                    sdkInt = sdk,
                    role = ManagementRole.ORG_OWNED_PROFILE_OWNER,
                    hasParentProfileDpm = false
                )
            )
            assertFalse(orgOwnerNoParent.isReadyForRealMode)
            assertTrue(orgOwnerNoParent.blockingReasons.contains(BlockingReason.ORG_PROFILE_MISSING_PARENT_DPM))
        }
    }

    // -------------------------------------------------------------------------
    // 3. eSIM / eUICC Boundary (API 27 vs 28 vs 34+) & Forbidden Flags (FR-08)
    // -------------------------------------------------------------------------

    @Test
    fun euiccBoundary_api27IsUnknown_api28ReadsFeature_api34AllowsFlagOptIn() {
        val api27 = evaluator.evaluate(
            qualifiedRawState(sdkInt = 27, hasEuiccFeature = false)
        )
        assertEquals(EuiccFeatureStatus.UNKNOWN_PRE_API_28, api27.euiccFeatureStatus)
        assertFalse(api27.supportsEuiccFlag)

        val api28Absent = evaluator.evaluate(
            qualifiedRawState(sdkInt = 28, hasEuiccFeature = false)
        )
        assertEquals(EuiccFeatureStatus.ABSENT, api28Absent.euiccFeatureStatus)
        assertFalse(api28Absent.supportsEuiccFlag)

        val api28Present = evaluator.evaluate(
            qualifiedRawState(sdkInt = 28, hasEuiccFeature = true)
        )
        assertEquals(EuiccFeatureStatus.PRESENT, api28Present.euiccFeatureStatus)
        assertFalse(api28Present.supportsEuiccFlag)

        val api34Present = evaluator.evaluate(
            qualifiedRawState(sdkInt = 34, hasEuiccFeature = true)
        )
        assertEquals(EuiccFeatureStatus.PRESENT, api34Present.euiccFeatureStatus)
        assertTrue(api34Present.supportsEuiccFlag)
        assertEquals(
            WipeFlags.WIPE_EXTERNAL_STORAGE or WipeFlags.WIPE_EUICC,
            api34Present.computeEffectiveFlags(WipeOptions(wipeExternalStorage = true, wipeEuicc = true))
        )
    }

    @Test
    fun forbiddenFlags_frpAndSilentAreNeverIncluded() {
        val rawCombined = WipeFlags.WIPE_EXTERNAL_STORAGE or
            WipeFlags.WIPE_EUICC or
            WipeFlags.WIPE_RESET_PROTECTION_DATA or
            WipeFlags.WIPE_SILENTLY
        assertTrue(WipeFlags.containsForbiddenFlags(rawCombined))
        val sanitized = WipeFlags.sanitizeAllowedFlags(rawCombined)
        assertFalse(WipeFlags.containsForbiddenFlags(sanitized))
        assertEquals(WipeFlags.WIPE_EXTERNAL_STORAGE or WipeFlags.WIPE_EUICC, sanitized)
    }

    // -------------------------------------------------------------------------
    // 4. Lock Screen, Encryption Status Mapping & Sanitization Levels (FR-06, Section 7)
    // -------------------------------------------------------------------------

    @Test
    fun lockAndEncryption_blocksInsecureOrDefaultKeyOrInactive() {
        assertEquals(
            EncryptionStatus.ACTIVE,
            EncryptionStatus.fromDpmConstant(EncryptionStatus.DPM_ENCRYPTION_STATUS_ACTIVE)
        )
        assertEquals(
            EncryptionStatus.ACTIVE_PER_USER,
            EncryptionStatus.fromDpmConstant(EncryptionStatus.DPM_ENCRYPTION_STATUS_ACTIVE_PER_USER)
        )
        assertEquals(
            EncryptionStatus.ACTIVE_DEFAULT_KEY,
            EncryptionStatus.fromDpmConstant(EncryptionStatus.DPM_ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY)
        )

        val defaultKeyEval = evaluator.evaluate(
            qualifiedRawState(encryptionStatus = EncryptionStatus.ACTIVE_DEFAULT_KEY)
        )
        assertFalse(defaultKeyEval.isReadyForRealMode)
        assertTrue(defaultKeyEval.blockingReasons.contains(BlockingReason.ENCRYPTION_DEFAULT_KEY_NOT_ACCEPTED))

        val insecureLockEval = evaluator.evaluate(
            qualifiedRawState(lockScreenStatus = LockScreenStatus.INSECURE)
        )
        assertFalse(insecureLockEval.isReadyForRealMode)
        assertTrue(insecureLockEval.blockingReasons.contains(BlockingReason.LOCK_SCREEN_NOT_SECURE))
    }

    @Test
    fun sanitizationLevels_classifiedCorrectly_andEncryptionAloneNeverRaisesLevel() {
        val blockedWithActiveEnc = evaluator.evaluate(
            qualifiedRawState(sdkInt = 34, role = ManagementRole.NONE, hasActiveWipePolicy = false)
        )
        assertEquals(
            SanitizationLevel.LEVEL_0_UNSUPPORTED,
            blockedWithActiveEnc.sanitizationLevelFor(WipeOptions(wipeExternalStorage = true))
        )

        val api30Eval = evaluator.evaluate(qualifiedRawState(sdkInt = 30))
        assertEquals(
            SanitizationLevel.LEVEL_1_LEGACY_WIPE_DATA,
            api30Eval.sanitizationLevelFor(WipeOptions(wipeExternalStorage = false, wipeEuicc = false))
        )
        assertEquals(
            SanitizationLevel.LEVEL_3_EXTENDED_FLAGS,
            api30Eval.sanitizationLevelFor(WipeOptions(wipeExternalStorage = true, wipeEuicc = false))
        )

        val api34Eval = evaluator.evaluate(qualifiedRawState(sdkInt = 34))
        assertEquals(
            SanitizationLevel.LEVEL_2_MODERN_WIPE_DEVICE,
            api34Eval.sanitizationLevelFor(WipeOptions(wipeExternalStorage = false, wipeEuicc = false))
        )
        assertEquals(
            SanitizationLevel.LEVEL_3_EXTENDED_FLAGS,
            api34Eval.sanitizationLevelFor(WipeOptions(wipeExternalStorage = false, wipeEuicc = true))
        )
    }

    // -------------------------------------------------------------------------
    // 5. Hardware-Family Identity Continuity (FR-05)
    // -------------------------------------------------------------------------

    @Test
    fun hardwareIdentity_toleratesOsPatchUpdate_rejectsDifferentHardwareOrTooFewFields() {
        val initial = sampleHardware(
            fingerprint = "google/shiba/shiba:14/UQ1A.240105.002/111:user/release-keys",
            securityPatch = "2026-01-05"
        )
        val afterSecurityPatch = sampleHardware(
            fingerprint = "google/shiba/shiba:14/UQ1A.241005.009/999:user/release-keys",
            securityPatch = "2026-10-05"
        )
        val matchAfterPatch = initial.matchesHardwareFamily(afterSecurityPatch)
        assertTrue(matchAfterPatch.matches)
        assertEquals(7, matchAfterPatch.matchingKnownFieldsCount)

        val differentDevice = sampleHardware(model = "Galaxy S24", device = "e1s")
        assertFalse(initial.matchesHardwareFamily(differentDevice).matches)

        val tooFewFields = HardwareIdentitySnapshot(
            manufacturer = "Google",
            brand = "google",
            model = "unknown",
            device = "unknown",
            board = "",
            hardware = "unknown",
            product = ""
        )
        assertFalse(tooFewFields.hasMinimumKnownFields())
        assertFalse(tooFewFields.matchesHardwareFamily(tooFewFields).matches)
    }

    // -------------------------------------------------------------------------
    // 6. Storage, TEST MODE Fallback, Schema Validation & Profile/Report (FR-03, FR-04, Section 8)
    // -------------------------------------------------------------------------

    @Test
    fun testMode_defaultIsFalse_andStorageReadErrorFallsBackToTrue() {
        val backend = InMemoryStorageBackend()
        val storage = RedButtonStorage(backend)

        val initial = storage.readTestModePreference()
        assertFalse(initial.testModeEnabled)
        assertFalse(initial.fallbackTriggered)

        backend.throwOnReadKeys = setOf(RedButtonStorage.KEY_TEST_MODE)
        val onError = storage.readTestModePreference()
        assertTrue(onError.testModeEnabled)
        assertTrue(onError.fallbackTriggered)

        backend.throwOnReadKeys = emptySet()
        backend.putRawForTest(RedButtonStorage.KEY_TEST_MODE, "corrupted_value")
        val onCorrupted = storage.readTestModePreference()
        assertTrue(onCorrupted.testModeEnabled)
        assertTrue(onCorrupted.fallbackTriggered)
    }

    @Test
    fun deviceProfileAndAttemptReport_persistedAndOverwrittenAsSingleLastAttempt() {
        val backend = InMemoryStorageBackend()
        val storage = RedButtonStorage(backend)
        val eval = evaluator.evaluate(qualifiedRawState(sdkInt = 35))
        val profile = evaluator.toDeviceProfile(eval, WipeOptions(wipeExternalStorage = true), 123456L)

        assertTrue(storage.saveDeviceProfile(profile))
        val loadedProfile = storage.readDeviceProfile()
        assertNotNull(loadedProfile)
        assertEquals(35, loadedProfile?.sdkInt)
        assertEquals(WipeApiPath.MODERN_WIPE_DEVICE, loadedProfile?.wipeApiPath)
        assertEquals(SanitizationLevel.LEVEL_3_EXTENDED_FLAGS, loadedProfile?.sanitizationLevel)

        // Verify AttemptReport is overwritten as a single last entry (Section 8)
        val fakeProvider = FakeWipeExecutionProvider()
        val executor = ArmedLaunchExecutor(
            storage = storage,
            inspector = object : DeviceStateInspector {
                override fun inspectCurrentState() = qualifiedRawState(sdkInt = 35)
            },
            evaluator = evaluator,
            wipeExecutionProvider = fakeProvider
        )

        executor.armForNextLaunch(
            executionMode = ExecutionMode.TEST_MODE,
            options = WipeOptions(wipeExternalStorage = false, wipeEuicc = false),
            commonConsentChecked = true,
            realModeConsentChecked = false
        )
        executor.consumeAndExecuteOnce()
        val firstReport = storage.readLastAttemptReport()
        assertNotNull(firstReport)
        assertEquals(SanitizationLevel.LEVEL_2_MODERN_WIPE_DEVICE, firstReport?.sanitizationLevel)

        executor.armForNextLaunch(
            executionMode = ExecutionMode.TEST_MODE,
            options = WipeOptions(wipeExternalStorage = true, wipeEuicc = true),
            commonConsentChecked = true,
            realModeConsentChecked = false
        )
        executor.consumeAndExecuteOnce()
        val secondReport = storage.readLastAttemptReport()
        assertNotNull(secondReport)
        assertEquals(SanitizationLevel.LEVEL_3_EXTENDED_FLAGS, secondReport?.sanitizationLevel)
    }

    @Test
    fun consentStorage_rejectsIncompleteSchemaMismatchOrTamperedRecord() {
        val backend = InMemoryStorageBackend()
        val storage = RedButtonStorage(backend)
        val fakeProvider = FakeWipeExecutionProvider()
        val rawState = qualifiedRawState()
        val executor = ArmedLaunchExecutor(
            storage = storage,
            inspector = object : DeviceStateInspector {
                override fun inspectCurrentState() = rawState
            },
            evaluator = evaluator,
            wipeExecutionProvider = fakeProvider
        )

        val armRes = executor.armForNextLaunch(
            executionMode = ExecutionMode.REAL_MODE,
            options = WipeOptions(wipeExternalStorage = true, wipeEuicc = false),
            commonConsentChecked = true,
            realModeConsentChecked = true
        )
        assertTrue(armRes is ArmConsentResult.Armed)

        val validRaw = backend.getRawForTest(RedButtonStorage.KEY_CONSENT_RECORD)!!

        // 1. Tamper with wipeEuicc without updating integrityHash
        val tamperedJson = JSONObject(validRaw).apply {
            put("wipeEuicc", true)
        }.toString()
        backend.putRawForTest(RedButtonStorage.KEY_CONSENT_RECORD, tamperedJson)

        val tamperedOutcome = executor.consumeAndExecuteOnce()
        assertEquals(AttemptOutcome.REFUSED, tamperedOutcome.outcome)
        assertTrue(tamperedOutcome.detailCode.contains("INTEGRITY_HASH_MISMATCH"))
        assertEquals(0, fakeProvider.callCount)

        // 2. Missing required field
        val missingFieldJson = JSONObject(validRaw).apply {
            remove("wipeExternalStorage")
        }.toString()
        backend.putRawForTest(RedButtonStorage.KEY_CONSENT_RECORD, missingFieldJson)

        val missingFieldOutcome = executor.consumeAndExecuteOnce()
        assertEquals(AttemptOutcome.REFUSED, missingFieldOutcome.outcome)
        assertTrue(missingFieldOutcome.detailCode.contains("MISSING_REQUIRED_FIELD:wipeExternalStorage"))
        assertEquals(0, fakeProvider.callCount)

        // 3. Unsupported schemaVersion
        val badSchemaJson = JSONObject(validRaw).apply {
            put("schemaVersion", 99)
        }.toString()
        backend.putRawForTest(RedButtonStorage.KEY_CONSENT_RECORD, badSchemaJson)

        val schemaOutcome = executor.consumeAndExecuteOnce()
        assertEquals(AttemptOutcome.REFUSED, schemaOutcome.outcome)
        assertTrue(schemaOutcome.detailCode.contains("UNSUPPORTED_SCHEMA_VERSION:99"))
        assertEquals(0, fakeProvider.callCount)
    }

    @Test
    fun consentStorage_writeFailureAndUncertainWriteBlockArming_andConsumptionErrorRefuses() {
        val backend = InMemoryStorageBackend()
        val storage = RedButtonStorage(backend)
        val fakeProvider = FakeWipeExecutionProvider()
        val executor = ArmedLaunchExecutor(
            storage = storage,
            inspector = object : DeviceStateInspector {
                override fun inspectCurrentState() = qualifiedRawState()
            },
            evaluator = evaluator,
            wipeExecutionProvider = fakeProvider
        )
        val controller = RedButtonSessionController(executor, storage)
        controller.onLaunchOrRecreate()
        controller.setCommonConsentChecked(true)
        controller.setRealModeConsentChecked(true)

        // Durable write returning false blocks arming
        backend.returnFalseOnWriteKeys = setOf(RedButtonStorage.KEY_CONSENT_RECORD)
        assertFalse(controller.armAction())
        assertEquals(ScreenStage.INSPECTION_AND_CONSENT, controller.uiState.value.stage)
        assertEquals(ArmingErrorState.WRITE_FAILED, controller.uiState.value.armingError)

        // Uncertain write throwing exception blocks arming with WRITE_UNCERTAIN warning
        backend.returnFalseOnWriteKeys = emptySet()
        backend.throwUncertainOnWriteKeys = setOf(RedButtonStorage.KEY_CONSENT_RECORD)
        assertFalse(controller.armAction())
        assertEquals(ScreenStage.INSPECTION_AND_CONSENT, controller.uiState.value.stage)
        assertEquals(ArmingErrorState.WRITE_UNCERTAIN, controller.uiState.value.armingError)

        // Now allow arming, then fail the consumption write AND invalidation remove
        backend.throwUncertainOnWriteKeys = emptySet()
        assertTrue(controller.armAction())

        backend.returnFalseOnWriteKeys = setOf(RedButtonStorage.KEY_CONSENT_RECORD)
        backend.returnFalseOnRemoveKeys = setOf(RedButtonStorage.KEY_CONSENT_RECORD)
        val consumeErrorSummary = executor.consumeAndExecuteOnce()
        assertEquals(AttemptOutcome.REFUSED, consumeErrorSummary.outcome)
        assertEquals("CONSENT_CONSUMPTION_STORAGE_ERROR", consumeErrorSummary.detailCode)
        assertTrue(consumeErrorSummary.storageStateUncertain)
        assertEquals(0, fakeProvider.callCount)
    }

    // -------------------------------------------------------------------------
    // 7. Pre-Call State Change, Report Write Failure & Inspector Exceptions (FR-05, FR-09)
    // -------------------------------------------------------------------------

    @Test
    fun preCallReinspectionChange_orReportWriteFailure_blocksRealModeWithoutProviderCall() {
        val backend = InMemoryStorageBackend()
        val storage = RedButtonStorage(backend)
        val fakeProvider = FakeWipeExecutionProvider(ProviderCallResult.Accepted)

        // Simulate state changing between initial launch analysis and final pre-call check
        var inspectCallCounter = 0
        val executor = ArmedLaunchExecutor(
            storage = storage,
            inspector = object : DeviceStateInspector {
                override fun inspectCurrentState(): RawDeviceState {
                    inspectCallCounter++
                    // Call 1: arming; Call 2: initial launch analysis; Call 3: pre-call re-verification
                    return if (inspectCallCounter == 3) {
                        qualifiedRawState(lockScreenStatus = LockScreenStatus.INSECURE)
                    } else {
                        qualifiedRawState(lockScreenStatus = LockScreenStatus.SECURE)
                    }
                }
            },
            evaluator = evaluator,
            wipeExecutionProvider = fakeProvider
        )

        executor.armForNextLaunch(
            executionMode = ExecutionMode.REAL_MODE,
            options = WipeOptions(wipeExternalStorage = true, wipeEuicc = false),
            commonConsentChecked = true,
            realModeConsentChecked = true
        )

        val summary = executor.consumeAndExecuteOnce()
        assertEquals(AttemptOutcome.REFUSED, summary.outcome)
        assertTrue(summary.detailCode.startsWith("PRE_CALL_NOT_READY"))
        assertEquals(0, fakeProvider.callCount)

        // Now test local attempt report write failure before provider call (FR-09)
        inspectCallCounter = 10 // keep state SECURE
        executor.armForNextLaunch(
            executionMode = ExecutionMode.REAL_MODE,
            options = WipeOptions(wipeExternalStorage = true, wipeEuicc = false),
            commonConsentChecked = true,
            realModeConsentChecked = true
        )
        backend.returnFalseOnWriteKeys = setOf(RedButtonStorage.KEY_LAST_ATTEMPT_REPORT)
        val reportFailSummary = executor.consumeAndExecuteOnce()
        assertEquals(AttemptOutcome.REFUSED, reportFailSummary.outcome)
        assertEquals("LOCAL_REPORT_WRITE_FAILED", reportFailSummary.detailCode)
        assertEquals("Provider must not be called when report write fails", 0, fakeProvider.callCount)
    }

    @Test
    fun inspectorException_andManualChecklistEphemeralGuarantee() {
        val backend = InMemoryStorageBackend()
        val storage = RedButtonStorage(backend)
        val fakeProvider = FakeWipeExecutionProvider()
        val throwingExecutor = ArmedLaunchExecutor(
            storage = storage,
            inspector = object : DeviceStateInspector {
                override fun inspectCurrentState(): RawDeviceState {
                    throw IllegalStateException("Simulated OS service failure")
                }
            },
            evaluator = evaluator,
            wipeExecutionProvider = fakeProvider
        )
        val controller = RedButtonSessionController(throwingExecutor, storage)
        controller.onLaunchOrRecreate()

        val state = controller.uiState.value
        assertNotNull(state.evaluation)
        assertFalse(state.evaluation!!.isReadyForRealMode)
        assertEquals(0, fakeProvider.callCount)

        // Manual checklist is ephemeral: never stored in StorageBackend
        controller.toggleManualChecklistItem(0)
        controller.toggleManualChecklistItem(2)
        assertEquals(setOf(0, 2), controller.uiState.value.manualChecklist)
        assertNull(backend.getRawForTest("manual_checklist"))
    }

    // -------------------------------------------------------------------------
    // 8. Concurrent Consumption & Activity Recreation (Section 9 & 10)
    // -------------------------------------------------------------------------

    @Test
    fun concurrentConsumption_executesProviderAtMostOnce() {
        val backend = InMemoryStorageBackend()
        val storage = RedButtonStorage(backend)
        val fakeProvider = FakeWipeExecutionProvider(ProviderCallResult.Accepted)
        val executor = ArmedLaunchExecutor(
            storage = storage,
            inspector = object : DeviceStateInspector {
                override fun inspectCurrentState() = qualifiedRawState(sdkInt = 34)
            },
            evaluator = evaluator,
            wipeExecutionProvider = fakeProvider
        )

        val armed = executor.armForNextLaunch(
            executionMode = ExecutionMode.REAL_MODE,
            options = WipeOptions(wipeExternalStorage = true, wipeEuicc = false),
            commonConsentChecked = true,
            realModeConsentChecked = true
        )
        assertTrue(armed is ArmConsentResult.Armed)

        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threads)
        val requestedCount = AtomicInteger(0)
        val refusedCount = AtomicInteger(0)

        repeat(threads) {
            pool.submit {
                try {
                    startLatch.await()
                    val summary = executor.consumeAndExecuteOnce()
                    if (summary.outcome == AttemptOutcome.WIPE_REQUESTED) {
                        requestedCount.incrementAndGet()
                    } else if (summary.outcome == AttemptOutcome.REFUSED) {
                        refusedCount.incrementAndGet()
                    }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals("Provider must be called exactly once", 1, fakeProvider.callCount)
        assertEquals(1, requestedCount.get())
        assertEquals(threads - 1, refusedCount.get())
    }

    @Test
    fun activityRecreation_doesNotReExecuteArmedLaunch() {
        val backend = InMemoryStorageBackend()
        val storage = RedButtonStorage(backend)
        val fakeProvider = FakeWipeExecutionProvider(ProviderCallResult.Accepted)
        val executor = ArmedLaunchExecutor(
            storage = storage,
            inspector = object : DeviceStateInspector {
                override fun inspectCurrentState() = qualifiedRawState(sdkInt = 34)
            },
            evaluator = evaluator,
            wipeExecutionProvider = fakeProvider
        )

        executor.armForNextLaunch(
            executionMode = ExecutionMode.REAL_MODE,
            options = WipeOptions(wipeExternalStorage = true, wipeEuicc = false),
            commonConsentChecked = true,
            realModeConsentChecked = true
        )
        assertEquals(0, fakeProvider.callCount)

        val secondLaunchController = RedButtonSessionController(executor, storage)
        secondLaunchController.onLaunchOrRecreate()
        assertEquals(ScreenStage.EXECUTION_OUTCOME, secondLaunchController.uiState.value.stage)
        assertEquals(
            AttemptOutcome.WIPE_REQUESTED,
            secondLaunchController.uiState.value.executionSummary?.outcome
        )
        assertEquals(1, fakeProvider.callCount)

        // Simulate Activity recreation
        secondLaunchController.onLaunchOrRecreate()
        secondLaunchController.onLaunchOrRecreate()
        assertEquals(1, fakeProvider.callCount)
        assertNotNull(fakeProvider.calls.first().expectedSnapshot)
        assertEquals(34, fakeProvider.calls.first().expectedSnapshot?.sdkInt)
        assertEquals(ScreenStage.EXECUTION_OUTCOME, secondLaunchController.uiState.value.stage)

        // Simulate process-death Activity recreation on EXECUTION_OUTCOME (new ViewModel + savedState)
        val savedOutcomeState = secondLaunchController.snapshotSessionState()
        val restoredAfterProcessDeathController = RedButtonSessionController(executor, storage)
        restoredAfterProcessDeathController.onLaunchOrRecreate(savedOutcomeState)
        assertEquals(ScreenStage.EXECUTION_OUTCOME, restoredAfterProcessDeathController.uiState.value.stage)
        assertEquals(
            AttemptOutcome.WIPE_REQUESTED,
            restoredAfterProcessDeathController.uiState.value.executionSummary?.outcome
        )
        assertEquals("Process-death restore must never re-execute provider", 1, fakeProvider.callCount)

        // Subsequent 3rd fresh launch opens unarmed inspection
        val thirdLaunchController = RedButtonSessionController(executor, storage)
        thirdLaunchController.onLaunchOrRecreate()
        assertEquals(ScreenStage.INSPECTION_AND_CONSENT, thirdLaunchController.uiState.value.stage)
        assertEquals(1, fakeProvider.callCount)
    }

    @Test
    fun processDeathDuringArmedClosing_doesNotConsumeOrExecuteArmedRecord() {
        val backend = InMemoryStorageBackend()
        val storage = RedButtonStorage(backend)
        val fakeProvider = FakeWipeExecutionProvider(ProviderCallResult.Accepted)
        val executor = ArmedLaunchExecutor(
            storage = storage,
            inspector = object : DeviceStateInspector {
                override fun inspectCurrentState() = qualifiedRawState(sdkInt = 34)
            },
            evaluator = evaluator,
            wipeExecutionProvider = fakeProvider
        )

        val firstLaunchController = RedButtonSessionController(executor, storage)
        firstLaunchController.onLaunchOrRecreate()
        firstLaunchController.setCommonConsentChecked(true)
        firstLaunchController.setRealModeConsentChecked(true)
        assertTrue(firstLaunchController.armAction())
        assertEquals(ScreenStage.ARMED_CLOSING, firstLaunchController.uiState.value.stage)

        // Simulate recreation with savedState while still in ARMED_CLOSING on Launch 1
        val savedArmedState = firstLaunchController.snapshotSessionState()
        val recreatedOnLaunch1 = RedButtonSessionController(executor, storage)
        recreatedOnLaunch1.onLaunchOrRecreate(savedArmedState)
        assertEquals(ScreenStage.ARMED_CLOSING, recreatedOnLaunch1.uiState.value.stage)
        assertEquals("Must not execute during Launch 1 recreation", 0, fakeProvider.callCount)

        // Now open fresh Launch 2 (savedState = null) -> consumes and executes once
        val secondLaunch = RedButtonSessionController(executor, storage)
        secondLaunch.onLaunchOrRecreate(null)
        assertEquals(ScreenStage.EXECUTION_OUTCOME, secondLaunch.uiState.value.stage)
        assertEquals(1, fakeProvider.callCount)
    }

    @Test
    fun diagnosticReportFormatter_formatsCompleteOfflineTechnicalSummary() {
        val eval = evaluator.evaluate(qualifiedRawState(sdkInt = 35))
        val options = WipeOptions(wipeExternalStorage = true, wipeEuicc = true)
        val reportText = DiagnosticReportFormatter.formatReport(
            activeMode = ExecutionMode.TEST_MODE,
            storageFallbackTriggered = false,
            options = options,
            evaluation = eval,
            executionSummary = null,
            lastAttemptReport = null
        )

        assertTrue(reportText.contains("ApplicationId: pl.leon.redbutton"))
        assertTrue(reportText.contains("ManufacturerModel: Google Pixel 8"))
        assertTrue(reportText.contains("KnownHardwareFieldsCount: 7/7"))
        assertTrue(reportText.contains("ManagementRole: DEVICE_OWNER"))
        assertTrue(reportText.contains("PublicDpmPath: MODERN_WIPE_DEVICE"))
        assertTrue(reportText.contains("RequestClassificationLevel: Level 3"))
        assertTrue(reportText.contains("LastAttempt: NONE"))
    }
}
