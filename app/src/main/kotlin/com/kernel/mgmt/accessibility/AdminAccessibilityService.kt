package com.kernel.mgmt.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.kernel.mgmt.network.ApiClient
import com.kernel.mgmt.network.TamperRequest
import com.kernel.mgmt.service.LockOverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * AdminAccessibilityService
 *
 * Monitors Settings screens and blocks the user from reaching:
 *  1. The uninstall confirmation screen for this app.
 *  2. The factory data reset confirmation screen.
 *
 * When either screen is detected, the service:
 *  - Immediately presses the global BACK button to navigate away
 *  - Shows a warning Toast
 *  - Triggers the lock overlay
 *  - Notifies the backend server of the tamper attempt
 *
 * This works WITHOUT Device Owner status and on any Android device.
 *
 * SETUP (user must grant once):
 *   Settings → Accessibility → Installed Apps → DLS → Enable
 *   OR the app auto-opens this screen via MainActivity.
 */
class AdminAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AdminAccessibility"

        // Package names that host uninstall / reset UI
        private val SETTINGS_PACKAGES = setOf(
            "com.android.settings",
            "com.samsung.android.settings",
            "com.miui.securitycenter",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller"
        )

        // Class name fragments that indicate an uninstall confirmation screen
        private val UNINSTALL_CLASS_FRAGMENTS = listOf(
            "UninstallActivity",
            "UninstallAppProgress",
            "UninstallerActivity",
            "AppInfoBase",
            "InstalledAppDetails",
            "AppInfoDashboard"
        )

        // Window title fragments that indicate factory reset screens
        private val FACTORY_RESET_TITLE_FRAGMENTS = listOf(
            "factory reset",
            "factory data reset",
            "erase all data",
            "reset phone",
            "reset device",
            "reset all settings"
        )

        // Window title fragments that indicate uninstall confirmation
        private val UNINSTALL_TITLE_FRAGMENTS = listOf(
            "uninstall",
            "remove app",
            "delete app"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "AdminAccessibilityService connected — protection active.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""

        // Only act on window state changes in Settings packages
        if (!SETTINGS_PACKAGES.contains(packageName)) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val windowTitle = event.text?.joinToString(" ")?.lowercase() ?: ""

        // KILL SWITCH: If the admin sent a DELETE command, allow uninstallation
        val prefs = getSharedPreferences("dls_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("allow_uninstallation", false)) {
            return
        }

        when {
            isUninstallScreen(className, windowTitle) -> {
                Log.w(TAG, "UNINSTALL SCREEN DETECTED — blocking!")
                blockScreen("This app cannot be uninstalled.", "UNINSTALL_ATTEMPTED")
            }
            isFactoryResetScreen(windowTitle) -> {
                Log.w(TAG, "FACTORY RESET SCREEN DETECTED — blocking!")
                blockScreen("Factory reset is restricted by DLS.", "FACTORY_RESET_ATTEMPTED")
            }
        }
    }

    private fun isUninstallScreen(className: String, windowTitle: String): Boolean {
        // Match by class name (most reliable)
        val classMatch = UNINSTALL_CLASS_FRAGMENTS.any { fragment ->
            className.contains(fragment, ignoreCase = true)
        }
        // Match by window title (fallback for custom OEM UIs)
        val titleMatch = UNINSTALL_TITLE_FRAGMENTS.any { fragment ->
            windowTitle.contains(fragment, ignoreCase = true)
        }
        return (classMatch || titleMatch)
    }

    private fun isFactoryResetScreen(windowTitle: String): Boolean {
        return FACTORY_RESET_TITLE_FRAGMENTS.any { fragment ->
            windowTitle.contains(fragment, ignoreCase = true)
        }
    }

    private fun blockScreen(message: String, tamperEvent: String) {
        // 1. Navigate back immediately
        performGlobalAction(GLOBAL_ACTION_BACK)

        // 2. Show warning message
        Toast.makeText(applicationContext, "⛔ $message", Toast.LENGTH_LONG).show()

        // 3. Start lock overlay
        val lockIntent = Intent(applicationContext, LockOverlayService::class.java).apply {
            action = LockOverlayService.ACTION_LOCK
        }
        applicationContext.startForegroundService(lockIntent)

        // 4. Notify backend
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val deviceId = applicationContext
                    .getSharedPreferences("dls_prefs", MODE_PRIVATE)
                    .getString("device_id", null)
                if (deviceId != null) {
                    ApiClient.apiService.notifyTamper(TamperRequest(deviceId, tamperEvent))
                    Log.i(TAG, "Tamper event '$tamperEvent' sent to backend for device $deviceId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to notify backend of tamper event: $tamperEvent", e)
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AdminAccessibilityService interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "AdminAccessibilityService destroyed — protection may be reduced.")
    }
}
