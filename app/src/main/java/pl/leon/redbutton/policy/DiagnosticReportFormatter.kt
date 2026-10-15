package pl.leon.redbutton.policy

import pl.leon.redbutton.model.AttemptReport
import pl.leon.redbutton.model.DeviceReadinessEvaluation
import pl.leon.redbutton.model.ExecutionMode
import pl.leon.redbutton.model.ExecutionSummary
import pl.leon.redbutton.model.SanitizationLevel
import pl.leon.redbutton.model.WipeFlags
import pl.leon.redbutton.model.WipeOptions

/**
 * Formats a local, privacy-safe technical diagnostic report (Section 8, Section 11).
 * Contains only public Android readiness metadata and the single last attempt report.
 * Never includes user content, credentials, IMEI, serial numbers, or volume IDs.
 */
object DiagnosticReportFormatter {

    fun formatReport(
        activeMode: ExecutionMode,
        storageFallbackTriggered: Boolean,
        options: WipeOptions,
        evaluation: DeviceReadinessEvaluation?,
        executionSummary: ExecutionSummary?,
        lastAttemptReport: AttemptReport?
    ): String {
        return buildString {
            appendLine("=== RED BUTTON DIAGNOSTIC REPORT ===")
            appendLine("ApplicationId: pl.leon.redbutton")
            appendLine("ActiveMode: ${activeMode.name}")
            appendLine("StorageFallbackForcedTestMode: $storageFallbackTriggered")
            appendLine(
                "SelectedOptions: WIPE_EXTERNAL_STORAGE=${options.wipeExternalStorage}, WIPE_EUICC=${options.wipeEuicc}"
            )

            appendLine()
            appendLine("--- DEVICE & POLICY READINESS ---")
            if (evaluation == null) {
                appendLine("Evaluation: UNAVAILABLE")
            } else {
                val hw = evaluation.hardwareIdentity
                val knownCount = hw.knownHardwareFields().size
                val level: SanitizationLevel = evaluation.sanitizationLevelFor(options)
                val effectiveFlags = evaluation.computeEffectiveFlags(options)
                val flagNames = WipeFlags.formatFlags(effectiveFlags)

                appendLine("ManufacturerModel: ${hw.manufacturer} ${hw.model}".trim())
                appendLine(
                    "HardwareFamily: brand=${hw.brand}, device=${hw.device}, board=${hw.board}, hardware=${hw.hardware}, product=${hw.product}"
                )
                appendLine("KnownHardwareFieldsCount: $knownCount/7 (min 3)")
                appendLine("AndroidRelease: ${hw.osRelease.ifEmpty { "unknown" }} (API ${evaluation.sdkInt})")
                appendLine("SecurityPatch: ${hw.securityPatch.ifEmpty { "unknown" }}")
                appendLine("BuildFingerprint: ${hw.buildFingerprint.ifEmpty { "unknown" }}")
                appendLine("ManagementRole: ${evaluation.managementRole.name}")
                appendLine("ActiveWipePolicy: ${evaluation.hasActiveWipePolicy}")
                appendLine("ParentProfileDpmAvailable: ${evaluation.hasParentProfileDpm}")
                appendLine("UserQualification: ${evaluation.userQualification.name}")
                appendLine("PublicDpmPath: ${evaluation.wipeApiPath.name} (${evaluation.wipeApiPath.technicalName})")
                appendLine("LockScreenStatus: ${evaluation.lockScreenStatus.name}")
                appendLine("EncryptionStatus: ${evaluation.encryptionStatus.name}")
                appendLine("EuiccFeatureStatus: ${evaluation.euiccFeatureStatus.name}")
                appendLine(
                    "SupportedFlags: WIPE_EXTERNAL_STORAGE=${evaluation.supportsExternalStorageFlag}, WIPE_EUICC=${evaluation.supportsEuiccFlag}"
                )
                appendLine(
                    "EffectiveFlags: mask=$effectiveFlags (${if (flagNames.isEmpty()) "NONE" else flagNames.joinToString(",")})"
                )
                appendLine("ReadyForRealMode: ${evaluation.isReadyForRealMode}")
                appendLine("RequestClassificationLevel: Level ${level.levelNumber} (${level.name})")
                appendLine(
                    "BlockingReasons: ${if (evaluation.blockingReasons.isEmpty()) "NONE" else evaluation.blockingReasons.joinToString(",") { it.name }}"
                )
                appendLine(
                    "InspectionWarnings: ${if (evaluation.warnings.isEmpty()) "NONE" else evaluation.warnings.joinToString(",")}"
                )
            }

            if (executionSummary != null) {
                appendLine()
                appendLine("--- CURRENT SESSION EXECUTION SUMMARY ---")
                appendLine("Outcome: ${executionSummary.outcome.statusLabel}")
                appendLine("ExecutionMode: ${executionSummary.executionMode.name}")
                appendLine("SelectedPath: ${executionSummary.selectedPath.technicalName}")
                appendLine(
                    "EffectiveFlags: ${if (executionSummary.effectiveFlagsNames.isEmpty()) "NONE" else executionSummary.effectiveFlagsNames.joinToString(",")}"
                )
                appendLine("ClassificationLevel: Level ${executionSummary.sanitizationLevel.levelNumber}")
                appendLine("DetailCode: ${executionSummary.detailCode}")
                appendLine("StorageStateUncertain: ${executionSummary.storageStateUncertain}")
            }

            appendLine()
            appendLine("--- LAST LOCAL ATTEMPT REPORT ---")
            if (lastAttemptReport == null) {
                appendLine("LastAttempt: NONE")
            } else {
                appendLine("TimestampEpochMs: ${lastAttemptReport.timestampEpochMs}")
                appendLine("Device: ${lastAttemptReport.makeAndModel} (Android ${lastAttemptReport.androidRelease}, API ${lastAttemptReport.sdkInt})")
                appendLine("ExecutionMode: ${lastAttemptReport.executionMode.name}")
                appendLine("SelectedProviderPath: ${lastAttemptReport.selectedProviderPath.technicalName}")
                appendLine(
                    "RequestedFlags: mask=${lastAttemptReport.requestedFlagsMask} (${if (lastAttemptReport.requestedFlagsNames.isEmpty()) "NONE" else lastAttemptReport.requestedFlagsNames.joinToString(",")})"
                )
                appendLine("ClassificationLevel: Level ${lastAttemptReport.sanitizationLevel.levelNumber}")
                appendLine("Outcome: ${lastAttemptReport.outcome.statusLabel}")
                appendLine("DetailCode: ${lastAttemptReport.detailCode}")
            }

            appendLine()
            appendLine("Disclaimer: Status WIPE REQUESTED confirms only that the public Android API call returned without refusal. It does not confirm completed reset or physical flash erasure.")
        }.trimEnd()
    }
}
