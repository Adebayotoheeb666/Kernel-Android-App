package com.kernel.mgmt.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.kernel.mgmt.R
import com.kernel.mgmt.network.ApiClient
import com.kernel.mgmt.service.LockOverlayService
import java.util.concurrent.TimeUnit

/**
 * StatusWorker
 *
 * A periodic WorkManager worker that polls the backend every 4 hours to check
 * whether the device should be locked or unlocked.
 *
 * Handles:
 *   - Normal lock/unlock based on server response
 *   - Offline grace period detection (>48 hrs without server contact)
 */
class StatusWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "StatusWorker"
        const val WORK_NAME = "DLS_STATUS_CHECK"
        const val NOTIFICATION_CHANNEL_ID = "dls_worker_channel"

        /**
         * Enqueues the periodic status check.
         * Call this from Application.onCreate() and BootReceiver.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<StatusWorker>(4, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(1, TimeUnit.MINUTES) // small delay on first run
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.i(TAG, "StatusWorker scheduled — polling every 4 hours.")
        }

        /**
         * Runs a one-time immediate status check (triggered by manual "Refresh" button or boot).
         * Set as Expedited to ensure it can start a foreground service from the background.
         */
        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<StatusWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInitialDelay(0, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            1002, // Different from LockOverlayService ID
            createNotification(context)
        )
    }

    override suspend fun doWork(): Result {
        val deviceId = ApiClient.getDeviceId(context)
        
        // Ensure CPU stays awake during this network operation
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Kernel:StatusCheckLock")
        wakeLock.acquire(10000) // 10s max

        // ── Offline too long? Show grace period warning ───────────────────────
        if (ApiClient.isOfflineTooLong(context)) {
            Log.w(TAG, "Offline for >48 hours — triggering grace period warning.")
            val intent = Intent(context, LockOverlayService::class.java).apply {
                action = LockOverlayService.ACTION_GRACE_PERIOD
            }
            context.startForegroundService(intent)
            return Result.success() // Still succeed — retry on next cycle
        }

        return try {
            val response = ApiClient.apiService.checkStatus(deviceId)
            val body = response.body()

            if (response.isSuccessful && body?.success == true && body.data != null) {
                val status = body.data
                ApiClient.recordServerSuccess(context)
                Log.i(TAG, "Status — locked=${status.isLocked}, amountDue=$${status.amountDue}")

                val serviceIntent = Intent(context, LockOverlayService::class.java).apply {
                    action = if (status.isLocked) LockOverlayService.ACTION_LOCK
                    else LockOverlayService.ACTION_UNLOCK
                    putExtra(LockOverlayService.EXTRA_AMOUNT_DUE, status.amountDue)
                    putExtra(LockOverlayService.EXTRA_MESSAGE, status.message ?: "")
                    putExtra(LockOverlayService.EXTRA_PAYMENT_URL, status.paymentUrl)
                }
                context.startForegroundService(serviceIntent)

                Result.success()
            } else {
                Log.e(TAG, "Server returned error: ${response.code()} / ${body?.message} — retrying later.")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error during status check", e)
            Result.retry()
        }
    }

    /**
     * Posts a persistent notification to satisfy Android foreground service requirements
     * for WorkManager's expedited work.
     */
    private fun createNotification(context: Context): android.app.Notification {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "DLS Background Check",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)

        return NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Device Status Check")
            .setContentText("Checking payment status...")
            .setSmallIcon(R.drawable.ic_lock_check)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
