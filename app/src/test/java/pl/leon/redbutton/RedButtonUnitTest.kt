package pl.leon.redbutton

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    // 1. Non-invasive API Matrix & Management Role Rules (FR-07, Section 10)
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
        // API 23 Device Owner -> Ready via LEGACY_WIPE_DATA
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

        // API 23 ordinary Device Admin -> Rejected because system user cannot be confirmed (FR-07)
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
        for (sdk in listOf(24, 28, 33)) {
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
            // Private unmanaged or ordinary Device Admin -> Rejected on API 34+
            val privateAdmin = evaluator.evaluate(
                qualifiedRawState(sdkInt = sdk, role = ManagementRole.DEVICE_ADMIN)
            )
            assertFalse(privateAdmin.isReadyForRealMode)
            assertEquals(WipeApiPath.NONE, privateAdmin.wipeApiPath)
            assertTrue(privateAdmin.blockingReasons.contains(BlockingReason.API34_PRIVATE_DEVICE_NO_OWNER_ROLE))

            // Device Owner -> Ready via MODERN_WIPE_DEVICE
            val deviceOwner = evaluator.evaluate(
                qualifiedRawState(sdkInt = sdk, role = ManagementRole.DEVICE_OWNER)
            )
            assertTrue(deviceOwner.isReadyForRealMode)
            assertEquals(WipeApiPath.MODERN_WIPE_DEVICE, deviceOwner.wipeApiPath)

            // Org-owned Profile Owner with parent DPM -> Ready via MODERN_PARENT_PROFILE_WIPE_DEVICE
            val orgOwnerOk = evaluator.evaluate(
                qualifiedRawState(
                    sdkInt = sdk,
                    role = ManagementRole.ORG_OWNED_PROFILE_OWNER,
                    hasParentProfileDpm = true
                )
            )
            assertTrue(orgOwnerOk.isReadyForRealMode)
            assertEquals(WipeApiPath.MODERN_PARENT_PROFILE_WIPE_DEVICE, orgOwnerOk.wipeApiPath)

            // Org-owned Profile Owner missing parent DPM -> Blocked
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
    // 2. eSIM / eUICC Boundary (API 27 vs 28 vs 34+) & Forbidden Flags (FR-08)
    // -------------------------------------------------------------------------

    @Test
    fun euiccBoundary_api27IsUnknown_api28ReadsFeature_api34AllowsFlagOptIn() {
        // API 27: even if boolean false is passed, must report UNKNOWN_PRE_API_28, NOT ABSENT
        val api27 = evaluator.evaluate(
            qualifiedRawState(sdkInt = 27, hasEuiccFeature = false)
        )
        assertEquals(EuiccFeatureStatus.UNKNOWN_PRE_API_28, api27.euiccFeatureStatus)
        assertFalse(api27.supportsEuiccFlag)

        // API 28: reports ABSENT or PRESENT accurately, but WIPE_EUICC flag requires API 34+
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

        // API 34 with PRESENT: supportsEuiccFlag is true
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
    // 3. Lock Screen, Encryption Status Mapping & Sanitization Levels (FR-06, Section 7)
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
        // Blocked device with ACTIVE encryption -> still Level 0
        val blockedWithActiveEnc = evaluator.evaluate(
            qualifiedRawState(sdkInt = 34, role = ManagementRole.NONE, hasActiveWipePolicy = false)
        )
        assertEquals(
            SanitizationLevel.LEVEL_0_UNSUPPORTED,
            blockedWithActiveEnc.sanitizationLevelFor(WipeOptions(wipeExternalStorage = true))
        )

        // API 30 qualified without extension flags -> Level 1
        val api30Eval = evaluator.evaluate(qualifiedRawState(sdkInt = 30))
        assertEquals(
            SanitizationLevel.LEVEL_1_LEGACY_WIPE_DATA,
            api30Eval.sanitizationLevelFor(WipeOptions(wipeExternalStorage = false, wipeEuicc = false))
        )
        // API 30 qualified with WIPE_EXTERNAL_STORAGE -> Level 3
        assertEquals(
            SanitizationLevel.LEVEL_3_EXTENDED_FLAGS,
            api30Eval.sanitizationLevelFor(WipeOptions(wipeExternalStorage = true, wipeEuicc = false))
        )

        // API 34 qualified without extension flags -> Level 2
        val api34Eval = evaluator.evaluate(qualifiedRawState(sdkInt = 34))
        assertEquals(
            SanitizationLevel.LEVEL_2_MODERN_WIPE_DEVICE,
            api34Eval.sanitizationLevelFor(WipeOptions(wipeExternalStorage = false, wipeEuicc = false))
        )
        // API 34 qualified with WIPE_EUICC -> Level 3
        assertEquals(
            SanitizationLevel.LEVEL_3_EXTENDED_FLAGS,
            api34Eval.sanitizationLevelFor(WipeOptions(wipeExternalStorage = false, wipeEuicc = true))
        )
    }

    // -------------------------------------------------------------------------
    // 4. Hardware-Family Identity Continuity (FR-05)
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
    // 5. Storage, TEST MODE Fallback, Schema Validation & Integrity (FR-03, FR-04)
    // -------------------------------------------------------------------------

    @Test
    fun testMode_defaultIsFalse_andStorageReadErrorFallsBackToTrue() {
        val backend = InMemoryStorageBackend()
        val storage = RedButtonStorage(backend)

        // Default is false (TEST MODE disabled)
        val initial = storage.readTestModePreference()
        assertFalse(initial.testModeEnabled)
        assertFalse(initial.fallbackTriggered)

        // Read exception -> safe fallback to TEST MODE = true
        backend.throwOnReadKeys = setOf(RedButtonStorage.KEY_TEST_MODE)
        val onError = storage.readTestModePreference()
        assertTrue(onError.testModeEnabled)
        assertTrue(onError.fallbackTriggered)

        // Corrupted preference value -> safe fallback to TEST MODE = true
        backend.throwOnReadKeys = emptySet()
        backend.putRawForTest(RedButtonStorage.KEY_TEST_MODE, "corrupted_value")
        val onCorrupted = storage.readTestModePreference()
        assertTrue(onCorrupted.testModeEnabled)
        assertTrue(onCorrupted.fallbackTriggered)
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

        // Arm valid record first
        val armRes = executor.armForNextLaunch(
            executionMode = ExecutionMode.REAL_MODE,
            options = WipeOptions(wipeExternalStorage = true, wipeEuicc = false),
            commonConsentChecked = true,
            realModeConsentChecked = true
        )
        assertTrue(armRes is ArmConsentResult.Armed)

        val validRaw = backend.getRawForTest(RedButtonStorage.KEY_CONSENT_RECORD)!!

        // 1. Tamper with executionMode without updating integrityHash
        val tamperedJson = JSONObject(validRaw).apply {
            put("wipeEuicc", true)
        }.toString()
        backend.putRawForTest(RedButtonStorage.KEY_CONSENT_RECORD, tamperedJson)

        val tamperedOutcome = executor.consumeAndExecuteOnce()
        assertEquals(AttemptOutcome.REFUSED, tamperedOutcome.outcome)
        assertTrue(tamperedOutcome.detailCode.contains("INTEGRITY_HASH_MISMATCH"))
        assertEquals(0, fakeProvider.callCount)

        // 2. Missing required field (e.g. wipeExternalStorage removed)
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
    fun consentStorage_writeFailureAndUncertainWriteBlockArming() {
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

        // Simulate durable write returning false
        backend.returnFalseOnWriteKeys = setOf(RedButtonStorage.KEY_CONSENT_RECORD)
        assertFalse(controller.armAction())
        assertEquals(ScreenStage.INSPECTION_AND_CONSENT, controller.uiState.value.stage)
        assertEquals(ArmingErrorState.WRITE_FAILED, controller.uiState.value.armingError)

        // Simulate uncertain write throwing exception
        backend.returnFalseOnWriteKeys = emptySet()
        backend.throwUncertainOnWriteKeys = setOf(RedButtonStorage.KEY_CONSENT_RECORD)
        assertFalse(controller.armAction())
        assertEquals(ScreenStage.INSPECTION_AND_CONSENT, controller.uiState.value.stage)
        assertEquals(ArmingErrorState.WRITE_UNCERTAIN, controller.uiState.value.armingError)
    }

    // -------------------------------------------------------------------------
    // 6. Concurrent Consumption, Recreation Safety, TEST MODE & Fail-Closed Execution
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
    fun testModeExecution_neverCallsProductionProvider() {
        val backend = InMemoryStorageBackend()
        val storage = RedButtonStorage(backend)
        val fakeProvider = FakeWipeExecutionProvider()
        val executor = ArmedLaunchExecutor(
            storage = storage,
            inspector = object : DeviceStateInspector {
                override fun inspectCurrentState() = qualifiedRawState(sdkInt = 34)
            },
            evaluator = evaluator,
            wipeExecutionProvider = fakeProvider
        )

        executor.armForNextLaunch(
            executionMode = ExecutionMode.TEST_MODE,
            options = WipeOptions(wipeExternalStorage = true, wipeEuicc = true),
            commonConsentChecked = true,
            realModeConsentChecked = false
        )

        val step = executor.handleLaunch()
        assertTrue(step is LaunchStepResult.ExecutedArmedLaunch)
        val executed = step as LaunchStepResult.ExecutedArmedLaunch
        assertEquals(AttemptOutcome.SIMULATED, executed.summary.outcome)
        assertEquals("TEST MODE must NEVER call requestWipe", 0, fakeProvider.callCount)
        assertNotNull(storage.readLastAttemptReport())
        assertEquals(AttemptOutcome.SIMULATED, storage.readLastAttemptReport()?.outcome)
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

        // Launch 1: Arm REAL MODE
        executor.armForNextLaunch(
            executionMode = ExecutionMode.REAL_MODE,
            options = WipeOptions(wipeExternalStorage = true, wipeEuicc = false),
            commonConsentChecked = true,
            realModeConsentChecked = true
        )
        assertEquals(0, fakeProvider.callCount)

        // Launch 2: SessionController starts and handles launch
        val secondLaunchController = RedButtonSessionController(executor, storage)
        secondLaunchController.onLaunchOrRecreate()
        assertEquals(ScreenStage.EXECUTION_OUTCOME, secondLaunchController.uiState.value.stage)
        assertEquals(
            AttemptOutcome.WIPE_REQUESTED,
            secondLaunchController.uiState.value.executionSummary?.outcome
        )
        assertEquals(1, fakeProvider.callCount)

        // Simulate Activity recreation (screen rotation) calling onLaunchOrRecreate() again
        secondLaunchController.onLaunchOrRecreate()
        secondLaunchController.onLaunchOrRecreate()
        assertEquals(1, fakeProvider.callCount)
        assertEquals(ScreenStage.EXECUTION_OUTCOME, secondLaunchController.uiState.value.stage)

        // Launch 3 (subsequent new process/launch after consumption): opens unarmed inspection
        val thirdLaunchController = RedButtonSessionController(executor, storage)
        thirdLaunchController.onLaunchOrRecreate()
        assertEquals(ScreenStage.INSPECTION_AND_CONSENT, thirdLaunchController.uiState.value.stage)
        assertEquals(1, fakeProvider.callCount)
    }

    @Test
    fun stateChangeOrProviderException_refusesWithNoFallbackAndNoRetry() {
        val backend = InMemoryStorageBackend()
        val storage = RedButtonStorage(backend)
        val fakeProvider = FakeWipeExecutionProvider(
            ProviderCallResult.Refused("DPM_SECURITY_EXCEPTION", "Rejected by OS")
        )
        var currentState = qualifiedRawState(sdkInt = 34)
        val executor = ArmedLaunchExecutor(
            storage = storage,
            inspector = object : DeviceStateInspector {
                override fun inspectCurrentState() = currentState
            },
            evaluator = evaluator,
            wipeExecutionProvider = fakeProvider
        )

        // 1. Arm while device is ready
        executor.armForNextLaunch(
            executionMode = ExecutionMode.REAL_MODE,
            options = WipeOptions(wipeExternalStorage = true, wipeEuicc = false),
            commonConsentChecked = true,
            realModeConsentChecked = true
        )

        // Change role before next launch -> blocked by continuity check without calling provider
        currentState = qualifiedRawState(sdkInt = 34, role = ManagementRole.NONE, hasActiveWipePolicy = false)
        val summary1 = executor.consumeAndExecuteOnce()
        assertEquals(AttemptOutcome.REFUSED, summary1.outcome)
        assertTrue(summary1.detailCode.startsWith("CONTINUITY_ROLE_CHANGED"))
        assertEquals(0, fakeProvider.callCount)

        // 2. Re-arm with valid state, then let provider reject with SecurityException
        currentState = qualifiedRawState(sdkInt = 34)
        executor.armForNextLaunch(
            executionMode = ExecutionMode.REAL_MODE,
            options = WipeOptions(wipeExternalStorage = true, wipeEuicc = false),
            commonConsentChecked = true,
            realModeConsentChecked = true
        )
        val summary2 = executor.consumeAndExecuteOnce()
        assertEquals(AttemptOutcome.REFUSED, summary2.outcome)
        assertEquals("DPM_SECURITY_EXCEPTION", summary2.detailCode)
        assertEquals("Called once and never retried", 1, fakeProvider.callCount)

        // Consent remains consumed after failure
        val summary3 = executor.consumeAndExecuteOnce()
        assertEquals(AttemptOutcome.REFUSED, summary3.outcome)
        assertEquals("CONSENT_ALREADY_CONSUMED", summary3.detailCode)
        assertEquals(1, fakeProvider.callCount)
    }
}
