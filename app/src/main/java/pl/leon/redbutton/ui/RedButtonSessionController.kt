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
    // Ephemeral manual checklist state (Section 6: never persisted, never used for wipe condition)
    val manualChecklist: Set<Int> = emptySet()
) {
    val activeExecutionMode: ExecutionMode
        get() = if (testModeEnabled) ExecutionMode.TEST_MODE else ExecutionMode.REAL_MODE

    val currentSanitizationLevel: SanitizationLevel
        get() = evaluation?.sanitizationLevelFor(options) ?: SanitizationLevel.LEVEL_0_UNSUPPORTED

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
 * Session controller preserving state across Activity recreations (Section 9, Section 10).
 * Ensures that launch evaluation and one-time execution happen at most ONCE per launch session.
 */
class RedButtonSessionController(
    private val executor: ArmedLaunchExecutor,
    private val storage: RedButtonStorage
) {
    private var launchHandledInSession = false

    private val _uiState = MutableStateFlow(RedButtonUiState())
    val uiState: StateFlow<RedButtonUiState> = _uiState.asStateFlow()

    /**
     * Called on Activity creation. If already called in this session (e.g., screen rotation /
     * Activity recreation), this is a no-op so the action is never re-executed.
     */
    @Synchronized
    fun onLaunchOrRecreate() {
        if (launchHandledInSession) {
            return
        }
        launchHandledInSession = true

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

    fun refreshDeviceInspection() {
        val current = _uiState.value
        if (current.stage != ScreenStage.INSPECTION_AND_CONSENT) return
        val refreshed = executor.inspectUnarmed(current.options)
        val sanitizedEuicc = current.options.wipeEuicc && refreshed.evaluation.supportsEuiccFlag
        _uiState.update {
            it.copy(
                evaluation = refreshed.evaluation,
                options = it.options.copy(wipeEuicc = sanitizedEuicc),
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
