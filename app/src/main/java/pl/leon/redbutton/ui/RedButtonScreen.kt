package pl.leon.redbutton.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.leon.redbutton.R
import pl.leon.redbutton.model.AttemptOutcome
import pl.leon.redbutton.model.AttemptReport
import pl.leon.redbutton.model.BlockingReason
import pl.leon.redbutton.model.DeviceReadinessEvaluation
import pl.leon.redbutton.model.EuiccFeatureStatus
import pl.leon.redbutton.model.ExecutionMode
import pl.leon.redbutton.model.ExecutionSummary
import pl.leon.redbutton.model.SanitizationLevel

private val RedButtonDarkColorScheme = darkColorScheme(
    primary = Color(0xFFE53935),
    onPrimary = Color.White,
    secondary = Color(0xFF90CAF9),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onBackground = Color(0xFFF5F5F5),
    onSurface = Color(0xFFF5F5F5)
)

@Composable
fun RedButtonTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RedButtonDarkColorScheme,
        content = content
    )
}

@Composable
fun RedButtonApp(
    controller: RedButtonSessionController,
    onRequestDeviceAdmin: () -> Unit,
    onCloseApplication: () -> Unit
) {
    val state by controller.uiState.collectAsState()
    val clipboardManager = LocalClipboardManager.current

    RedButtonTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            when (state.stage) {
                ScreenStage.INSPECTION_AND_CONSENT -> {
                    InspectionAndConsentScreen(
                        state = state,
                        onToggleTestMode = controller::setTestModeEnabled,
                        onToggleExternalStorage = controller::setWipeExternalStorage,
                        onToggleEuicc = controller::setWipeEuicc,
                        onToggleCommonConsent = controller::setCommonConsentChecked,
                        onToggleRealConsent = controller::setRealModeConsentChecked,
                        onToggleManualChecklist = controller::toggleManualChecklistItem,
                        onToggleDiagnosticsPreview = controller::toggleDiagnosticsPreview,
                        onCopyDiagnostics = {
                            val reportText = state.buildDiagnosticReport()
                            clipboardManager.setText(AnnotatedString(reportText))
                            controller.markDiagnosticsCopied()
                        },
                        onRefresh = controller::refreshDeviceInspection,
                        onRequestDeviceAdmin = onRequestDeviceAdmin,
                        onArm = {
                            val armed = controller.armAction()
                            if (armed) {
                                onCloseApplication()
                            }
                        }
                    )
                }
                ScreenStage.ARMED_CLOSING -> {
                    ArmedClosingScreen(
                        armedMode = state.armedExecutionMode ?: state.activeExecutionMode,
                        onCloseApplication = onCloseApplication
                    )
                }
                ScreenStage.EXECUTION_OUTCOME -> {
                    val summary = state.executionSummary
                    if (summary != null) {
                        ExecutionOutcomeScreen(
                            state = state,
                            summary = summary,
                            lastReport = state.lastAttemptReport,
                            onToggleDiagnosticsPreview = controller::toggleDiagnosticsPreview,
                            onCopyDiagnostics = {
                                val reportText = state.buildDiagnosticReport()
                                clipboardManager.setText(AnnotatedString(reportText))
                                controller.markDiagnosticsCopied()
                            },
                            onReturnToInspection = controller::returnToDisarmedInspection
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectionAndConsentScreen(
    state: RedButtonUiState,
    onToggleTestMode: (Boolean) -> Unit,
    onToggleExternalStorage: (Boolean) -> Unit,
    onToggleEuicc: (Boolean) -> Unit,
    onToggleCommonConsent: (Boolean) -> Unit,
    onToggleRealConsent: (Boolean) -> Unit,
    onToggleManualChecklist: (Int) -> Unit,
    onToggleDiagnosticsPreview: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onRefresh: () -> Unit,
    onRequestDeviceAdmin: () -> Unit,
    onArm: () -> Unit
) {
    val scrollState = rememberScrollState()
    val eval = state.evaluation

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header & Mode Banner (Section 6)
        Text(
            text = stringResource(R.string.app_name),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.semantics { heading() }
        )

        ModeAndReadinessBanner(state = state)

        if (state.storageFallbackTriggered) {
            WarningBanner(
                text = stringResource(R.string.storage_fallback_warning),
                tag = "storage_fallback_banner"
            )
        }

        // Core Limitation Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2B1D1D)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = stringResource(R.string.core_limitation_title),
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF8A80),
                    fontSize = 16.sp,
                    modifier = Modifier.semantics { heading() }
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.core_limitation_body),
                    fontSize = 14.sp,
                    color = Color(0xFFEEEEEE)
                )
            }
        }

        // Device & Policy Readiness Section
        if (eval != null) {
            DeviceInspectionCard(
                eval = eval,
                currentLevel = state.currentSanitizationLevel,
                onRefresh = onRefresh,
                onRequestDeviceAdmin = onRequestDeviceAdmin
            )

            if (eval.blockingReasons.isNotEmpty()) {
                BlockingReasonsCard(blockingReasons = eval.blockingReasons)
            }
        }

        // Execution Mode & Options (FR-03, FR-08)
        ModeAndOptionsCard(
            state = state,
            onToggleTestMode = onToggleTestMode,
            onToggleExternalStorage = onToggleExternalStorage,
            onToggleEuicc = onToggleEuicc
        )

        // Informed Consent Section (FR-02, FR-04, FR-10)
        InformedConsentCard(
            state = state,
            onToggleCommonConsent = onToggleCommonConsent,
            onToggleRealConsent = onToggleRealConsent,
            onArm = onArm
        )

        // Manual Ephemeral Theft Readiness Checklist (Section 6)
        ManualChecklistCard(
            checkedItems = state.manualChecklist,
            onToggleItem = onToggleManualChecklist
        )

        // Last Local Attempt Report (Section 8)
        LastAttemptReportCard(report = state.lastAttemptReport)

        // Technical Diagnostics Export / Preview (Section 8, Section 11)
        TechnicalDiagnosticsCard(
            state = state,
            onTogglePreview = onToggleDiagnosticsPreview,
            onCopyDiagnostics = onCopyDiagnostics
        )
    }
}

@Composable
private fun ModeAndReadinessBanner(state: RedButtonUiState) {
    val isTest = state.testModeEnabled
    val isReadyReal = state.evaluation?.isReadyForRealMode == true

    val modeBg = if (isTest) Color(0xFF1565C0) else Color(0xFFC62828)
    val modeLabel = if (isTest) {
        stringResource(R.string.mode_test_badge)
    } else {
        stringResource(R.string.mode_real_badge)
    }
    val modeDesc = if (isTest) {
        stringResource(R.string.mode_test_subtitle)
    } else {
        stringResource(R.string.mode_real_subtitle)
    }

    val readinessBg = when {
        isReadyReal -> Color(0xFF2E7D32)
        isTest -> Color(0xFFEF6C00)
        else -> Color(0xFF424242)
    }
    val readinessLabel = when {
        isTest && isReadyReal -> stringResource(R.string.readiness_test_ready)
        isTest && !isReadyReal -> stringResource(R.string.readiness_test_blocked)
        isReadyReal -> stringResource(R.string.readiness_ready)
        else -> stringResource(R.string.readiness_blocked)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(modeBg, RoundedCornerShape(8.dp))
                .padding(12.dp)
                .testTag("mode_badge")
        ) {
            Column {
                Text(
                    text = modeLabel,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = modeDesc,
                    color = Color(0xFFE3F2FD),
                    fontSize = 13.sp
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(readinessBg, RoundedCornerShape(8.dp))
                .padding(10.dp)
                .testTag("readiness_badge")
        ) {
            Text(
                text = readinessLabel,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun WarningBanner(text: String, tag: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFB71C1C), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFFFF8A80), RoundedCornerShape(8.dp))
            .padding(12.dp)
            .testTag(tag)
    ) {
        Text(
            text = text,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun DeviceInspectionCard(
    eval: DeviceReadinessEvaluation,
    currentLevel: SanitizationLevel,
    onRefresh: () -> Unit,
    onRequestDeviceAdmin: () -> Unit
) {
    val yes = stringResource(R.string.value_yes)
    val no = stringResource(R.string.value_no)
    val hw = eval.hardwareIdentity
    val makeModel = "${hw.manufacturer} ${hw.model}".trim().ifEmpty { "unknown" }
    val knownFieldsCount = hw.knownHardwareFields().size

    val euiccText = when (eval.euiccFeatureStatus) {
        EuiccFeatureStatus.UNKNOWN_PRE_API_28 -> "UNKNOWN (API < 28)"
        EuiccFeatureStatus.PRESENT -> "PRESENT"
        EuiccFeatureStatus.ABSENT -> "ABSENT"
        EuiccFeatureStatus.UNKNOWN_ERROR -> "UNKNOWN (READ ERROR)"
    }

    val levelText = when (currentLevel) {
        SanitizationLevel.LEVEL_0_UNSUPPORTED -> stringResource(R.string.level_0_label)
        SanitizationLevel.LEVEL_1_LEGACY_WIPE_DATA -> stringResource(R.string.level_1_label)
        SanitizationLevel.LEVEL_2_MODERN_WIPE_DEVICE -> stringResource(R.string.level_2_label)
        SanitizationLevel.LEVEL_3_EXTENDED_FLAGS -> stringResource(R.string.level_3_label)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.section_device_status),
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                modifier = Modifier.semantics { heading() }
            )
            HorizontalDivider(color = Color(0xFF333333))

            StatusRow(stringResource(R.string.label_device_model), makeModel)
            StatusRow(
                stringResource(R.string.label_android_api),
                "Android ${hw.osRelease.ifEmpty { "?" }} (API ${eval.sdkInt})"
            )
            StatusRow(
                stringResource(R.string.label_security_patch),
                hw.securityPatch.ifEmpty { "unknown" }
            )
            StatusRow(
                stringResource(R.string.label_hardware_fields),
                "$knownFieldsCount / 7 (min 3)"
            )
            StatusRow(
                "Hardware Family",
                "${hw.brand} / ${hw.device} / ${hw.board} / ${hw.hardware} / ${hw.product}"
            )
            if (hw.buildFingerprint.isNotEmpty()) {
                StatusRow("Build Fingerprint", hw.buildFingerprint)
            }
            StatusRow(stringResource(R.string.label_management_role), eval.managementRole.name)
            StatusRow(
                stringResource(R.string.label_active_wipe_policy),
                if (eval.hasActiveWipePolicy) yes else no
            )
            StatusRow(stringResource(R.string.label_user_qualification), eval.userQualification.name)
            StatusRow(stringResource(R.string.label_wipe_api_path), eval.wipeApiPath.technicalName)
            StatusRow(stringResource(R.string.label_lock_screen), eval.lockScreenStatus.name)
            StatusRow(stringResource(R.string.label_encryption), eval.encryptionStatus.name)
            StatusRow(stringResource(R.string.label_euicc_feature), euiccText)
            StatusRow(
                stringResource(R.string.label_sanitization_level),
                levelText,
                valueTag = "sanitization_level_value"
            )
            if (eval.warnings.isNotEmpty()) {
                StatusRow("Inspection Warnings", eval.warnings.joinToString(", "))
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.encryption_note),
                fontSize = 12.sp,
                color = Color(0xFFBDBDBD)
            )
            Text(
                text = stringResource(R.string.sanitization_note),
                fontSize = 12.sp,
                color = Color(0xFFBDBDBD)
            )

            Spacer(modifier = Modifier.height(6.dp))
            if (eval.canRequestAdminActivation) {
                OutlinedButton(
                    onClick = onRequestDeviceAdmin,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("btn_request_admin")
                ) {
                    Text(stringResource(R.string.btn_request_device_admin))
                }
                Text(
                    text = stringResource(R.string.admin_activation_hint),
                    fontSize = 12.sp,
                    color = Color(0xFFB0BEC5)
                )
            }

            OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("btn_refresh_inspection")
            ) {
                Text(stringResource(R.string.btn_refresh_state))
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, valueTag: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = Color(0xFFBDBDBD),
            modifier = Modifier.weight(0.48f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            modifier = Modifier
                .weight(0.52f)
                .let { if (valueTag != null) it.testTag(valueTag) else it }
        )
    }
}

@Composable
private fun BlockingReasonsCard(blockingReasons: List<BlockingReason>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF332018)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("blocking_reasons_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.section_blocking_reasons),
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFFAB91),
                fontSize = 16.sp,
                modifier = Modifier.semantics { heading() }
            )
            blockingReasons.forEach { reason ->
                Text(
                    text = "• ${blockingReasonText(reason)}",
                    fontSize = 13.sp,
                    color = Color(0xFFFFCCBC)
                )
            }
        }
    }
}

@Composable
private fun blockingReasonText(reason: BlockingReason): String {
    return when (reason) {
        BlockingReason.API_BELOW_23 -> stringResource(R.string.block_api_below_23)
        BlockingReason.NO_ACTIVE_ADMIN_OR_POLICY -> stringResource(R.string.block_no_active_admin)
        BlockingReason.API23_REQUIRES_DEVICE_OWNER -> stringResource(R.string.block_api23_requires_do)
        BlockingReason.PROFILE_OWNER_NOT_ALLOWED_API24_33 ->
            stringResource(R.string.block_profile_owner_api24_33)
        BlockingReason.SECONDARY_USER_NOT_ALLOWED -> stringResource(R.string.block_secondary_user)
        BlockingReason.USER_STATE_UNKNOWN -> stringResource(R.string.block_user_unknown)
        BlockingReason.API34_PRIVATE_DEVICE_NO_OWNER_ROLE ->
            stringResource(R.string.block_api34_requires_owner)
        BlockingReason.ORG_PROFILE_MISSING_PARENT_DPM ->
            stringResource(R.string.block_org_profile_missing_parent)
        BlockingReason.LOCK_SCREEN_NOT_SECURE -> stringResource(R.string.block_lock_not_secure)
        BlockingReason.LOCK_SCREEN_STATUS_UNKNOWN -> stringResource(R.string.block_lock_unknown)
        BlockingReason.ENCRYPTION_NOT_ACTIVE -> stringResource(R.string.block_encryption_inactive)
        BlockingReason.ENCRYPTION_DEFAULT_KEY_NOT_ACCEPTED ->
            stringResource(R.string.block_encryption_default_key)
        BlockingReason.ENCRYPTION_STATUS_UNKNOWN -> stringResource(R.string.block_encryption_unknown)
        BlockingReason.INSUFFICIENT_HARDWARE_IDENTITY_FIELDS ->
            stringResource(R.string.block_insufficient_identity)
    }
}

@Composable
private fun ModeAndOptionsCard(
    state: RedButtonUiState,
    onToggleTestMode: (Boolean) -> Unit,
    onToggleExternalStorage: (Boolean) -> Unit,
    onToggleEuicc: (Boolean) -> Unit
) {
    val supportsEuicc = state.evaluation?.supportsEuiccFlag == true
    val testModeToggleDesc = stringResource(R.string.toggle_test_mode_title)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.section_mode_and_options),
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                modifier = Modifier.semantics { heading() }
            )
            HorizontalDivider(color = Color(0xFF333333))

            // TEST MODE switch
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleTestMode(!state.testModeEnabled) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.toggle_test_mode_title),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = stringResource(R.string.toggle_test_mode_desc),
                        fontSize = 12.sp,
                        color = Color(0xFFBDBDBD)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = state.testModeEnabled,
                    onCheckedChange = onToggleTestMode,
                    modifier = Modifier
                        .testTag("switch_test_mode")
                        .semantics { contentDescription = testModeToggleDesc }
                )
            }

            HorizontalDivider(color = Color(0xFF2A2A2A))

            // WIPE_EXTERNAL_STORAGE option
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExternalStorage(!state.options.wipeExternalStorage) },
                verticalAlignment = Alignment.Top
            ) {
                Checkbox(
                    checked = state.options.wipeExternalStorage,
                    onCheckedChange = onToggleExternalStorage,
                    modifier = Modifier.testTag("checkbox_wipe_external")
                )
                Column(modifier = Modifier.padding(start = 6.dp)) {
                    Text(
                        text = stringResource(R.string.option_external_storage_title),
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                    Text(
                        text = stringResource(R.string.option_external_storage_desc),
                        fontSize = 12.sp,
                        color = Color(0xFFBDBDBD)
                    )
                }
            }

            // WIPE_EUICC option (only enabled on supported API 34+ configuration per FR-08)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let {
                        if (supportsEuicc) {
                            it.clickable { onToggleEuicc(!state.options.wipeEuicc) }
                        } else {
                            it
                        }
                    },
                verticalAlignment = Alignment.Top
            ) {
                Checkbox(
                    checked = state.options.wipeEuicc && supportsEuicc,
                    onCheckedChange = { onToggleEuicc(it) },
                    enabled = supportsEuicc,
                    modifier = Modifier.testTag("checkbox_wipe_euicc")
                )
                Column(modifier = Modifier.padding(start = 6.dp)) {
                    Text(
                        text = stringResource(R.string.option_euicc_title),
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        color = if (supportsEuicc) Color.White else Color(0xFF757575)
                    )
                    Text(
                        text = stringResource(R.string.option_euicc_desc),
                        fontSize = 12.sp,
                        color = Color(0xFFBDBDBD)
                    )
                }
            }

            Text(
                text = stringResource(R.string.forbidden_flags_note),
                fontSize = 12.sp,
                color = Color(0xFF90CAF9)
            )
        }
    }
}

@Composable
private fun InformedConsentCard(
    state: RedButtonUiState,
    onToggleCommonConsent: (Boolean) -> Unit,
    onToggleRealConsent: (Boolean) -> Unit,
    onArm: () -> Unit
) {
    val commonConsentLabel = stringResource(R.string.checkbox_consent_common)
    val realConsentLabel = stringResource(R.string.checkbox_consent_real)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.section_informed_consent),
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                modifier = Modifier.semantics { heading() }
            )
            HorizontalDivider(color = Color(0xFF333333))

            Text(stringResource(R.string.consent_bullet_1), fontSize = 13.sp)
            Text(stringResource(R.string.consent_bullet_2), fontSize = 13.sp)
            Text(stringResource(R.string.consent_bullet_3), fontSize = 13.sp)
            Text(stringResource(R.string.consent_bullet_4), fontSize = 13.sp)
            Text(stringResource(R.string.consent_bullet_5), fontSize = 13.sp)
            Text(stringResource(R.string.consent_bullet_6), fontSize = 13.sp)
            Text(stringResource(R.string.consent_bullet_7), fontSize = 13.sp)

            HorizontalDivider(color = Color(0xFF333333))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleCommonConsent(!state.commonConsentChecked) },
                verticalAlignment = Alignment.Top
            ) {
                Checkbox(
                    checked = state.commonConsentChecked,
                    onCheckedChange = onToggleCommonConsent,
                    modifier = Modifier
                        .testTag("checkbox_consent_common")
                        .semantics { contentDescription = commonConsentLabel }
                )
                Text(
                    text = commonConsentLabel,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 6.dp, top = 8.dp)
                )
            }

            if (!state.testModeEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleRealConsent(!state.realModeConsentChecked) },
                    verticalAlignment = Alignment.Top
                ) {
                    Checkbox(
                        checked = state.realModeConsentChecked,
                        onCheckedChange = onToggleRealConsent,
                        modifier = Modifier
                            .testTag("checkbox_consent_real")
                            .semantics { contentDescription = realConsentLabel }
                    )
                    Text(
                        text = realConsentLabel,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFFF8A80),
                        modifier = Modifier.padding(start = 6.dp, top = 8.dp)
                    )
                }
            }

            if (state.armingError == ArmingErrorState.WRITE_FAILED) {
                WarningBanner(
                    text = stringResource(R.string.arm_error_failed),
                    tag = "arm_error_failed_banner"
                )
            } else if (state.armingError == ArmingErrorState.WRITE_UNCERTAIN) {
                WarningBanner(
                    text = stringResource(R.string.arm_error_uncertain),
                    tag = "arm_error_uncertain_banner"
                )
            }

            val buttonColors = if (state.testModeEnabled) {
                ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
            } else {
                ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
            }

            Button(
                onClick = onArm,
                enabled = state.canArm,
                colors = buttonColors,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("btn_arm_action")
            ) {
                Text(
                    text = if (state.testModeEnabled) {
                        stringResource(R.string.btn_arm_test)
                    } else {
                        stringResource(R.string.btn_arm_real)
                    },
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun ManualChecklistCard(
    checkedItems: Set<Int>,
    onToggleItem: (Int) -> Unit
) {
    val items = listOf(
        stringResource(R.string.checklist_item_1),
        stringResource(R.string.checklist_item_2),
        stringResource(R.string.checklist_item_3),
        stringResource(R.string.checklist_item_4),
        stringResource(R.string.checklist_item_5)
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.section_manual_checklist),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = stringResource(R.string.manual_checklist_disclaimer),
                fontSize = 12.sp,
                color = Color(0xFFBDBDBD)
            )
            items.forEachIndexed { index, text ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleItem(index) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = checkedItems.contains(index),
                        onCheckedChange = { onToggleItem(index) }
                    )
                    Text(
                        text = text,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LastAttemptReportCard(report: AttemptReport?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.section_last_attempt),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.semantics { heading() }
            )
            if (report == null) {
                Text(
                    text = stringResource(R.string.last_attempt_none),
                    fontSize = 13.sp,
                    color = Color(0xFFBDBDBD)
                )
            } else {
                StatusRow("Outcome", report.outcome.statusLabel)
                StatusRow("Mode", report.executionMode.name)
                StatusRow("Device", "${report.makeAndModel} (API ${report.sdkInt})")
                StatusRow("Provider Path", report.selectedProviderPath.technicalName)
                StatusRow(
                    "Flags",
                    if (report.requestedFlagsNames.isEmpty()) "NONE"
                    else report.requestedFlagsNames.joinToString(", ")
                )
                StatusRow("Detail", report.detailCode)
            }
        }
    }
}

@Composable
private fun TechnicalDiagnosticsCard(
    state: RedButtonUiState,
    onTogglePreview: () -> Unit,
    onCopyDiagnostics: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("technical_diagnostics_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.section_diagnostics),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = stringResource(R.string.diagnostics_desc),
                fontSize = 12.sp,
                color = Color(0xFFBDBDBD)
            )

            OutlinedButton(
                onClick = onCopyDiagnostics,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("btn_copy_diagnostics")
            ) {
                Text(stringResource(R.string.btn_copy_diagnostics))
            }

            if (state.diagnosticsCopied) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1B5E20), RoundedCornerShape(6.dp))
                        .padding(10.dp)
                        .testTag("diagnostics_copied_banner")
                ) {
                    Text(
                        text = stringResource(R.string.diagnostics_copied_confirmation),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            OutlinedButton(
                onClick = onTogglePreview,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("btn_toggle_diagnostics_preview")
            ) {
                Text(
                    if (state.showDiagnosticsPreview) {
                        stringResource(R.string.btn_hide_diagnostics)
                    } else {
                        stringResource(R.string.btn_show_diagnostics)
                    }
                )
            }

            if (state.showDiagnosticsPreview) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF101010), RoundedCornerShape(6.dp))
                        .border(1.dp, Color(0xFF424242), RoundedCornerShape(6.dp))
                        .padding(10.dp)
                        .testTag("diagnostics_preview_box")
                ) {
                    Text(
                        text = state.buildDiagnosticReport(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFFE0E0E0)
                    )
                }
            }
        }
    }
}

/**
 * FR-10: After arming, there is NO cancel button inside Red Button.
 * The only path to disarm before the next launch is clearing app storage in Android Settings.
 */
@Composable
private fun ArmedClosingScreen(
    armedMode: ExecutionMode,
    onCloseApplication: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .testTag("armed_closing_screen"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (armedMode == ExecutionMode.TEST_MODE) {
                    Color(0xFF102840)
                } else {
                    Color(0xFF3B1212)
                }
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.armed_screen_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = Color.White,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    text = if (armedMode == ExecutionMode.TEST_MODE) {
                        stringResource(R.string.armed_screen_body_test)
                    } else {
                        stringResource(R.string.armed_screen_body_real)
                    },
                    fontSize = 14.sp,
                    color = Color(0xFFEEEEEE)
                )
                Text(
                    text = stringResource(R.string.armed_screen_no_cancel_note),
                    fontSize = 13.sp,
                    color = Color(0xFFFFCC80)
                )
                Button(
                    onClick = onCloseApplication,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("btn_close_after_arm")
                ) {
                    Text(stringResource(R.string.btn_close_after_arm))
                }
            }
        }
    }
}

/**
 * FR-09 & Section 6: Clearly displays the one-time execution result:
 * WIPE REQUESTED, SIMULATED, REFUSED, or ERROR — never claiming that a reset completed.
 */
@Composable
private fun ExecutionOutcomeScreen(
    state: RedButtonUiState,
    summary: ExecutionSummary,
    lastReport: AttemptReport?,
    onToggleDiagnosticsPreview: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onReturnToInspection: () -> Unit
) {
    val scrollState = rememberScrollState()
    val badgeColor = when (summary.outcome) {
        AttemptOutcome.WIPE_REQUESTED -> Color(0xFFC62828)
        AttemptOutcome.SIMULATED -> Color(0xFF1565C0)
        AttemptOutcome.REFUSED -> Color(0xFFEF6C00)
        AttemptOutcome.ERROR -> Color(0xFF6A1B9A)
    }
    val outcomeLabel = when (summary.outcome) {
        AttemptOutcome.WIPE_REQUESTED -> stringResource(R.string.outcome_wipe_requested)
        AttemptOutcome.SIMULATED -> stringResource(R.string.outcome_simulated)
        AttemptOutcome.REFUSED -> stringResource(R.string.outcome_refused)
        AttemptOutcome.ERROR -> stringResource(R.string.outcome_error)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
            .testTag("execution_outcome_screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = stringResource(R.string.outcome_screen_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.semantics { heading() }
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(badgeColor, RoundedCornerShape(8.dp))
                .padding(14.dp)
                .testTag("outcome_status_badge")
        ) {
            Column {
                Text(
                    text = outcomeLabel,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Mode: ${summary.executionMode.name} | Code: ${summary.detailCode}",
                    fontSize = 13.sp,
                    color = Color(0xFFEEEEEE)
                )
            }
        }

        if (summary.storageStateUncertain) {
            WarningBanner(
                text = stringResource(R.string.outcome_uncertain_storage_warning),
                tag = "outcome_uncertain_storage_banner"
            )
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusRow("Outcome", summary.outcome.statusLabel, valueTag = "outcome_raw_status")
                StatusRow("Execution Mode", summary.executionMode.name)
                StatusRow("DPM Path", summary.selectedPath.technicalName)
                StatusRow(
                    "Requested Flags",
                    if (summary.effectiveFlagsNames.isEmpty()) "NONE"
                    else summary.effectiveFlagsNames.joinToString(", ")
                )
                StatusRow("Classification Level", "Level ${summary.sanitizationLevel.levelNumber}")
                StatusRow("Detail Code", summary.detailCode)

                HorizontalDivider(color = Color(0xFF333333))
                Text(
                    text = summary.detailMessage,
                    fontSize = 13.sp,
                    color = Color(0xFFE0E0E0)
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2B1D1D)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.outcome_disclaimer),
                fontSize = 13.sp,
                color = Color(0xFFFFCCBC),
                modifier = Modifier.padding(14.dp)
            )
        }

        LastAttemptReportCard(report = lastReport)

        TechnicalDiagnosticsCard(
            state = state,
            onTogglePreview = onToggleDiagnosticsPreview,
            onCopyDiagnostics = onCopyDiagnostics
        )

        OutlinedButton(
            onClick = onReturnToInspection,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("btn_return_to_inspection")
        ) {
            Text(stringResource(R.string.btn_return_to_inspection))
        }
    }
}
