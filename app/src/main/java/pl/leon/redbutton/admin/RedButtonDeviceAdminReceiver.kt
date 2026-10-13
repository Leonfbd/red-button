package pl.leon.redbutton.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Standard Android DeviceAdminReceiver declaring the <wipe-data /> policy.
 *
 * Per FR-01: Granting administrator rights in Android Settings is a separate system process
 * and never triggers a device reset by itself.
 */
class RedButtonDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        // Intentionally no wipe action on enabling admin (FR-01).
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
    }

    companion object {
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, RedButtonDeviceAdminReceiver::class.java)
        }
    }
}
