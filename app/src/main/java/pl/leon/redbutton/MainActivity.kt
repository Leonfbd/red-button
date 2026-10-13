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

class RedButtonViewModel(
    val controller: RedButtonSessionController
) : ViewModel()

class RedButtonViewModelFactory(
    private val appContext: android.content.Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val storage = RedButtonStorage(SharedPreferencesStorageBackend(appContext))
        val inspector = AndroidDeviceStateInspector(appContext)
        val evaluator = DevicePolicyEvaluator()
        val wipeProvider = AndroidDpmWipeExecutionProvider(appContext)
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

        // Section 9 & 10: Safe across Activity recreations (e.g., screen rotation).
        // Evaluates launch and consumes armed consent at most once per ViewModel session.
        viewModel.controller.onLaunchOrRecreate()

        setContent {
            RedButtonApp(
                controller = viewModel.controller,
                onRequestDeviceAdmin = ::launchDeviceAdminActivation,
                onCloseApplication = { finish() }
            )
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
}
