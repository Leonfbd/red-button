package pl.leon.redbutton.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import pl.leon.redbutton.execution.ArmedLaunchExecutor
import pl.leon.redbutton.execution.LaunchStepResult
import pl.leon.redbutton.model.AttemptReport
import pl.leon.redbutton.model.DeviceReadinessEvaluation
import pl.leon.redbutton.model.ExecutionMode
import pl.leon.redbutton.model.ExecutionSummary
import pl.leon.redbutton.model.SanitizationLevel
import pl.leon.redbutton.model.WipeOptions
import pl.leon.redbutton.policy.DiagnosticReportFormatter
import pl.leon.redbutton.storage.ArmConsentResult
import pl.leon.redbutton.storage.RedButtonStorage

enum class ScreenStage {
    INSPECTION_AND_CONSENT,
    ARMED_CLOSING,
    EXECUTION_OUTCOME
}

enum class ArmingErrorState {
    NONE,
    WRITE_FAILED,
    WRITE_UNCERTAIN
}

data class SavedSessionState(
    val stageName: String,
    val armedExecutionModeName: String?,
    val commonConsentChecked: Boolean = false,
    val realModeConsentChecked: Boolean = false
)

data class RedButtonUiState(
    val stage: ScreenStage = ScreenStage.INSPECTION_AND_CONSENT,
    val testModeEnabled: Boolean = false,
    val storageFallbackTriggered: Boolean = false,
    val options: WipeOptions = WipeOptions(wipeExternalStorage = true, wipeEuicc = false),
    val evaluation: DeviceReadinessEvaluation? = null,
    val commonConsentChecked: Boolean = false,
    val realModeConsentChecked: Boolean = false,
    val armingError: ArmingErrorState = ArmingErrorState.NONE,
    val armedExecutionMode: ExecutionMode? = null,
    val executionSummary: ExecutionSummary? = null,
    val lastAttemptReport: AttemptReport? = null,
    val showDiagnosticsPreview: Boolean = false,
    val diagnosticsCopied: Boolean = false,
    // Ephemeral manual checklist state (Section 6: never persisted, never used for wipe condition)
    val manualChecklist: Set<Int> = emptySet()
) {
    val activeExecutionMode: ExecutionMode
        get() = if (testModeEnabled) ExecutionMode.TEST_MODE else ExecutionMode.REAL_MODE

    val currentSanitizationLevel: SanitizationLevel
        get() = evaluation?.sanitizationLevelFor(options) ?: SanitizationLevel.LEVEL_0_UNSUPPORTED

    fun buildDiagnosticReport(): String {
        return DiagnosticReportFormatter.formatReport(
            activeMode = activeExecutionMode,
            storageFallbackTriggered = storageFallbackTriggered,
            options = options,
            evaluation = evaluation,
            executionSummary = executionSummary,
            lastAttemptReport = lastAttemptReport
        )
    }

    val canArm: Boolean
        get() {
            val eval = evaluation ?: return false
            if (!commonConsentChecked) return false
            return if (testModeEnabled) {
                true
            } else {
                eval.isReadyForRealMode && realModeConsentChecked
            }
        }
}

/**
 * Session controller preserving state across Activity recreations and process-death restores
 * (Section 9, Section 10).
 * Ensures that launch evaluation and one-time execution happen at most ONCE per launch session,
 * and that recreating the Activity while on ARMED_CLOSING or EXECUTION_OUTCOME never triggers
 * a second execution.
 */
class RedButtonSessionController(
    private val executor: ArmedLaunchExecutor,
    private val storage: RedButtonStorage
) {
    private var launchHandledInSession = false

    private val _uiState = MutableStateFlow(RedButtonUiState())
    val uiState: StateFlow<RedButtonUiState> = _uiState.asStateFlow()

    /**
     * Called on Activity creation. If already called in this session (e.g., screen rotation),
     * this is a no-op. If the Activity is restored after process death with a non-null
     * [savedState] in `ARMED_CLOSING` or `EXECUTION_OUTCOME`, restores the UI without
     * consuming or re-executing any action.
     */
    @Synchronized
    fun onLaunchOrRecreate(savedState: SavedSessionState? = null) {
        if (launchHandledInSession) {
            return
        }
        launchHandledInSession = true

        if (savedState != null) {
            val savedStage = try {
                ScreenStage.valueOf(savedState.stageName)
            } catch (_: IllegalArgumentException) {
                null
            }
            if (savedStage == ScreenStage.ARMED_CLOSING) {
                val mode = savedState.armedExecutionModeName?.let {
                    try {
                        ExecutionMode.valueOf(it)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                } ?: ExecutionMode.TEST_MODE
                _uiState.value = RedButtonUiState(
                    stage = ScreenStage.ARMED_CLOSING,
                    testModeEnabled = mode == ExecutionMode.TEST_MODE,
                    armedExecutionMode = mode,
                    lastAttemptReport = storage.readLastAttemptReport()
                )
                return
            } else if (savedStage == ScreenStage.EXECUTION_OUTCOME) {
                val report = storage.readLastAttemptReport()
                val inspected = executor.inspectUnarmed()
                val restoredSummary = if (report != null) {
                    ExecutionSummary(
                        outcome = report.outcome,
                        executionMode = report.executionMode,
                        selectedPath = report.selectedProviderPath,
                        effectiveFlagsMask = report.requestedFlagsMask,
                        effectiveFlagsNames = report.requestedFlagsNames,
                        sanitizationLevel = report.sanitizationLevel,
                        detailCode = report.detailCode,
                        detailMessage = report.detailMessage
                    )
                } else {
                    null
                }
                if (restoredSummary != null) {
                    _uiState.value = RedButtonUiState(
                        stage = ScreenStage.EXECUTION_OUTCOME,
                        testModeEnabled = restoredSummary.executionMode == ExecutionMode.TEST_MODE,
                        evaluation = inspected.evaluation,
                        executionSummary = restoredSummary,
                        lastAttemptReport = report
                    )
                    return
                }
            }
        }

        when (val result = executor.handleLaunch()) {
            is LaunchStepResult.OpenUnarmedInspection -> {
                val eval = result.evaluation
                val defaultOptions = WipeOptions(
                    wipeExternalStorage = eval.supportsExternalStorageFlag || true,
                    wipeEuicc = false
                )
                _uiState.value = RedButtonUiState(
                    stage = ScreenStage.INSPECTION_AND_CONSENT,
                    testModeEnabled = result.testModeState.testModeEnabled,
                    storageFallbackTriggered = result.testModeState.fallbackTriggered,
                    options = defaultOptions,
                    evaluation = eval,
                    commonConsentChecked = savedState?.commonConsentChecked ?: false,
                    realModeConsentChecked = if (eval.isReadyForRealMode) {
                        savedState?.realModeConsentChecked ?: false
                    } else {
                        false
                    },
                    lastAttemptReport = result.lastAttemptReport
                )
            }
            is LaunchStepResult.ExecutedArmedLaunch -> {
                _uiState.value = RedButtonUiState(
                    stage = ScreenStage.EXECUTION_OUTCOME,
                    testModeEnabled = result.summary.executionMode == ExecutionMode.TEST_MODE,
                    evaluation = result.currentEvaluation,
                    executionSummary = result.summary,
                    lastAttemptReport = result.lastAttemptReport
                )
            }
        }
    }

    fun snapshotSessionState(): SavedSessionState {
        val current = _uiState.value
        return SavedSessionState(
            stageName = current.stage.name,
            armedExecutionModeName = current.armedExecutionMode?.name,
            commonConsentChecked = current.commonConsentChecked,
            realModeConsentChecked = current.realModeConsentChecked
        )
    }

    fun refreshDeviceInspection() {
        val current = _uiState.value
        if (current.stage != ScreenStage.INSPECTION_AND_CONSENT) return
        val refreshed = executor.inspectUnarmed(current.options)
        val sanitizedEuicc = current.options.wipeEuicc && refreshed.evaluation.supportsEuiccFlag
        _uiState.update {
            it.copy(
                evaluation = refreshed.evaluation,
                options = it.options.copy(wipeEuicc = sanitizedEuicc),
                realModeConsentChecked = if (!refreshed.evaluation.isReadyForRealMode) {
                    false
                } else {
                    it.realModeConsentChecked
                },
                lastAttemptReport = refreshed.lastAttemptReport
            )
        }
    }

    fun setTestModeEnabled(enabled: Boolean) {
        val saved = storage.writeTestModePreference(enabled)
        _uiState.update {
            // If saving the preference failed and user tried to turn OFF test mode, keep safe fallback
            val effectiveEnabled = if (!saved && !enabled) true else enabled
            it.copy(
                testModeEnabled = effectiveEnabled,
                storageFallbackTriggered = if (!saved && !enabled) true else it.storageFallbackTriggered,
                armingError = ArmingErrorState.NONE,
                realModeConsentChecked = if (effectiveEnabled) false else it.realModeConsentChecked
            )
        }
    }

    fun setWipeExternalStorage(enabled: Boolean) {
        _uiState.update {
            it.copy(
                options = it.options.copy(wipeExternalStorage = enabled),
                armingError = ArmingErrorState.NONE
            )
        }
    }

    fun setWipeEuicc(enabled: Boolean) {
        _uiState.update {
            val allowed = enabled && (it.evaluation?.supportsEuiccFlag == true)
            it.copy(
                options = it.options.copy(wipeEuicc = allowed),
                armingError = ArmingErrorState.NONE
            )
        }
    }

    fun setCommonConsentChecked(checked: Boolean) {
        _uiState.update {
            it.copy(
                commonConsentChecked = checked,
                armingError = ArmingErrorState.NONE
            )
        }
    }

    fun setRealModeConsentChecked(checked: Boolean) {
        _uiState.update {
            it.copy(
                realModeConsentChecked = checked,
                armingError = ArmingErrorState.NONE
            )
        }
    }

    /**
     * Section 6: Ephemeral manual helper only. Never persisted and never affects readiness.
     */
    fun toggleManualChecklistItem(index: Int) {
        _uiState.update { state ->
            val updated = state.manualChecklist.toMutableSet()
            if (!updated.add(index)) {
                updated.remove(index)
            }
            state.copy(manualChecklist = updated)
        }
    }

    fun toggleDiagnosticsPreview() {
        _uiState.update { state ->
            state.copy(showDiagnosticsPreview = !state.showDiagnosticsPreview)
        }
    }

    fun markDiagnosticsCopied() {
        _uiState.update { state ->
            state.copy(diagnosticsCopied = true)
        }
    }

    /**
     * FR-02, FR-04, FR-10: Attempts to arm one-time action for the next launch.
     * Returns true ONLY if the consent record was durably persisted.
     */
    fun armAction(): Boolean {
        val state = _uiState.value
        if (!state.canArm) return false

        val mode = state.activeExecutionMode
        return when (
            executor.armForNextLaunch(
                executionMode = mode,
                options = state.options,
                commonConsentChecked = state.commonConsentChecked,
                realModeConsentChecked = state.realModeConsentChecked
            )
        ) {
            is ArmConsentResult.Armed -> {
                _uiState.update {
                    it.copy(
                        stage = ScreenStage.ARMED_CLOSING,
                        armedExecutionMode = mode,
                        armingError = ArmingErrorState.NONE
                    )
                }
                true
            }
            is ArmConsentResult.ConsentNotConfirmed -> {
                _uiState.update {
                    it.copy(armingError = ArmingErrorState.WRITE_FAILED)
                }
                false
            }
            is ArmConsentResult.WriteFailed -> {
                // FR-04: Durable write failure blocks closing the screen as armed
                _uiState.update {
                    it.copy(armingError = ArmingErrorState.WRITE_FAILED)
                }
                false
            }
            is ArmConsentResult.WriteUncertain -> {
                // FR-04: Uncertain write result warns that action might still be pending
                _uiState.update {
                    it.copy(armingError = ArmingErrorState.WRITE_UNCERTAIN)
                }
                false
            }
        }
    }

    /**
     * Transitions from the post-execution outcome screen back to unarmed inspection.
     * Does NOT un-consume the used consent; a new action requires full re-arming (FR-09).
     */
    fun returnToDisarmedInspection() {
        val refreshed = executor.inspectUnarmed()
        _uiState.value = RedButtonUiState(
            stage = ScreenStage.INSPECTION_AND_CONSENT,
            testModeEnabled = refreshed.testModeState.testModeEnabled,
            storageFallbackTriggered = refreshed.testModeState.fallbackTriggered,
            options = WipeOptions(
                wipeExternalStorage = refreshed.evaluation.supportsExternalStorageFlag || true,
                wipeEuicc = false
            ),
            evaluation = refreshed.evaluation,
            commonConsentChecked = false,
            realModeConsentChecked = false,
            lastAttemptReport = refreshed.lastAttemptReport
        )
    }
}
