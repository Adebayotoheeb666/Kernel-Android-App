package com.amirapay.mgmt.admin

import android.app.admin.DevicePolicyManager
import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log
import com.amirapay.mgmt.service.LockOverlayService
import com.amirapay.mgmt.network.ApiClient
import com.amirapay.mgmt.network.TamperRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * DeviceAdminManager
 *
 * Extends DeviceAdminReceiver to handle device admin lifecycle events.
 *
 * IMPORTANT: For full Device Owner capabilities (required for dpm.lockNow() without user
 * intervention), the app must be set as Device Owner via ADB during device provisioning:
 *   adb shell dpm set-device-owner com.amirapay.mgmt/.admin.DeviceAdminManager
 *
 * This cannot be done once the device has regular user accounts already set up.
 */
class DeviceAdminManager : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "DeviceAdminManager"
    }

    /**
     * Called when this app is granted Device Administrator privileges.
     */
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Admin ENABLED — DLS is now active.")

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = getWho(context)

        // If the app is set as Device Owner, apply critical persistency restrictions
        if (dpm.isDeviceOwnerApp(context.packageName)) {
            Log.i(TAG, "App is Device Owner. Applying persistence restrictions...")
            try {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS)
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
                Log.i(TAG, "Restrictions applied: DISALLOW_UNINSTALL_APPS, DISALLOW_FACTORY_RESET")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply user restrictions", e)
            }
        } else {
            Log.w(TAG, "App is not Device Owner. Some restrictions (uninstall/reset) cannot be enforced.")
        }

        // Save admin status in prefs
        context.getSharedPreferences("dls_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("is_admin_active", true)
            .apply()
    }

    /**
     * Called just before Device Admin is about to be disabled.
     * This is the last chance to react — we lock the device immediately
     * and notify the server that someone is trying to remove admin rights.
     */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Log.w(TAG, "Device Admin DISABLE REQUESTED — locking device and notifying server.")

        // Immediately trigger a lock + start overlay
        val lockIntent = Intent(context, LockOverlayService::class.java).apply {
            action = LockOverlayService.ACTION_LOCK
        }
        context.startForegroundService(lockIntent)

        // Notify server asynchronously
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val deviceId = context.getSharedPreferences("dls_prefs", Context.MODE_PRIVATE)
                    .getString("device_id", null)
                if (deviceId != null) {
                    ApiClient.apiService.notifyTamper(TamperRequest(deviceId, "ADMIN_DISABLE_ATTEMPTED"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to notify server of tamper attempt", e)
            }
        }

        return context.getString(com.amirapay.mgmt.R.string.admin_disable_warning)
    }

    /**
     * Called after Device Admin has been fully disabled.
     */
    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.e(TAG, "Device Admin DISABLED — DLS protection removed!")
        context.getSharedPreferences("dls_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("is_admin_active", false)
            .apply()

        // Notify server that protection has been removed
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val deviceId = context.getSharedPreferences("dls_prefs", Context.MODE_PRIVATE)
                    .getString("device_id", null)
                if (deviceId != null) {
                    ApiClient.apiService.notifyTamper(TamperRequest(deviceId, "ADMIN_DISABLED"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to notify server of admin disabled event", e)
            }
        }
    }

    /**
     * Called when the device is locked due to password failure.
     */
    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)
        Log.w(TAG, "Password attempt failed on locked device.")
    }
}
