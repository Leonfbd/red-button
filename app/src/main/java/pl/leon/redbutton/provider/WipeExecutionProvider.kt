package pl.leon.redbutton.provider

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import pl.leon.redbutton.admin.RedButtonDeviceAdminReceiver
import pl.leon.redbutton.model.HardwareIdentitySnapshot
import pl.leon.redbutton.model.ManagementRole
import pl.leon.redbutton.model.UserQualification
import pl.leon.redbutton.model.WipeApiPath
import pl.leon.redbutton.model.WipeFlags
import pl.leon.redbutton.policy.DevicePolicyEvaluator

data class ExpectedExecutionSnapshot(
    val sdkInt: Int,
    val managementRole: ManagementRole,
    val wipeApiPath: WipeApiPath,
    val hasActiveWipePolicy: Boolean,
    val userQualification: UserQualification,
    val hardwareIdentity: HardwareIdentitySnapshot
)

sealed class ProviderCallResult {
    data object Accepted : ProviderCallResult()
    data class Refused(val code: String, val message: String) : ProviderCallResult()
    data class FailedWithError(val code: String, val message: String) : ProviderCallResult()
}

interface WipeExecutionProvider {
    fun requestWipe(
        path: WipeApiPath,
        flags: Int,
        expectedSnapshot: ExpectedExecutionSnapshot? = null
    ): ProviderCallResult
}

/**
 * Production DevicePolicyManager wipe provider (FR-05, FR-07, FR-08, FR-09).
 *
 * Uses ONLY supported public Android DevicePolicyManager APIs:
 * - API 23–33: DevicePolicyManager.wipeData(int)
 * - API 34+: DevicePolicyManager.wipeDevice(int) (or via parent profile DPM instance)
 *
 * Per FR-05 & FR-07: Re-detects current device and permission state immediately before
 * invoking the native DPM method and refuses if any condition or continuity check fails.
 * Never uses shell commands, root, hidden APIs, or file-deletion fallbacks.
 */
class AndroidDpmWipeExecutionProvider(
    private val context: Context,
    private val inspector: DeviceStateInspector = AndroidDeviceStateInspector(context),
    private val evaluator: DevicePolicyEvaluator = DevicePolicyEvaluator()
) : WipeExecutionProvider {

    @SuppressLint("NewApi")
    override fun requestWipe(
        path: WipeApiPath,
        flags: Int,
        expectedSnapshot: ExpectedExecutionSnapshot?
    ): ProviderCallResult {
        if (path == WipeApiPath.NONE) {
            return ProviderCallResult.Refused("UNSUPPORTED_PATH", "Wipe API path is NONE")
        }

        // FR-08: WIPE_RESET_PROTECTION_DATA and WIPE_SILENTLY are strictly forbidden.
        if (WipeFlags.containsForbiddenFlags(flags)) {
            return ProviderCallResult.Refused(
                "FORBIDDEN_FLAGS",
                "Forbidden wipe flags (FRP or SILENT) are never allowed"
            )
        }
        val cleanFlags = WipeFlags.sanitizeAllowedFlags(flags)

        // FR-05 & FR-07: Provider re-detects current state and checks device/permission continuity
        // immediately before issuing the native DPM request.
        val liveEvaluation = try {
            evaluator.evaluate(inspector.inspectCurrentState())
        } catch (e: Exception) {
            return ProviderCallResult.Refused(
                "PROVIDER_REINSPECTION_FAILED",
                e.message ?: "Provider failed to re-inspect device state"
            )
        }

        if (!liveEvaluation.isReadyForRealMode || liveEvaluation.wipeApiPath != path) {
            return ProviderCallResult.Refused(
                "PROVIDER_STATE_NOT_READY",
                "Device state or DPM path is no longer qualified immediately before request"
            )
        }

        if (expectedSnapshot != null) {
            val idMatch = expectedSnapshot.hardwareIdentity.matchesHardwareFamily(
                liveEvaluation.hardwareIdentity
            )
            if (!idMatch.matches) {
                return ProviderCallResult.Refused(
                    "PROVIDER_IDENTITY_MISMATCH",
                    "Hardware-family identity mismatch detected by provider"
                )
            }
            if (liveEvaluation.sdkInt != expectedSnapshot.sdkInt ||
                liveEvaluation.managementRole != expectedSnapshot.managementRole ||
                liveEvaluation.wipeApiPath != expectedSnapshot.wipeApiPath ||
                liveEvaluation.hasActiveWipePolicy != expectedSnapshot.hasActiveWipePolicy ||
                liveEvaluation.userQualification != expectedSnapshot.userQualification
            ) {
                return ProviderCallResult.Refused(
                    "PROVIDER_CONTINUITY_MISMATCH",
                    "API, role, policy, or user changed immediately before provider request"
                )
            }
        }

        val dpm = try {
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        } catch (e: Exception) {
            return ProviderCallResult.FailedWithError("DPM_SERVICE_ERROR", e.message ?: "DPM error")
        } ?: return ProviderCallResult.Refused("DPM_UNAVAILABLE", "DevicePolicyManager is null")

        val adminComponent = RedButtonDeviceAdminReceiver.getComponentName(context)

        return try {
            when (path) {
                WipeApiPath.LEGACY_WIPE_DATA -> {
                    if (Build.VERSION.SDK_INT !in 23..33) {
                        return ProviderCallResult.Refused(
                            "API_PATH_MISMATCH",
                            "wipeData(int) path is restricted to API 23..33"
                        )
                    }
                    dpm.wipeData(cleanFlags)
                    ProviderCallResult.Accepted
                }
                WipeApiPath.MODERN_WIPE_DEVICE -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        return ProviderCallResult.Refused(
                            "API_PATH_MISMATCH",
                            "wipeDevice(int) requires API 34+"
                        )
                    }
                    dpm.wipeDevice(cleanFlags)
                    ProviderCallResult.Accepted
                }
                WipeApiPath.MODERN_PARENT_PROFILE_WIPE_DEVICE -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        return ProviderCallResult.Refused(
                            "API_PATH_MISMATCH",
                            "Parent profile wipeDevice(int) requires API 34+"
                        )
                    }
                    val parentDpm = dpm.getParentProfileInstance(adminComponent)
                    parentDpm.wipeDevice(cleanFlags)
                    ProviderCallResult.Accepted
                }
                WipeApiPath.NONE -> {
                    ProviderCallResult.Refused("UNSUPPORTED_PATH", "No wipe path")
                }
            }
        } catch (se: SecurityException) {
            ProviderCallResult.Refused(
                "DPM_SECURITY_EXCEPTION",
                se.message ?: "SecurityException rejected by Android DPM"
            )
        } catch (iae: IllegalArgumentException) {
            ProviderCallResult.Refused(
                "DPM_ILLEGAL_ARGUMENT",
                iae.message ?: "IllegalArgumentException from Android DPM"
            )
        } catch (ise: IllegalStateException) {
            ProviderCallResult.Refused(
                "DPM_ILLEGAL_STATE",
                ise.message ?: "IllegalStateException from Android DPM"
            )
        } catch (t: Throwable) {
            ProviderCallResult.FailedWithError(
                "DPM_UNEXPECTED_${t.javaClass.simpleName}",
                t.message ?: "Unexpected exception during DPM call"
            )
        }
    }
}

/**
 * Fake provider for unit tests and Compose instrumentation tests (FR-03, Section 10).
 * Never calls Android DevicePolicyManager.
 */
class FakeWipeExecutionProvider(
    var nextResult: ProviderCallResult = ProviderCallResult.Accepted,
    var onBeforeRequest: (() -> Unit)? = null
) : WipeExecutionProvider {

    data class RecordedCall(
        val path: WipeApiPath,
        val flags: Int,
        val expectedSnapshot: ExpectedExecutionSnapshot?
    )

    private val _calls = mutableListOf<RecordedCall>()
    val calls: List<RecordedCall> get() = _calls.toList()
    val callCount: Int get() = _calls.size

    override fun requestWipe(
        path: WipeApiPath,
        flags: Int,
        expectedSnapshot: ExpectedExecutionSnapshot?
    ): ProviderCallResult {
        onBeforeRequest?.invoke()
        _calls.add(RecordedCall(path, flags, expectedSnapshot))
        return nextResult
    }
}
