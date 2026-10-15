package pl.leon.redbutton.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import pl.leon.redbutton.model.AttemptOutcome
import pl.leon.redbutton.model.AttemptReport
import pl.leon.redbutton.model.BlockingReason
import pl.leon.redbutton.model.ConsentRecord
import pl.leon.redbutton.model.DeviceProfile
import pl.leon.redbutton.model.EncryptionStatus
import pl.leon.redbutton.model.EuiccFeatureStatus
import pl.leon.redbutton.model.ExecutionMode
import pl.leon.redbutton.model.HardwareIdentitySnapshot
import pl.leon.redbutton.model.LockScreenStatus
import pl.leon.redbutton.model.ManagementRole
import pl.leon.redbutton.model.SanitizationLevel
import pl.leon.redbutton.model.UserQualification
import pl.leon.redbutton.model.WipeApiPath
import java.security.MessageDigest
import java.util.Collections

class StorageOperationException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface StorageBackend {
    @Throws(StorageOperationException::class)
    fun getString(key: String): String?

    @Throws(StorageOperationException::class)
    fun putStringCommit(key: String, value: String): Boolean

    @Throws(StorageOperationException::class)
    fun removeCommit(key: String): Boolean
}

class SharedPreferencesStorageBackend(context: Context) : StorageBackend {
    private val prefs = try {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    } catch (e: Exception) {
        null
    }

    override fun getString(key: String): String? {
        val p = prefs ?: throw StorageOperationException("SharedPreferences unavailable")
        return try {
            p.getString(key, null)
        } catch (e: Exception) {
            throw StorageOperationException("Failed reading key $key", e)
        }
    }

    override fun putStringCommit(key: String, value: String): Boolean {
        val p = prefs ?: throw StorageOperationException("SharedPreferences unavailable")
        return try {
            p.edit().putString(key, value).commit()
        } catch (e: Exception) {
            throw StorageOperationException("Uncertain commit state for key $key", e)
        }
    }

    override fun removeCommit(key: String): Boolean {
        val p = prefs ?: throw StorageOperationException("SharedPreferences unavailable")
        return try {
            p.edit().remove(key).commit()
        } catch (e: Exception) {
            throw StorageOperationException("Uncertain remove state for key $key", e)
        }
    }

    companion object {
        const val PREFS_NAME = "pl.leon.redbutton.private_store"
    }
}

/**
 * In-memory StorageBackend for JVM and Compose tests, with deterministic fault injection.
 */
class InMemoryStorageBackend : StorageBackend {
    private val map = LinkedHashMap<String, String>()

    @Volatile
    var throwOnReadKeys: Set<String> = emptySet()

    @Volatile
    var returnFalseOnWriteKeys: Set<String> = emptySet()

    @Volatile
    var throwUncertainOnWriteKeys: Set<String> = emptySet()

    @Volatile
    var returnFalseOnRemoveKeys: Set<String> = emptySet()

    @Synchronized
    override fun getString(key: String): String? {
        if (throwOnReadKeys.contains(key)) {
            throw StorageOperationException("Simulated read error for key=$key")
        }
        return map[key]
    }

    @Synchronized
    override fun putStringCommit(key: String, value: String): Boolean {
        if (throwUncertainOnWriteKeys.contains(key)) {
            throw StorageOperationException("Simulated uncertain write exception for key=$key")
        }
        if (returnFalseOnWriteKeys.contains(key)) {
            return false
        }
        map[key] = value
        return true
    }

    @Synchronized
    override fun removeCommit(key: String): Boolean {
        if (returnFalseOnRemoveKeys.contains(key)) {
            return false
        }
        map.remove(key)
        return true
    }

    @Synchronized
    fun putRawForTest(key: String, rawValue: String) {
        map[key] = rawValue
    }

    @Synchronized
    fun getRawForTest(key: String): String? = map[key]
}

data class TestModeReadResult(
    val testModeEnabled: Boolean,
    val fallbackTriggered: Boolean
)

sealed class ArmConsentResult {
    data class Armed(val record: ConsentRecord) : ArmConsentResult()
    data object ConsentNotConfirmed : ArmConsentResult()
    data object WriteFailed : ArmConsentResult()
    data object WriteUncertain : ArmConsentResult()
}

sealed class ConsumeConsentResult {
    data object NoPendingConsent : ConsumeConsentResult()
    data class AlreadyConsumed(val record: ConsentRecord) : ConsumeConsentResult()
    data class Consumed(val record: ConsentRecord) : ConsumeConsentResult()
    data class CorruptedOrIncompatible(
        val reason: String,
        val storageStateUncertain: Boolean
    ) : ConsumeConsentResult()
    data class ConsumptionWriteFailed(
        val storageStateUncertain: Boolean
    ) : ConsumeConsentResult()
}

/**
 * Local storage manager enforcing FR-03, FR-04, FR-05, FR-09, and Section 8.
 */
class RedButtonStorage(private val backend: StorageBackend) {

    private val consumedTokenIdsInProcess: MutableSet<String> =
        Collections.synchronizedSet( LinkedHashSet<String>() )

    /**
     * FR-03: Default TEST MODE is disabled (false).
     * On read error or corrupted preference value, falls back to safe value: TEST MODE enabled (true).
     */
    fun readTestModePreference(): TestModeReadResult {
        return try {
            when (val raw = backend.getString(KEY_TEST_MODE)) {
                null -> TestModeReadResult(testModeEnabled = false, fallbackTriggered = false)
                "true" -> TestModeReadResult(testModeEnabled = true, fallbackTriggered = false)
                "false" -> TestModeReadResult(testModeEnabled = false, fallbackTriggered = false)
                else -> TestModeReadResult(testModeEnabled = true, fallbackTriggered = true)
            }
        } catch (_: Exception) {
            TestModeReadResult(testModeEnabled = true, fallbackTriggered = true)
        }
    }

    fun writeTestModePreference(enabled: Boolean): Boolean {
        return try {
            backend.putStringCommit(KEY_TEST_MODE, if (enabled) "true" else "false")
        } catch (_: Exception) {
            false
        }
    }

    /**
     * FR-04: Durably writes a one-time consent record with schema version and SHA-256 integrity hash.
     * Write failure blocks closing the screen as armed; uncertain write warns the user.
     */
    @Synchronized
    fun armConsent(record: ConsentRecord): ArmConsentResult {
        if (!record.consentConfirmed) {
            return ArmConsentResult.ConsentNotConfirmed
        }
        val unconsumedRecord = record.copy(
            schemaVersion = CURRENT_CONSENT_SCHEMA_VERSION,
            consumed = false
        )
        val serialized = serializeConsentRecord(unconsumedRecord)
        return try {
            val committed = backend.putStringCommit(KEY_CONSENT_RECORD, serialized)
            if (committed) {
                ArmConsentResult.Armed(unconsumedRecord)
            } else {
                ArmConsentResult.WriteFailed
            }
        } catch (_: Exception) {
            ArmConsentResult.WriteUncertain
        }
    }

    /**
     * FR-04: Atomically consumes a pending one-time consent BEFORE executive analysis or provider call.
     * Any incomplete, unknown, tampered, or schema-mismatched record is invalidated and never triggers a wipe.
     */
    @Synchronized
    fun consumePendingConsent(): ConsumeConsentResult {
        val raw = try {
            backend.getString(KEY_CONSENT_RECORD)
        } catch (_: Exception) {
            val invalidated = tryInvalidateConsentRecord()
            return ConsumeConsentResult.ConsumptionWriteFailed(storageStateUncertain = !invalidated)
        }

        if (raw == null) {
            return ConsumeConsentResult.NoPendingConsent
        }

        val parsed = parseAndValidateConsentRecord(raw)
        if (parsed is ParsedConsent.Invalid) {
            val invalidated = tryInvalidateConsentRecord()
            return ConsumeConsentResult.CorruptedOrIncompatible(
                reason = parsed.reason,
                storageStateUncertain = !invalidated
            )
        }

        val record = (parsed as ParsedConsent.Valid).record
        if (record.consumed || consumedTokenIdsInProcess.contains(record.tokenId)) {
            return ConsumeConsentResult.AlreadyConsumed(record)
        }

        // Mark consumed in-memory and durably in storage BEFORE returning for analysis/execution
        consumedTokenIdsInProcess.add(record.tokenId)
        val consumedRecord = record.copy(consumed = true)
        val serializedConsumed = serializeConsentRecord(consumedRecord)

        return try {
            val committed = backend.putStringCommit(KEY_CONSENT_RECORD, serializedConsumed)
            if (committed) {
                ConsumeConsentResult.Consumed(consumedRecord)
            } else {
                val removed = tryInvalidateConsentRecord()
                ConsumeConsentResult.ConsumptionWriteFailed(storageStateUncertain = !removed)
            }
        } catch (_: Exception) {
            val removed = tryInvalidateConsentRecord()
            ConsumeConsentResult.ConsumptionWriteFailed(storageStateUncertain = !removed)
        }
    }

    private fun tryInvalidateConsentRecord(): Boolean {
        return try {
            backend.removeCommit(KEY_CONSENT_RECORD)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Section 8: Stores local technical profile in private app storage.
     */
    fun saveDeviceProfile(profile: DeviceProfile): Boolean {
        return try {
            val json = JSONObject().apply {
                put("capturedAtEpochMs", profile.capturedAtEpochMs)
                put("sdkInt", profile.sdkInt)
                put("hardwareIdentity", serializeHardwareIdentity(profile.hardwareIdentity))
                put("managementRole", profile.managementRole.name)
                put("hasActiveWipePolicy", profile.hasActiveWipePolicy)
                put("userQualification", profile.userQualification.name)
                put("lockScreenStatus", profile.lockScreenStatus.name)
                put("encryptionStatus", profile.encryptionStatus.name)
                put("euiccFeatureStatus", profile.euiccFeatureStatus.name)
                put("wipeApiPath", profile.wipeApiPath.name)
                put("supportsExternalStorageFlag", profile.supportsExternalStorageFlag)
                put("supportsEuiccFlag", profile.supportsEuiccFlag)
                put("isReadyForRealMode", profile.isReadyForRealMode)
                put("sanitizationLevel", profile.sanitizationLevel.name)
                put("blockingReasons", JSONArray(profile.blockingReasons.map { it.name }))
                put("warnings", JSONArray(profile.warnings))
            }
            backend.putStringCommit(KEY_DEVICE_PROFILE, json.toString())
        } catch (_: Exception) {
            false
        }
    }

    fun readDeviceProfile(): DeviceProfile? {
        return try {
            val raw = backend.getString(KEY_DEVICE_PROFILE) ?: return null
            val json = JSONObject(raw)
            val blockingArray = json.optJSONArray("blockingReasons") ?: JSONArray()
            val blocking = mutableListOf<BlockingReason>()
            for (i in 0 until blockingArray.length()) {
                blocking.add(BlockingReason.valueOf(blockingArray.getString(i)))
            }
            val warningsArray = json.optJSONArray("warnings") ?: JSONArray()
            val warnings = mutableListOf<String>()
            for (i in 0 until warningsArray.length()) {
                warnings.add(warningsArray.getString(i))
            }
            DeviceProfile(
                capturedAtEpochMs = json.getLong("capturedAtEpochMs"),
                sdkInt = json.getInt("sdkInt"),
                hardwareIdentity = parseHardwareIdentityStrict(json.getJSONObject("hardwareIdentity")),
                managementRole = ManagementRole.valueOf(json.getString("managementRole")),
                hasActiveWipePolicy = json.getBoolean("hasActiveWipePolicy"),
                userQualification = UserQualification.valueOf(json.getString("userQualification")),
                lockScreenStatus = LockScreenStatus.valueOf(json.getString("lockScreenStatus")),
                encryptionStatus = EncryptionStatus.valueOf(json.getString("encryptionStatus")),
                euiccFeatureStatus = EuiccFeatureStatus.valueOf(json.getString("euiccFeatureStatus")),
                wipeApiPath = WipeApiPath.valueOf(json.getString("wipeApiPath")),
                supportsExternalStorageFlag = json.getBoolean("supportsExternalStorageFlag"),
                supportsEuiccFlag = json.getBoolean("supportsEuiccFlag"),
                isReadyForRealMode = json.getBoolean("isReadyForRealMode"),
                sanitizationLevel = SanitizationLevel.valueOf(json.getString("sanitizationLevel")),
                blockingReasons = blocking,
                warnings = warnings
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Section 8 & FR-09: Overwrites the single last attempt report (never an unbounded log).
     */
    fun saveAttemptReport(report: AttemptReport): Boolean {
        return try {
            val json = JSONObject().apply {
                put("timestampEpochMs", report.timestampEpochMs)
                put("makeAndModel", report.makeAndModel)
                put("androidRelease", report.androidRelease)
                put("sdkInt", report.sdkInt)
                put("executionMode", report.executionMode.name)
                put("selectedProviderPath", report.selectedProviderPath.name)
                put("requestedFlagsMask", report.requestedFlagsMask)
                put("requestedFlagsNames", JSONArray(report.requestedFlagsNames))
                put("sanitizationLevel", report.sanitizationLevel.name)
                put("outcome", report.outcome.name)
                put("detailCode", report.detailCode)
                put("detailMessage", report.detailMessage)
            }
            backend.putStringCommit(KEY_LAST_ATTEMPT_REPORT, json.toString())
        } catch (_: Exception) {
            false
        }
    }

    fun readLastAttemptReport(): AttemptReport? {
        return try {
            val raw = backend.getString(KEY_LAST_ATTEMPT_REPORT) ?: return null
            val json = JSONObject(raw)
            val namesArr = json.getJSONArray("requestedFlagsNames")
            val names = mutableListOf<String>()
            for (i in 0 until namesArr.length()) {
                names.add(namesArr.getString(i))
            }
            AttemptReport(
                timestampEpochMs = json.getLong("timestampEpochMs"),
                makeAndModel = json.getString("makeAndModel"),
                androidRelease = json.getString("androidRelease"),
                sdkInt = json.getInt("sdkInt"),
                executionMode = ExecutionMode.valueOf(json.getString("executionMode")),
                selectedProviderPath = WipeApiPath.valueOf(json.getString("selectedProviderPath")),
                requestedFlagsMask = json.getInt("requestedFlagsMask"),
                requestedFlagsNames = names,
                sanitizationLevel = SanitizationLevel.valueOf(json.getString("sanitizationLevel")),
                outcome = AttemptOutcome.valueOf(json.getString("outcome")),
                detailCode = json.getString("detailCode"),
                detailMessage = json.getString("detailMessage")
            )
        } catch (_: Exception) {
            null
        }
    }

    private sealed class ParsedConsent {
        data class Valid(val record: ConsentRecord) : ParsedConsent()
        data class Invalid(val reason: String) : ParsedConsent()
    }

    private fun serializeConsentRecord(record: ConsentRecord): String {
        val hash = computeIntegrityHash(record)
        return JSONObject().apply {
            put("schemaVersion", record.schemaVersion)
            put("tokenId", record.tokenId)
            put("executionMode", record.executionMode.name)
            put("wipeExternalStorage", record.wipeExternalStorage)
            put("wipeEuicc", record.wipeEuicc)
            put("consentConfirmed", record.consentConfirmed)
            put("armedAtEpochMs", record.armedAtEpochMs)
            put("armedSdkInt", record.armedSdkInt)
            put("armedManagementRole", record.armedManagementRole.name)
            put("armedWipeApiPath", record.armedWipeApiPath.name)
            put("armedHasActiveWipePolicy", record.armedHasActiveWipePolicy)
            put("armedUserQualification", record.armedUserQualification.name)
            put("hardwareIdentity", serializeHardwareIdentity(record.hardwareIdentity))
            put("consumed", record.consumed)
            put("integrityHash", hash)
        }.toString()
    }

    /**
     * FR-04: Strict validation of every required field, its runtime type, schema version,
     * and SHA-256 integrity hash. Never supplies default values for missing fields.
     */
    private fun parseAndValidateConsentRecord(rawJson: String): ParsedConsent {
        val json = try {
            JSONObject(rawJson)
        } catch (_: JSONException) {
            return ParsedConsent.Invalid("MALFORMED_JSON")
        }

        val requiredKeys = listOf(
            "schemaVersion",
            "tokenId",
            "executionMode",
            "wipeExternalStorage",
            "wipeEuicc",
            "consentConfirmed",
            "armedAtEpochMs",
            "armedSdkInt",
            "armedManagementRole",
            "armedWipeApiPath",
            "armedHasActiveWipePolicy",
            "armedUserQualification",
            "hardwareIdentity",
            "consumed",
            "integrityHash"
        )
        for (key in requiredKeys) {
            if (!json.has(key) || json.isNull(key)) {
                return ParsedConsent.Invalid("MISSING_REQUIRED_FIELD:$key")
            }
        }

        val schemaVersion = readStrictInt(json, "schemaVersion")
            ?: return ParsedConsent.Invalid("INVALID_TYPE:schemaVersion")
        if (schemaVersion != CURRENT_CONSENT_SCHEMA_VERSION) {
            return ParsedConsent.Invalid("UNSUPPORTED_SCHEMA_VERSION:$schemaVersion")
        }

        val tokenId = readStrictString(json, "tokenId")
            ?: return ParsedConsent.Invalid("INVALID_TYPE:tokenId")
        if (tokenId.isBlank()) {
            return ParsedConsent.Invalid("EMPTY_TOKEN_ID")
        }

        val executionModeStr = readStrictString(json, "executionMode")
            ?: return ParsedConsent.Invalid("INVALID_TYPE:executionMode")
        val executionMode = try {
            ExecutionMode.valueOf(executionModeStr)
        } catch (_: IllegalArgumentException) {
            return ParsedConsent.Invalid("UNKNOWN_EXECUTION_MODE:$executionModeStr")
        }

        val wipeExternalStorage = readStrictBoolean(json, "wipeExternalStorage")
            ?: return ParsedConsent.Invalid("INVALID_TYPE:wipeExternalStorage")
        val wipeEuicc = readStrictBoolean(json, "wipeEuicc")
            ?: return ParsedConsent.Invalid("INVALID_TYPE:wipeEuicc")
        val consentConfirmed = readStrictBoolean(json, "consentConfirmed")
            ?: return ParsedConsent.Invalid("INVALID_TYPE:consentConfirmed")
        if (!consentConfirmed) {
            return ParsedConsent.Invalid("CONSENT_NOT_CONFIRMED")
        }

        val armedAtEpochMs = readStrictLong(json, "armedAtEpochMs")
            ?: return ParsedConsent.Invalid("INVALID_TYPE:armedAtEpochMs")
        if (armedAtEpochMs <= 0L) {
            return ParsedConsent.Invalid("INVALID_TIMESTAMP")
        }

        val armedSdkInt = readStrictInt(json, "armedSdkInt")
            ?: return ParsedConsent.Invalid("INVALID_TYPE:armedSdkInt")

        val roleStr = readStrictString(json, "armedManagementRole")
            ?: return ParsedConsent.Invalid("INVALID_TYPE:armedManagementRole")
        val armedManagementRole = try {
            ManagementRole.valueOf(roleStr)
        } catch (_: IllegalArgumentException) {
            return ParsedConsent.Invalid("UNKNOWN_MANAGEMENT_ROLE:$roleStr")
        }

        val pathStr = readStrictString(json, "armedWipeApiPath")
            ?: return ParsedConsent.Invalid("INVALID_TYPE:armedWipeApiPath")
        val armedWipeApiPath = try {
            WipeApiPath.valueOf(pathStr)
        } catch (_: IllegalArgumentException) {
            return ParsedConsent.Invalid("UNKNOWN_WIPE_API_PATH:$pathStr")
        }

        val armedHasActiveWipePolicy = readStrictBoolean(json, "armedHasActiveWipePolicy")
            ?: return ParsedConsent.Invalid("INVALID_TYPE:armedHasActiveWipePolicy")

        val userQualStr = readStrictString(json, "armedUserQualification")
            ?: return ParsedConsent.Invalid("INVALID_TYPE:armedUserQualification")
        val armedUserQualification = try {
            UserQualification.valueOf(userQualStr)
        } catch (_: IllegalArgumentException) {
            return ParsedConsent.Invalid("UNKNOWN_USER_QUALIFICATION:$userQualStr")
        }

        val hwObj = json.optJSONObject("hardwareIdentity")
            ?: return ParsedConsent.Invalid("INVALID_TYPE:hardwareIdentity")
        val hardwareIdentity = try {
            parseHardwareIdentityStrict(hwObj)
        } catch (e: IllegalArgumentException) {
            return ParsedConsent.Invalid("INVALID_HARDWARE_IDENTITY:${e.message}")
        }

        val consumed = readStrictBoolean(json, "consumed")
            ?: return ParsedConsent.Invalid("INVALID_TYPE:consumed")

        val storedHash = readStrictString(json, "integrityHash")
            ?: return ParsedConsent.Invalid("INVALID_TYPE:integrityHash")

        val record = ConsentRecord(
            schemaVersion = schemaVersion,
            tokenId = tokenId,
            executionMode = executionMode,
            wipeExternalStorage = wipeExternalStorage,
            wipeEuicc = wipeEuicc,
            consentConfirmed = consentConfirmed,
            armedAtEpochMs = armedAtEpochMs,
            armedSdkInt = armedSdkInt,
            armedManagementRole = armedManagementRole,
            armedWipeApiPath = armedWipeApiPath,
            armedHasActiveWipePolicy = armedHasActiveWipePolicy,
            armedUserQualification = armedUserQualification,
            hardwareIdentity = hardwareIdentity,
            consumed = consumed
        )

        val expectedHash = computeIntegrityHash(record)
        if (!expectedHash.equals(storedHash, ignoreCase = true)) {
            return ParsedConsent.Invalid("INTEGRITY_HASH_MISMATCH")
        }

        return ParsedConsent.Valid(record)
    }

    private fun serializeHardwareIdentity(hw: HardwareIdentitySnapshot): JSONObject {
        return JSONObject().apply {
            put("manufacturer", hw.manufacturer)
            put("brand", hw.brand)
            put("model", hw.model)
            put("device", hw.device)
            put("board", hw.board)
            put("hardware", hw.hardware)
            put("product", hw.product)
            put("buildFingerprint", hw.buildFingerprint)
            put("securityPatch", hw.securityPatch)
            put("osRelease", hw.osRelease)
        }
    }

    private fun parseHardwareIdentityStrict(obj: JSONObject): HardwareIdentitySnapshot {
        val required = listOf(
            "manufacturer",
            "brand",
            "model",
            "device",
            "board",
            "hardware",
            "product",
            "buildFingerprint",
            "securityPatch",
            "osRelease"
        )
        for (key in required) {
            if (!obj.has(key) || obj.isNull(key)) {
                throw IllegalArgumentException("Missing hardware field $key")
            }
            if (readStrictString(obj, key) == null) {
                throw IllegalArgumentException("Non-string hardware field $key")
            }
        }
        return HardwareIdentitySnapshot(
            manufacturer = obj.getString("manufacturer"),
            brand = obj.getString("brand"),
            model = obj.getString("model"),
            device = obj.getString("device"),
            board = obj.getString("board"),
            hardware = obj.getString("hardware"),
            product = obj.getString("product"),
            buildFingerprint = obj.getString("buildFingerprint"),
            securityPatch = obj.getString("securityPatch"),
            osRelease = obj.getString("osRelease")
        )
    }

    private fun readStrictString(json: JSONObject, key: String): String? {
        val v = json.opt(key)
        return v as? String
    }

    private fun readStrictBoolean(json: JSONObject, key: String): Boolean? {
        val v = json.opt(key)
        return v as? Boolean
    }

    private fun readStrictInt(json: JSONObject, key: String): Int? {
        val v = json.opt(key)
        return when (v) {
            is Int -> v
            is Long -> if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else null
            else -> null
        }
    }

    private fun readStrictLong(json: JSONObject, key: String): Long? {
        val v = json.opt(key)
        return when (v) {
            is Long -> v
            is Int -> v.toLong()
            else -> null
        }
    }

    private fun computeIntegrityHash(record: ConsentRecord): String {
        val canonical = buildString {
            append("v=").append(record.schemaVersion).append('|')
            append("token=").append(record.tokenId).append('|')
            append("mode=").append(record.executionMode.name).append('|')
            append("ext=").append(record.wipeExternalStorage).append('|')
            append("euicc=").append(record.wipeEuicc).append('|')
            append("confirmed=").append(record.consentConfirmed).append('|')
            append("ts=").append(record.armedAtEpochMs).append('|')
            append("sdk=").append(record.armedSdkInt).append('|')
            append("role=").append(record.armedManagementRole.name).append('|')
            append("path=").append(record.armedWipeApiPath.name).append('|')
            append("policy=").append(record.armedHasActiveWipePolicy).append('|')
            append("user=").append(record.armedUserQualification.name).append('|')
            append("hw=").append(record.hardwareIdentity.manufacturer).append(',')
            append(record.hardwareIdentity.brand).append(',')
            append(record.hardwareIdentity.model).append(',')
            append(record.hardwareIdentity.device).append(',')
            append(record.hardwareIdentity.board).append(',')
            append(record.hardwareIdentity.hardware).append(',')
            append(record.hardwareIdentity.product).append('|')
            append("consumed=").append(record.consumed)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val CURRENT_CONSENT_SCHEMA_VERSION = 1
        const val KEY_TEST_MODE = "pref_test_mode"
        const val KEY_CONSENT_RECORD = "consent_record_v1"
        const val KEY_DEVICE_PROFILE = "device_profile_v1"
        const val KEY_LAST_ATTEMPT_REPORT = "last_attempt_report_v1"
    }
}
