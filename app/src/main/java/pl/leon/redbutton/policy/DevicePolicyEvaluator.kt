package pl.leon.redbutton.policy

import pl.leon.redbutton.model.BlockingReason
import pl.leon.redbutton.model.DeviceProfile
import pl.leon.redbutton.model.DeviceReadinessEvaluation
import pl.leon.redbutton.model.EncryptionStatus
import pl.leon.redbutton.model.EuiccFeatureStatus
import pl.leon.redbutton.model.LockScreenStatus
import pl.leon.redbutton.model.ManagementRole
import pl.leon.redbutton.model.RawDeviceState
import pl.leon.redbutton.model.UserQualification
import pl.leon.redbutton.model.WipeApiPath
import pl.leon.redbutton.model.WipeOptions

/**
 * Evaluates raw Android device/policy state against FR-05, FR-06, FR-07, FR-08, and Section 7.
 * Strictly fail-closed: any missing qualification, unknown lock/encryption state, or role mismatch
 * produces explicit blocking reasons and prevents REAL MODE execution.
 */
class DevicePolicyEvaluator {

    fun evaluate(raw: RawDeviceState): DeviceReadinessEvaluation {
        val blocking = linkedSetOf<BlockingReason>()
        val warnings = mutableListOf<String>()
        warnings.addAll(raw.inspectionWarnings)

        val euiccStatus = resolveEuiccFeatureStatus(raw.sdkInt, raw.hasEuiccHardwareFeature)
        val wipeApiPath = resolveWipePathAndRoleBlocks(raw, blocking)

        // FR-06: Screen Lock verification
        when (raw.lockScreenStatus) {
            LockScreenStatus.SECURE -> Unit
            LockScreenStatus.INSECURE -> blocking.add(BlockingReason.LOCK_SCREEN_NOT_SECURE)
            LockScreenStatus.UNKNOWN -> blocking.add(BlockingReason.LOCK_SCREEN_STATUS_UNKNOWN)
        }

        // FR-06: Storage Encryption verification
        when (raw.encryptionStatus) {
            EncryptionStatus.ACTIVE,
            EncryptionStatus.ACTIVE_PER_USER -> Unit
            EncryptionStatus.ACTIVE_DEFAULT_KEY ->
                blocking.add(BlockingReason.ENCRYPTION_DEFAULT_KEY_NOT_ACCEPTED)
            EncryptionStatus.INACTIVE,
            EncryptionStatus.ACTIVATING ->
                blocking.add(BlockingReason.ENCRYPTION_NOT_ACTIVE)
            EncryptionStatus.UNSUPPORTED,
            EncryptionStatus.UNKNOWN ->
                blocking.add(BlockingReason.ENCRYPTION_STATUS_UNKNOWN)
        }

        // FR-05: Hardware-family identity field threshold (>= 3 known fields)
        if (!raw.hardwareIdentity.hasMinimumKnownFields()) {
            blocking.add(BlockingReason.INSUFFICIENT_HARDWARE_IDENTITY_FIELDS)
        }

        // FR-08: Supported flags
        val supportsExternalStorageFlag = raw.sdkInt >= 23 && wipeApiPath != WipeApiPath.NONE
        val supportsEuiccFlag =
            raw.sdkInt >= 34 &&
                wipeApiPath != WipeApiPath.NONE &&
                euiccStatus == EuiccFeatureStatus.PRESENT

        val canRequestAdminActivation = raw.sdkInt >= 23 && !raw.hasActiveWipePolicy

        return DeviceReadinessEvaluation(
            sdkInt = raw.sdkInt,
            hardwareIdentity = raw.hardwareIdentity,
            managementRole = raw.managementRole,
            hasActiveWipePolicy = raw.hasActiveWipePolicy,
            hasParentProfileDpm = raw.hasParentProfileDpm,
            userQualification = raw.userQualification,
            lockScreenStatus = raw.lockScreenStatus,
            encryptionStatus = raw.encryptionStatus,
            euiccFeatureStatus = euiccStatus,
            wipeApiPath = wipeApiPath,
            supportsExternalStorageFlag = supportsExternalStorageFlag,
            supportsEuiccFlag = supportsEuiccFlag,
            canRequestAdminActivation = canRequestAdminActivation,
            blockingReasons = blocking.toList(),
            warnings = warnings
        )
    }

    fun toDeviceProfile(
        evaluation: DeviceReadinessEvaluation,
        options: WipeOptions,
        timestampEpochMs: Long
    ): DeviceProfile {
        return DeviceProfile(
            capturedAtEpochMs = timestampEpochMs,
            sdkInt = evaluation.sdkInt,
            hardwareIdentity = evaluation.hardwareIdentity,
            managementRole = evaluation.managementRole,
            hasActiveWipePolicy = evaluation.hasActiveWipePolicy,
            userQualification = evaluation.userQualification,
            lockScreenStatus = evaluation.lockScreenStatus,
            encryptionStatus = evaluation.encryptionStatus,
            euiccFeatureStatus = evaluation.euiccFeatureStatus,
            wipeApiPath = evaluation.wipeApiPath,
            supportsExternalStorageFlag = evaluation.supportsExternalStorageFlag,
            supportsEuiccFlag = evaluation.supportsEuiccFlag,
            isReadyForRealMode = evaluation.isReadyForRealMode,
            sanitizationLevel = evaluation.sanitizationLevelFor(options),
            blockingReasons = evaluation.blockingReasons,
            warnings = evaluation.warnings
        )
    }

    private fun resolveEuiccFeatureStatus(
        sdkInt: Int,
        hasEuiccHardwareFeature: Boolean?
    ): EuiccFeatureStatus {
        // FR-08: Below API 28, eSIM feature is "unknown", NOT "absent".
        if (sdkInt < 28) {
            return EuiccFeatureStatus.UNKNOWN_PRE_API_28
        }
        return when (hasEuiccHardwareFeature) {
            true -> EuiccFeatureStatus.PRESENT
            false -> EuiccFeatureStatus.ABSENT
            null -> EuiccFeatureStatus.UNKNOWN_ERROR
        }
    }

    private fun resolveWipePathAndRoleBlocks(
        raw: RawDeviceState,
        blocking: MutableSet<BlockingReason>
    ): WipeApiPath {
        val sdk = raw.sdkInt
        if (sdk < 23) {
            blocking.add(BlockingReason.API_BELOW_23)
            return WipeApiPath.NONE
        }

        if (sdk == 23) {
            // FR-07: On API 23, full scope is accepted ONLY for Device Owner with active wipe policy.
            if (!raw.hasActiveWipePolicy) {
                blocking.add(BlockingReason.NO_ACTIVE_ADMIN_OR_POLICY)
            }
            if (raw.managementRole != ManagementRole.DEVICE_OWNER) {
                blocking.add(BlockingReason.API23_REQUIRES_DEVICE_OWNER)
            }
            return if (raw.hasActiveWipePolicy && raw.managementRole == ManagementRole.DEVICE_OWNER) {
                WipeApiPath.LEGACY_WIPE_DATA
            } else {
                WipeApiPath.NONE
            }
        }

        if (sdk in 24..33) {
            // FR-07: On API 24-33, Device Owner OR qualified active wipe policy in system/main user.
            // Ordinary profile owner and secondary user are rejected.
            if (!raw.hasActiveWipePolicy) {
                blocking.add(BlockingReason.NO_ACTIVE_ADMIN_OR_POLICY)
            }
            when (raw.managementRole) {
                ManagementRole.DEVICE_OWNER -> {
                    if (raw.userQualification == UserQualification.SECONDARY_OR_PROFILE_USER) {
                        blocking.add(BlockingReason.SECONDARY_USER_NOT_ALLOWED)
                        return WipeApiPath.NONE
                    }
                    if (raw.userQualification == UserQualification.UNKNOWN ||
                        raw.userQualification == UserQualification.UNCONFIRMED_API23_USER
                    ) {
                        blocking.add(BlockingReason.USER_STATE_UNKNOWN)
                        return WipeApiPath.NONE
                    }
                    return if (raw.hasActiveWipePolicy) {
                        WipeApiPath.LEGACY_WIPE_DATA
                    } else {
                        WipeApiPath.NONE
                    }
                }
                ManagementRole.DEVICE_ADMIN -> {
                    return when (raw.userQualification) {
                        UserQualification.SYSTEM_OR_MAIN_USER -> {
                            if (raw.hasActiveWipePolicy) {
                                WipeApiPath.LEGACY_WIPE_DATA
                            } else {
                                WipeApiPath.NONE
                            }
                        }
                        UserQualification.SECONDARY_OR_PROFILE_USER -> {
                            blocking.add(BlockingReason.SECONDARY_USER_NOT_ALLOWED)
                            WipeApiPath.NONE
                        }
                        UserQualification.UNCONFIRMED_API23_USER,
                        UserQualification.UNKNOWN -> {
                            blocking.add(BlockingReason.USER_STATE_UNKNOWN)
                            WipeApiPath.NONE
                        }
                    }
                }
                ManagementRole.PROFILE_OWNER,
                ManagementRole.ORG_OWNED_PROFILE_OWNER -> {
                    blocking.add(BlockingReason.PROFILE_OWNER_NOT_ALLOWED_API24_33)
                    return WipeApiPath.NONE
                }
                ManagementRole.NONE -> {
                    blocking.add(BlockingReason.NO_ACTIVE_ADMIN_OR_POLICY)
                    return WipeApiPath.NONE
                }
            }
        }

        // API 34+ (FR-07): DevicePolicyManager.wipeDevice(int)
        // Accepted only for Device Owner OR qualified Organization-Owned Profile Owner via parent DPM.
        return when (raw.managementRole) {
            ManagementRole.DEVICE_OWNER -> {
                if (!raw.hasActiveWipePolicy) {
                    blocking.add(BlockingReason.NO_ACTIVE_ADMIN_OR_POLICY)
                    WipeApiPath.NONE
                } else {
                    WipeApiPath.MODERN_WIPE_DEVICE
                }
            }
            ManagementRole.ORG_OWNED_PROFILE_OWNER -> {
                if (!raw.hasActiveWipePolicy) {
                    blocking.add(BlockingReason.NO_ACTIVE_ADMIN_OR_POLICY)
                }
                if (!raw.hasParentProfileDpm) {
                    blocking.add(BlockingReason.ORG_PROFILE_MISSING_PARENT_DPM)
                }
                if (raw.hasActiveWipePolicy && raw.hasParentProfileDpm) {
                    WipeApiPath.MODERN_PARENT_PROFILE_WIPE_DEVICE
                } else {
                    WipeApiPath.NONE
                }
            }
            ManagementRole.DEVICE_ADMIN,
            ManagementRole.PROFILE_OWNER,
            ManagementRole.NONE -> {
                blocking.add(BlockingReason.API34_PRIVATE_DEVICE_NO_OWNER_ROLE)
                if (!raw.hasActiveWipePolicy) {
                    blocking.add(BlockingReason.NO_ACTIVE_ADMIN_OR_POLICY)
                }
                WipeApiPath.NONE
            }
        }
    }
}
