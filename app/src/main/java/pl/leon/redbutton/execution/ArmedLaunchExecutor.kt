package pl.leon.redbutton.execution

import pl.leon.redbutton.model.AttemptOutcome
import pl.leon.redbutton.model.AttemptReport
import pl.leon.redbutton.model.BlockingReason
import pl.leon.redbutton.model.ConsentRecord
import pl.leon.redbutton.model.DeviceReadinessEvaluation
import pl.leon.redbutton.model.ExecutionMode
import pl.leon.redbutton.model.ExecutionSummary
import pl.leon.redbutton.model.HardwareIdentitySnapshot
import pl.leon.redbutton.model.SanitizationLevel
import pl.leon.redbutton.model.WipeApiPath
import pl.leon.redbutton.model.WipeFlags
import pl.leon.redbutton.model.WipeOptions
import pl.leon.redbutton.policy.DevicePolicyEvaluator
import pl.leon.redbutton.provider.DeviceStateInspector
import pl.leon.redbutton.provider.ExpectedExecutionSnapshot
import pl.leon.redbutton.provider.ProviderCallResult
import pl.leon.redbutton.provider.WipeExecutionProvider
import pl.leon.redbutton.storage.ArmConsentResult
import pl.leon.redbutton.storage.ConsumeConsentResult
import pl.leon.redbutton.storage.RedButtonStorage
import pl.leon.redbutton.storage.TestModeReadResult
import java.util.UUID

sealed class LaunchStepResult {
    data class OpenUnarmedInspection(
        val evaluation: DeviceReadinessEvaluation,
        val testModeState: TestModeReadResult,
        val lastAttemptReport: AttemptReport?
    ) : LaunchStepResult()

    data class ExecutedArmedLaunch(
        val summary: ExecutionSummary,
        val currentEvaluation: DeviceReadinessEvaluation?,
        val lastAttemptReport: AttemptReport?
    ) : LaunchStepResult()
}

/**
 * Coordinates first-launch inspection, arming, and one-time armed execution on the next launch.
 * Enforces FR-01 through FR-10 and Section 9 (recreation safety, fail-closed, no retry/fallback).
 */
class ArmedLaunchExecutor(
    private val storage: RedButtonStorage,
    private val inspector: DeviceStateInspector,
    private val evaluator: DevicePolicyEvaluator,
    private val wipeExecutionProvider: WipeExecutionProvider,
    private val clockEpochMs: () -> Long = { System.currentTimeMillis() }
) {

    /**
     * Evaluates app launch. If an unconsumed consent record exists, consumes it BEFORE
     * executive analysis or provider invocation and runs the one-time action.
     * If no unconsumed consent exists, performs non-destructive readiness inspection (FR-01).
     */
    @Synchronized
    fun handleLaunch(): LaunchStepResult {
        return when (val consumeResult = storage.consumePendingConsent()) {
            is ConsumeConsentResult.NoPendingConsent,
            is ConsumeConsentResult.AlreadyConsumed -> {
                inspectUnarmed()
            }
            is ConsumeConsentResult.CorruptedOrIncompatible -> {
                handleCorruptedConsent(
                    reason = consumeResult.reason,
                    storageStateUncertain = consumeResult.storageStateUncertain
                )
            }
            is ConsumeConsentResult.ConsumptionWriteFailed -> {
                handleConsumptionStorageError(
                    storageStateUncertain = consumeResult.storageStateUncertain
                )
            }
            is ConsumeConsentResult.Consumed -> {
                executeConsumedRecord(consumeResult.record)
            }
        }
    }

    /**
     * Explicit direct execution entry point for testing repeat/concurrent consumption attempts.
     * Unlike [handleLaunch] (which opens normal unarmed inspection when consent was already used
     * in a previous launch), this method returns a REFUSED ExecutionSummary if no unconsumed
     * consent is available.
     */
    @Synchronized
    fun consumeAndExecuteOnce(): ExecutionSummary {
        return when (val consumeResult = storage.consumePendingConsent()) {
            is ConsumeConsentResult.NoPendingConsent -> ExecutionSummary(
                outcome = AttemptOutcome.REFUSED,
                executionMode = ExecutionMode.TEST_MODE,
                selectedPath = WipeApiPath.NONE,
                effectiveFlagsMask = 0,
                effectiveFlagsNames = emptyList(),
                sanitizationLevel = SanitizationLevel.LEVEL_0_UNSUPPORTED,
                detailCode = "NO_PENDING_CONSENT",
                detailMessage = "No armed consent found in storage."
            )
            is ConsumeConsentResult.AlreadyConsumed -> ExecutionSummary(
                outcome = AttemptOutcome.REFUSED,
                executionMode = consumeResult.record.executionMode,
                selectedPath = WipeApiPath.NONE,
                effectiveFlagsMask = 0,
                effectiveFlagsNames = emptyList(),
                sanitizationLevel = SanitizationLevel.LEVEL_0_UNSUPPORTED,
                detailCode = "CONSENT_ALREADY_CONSUMED",
                detailMessage = "One-time consent has already been consumed and cannot be reused."
            )
            is ConsumeConsentResult.CorruptedOrIncompatible -> {
                val res = handleCorruptedConsent(
                    reason = consumeResult.reason,
                    storageStateUncertain = consumeResult.storageStateUncertain
                )
                res.summary
            }
            is ConsumeConsentResult.ConsumptionWriteFailed -> {
                val res = handleConsumptionStorageError(
                    storageStateUncertain = consumeResult.storageStateUncertain
                )
                res.summary
            }
            is ConsumeConsentResult.Consumed -> {
                executeConsumedRecord(consumeResult.record).summary
            }
        }
    }

    /**
     * FR-01: Non-destructive inspection. Never invokes wipeExecutionProvider.
     */
    fun inspectUnarmed(options: WipeOptions = WipeOptions()): LaunchStepResult.OpenUnarmedInspection {
        val now = clockEpochMs()
        val testModeState = storage.readTestModePreference()
        val evaluation = safeInspectAndEvaluate()
        val profile = evaluator.toDeviceProfile(evaluation, options, now)
        storage.saveDeviceProfile(profile)
        return LaunchStepResult.OpenUnarmedInspection(
            evaluation = evaluation,
            testModeState = testModeState,
            lastAttemptReport = storage.readLastAttemptReport()
        )
    }

    /**
     * FR-02, FR-03, FR-04: Arms one-time consent for the NEXT launch.
     * Never calls wipeData() or wipeDevice() during arming.
     */
    @Synchronized
    fun armForNextLaunch(
        executionMode: ExecutionMode,
        options: WipeOptions,
        commonConsentChecked: Boolean,
        realModeConsentChecked: Boolean
    ): ArmConsentResult {
        if (!commonConsentChecked) {
            return ArmConsentResult.ConsentNotConfirmed
        }
        if (executionMode == ExecutionMode.REAL_MODE && !realModeConsentChecked) {
            return ArmConsentResult.ConsentNotConfirmed
        }

        val currentEvaluation = safeInspectAndEvaluate()
        if (executionMode == ExecutionMode.REAL_MODE && !currentEvaluation.isReadyForRealMode) {
            return ArmConsentResult.ConsentNotConfirmed
        }

        // FR-08: WIPE_EUICC is only allowed on supported API 34+ configuration with opt-in
        val sanitizedEuicc = options.wipeEuicc && currentEvaluation.supportsEuiccFlag
        val sanitizedExt = options.wipeExternalStorage

        val record = ConsentRecord(
            schemaVersion = RedButtonStorage.CURRENT_CONSENT_SCHEMA_VERSION,
            tokenId = UUID.randomUUID().toString(),
            executionMode = executionMode,
            wipeExternalStorage = sanitizedExt,
            wipeEuicc = sanitizedEuicc,
            consentConfirmed = true,
            armedAtEpochMs = clockEpochMs(),
            armedSdkInt = currentEvaluation.sdkInt,
            armedManagementRole = currentEvaluation.managementRole,
            armedWipeApiPath = currentEvaluation.wipeApiPath,
            armedHasActiveWipePolicy = currentEvaluation.hasActiveWipePolicy,
            armedUserQualification = currentEvaluation.userQualification,
            hardwareIdentity = currentEvaluation.hardwareIdentity,
            consumed = false
        )

        val profile = evaluator.toDeviceProfile(
            evaluation = currentEvaluation,
            options = WipeOptions(wipeExternalStorage = sanitizedExt, wipeEuicc = sanitizedEuicc),
            timestampEpochMs = record.armedAtEpochMs
        )
        storage.saveDeviceProfile(profile)

        return storage.armConsent(record)
    }

    private fun executeConsumedRecord(record: ConsentRecord): LaunchStepResult.ExecutedArmedLaunch {
        val now = clockEpochMs()
        val testModePref = storage.readTestModePreference()
        // FR-03: If storage read error triggered test mode fallback, force TEST_MODE!
        val effectiveMode = if (testModePref.fallbackTriggered) {
            ExecutionMode.TEST_MODE
        } else {
            record.executionMode
        }

        val currentEvaluation = try {
            val raw = inspector.inspectCurrentState()
            evaluator.evaluate(raw)
        } catch (e: Exception) {
            val summary = ExecutionSummary(
                outcome = AttemptOutcome.ERROR,
                executionMode = effectiveMode,
                selectedPath = WipeApiPath.NONE,
                effectiveFlagsMask = 0,
                effectiveFlagsNames = emptyList(),
                sanitizationLevel = SanitizationLevel.LEVEL_0_UNSUPPORTED,
                detailCode = "INSPECTION_EXCEPTION:${e.javaClass.simpleName}",
                detailMessage = e.message ?: "Exception during device state inspection."
            )
            val report = buildAttemptReport(
                timestampEpochMs = now,
                identity = record.hardwareIdentity,
                sdkInt = record.armedSdkInt,
                summary = summary
            )
            storage.saveAttemptReport(report)
            return LaunchStepResult.ExecutedArmedLaunch(
                summary = summary,
                currentEvaluation = null,
                lastAttemptReport = report
            )
        }

        val requestedOptions = WipeOptions(
            wipeExternalStorage = record.wipeExternalStorage,
            wipeEuicc = record.wipeEuicc
        )
        storage.saveDeviceProfile(
            evaluator.toDeviceProfile(currentEvaluation, requestedOptions, now)
        )

        val continuityFailure = checkContinuity(record, currentEvaluation)
        val effectiveFlags = currentEvaluation.computeEffectiveFlags(requestedOptions)
        val effectiveFlagNames = WipeFlags.formatFlags(effectiveFlags)
        val sanitizationLevel = currentEvaluation.sanitizationLevelFor(requestedOptions)

        // FR-03: TEST MODE never invokes wipeExecutionProvider.requestWipe()
        if (effectiveMode == ExecutionMode.TEST_MODE) {
            val simDetailCode = when {
                testModePref.fallbackTriggered -> "SIMULATED_FALLBACK_TEST_MODE"
                continuityFailure != null -> "SIMULATED_WOULD_BLOCK:$continuityFailure"
                !currentEvaluation.isReadyForRealMode ->
                    "SIMULATED_WOULD_BLOCK:${currentEvaluation.blockingReasons.joinToString(",")}"
                else -> "SIMULATED_READY:${currentEvaluation.wipeApiPath.name}"
            }
            val simDetailMsg = buildString {
                append("TEST MODE simulation completed. Production API was NOT called. ")
                if (continuityFailure != null) {
                    append("Continuity check would block REAL MODE ($continuityFailure). ")
                } else if (!currentEvaluation.isReadyForRealMode) {
                    append("Readiness check would block REAL MODE (${currentEvaluation.blockingReasons.joinToString(", ")}). ")
                } else {
                    append("Device is qualified via ${currentEvaluation.wipeApiPath.technicalName}. ")
                }
                append("Flags: ${if (effectiveFlagNames.isEmpty()) "NONE" else effectiveFlagNames.joinToString(", ")}.")
            }

            val summary = ExecutionSummary(
                outcome = AttemptOutcome.SIMULATED,
                executionMode = ExecutionMode.TEST_MODE,
                selectedPath = currentEvaluation.wipeApiPath,
                effectiveFlagsMask = effectiveFlags,
                effectiveFlagsNames = effectiveFlagNames,
                sanitizationLevel = sanitizationLevel,
                detailCode = simDetailCode,
                detailMessage = simDetailMsg,
                blockingReasons = currentEvaluation.blockingReasons
            )
            val report = buildAttemptReport(
                timestampEpochMs = now,
                identity = currentEvaluation.hardwareIdentity,
                sdkInt = currentEvaluation.sdkInt,
                summary = summary
            )
            storage.saveAttemptReport(report)
            return LaunchStepResult.ExecutedArmedLaunch(
                summary = summary,
                currentEvaluation = currentEvaluation,
                lastAttemptReport = report
            )
        }

        // REAL MODE execution path (FR-04, FR-05, FR-06, FR-07, FR-08, FR-09)
        if (!record.consentConfirmed) {
            return refuseRealMode(
                now = now,
                evaluation = currentEvaluation,
                code = "CONSENT_NOT_CONFIRMED",
                message = "Consent confirmation flag was false."
            )
        }

        if (continuityFailure != null) {
            return refuseRealMode(
                now = now,
                evaluation = currentEvaluation,
                code = continuityFailure,
                message = "Device identity, API, role, policy, or flag support changed since arming ($continuityFailure)."
            )
        }

        if (!currentEvaluation.isReadyForRealMode) {
            val reasons = currentEvaluation.blockingReasons.joinToString(",") { it.name }
            return refuseRealMode(
                now = now,
                evaluation = currentEvaluation,
                code = "BLOCKED_NOT_READY:$reasons",
                message = "Device failed readiness checks: $reasons"
            )
        }

        // FR-05 & FR-07: Provider must re-detect current state and verify no change immediately before the request
        val finalEvaluation = try {
            evaluator.evaluate(inspector.inspectCurrentState())
        } catch (e: Exception) {
            return refuseRealMode(
                now = now,
                evaluation = currentEvaluation,
                code = "PRE_CALL_REINSPECTION_ERROR:${e.javaClass.simpleName}",
                message = "Failed to re-verify device state immediately before DPM request."
            )
        }

        val finalContinuityFailure = checkContinuity(record, finalEvaluation)
        if (finalContinuityFailure != null || !finalEvaluation.isReadyForRealMode) {
            val code = finalContinuityFailure
                ?: "PRE_CALL_NOT_READY:${finalEvaluation.blockingReasons.joinToString(",")}"
            return refuseRealMode(
                now = now,
                evaluation = finalEvaluation,
                code = code,
                message = "Device or policy state changed immediately before DPM request ($code)."
            )
        }

        val finalFlags = finalEvaluation.computeEffectiveFlags(requestedOptions)
        val finalFlagNames = WipeFlags.formatFlags(finalFlags)
        val finalLevel = finalEvaluation.sanitizationLevelFor(requestedOptions)

        // FR-08 defensive check: never allow forbidden flags
        if (WipeFlags.containsForbiddenFlags(finalFlags)) {
            return refuseRealMode(
                now = now,
                evaluation = finalEvaluation,
                code = "FORBIDDEN_FLAGS_BLOCKED",
                message = "Forbidden wipe flags detected and blocked."
            )
        }

        // FR-09: Write local attempt report BEFORE invoking native DPM wipe (and fail closed if report write fails)
        val preCallReport = AttemptReport(
            timestampEpochMs = now,
            makeAndModel = "${finalEvaluation.hardwareIdentity.manufacturer} ${finalEvaluation.hardwareIdentity.model}".trim(),
            androidRelease = finalEvaluation.hardwareIdentity.osRelease,
            sdkInt = finalEvaluation.sdkInt,
            executionMode = ExecutionMode.REAL_MODE,
            selectedProviderPath = finalEvaluation.wipeApiPath,
            requestedFlagsMask = finalFlags,
            requestedFlagsNames = finalFlagNames,
            sanitizationLevel = finalLevel,
            outcome = AttemptOutcome.REFUSED,
            detailCode = "PRE_CALL_PENDING",
            detailMessage = "Pre-execution attempt record created."
        )
        if (!storage.saveAttemptReport(preCallReport)) {
            val summary = ExecutionSummary(
                outcome = AttemptOutcome.REFUSED,
                executionMode = ExecutionMode.REAL_MODE,
                selectedPath = WipeApiPath.NONE,
                effectiveFlagsMask = 0,
                effectiveFlagsNames = emptyList(),
                sanitizationLevel = SanitizationLevel.LEVEL_0_UNSUPPORTED,
                detailCode = "LOCAL_REPORT_WRITE_FAILED",
                detailMessage = "Failed to persist local attempt report; refusing wipe request (fail-closed).",
                blockingReasons = finalEvaluation.blockingReasons
            )
            return LaunchStepResult.ExecutedArmedLaunch(
                summary = summary,
                currentEvaluation = finalEvaluation,
                lastAttemptReport = null
            )
        }

        // Single native DPM call — no retry, no fallback (FR-05, FR-07, FR-09)
        val expectedSnapshot = ExpectedExecutionSnapshot(
            sdkInt = record.armedSdkInt,
            managementRole = record.armedManagementRole,
            wipeApiPath = record.armedWipeApiPath,
            hasActiveWipePolicy = record.armedHasActiveWipePolicy,
            userQualification = record.armedUserQualification,
            hardwareIdentity = record.hardwareIdentity
        )
        val providerResult = try {
            wipeExecutionProvider.requestWipe(
                path = finalEvaluation.wipeApiPath,
                flags = finalFlags,
                expectedSnapshot = expectedSnapshot
            )
        } catch (t: Throwable) {
            ProviderCallResult.FailedWithError(
                code = "PROVIDER_UNCAUGHT_${t.javaClass.simpleName}",
                message = t.message ?: "Uncaught exception from wipe provider"
            )
        }

        val summary = when (providerResult) {
            is ProviderCallResult.Accepted -> ExecutionSummary(
                outcome = AttemptOutcome.WIPE_REQUESTED,
                executionMode = ExecutionMode.REAL_MODE,
                selectedPath = finalEvaluation.wipeApiPath,
                effectiveFlagsMask = finalFlags,
                effectiveFlagsNames = finalFlagNames,
                sanitizationLevel = finalLevel,
                detailCode = "WIPE_REQUESTED_OK",
                detailMessage = "Android DPM call (${finalEvaluation.wipeApiPath.technicalName}) returned without refusal. This does NOT confirm completion of the reset or physical erasure of all flash cells.",
                blockingReasons = emptyList()
            )
            is ProviderCallResult.Refused -> ExecutionSummary(
                outcome = AttemptOutcome.REFUSED,
                executionMode = ExecutionMode.REAL_MODE,
                selectedPath = finalEvaluation.wipeApiPath,
                effectiveFlagsMask = finalFlags,
                effectiveFlagsNames = finalFlagNames,
                sanitizationLevel = SanitizationLevel.LEVEL_0_UNSUPPORTED,
                detailCode = providerResult.code,
                detailMessage = providerResult.message,
                blockingReasons = finalEvaluation.blockingReasons
            )
            is ProviderCallResult.FailedWithError -> ExecutionSummary(
                outcome = AttemptOutcome.ERROR,
                executionMode = ExecutionMode.REAL_MODE,
                selectedPath = finalEvaluation.wipeApiPath,
                effectiveFlagsMask = finalFlags,
                effectiveFlagsNames = finalFlagNames,
                sanitizationLevel = SanitizationLevel.LEVEL_0_UNSUPPORTED,
                detailCode = providerResult.code,
                detailMessage = providerResult.message,
                blockingReasons = finalEvaluation.blockingReasons
            )
        }

        val finalReport = buildAttemptReport(
            timestampEpochMs = now,
            identity = finalEvaluation.hardwareIdentity,
            sdkInt = finalEvaluation.sdkInt,
            summary = summary
        )
        storage.saveAttemptReport(finalReport)

        return LaunchStepResult.ExecutedArmedLaunch(
            summary = summary,
            currentEvaluation = finalEvaluation,
            lastAttemptReport = finalReport
        )
    }

    private fun checkContinuity(
        record: ConsentRecord,
        current: DeviceReadinessEvaluation
    ): String? {
        // FR-05: Hardware-family identity check (at least 3 known matching fields; OS update / patch tolerated)
        val idMatch = record.hardwareIdentity.matchesHardwareFamily(current.hardwareIdentity)
        if (!idMatch.matches) {
            return if (idMatch.insufficientKnownFields) {
                "IDENTITY_INSUFFICIENT_KNOWN_FIELDS"
            } else {
                "IDENTITY_HARDWARE_MISMATCH:${idMatch.mismatchedFields.joinToString(",")}"
            }
        }

        // FR-07: Change of API, role, policy, path, or user between arming and execution blocks the request
        if (current.sdkInt != record.armedSdkInt) {
            return "CONTINUITY_API_CHANGED:${record.armedSdkInt}->${current.sdkInt}"
        }
        if (current.managementRole != record.armedManagementRole) {
            return "CONTINUITY_ROLE_CHANGED:${record.armedManagementRole}->${current.managementRole}"
        }
        if (current.wipeApiPath != record.armedWipeApiPath) {
            return "CONTINUITY_PATH_CHANGED:${record.armedWipeApiPath}->${current.wipeApiPath}"
        }
        if (current.hasActiveWipePolicy != record.armedHasActiveWipePolicy) {
            return "CONTINUITY_POLICY_CHANGED:${record.armedHasActiveWipePolicy}->${current.hasActiveWipePolicy}"
        }
        if (current.userQualification != record.armedUserQualification) {
            return "CONTINUITY_USER_CHANGED:${record.armedUserQualification}->${current.userQualification}"
        }

        // FR-08: If eSIM wipe was selected, verify eUICC flag is still supported
        if (record.wipeEuicc && !current.supportsEuiccFlag) {
            return "CONTINUITY_EUICC_UNSUPPORTED"
        }

        return null
    }

    private fun refuseRealMode(
        now: Long,
        evaluation: DeviceReadinessEvaluation,
        code: String,
        message: String
    ): LaunchStepResult.ExecutedArmedLaunch {
        val summary = ExecutionSummary(
            outcome = AttemptOutcome.REFUSED,
            executionMode = ExecutionMode.REAL_MODE,
            selectedPath = WipeApiPath.NONE,
            effectiveFlagsMask = 0,
            effectiveFlagsNames = emptyList(),
            sanitizationLevel = SanitizationLevel.LEVEL_0_UNSUPPORTED,
            detailCode = code,
            detailMessage = message,
            blockingReasons = evaluation.blockingReasons
        )
        val report = buildAttemptReport(
            timestampEpochMs = now,
            identity = evaluation.hardwareIdentity,
            sdkInt = evaluation.sdkInt,
            summary = summary
        )
        storage.saveAttemptReport(report)
        return LaunchStepResult.ExecutedArmedLaunch(
            summary = summary,
            currentEvaluation = evaluation,
            lastAttemptReport = report
        )
    }

    private fun handleCorruptedConsent(
        reason: String,
        storageStateUncertain: Boolean
    ): LaunchStepResult.ExecutedArmedLaunch {
        val now = clockEpochMs()
        val eval = safeInspectAndEvaluate()
        val summary = ExecutionSummary(
            outcome = AttemptOutcome.REFUSED,
            executionMode = ExecutionMode.TEST_MODE,
            selectedPath = WipeApiPath.NONE,
            effectiveFlagsMask = 0,
            effectiveFlagsNames = emptyList(),
            sanitizationLevel = SanitizationLevel.LEVEL_0_UNSUPPORTED,
            detailCode = "CONSENT_INVALIDATED:$reason",
            detailMessage = "Stored consent record was incomplete, corrupted, or had an incompatible schema ($reason). It was invalidated without calling any wipe API.",
            storageStateUncertain = storageStateUncertain,
            blockingReasons = eval.blockingReasons
        )
        val report = buildAttemptReport(
            timestampEpochMs = now,
            identity = eval.hardwareIdentity,
            sdkInt = eval.sdkInt,
            summary = summary
        )
        storage.saveAttemptReport(report)
        return LaunchStepResult.ExecutedArmedLaunch(
            summary = summary,
            currentEvaluation = eval,
            lastAttemptReport = report
        )
    }

    private fun handleConsumptionStorageError(
        storageStateUncertain: Boolean
    ): LaunchStepResult.ExecutedArmedLaunch {
        val now = clockEpochMs()
        val eval = safeInspectAndEvaluate()
        val summary = ExecutionSummary(
            outcome = AttemptOutcome.REFUSED,
            executionMode = ExecutionMode.TEST_MODE,
            selectedPath = WipeApiPath.NONE,
            effectiveFlagsMask = 0,
            effectiveFlagsNames = emptyList(),
            sanitizationLevel = SanitizationLevel.LEVEL_0_UNSUPPORTED,
            detailCode = "CONSENT_CONSUMPTION_STORAGE_ERROR",
            detailMessage = "Failed to durably mark one-time consent as consumed in local storage. Wipe request was refused (fail-closed).",
            storageStateUncertain = storageStateUncertain,
            blockingReasons = eval.blockingReasons
        )
        val report = buildAttemptReport(
            timestampEpochMs = now,
            identity = eval.hardwareIdentity,
            sdkInt = eval.sdkInt,
            summary = summary
        )
        storage.saveAttemptReport(report)
        return LaunchStepResult.ExecutedArmedLaunch(
            summary = summary,
            currentEvaluation = eval,
            lastAttemptReport = report
        )
    }

    private fun safeInspectAndEvaluate(): DeviceReadinessEvaluation {
        return try {
            evaluator.evaluate(inspector.inspectCurrentState())
        } catch (e: Exception) {
            val emptyHw = HardwareIdentitySnapshot(
                manufacturer = "unknown",
                brand = "unknown",
                model = "unknown",
                device = "unknown",
                board = "unknown",
                hardware = "unknown",
                product = "unknown"
            )
            DeviceReadinessEvaluation(
                sdkInt = 0,
                hardwareIdentity = emptyHw,
                managementRole = pl.leon.redbutton.model.ManagementRole.NONE,
                hasActiveWipePolicy = false,
                hasParentProfileDpm = false,
                userQualification = pl.leon.redbutton.model.UserQualification.UNKNOWN,
                lockScreenStatus = pl.leon.redbutton.model.LockScreenStatus.UNKNOWN,
                encryptionStatus = pl.leon.redbutton.model.EncryptionStatus.UNKNOWN,
                euiccFeatureStatus = pl.leon.redbutton.model.EuiccFeatureStatus.UNKNOWN_ERROR,
                wipeApiPath = WipeApiPath.NONE,
                supportsExternalStorageFlag = false,
                supportsEuiccFlag = false,
                canRequestAdminActivation = false,
                blockingReasons = listOf(
                    BlockingReason.NO_ACTIVE_ADMIN_OR_POLICY,
                    BlockingReason.LOCK_SCREEN_STATUS_UNKNOWN,
                    BlockingReason.ENCRYPTION_STATUS_UNKNOWN,
                    BlockingReason.INSUFFICIENT_HARDWARE_IDENTITY_FIELDS
                ),
                warnings = listOf("INSPECTOR_FAILURE:${e.javaClass.simpleName}")
            )
        }
    }

    private fun buildAttemptReport(
        timestampEpochMs: Long,
        identity: HardwareIdentitySnapshot,
        sdkInt: Int,
        summary: ExecutionSummary
    ): AttemptReport {
        val makeModel = "${identity.manufacturer} ${identity.model}".trim().ifEmpty { "unknown" }
        return AttemptReport(
            timestampEpochMs = timestampEpochMs,
            makeAndModel = makeModel,
            androidRelease = identity.osRelease.ifEmpty { "unknown" },
            sdkInt = sdkInt,
            executionMode = summary.executionMode,
            selectedProviderPath = summary.selectedPath,
            requestedFlagsMask = summary.effectiveFlagsMask,
            requestedFlagsNames = summary.effectiveFlagsNames,
            sanitizationLevel = summary.sanitizationLevel,
            outcome = summary.outcome,
            detailCode = summary.detailCode,
            detailMessage = summary.detailMessage
        )
    }
}
