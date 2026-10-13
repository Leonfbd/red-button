package pl.leon.redbutton.model

/**
 * Public Android DevicePolicyManager flag constants.
 *
 * Per FR-08:
 * - WIPE_EXTERNAL_STORAGE (0x0001) is requested by default when supported.
 * - WIPE_EUICC (0x0004) is opt-in only on supported API 34+ configurations.
 * - WIPE_RESET_PROTECTION_DATA (0x0002) and WIPE_SILENTLY (0x0008) are NEVER requested.
 */
object WipeFlags {
    const val WIPE_EXTERNAL_STORAGE: Int = 0x0001
    const val WIPE_RESET_PROTECTION_DATA: Int = 0x0002
    const val WIPE_EUICC: Int = 0x0004
    const val WIPE_SILENTLY: Int = 0x0008

    const val ALLOWED_MASK: Int = WIPE_EXTERNAL_STORAGE or WIPE_EUICC
    const val FORBIDDEN_MASK: Int = WIPE_RESET_PROTECTION_DATA or WIPE_SILENTLY

    fun sanitizeAllowedFlags(rawFlags: Int): Int {
        return (rawFlags and ALLOWED_MASK) and FORBIDDEN_MASK.inv()
    }

    fun containsForbiddenFlags(rawFlags: Int): Boolean {
        return (rawFlags and FORBIDDEN_MASK) != 0
    }

    fun formatFlags(flags: Int): List<String> {
        val list = mutableListOf<String>()
        if ((flags and WIPE_EXTERNAL_STORAGE) != 0) {
            list.add("WIPE_EXTERNAL_STORAGE")
        }
        if ((flags and WIPE_EUICC) != 0) {
            list.add("WIPE_EUICC")
        }
        return list
    }
}

enum class ExecutionMode {
    TEST_MODE,
    REAL_MODE
}

enum class ManagementRole {
    NONE,
    DEVICE_ADMIN,
    PROFILE_OWNER,
    ORG_OWNED_PROFILE_OWNER,
    DEVICE_OWNER
}

enum class UserQualification {
    SYSTEM_OR_MAIN_USER,
    UNCONFIRMED_API23_USER,
    SECONDARY_OR_PROFILE_USER,
    UNKNOWN
}

enum class WipeApiPath(val technicalName: String) {
    NONE("Brak / Unsupported"),
    LEGACY_WIPE_DATA("DevicePolicyManager.wipeData(int)"),
    MODERN_WIPE_DEVICE("DevicePolicyManager.wipeDevice(int)"),
    MODERN_PARENT_PROFILE_WIPE_DEVICE("ParentProfile.DevicePolicyManager.wipeDevice(int)")
}

enum class EncryptionStatus(val isQualifiedForRealMode: Boolean) {
    ACTIVE(true),
    ACTIVE_PER_USER(true),
    ACTIVE_DEFAULT_KEY(false),
    ACTIVATING(false),
    INACTIVE(false),
    UNSUPPORTED(false),
    UNKNOWN(false);

    companion object {
        // Matches android.app.admin.DevicePolicyManager constants:
        const val DPM_ENCRYPTION_STATUS_UNSUPPORTED = 0
        const val DPM_ENCRYPTION_STATUS_INACTIVE = 1
        const val DPM_ENCRYPTION_STATUS_ACTIVATING = 2
        const val DPM_ENCRYPTION_STATUS_ACTIVE = 3
        const val DPM_ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY = 4
        const val DPM_ENCRYPTION_STATUS_ACTIVE_PER_USER = 5

        fun fromDpmConstant(status: Int): EncryptionStatus {
            return when (status) {
                DPM_ENCRYPTION_STATUS_ACTIVE -> ACTIVE
                DPM_ENCRYPTION_STATUS_ACTIVE_PER_USER -> ACTIVE_PER_USER
                DPM_ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY -> ACTIVE_DEFAULT_KEY
                DPM_ENCRYPTION_STATUS_ACTIVATING -> ACTIVATING
                DPM_ENCRYPTION_STATUS_INACTIVE -> INACTIVE
                DPM_ENCRYPTION_STATUS_UNSUPPORTED -> UNSUPPORTED
                else -> UNKNOWN
            }
        }
    }
}

enum class LockScreenStatus(val isQualifiedForRealMode: Boolean) {
    SECURE(true),
    INSECURE(false),
    UNKNOWN(false)
}

enum class EuiccFeatureStatus {
    UNKNOWN_PRE_API_28,
    PRESENT,
    ABSENT,
    UNKNOWN_ERROR
}

/**
 * Internal classification of the available request (Section 7).
 * Level 4 (OEM sanitization) is intentionally not representable as an active output level
 * because no OEM sanitization integration is implemented.
 */
enum class SanitizationLevel(val levelNumber: Int) {
    LEVEL_0_UNSUPPORTED(0),
    LEVEL_1_LEGACY_WIPE_DATA(1),
    LEVEL_2_MODERN_WIPE_DEVICE(2),
    LEVEL_3_EXTENDED_FLAGS(3)
}

enum class BlockingReason {
    API_BELOW_23,
    NO_ACTIVE_ADMIN_OR_POLICY,
    API23_REQUIRES_DEVICE_OWNER,
    PROFILE_OWNER_NOT_ALLOWED_API24_33,
    SECONDARY_USER_NOT_ALLOWED,
    USER_STATE_UNKNOWN,
    API34_PRIVATE_DEVICE_NO_OWNER_ROLE,
    ORG_PROFILE_MISSING_PARENT_DPM,
    LOCK_SCREEN_NOT_SECURE,
    LOCK_SCREEN_STATUS_UNKNOWN,
    ENCRYPTION_NOT_ACTIVE,
    ENCRYPTION_DEFAULT_KEY_NOT_ACCEPTED,
    ENCRYPTION_STATUS_UNKNOWN,
    INSUFFICIENT_HARDWARE_IDENTITY_FIELDS
}

data class IdentityMatchResult(
    val matches: Boolean,
    val matchingKnownFieldsCount: Int,
    val mismatchedFields: List<String>,
    val insufficientKnownFields: Boolean
)

/**
 * Hardware-family and build metadata (FR-05, Section 8).
 * Does NOT include IMEI, serial number, volume IDs, or user credentials.
 * Normal OS updates or security patch changes do NOT invalidate hardware-family matching.
 */
data class HardwareIdentitySnapshot(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val board: String,
    val hardware: String,
    val product: String,
    val buildFingerprint: String = "",
    val securityPatch: String = "",
    val osRelease: String = ""
) {
    fun hardwareFamilyMap(): Map<String, String> = linkedMapOf(
        "manufacturer" to manufacturer,
        "brand" to brand,
        "model" to model,
        "device" to device,
        "board" to board,
        "hardware" to hardware,
        "product" to product
    )

    fun knownHardwareFields(): Map<String, String> {
        return hardwareFamilyMap()
            .mapValues { it.value.trim() }
            .filterValues { isKnownFieldValue(it) }
    }

    fun hasMinimumKnownFields(minFields: Int = MIN_HARDWARE_FAMILY_FIELDS): Boolean {
        return knownHardwareFields().size >= minFields
    }

    fun matchesHardwareFamily(
        other: HardwareIdentitySnapshot,
        minFields: Int = MIN_HARDWARE_FAMILY_FIELDS
    ): IdentityMatchResult {
        val selfKnown = knownHardwareFields()
        val otherKnown = other.knownHardwareFields()

        if (selfKnown.size < minFields || otherKnown.size < minFields) {
            return IdentityMatchResult(
                matches = false,
                matchingKnownFieldsCount = 0,
                mismatchedFields = emptyList(),
                insufficientKnownFields = true
            )
        }

        val mismatched = mutableListOf<String>()
        var matchedCount = 0

        for (key in hardwareFamilyMap().keys) {
            val a = selfKnown[key]
            val b = otherKnown[key]
            if (a != null && b != null) {
                if (a.equals(b, ignoreCase = true)) {
                    matchedCount++
                } else {
                    mismatched.add(key)
                }
            } else if (a != null || b != null) {
                // One snapshot had a known value and the other lost/changed it to unknown
                mismatched.add(key)
            }
        }

        val isMatch = mismatched.isEmpty() && matchedCount >= minFields
        return IdentityMatchResult(
            matches = isMatch,
            matchingKnownFieldsCount = matchedCount,
            mismatchedFields = mismatched,
            insufficientKnownFields = matchedCount < minFields
        )
    }

    companion object {
        const val MIN_HARDWARE_FAMILY_FIELDS: Int = 3

        fun isKnownFieldValue(raw: String?): Boolean {
            if (raw == null) return false
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return false
            return !trimmed.equals("unknown", ignoreCase = true)
        }
    }
}

data class WipeOptions(
    val wipeExternalStorage: Boolean = true,
    val wipeEuicc: Boolean = false
)

data class RawDeviceState(
    val sdkInt: Int,
    val hardwareIdentity: HardwareIdentitySnapshot,
    val managementRole: ManagementRole,
    val hasActiveWipePolicy: Boolean,
    val hasParentProfileDpm: Boolean,
    val userQualification: UserQualification,
    val lockScreenStatus: LockScreenStatus,
    val encryptionStatus: EncryptionStatus,
    val hasEuiccHardwareFeature: Boolean?,
    val inspectionWarnings: List<String> = emptyList()
)

data class DeviceReadinessEvaluation(
    val sdkInt: Int,
    val hardwareIdentity: HardwareIdentitySnapshot,
    val managementRole: ManagementRole,
    val hasActiveWipePolicy: Boolean,
    val hasParentProfileDpm: Boolean,
    val userQualification: UserQualification,
    val lockScreenStatus: LockScreenStatus,
    val encryptionStatus: EncryptionStatus,
    val euiccFeatureStatus: EuiccFeatureStatus,
    val wipeApiPath: WipeApiPath,
    val supportsExternalStorageFlag: Boolean,
    val supportsEuiccFlag: Boolean,
    val canRequestAdminActivation: Boolean,
    val blockingReasons: List<BlockingReason>,
    val warnings: List<String>
) {
    val isReadyForRealMode: Boolean
        get() = wipeApiPath != WipeApiPath.NONE && blockingReasons.isEmpty()

    fun computeEffectiveFlags(options: WipeOptions): Int {
        var flags = 0
        if (options.wipeExternalStorage && supportsExternalStorageFlag) {
            flags = flags or WipeFlags.WIPE_EXTERNAL_STORAGE
        }
        if (options.wipeEuicc && supportsEuiccFlag) {
            flags = flags or WipeFlags.WIPE_EUICC
        }
        return WipeFlags.sanitizeAllowedFlags(flags)
    }

    fun sanitizationLevelFor(options: WipeOptions): SanitizationLevel {
        if (!isReadyForRealMode) {
            return SanitizationLevel.LEVEL_0_UNSUPPORTED
        }
        val effectiveFlags = computeEffectiveFlags(options)
        if (effectiveFlags != 0) {
            return SanitizationLevel.LEVEL_3_EXTENDED_FLAGS
        }
        return when (wipeApiPath) {
            WipeApiPath.LEGACY_WIPE_DATA -> SanitizationLevel.LEVEL_1_LEGACY_WIPE_DATA
            WipeApiPath.MODERN_WIPE_DEVICE,
            WipeApiPath.MODERN_PARENT_PROFILE_WIPE_DEVICE -> SanitizationLevel.LEVEL_2_MODERN_WIPE_DEVICE
            WipeApiPath.NONE -> SanitizationLevel.LEVEL_0_UNSUPPORTED
        }
    }
}

data class ConsentRecord(
    val schemaVersion: Int,
    val tokenId: String,
    val executionMode: ExecutionMode,
    val wipeExternalStorage: Boolean,
    val wipeEuicc: Boolean,
    val consentConfirmed: Boolean,
    val armedAtEpochMs: Long,
    val armedSdkInt: Int,
    val armedManagementRole: ManagementRole,
    val armedWipeApiPath: WipeApiPath,
    val armedHasActiveWipePolicy: Boolean,
    val armedUserQualification: UserQualification,
    val hardwareIdentity: HardwareIdentitySnapshot,
    val consumed: Boolean
)

enum class AttemptOutcome(val statusLabel: String) {
    WIPE_REQUESTED("WIPE REQUESTED"),
    SIMULATED("SIMULATED"),
    REFUSED("REFUSED"),
    ERROR("ERROR")
}

data class AttemptReport(
    val timestampEpochMs: Long,
    val makeAndModel: String,
    val androidRelease: String,
    val sdkInt: Int,
    val executionMode: ExecutionMode,
    val selectedProviderPath: WipeApiPath,
    val requestedFlagsMask: Int,
    val requestedFlagsNames: List<String>,
    val sanitizationLevel: SanitizationLevel,
    val outcome: AttemptOutcome,
    val detailCode: String,
    val detailMessage: String
)

data class DeviceProfile(
    val capturedAtEpochMs: Long,
    val sdkInt: Int,
    val hardwareIdentity: HardwareIdentitySnapshot,
    val managementRole: ManagementRole,
    val hasActiveWipePolicy: Boolean,
    val userQualification: UserQualification,
    val lockScreenStatus: LockScreenStatus,
    val encryptionStatus: EncryptionStatus,
    val euiccFeatureStatus: EuiccFeatureStatus,
    val wipeApiPath: WipeApiPath,
    val supportsExternalStorageFlag: Boolean,
    val supportsEuiccFlag: Boolean,
    val isReadyForRealMode: Boolean,
    val sanitizationLevel: SanitizationLevel,
    val blockingReasons: List<BlockingReason>,
    val warnings: List<String>
)

data class ExecutionSummary(
    val outcome: AttemptOutcome,
    val executionMode: ExecutionMode,
    val selectedPath: WipeApiPath,
    val effectiveFlagsMask: Int,
    val effectiveFlagsNames: List<String>,
    val sanitizationLevel: SanitizationLevel,
    val detailCode: String,
    val detailMessage: String,
    val storageStateUncertain: Boolean = false,
    val blockingReasons: List<BlockingReason> = emptyList()
)
