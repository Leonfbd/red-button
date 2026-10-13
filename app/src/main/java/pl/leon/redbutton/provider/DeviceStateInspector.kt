package pl.leon.redbutton.provider

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import pl.leon.redbutton.admin.RedButtonDeviceAdminReceiver
import pl.leon.redbutton.model.EncryptionStatus
import pl.leon.redbutton.model.HardwareIdentitySnapshot
import pl.leon.redbutton.model.LockScreenStatus
import pl.leon.redbutton.model.ManagementRole
import pl.leon.redbutton.model.RawDeviceState
import pl.leon.redbutton.model.UserQualification

interface DeviceStateInspector {
    fun inspectCurrentState(): RawDeviceState
}

/**
 * Production Android inspector reading only public Android APIs (Section 2, Section 8).
 * Never reads user credentials, PIN/password strength, IMEI, serial number, or volume IDs.
 */
class AndroidDeviceStateInspector(private val context: Context) : DeviceStateInspector {

    override fun inspectCurrentState(): RawDeviceState {
        val sdkInt = Build.VERSION.SDK_INT
        val warnings = mutableListOf<String>()

        val hardwareIdentity = readHardwareIdentity(sdkInt)

        val dpm = try {
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        } catch (e: Exception) {
            warnings.add("DPM_UNAVAILABLE:${e.javaClass.simpleName}")
            null
        }

        val adminComponent = RedButtonDeviceAdminReceiver.getComponentName(context)
        val pkg = context.packageName

        val hasActiveWipePolicy = try {
            dpm?.isAdminActive(adminComponent) == true
        } catch (e: Exception) {
            warnings.add("ADMIN_ACTIVE_READ_ERROR:${e.javaClass.simpleName}")
            false
        }

        val isDeviceOwner = try {
            dpm?.isDeviceOwnerApp(pkg) == true
        } catch (e: Exception) {
            warnings.add("DEVICE_OWNER_READ_ERROR:${e.javaClass.simpleName}")
            false
        }

        val isProfileOwner = try {
            dpm?.isProfileOwnerApp(pkg) == true
        } catch (e: Exception) {
            warnings.add("PROFILE_OWNER_READ_ERROR:${e.javaClass.simpleName}")
            false
        }

        val isOrgOwnedProfile = if (sdkInt >= Build.VERSION_CODES.R && isProfileOwner) {
            try {
                dpm?.isOrganizationOwnedDeviceWithManagedProfile == true
            } catch (e: Exception) {
                warnings.add("ORG_OWNED_READ_ERROR:${e.javaClass.simpleName}")
                false
            }
        } else {
            false
        }

        val hasParentProfileDpm = if (isOrgOwnedProfile && hasActiveWipePolicy) {
            try {
                dpm?.getParentProfileInstance(adminComponent) != null
            } catch (e: Exception) {
                warnings.add("PARENT_DPM_READ_ERROR:${e.javaClass.simpleName}")
                false
            }
        } else {
            false
        }

        val managementRole = when {
            isDeviceOwner -> ManagementRole.DEVICE_OWNER
            isOrgOwnedProfile -> ManagementRole.ORG_OWNED_PROFILE_OWNER
            isProfileOwner -> ManagementRole.PROFILE_OWNER
            hasActiveWipePolicy -> ManagementRole.DEVICE_ADMIN
            else -> ManagementRole.NONE
        }

        val userQualification = readUserQualification(sdkInt, warnings)
        val lockScreenStatus = readLockScreenStatus(warnings)
        val encryptionStatus = readEncryptionStatus(dpm, warnings)
        val hasEuiccFeature = readEuiccFeature(sdkInt, warnings)

        return RawDeviceState(
            sdkInt = sdkInt,
            hardwareIdentity = hardwareIdentity,
            managementRole = managementRole,
            hasActiveWipePolicy = hasActiveWipePolicy,
            hasParentProfileDpm = hasParentProfileDpm,
            userQualification = userQualification,
            lockScreenStatus = lockScreenStatus,
            encryptionStatus = encryptionStatus,
            hasEuiccHardwareFeature = hasEuiccFeature,
            inspectionWarnings = warnings
        )
    }

    private fun readHardwareIdentity(sdkInt: Int): HardwareIdentitySnapshot {
        val securityPatch = if (sdkInt >= Build.VERSION_CODES.M) {
            try {
                Build.VERSION.SECURITY_PATCH ?: ""
            } catch (_: Exception) {
                ""
            }
        } else {
            ""
        }
        return HardwareIdentitySnapshot(
            manufacturer = Build.MANUFACTURER ?: "",
            brand = Build.BRAND ?: "",
            model = Build.MODEL ?: "",
            device = Build.DEVICE ?: "",
            board = Build.BOARD ?: "",
            hardware = Build.HARDWARE ?: "",
            product = Build.PRODUCT ?: "",
            buildFingerprint = Build.FINGERPRINT ?: "",
            securityPatch = securityPatch,
            osRelease = Build.VERSION.RELEASE ?: ""
        )
    }

    private fun readUserQualification(sdkInt: Int, warnings: MutableList<String>): UserQualification {
        // FR-07: On API 23, system user is not treated as reliably confirmed via public read.
        if (sdkInt <= Build.VERSION_CODES.M) {
            return UserQualification.UNCONFIRMED_API23_USER
        }
        return try {
            val um = context.getSystemService(Context.USER_SERVICE) as? UserManager
            if (um == null) {
                warnings.add("USER_MANAGER_NULL")
                UserQualification.UNKNOWN
            } else if (um.isSystemUser) {
                UserQualification.SYSTEM_OR_MAIN_USER
            } else {
                UserQualification.SECONDARY_OR_PROFILE_USER
            }
        } catch (e: Exception) {
            warnings.add("USER_READ_ERROR:${e.javaClass.simpleName}")
            UserQualification.UNKNOWN
        }
    }

    private fun readLockScreenStatus(warnings: MutableList<String>): LockScreenStatus {
        return try {
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            if (km == null) {
                warnings.add("KEYGUARD_MANAGER_NULL")
                LockScreenStatus.UNKNOWN
            } else if (km.isDeviceSecure) {
                LockScreenStatus.SECURE
            } else {
                LockScreenStatus.INSECURE
            }
        } catch (e: Exception) {
            warnings.add("KEYGUARD_READ_ERROR:${e.javaClass.simpleName}")
            LockScreenStatus.UNKNOWN
        }
    }

    private fun readEncryptionStatus(
        dpm: DevicePolicyManager?,
        warnings: MutableList<String>
    ): EncryptionStatus {
        if (dpm == null) return EncryptionStatus.UNKNOWN
        return try {
            val rawStatus = dpm.storageEncryptionStatus
            EncryptionStatus.fromDpmConstant(rawStatus)
        } catch (e: Exception) {
            warnings.add("ENCRYPTION_READ_ERROR:${e.javaClass.simpleName}")
            EncryptionStatus.UNKNOWN
        }
    }

    private fun readEuiccFeature(sdkInt: Int, warnings: MutableList<String>): Boolean? {
        // FR-08: Below API 28, eSIM feature is "unknown", not "absent".
        if (sdkInt < Build.VERSION_CODES.P) {
            return null
        }
        return try {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_EUICC)
        } catch (e: Exception) {
            warnings.add("EUICC_FEATURE_READ_ERROR:${e.javaClass.simpleName}")
            null
        }
    }
}
