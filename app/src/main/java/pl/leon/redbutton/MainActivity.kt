package pl.leon.redbutton

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import pl.leon.redbutton.admin.RedButtonDeviceAdminReceiver
import pl.leon.redbutton.execution.ArmedLaunchExecutor
import pl.leon.redbutton.policy.DevicePolicyEvaluator
import pl.leon.redbutton.provider.AndroidDeviceStateInspector
import pl.leon.redbutton.provider.AndroidDpmWipeExecutionProvider
import pl.leon.redbutton.storage.RedButtonStorage
import pl.leon.redbutton.storage.SharedPreferencesStorageBackend
import pl.leon.redbutton.ui.RedButtonApp
import pl.leon.redbutton.ui.RedButtonSessionController
import pl.leon.redbutton.ui.SavedSessionState

class RedButtonViewModel(
    val controller: RedButtonSessionController
) : ViewModel()

class RedButtonViewModelFactory(
    private val appContext: android.content.Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val customFactory = testControllerFactoryOverride
        if (customFactory != null) {
            return RedButtonViewModel(customFactory(appContext)) as T
        }
        val storage = RedButtonStorage(SharedPreferencesStorageBackend(appContext))
        val inspector = AndroidDeviceStateInspector(appContext)
        val evaluator = DevicePolicyEvaluator()
        val wipeProvider = AndroidDpmWipeExecutionProvider(appContext, inspector, evaluator)
        val executor = ArmedLaunchExecutor(
            storage = storage,
            inspector = inspector,
            evaluator = evaluator,
            wipeExecutionProvider = wipeProvider
        )
        val controller = RedButtonSessionController(
            executor = executor,
            storage = storage
        )
        return RedButtonViewModel(controller) as T
    }

    companion object {
        @Volatile
        var testControllerFactoryOverride: ((android.content.Context) -> RedButtonSessionController)? = null
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: RedButtonViewModel

    private val addDeviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // FR-01: Granting Device Admin is a separate system process and never triggers a wipe.
        // We only refresh the read-only readiness inspection when returning.
        if (::viewModel.isInitialized) {
            viewModel.controller.refreshDeviceInspection()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel = ViewModelProvider(
            this,
            RedButtonViewModelFactory(applicationContext)
        )[RedButtonViewModel::class.java]

        val savedSession = savedInstanceState?.getString(KEY_STAGE_NAME)?.let { stageName ->
            SavedSessionState(
                stageName = stageName,
                armedExecutionModeName = savedInstanceState.getString(KEY_ARMED_MODE_NAME),
                commonConsentChecked = savedInstanceState.getBoolean(KEY_COMMON_CONSENT, false),
                realModeConsentChecked = savedInstanceState.getBoolean(KEY_REAL_CONSENT, false)
            )
        }

        // Section 9 & 10: Safe across Activity recreations (e.g., screen rotation or process death).
        // Evaluates launch and consumes armed consent at most once per session.
        viewModel.controller.onLaunchOrRecreate(savedSession)

        setContent {
            RedButtonApp(
                controller = viewModel.controller,
                onRequestDeviceAdmin = ::launchDeviceAdminActivation,
                onCloseApplication = { finish() }
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::viewModel.isInitialized) {
            val snapshot = viewModel.controller.snapshotSessionState()
            outState.putString(KEY_STAGE_NAME, snapshot.stageName)
            if (snapshot.armedExecutionModeName != null) {
                outState.putString(KEY_ARMED_MODE_NAME, snapshot.armedExecutionModeName)
            }
            outState.putBoolean(KEY_COMMON_CONSENT, snapshot.commonConsentChecked)
            outState.putBoolean(KEY_REAL_CONSENT, snapshot.realModeConsentChecked)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::viewModel.isInitialized) {
            viewModel.controller.refreshDeviceInspection()
        }
    }

    private fun launchDeviceAdminActivation() {
        try {
            val adminComponent = RedButtonDeviceAdminReceiver.getComponentName(this)
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    getString(R.string.device_admin_description)
                )
            }
            addDeviceAdminLauncher.launch(intent)
        } catch (_: Exception) {
            // Fail gracefully if the OEM/system does not expose the Device Admin screen.
        }
    }

    companion object {
        private const val KEY_STAGE_NAME = "rb_stage_name"
        private const val KEY_ARMED_MODE_NAME = "rb_armed_mode_name"
        private const val KEY_COMMON_CONSENT = "rb_common_consent"
        private const val KEY_REAL_CONSENT = "rb_real_consent"
    }
}
