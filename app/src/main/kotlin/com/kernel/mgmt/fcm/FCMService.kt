package com.kernel.mgmt.fcm

import android.content.Intent
import android.util.Log
import com.kernel.mgmt.network.ApiClient
import com.kernel.mgmt.network.FcmTokenUpdateRequest
import com.kernel.mgmt.service.LockOverlayService
import com.kernel.mgmt.service.CoreEnforcementService
import com.kernel.mgmt.worker.StatusWorker
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * FCMService
 *
 * Handles incoming Firebase Cloud Messaging (FCM) data messages.
 * All messages are sent as high-priority DATA messages (not notification messages)
 * so they wake the device even when in Doze mode.
 */
class FCMService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FCMService"
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.i(TAG, "FCM message received — from: ${message.from}")

        // Acquire a temporary WakeLock to ensure processing survives Doze/Infinix freezing
        val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        val wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "KernelMgmt:FCM_WAKE")
        wakeLock.acquire(10_000L) // Hold for 10 seconds max

        val data = message.data
        val fcmAction = data["action"] ?: run {
            Log.w(TAG, "FCM message missing 'action' field — ignoring.")
             if (wakeLock.isHeld) wakeLock.release()
            return
        }
        val amountDue = data["amount_due"]?.toDoubleOrNull() ?: 0.0

        Log.i(TAG, "FCM action: $fcmAction, amount: $amountDue")
        Log.i(TAG, "Triggering immediate status check via CoreEnforcementService for reliable background enforcement.")
        
        // 1. Trigger the CoreEnforcementService to perform a refresh.
        // This ensures the process stays active as a foreground service.
        CoreEnforcementService.start(this)
        val refreshIntent = Intent(this, CoreEnforcementService::class.java).apply {
            this.action = CoreEnforcementService.ACTION_REFRESH
        }
        startForegroundService(refreshIntent)

        // 2. Also trigger a StatusWorker job for redundancy
        StatusWorker.runOnce(this)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "FCM token refreshed — updating backend.")

        val deviceId = ApiClient.getDeviceId(this)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ApiClient.apiService.registerDevice(
                    deviceId = deviceId,
                    fcmToken = token
                )
                Log.i(TAG, "FCM token updated on server via registration endpoint.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update FCM token on server", e)
            }
        }
    }
}
