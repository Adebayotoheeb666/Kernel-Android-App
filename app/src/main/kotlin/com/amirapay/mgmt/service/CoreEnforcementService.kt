package com.amirapay.mgmt.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.amirapay.mgmt.R
import com.amirapay.mgmt.network.ApiClient
import com.amirapay.mgmt.worker.StatusWorker
import kotlinx.coroutines.*

/**
 * CoreEnforcementService
 *
 * A permanent 'Sticky' Foreground Service that ensures the app process stays alive.
 * This is the 'Anchor' that allows FCM and WorkManager to function reliably on
 * restrictive devices (Infinix, Tecno, etc.) when the app is swiped away.
 */
class CoreEnforcementService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private lateinit var alarmManager: AlarmManager
    private lateinit var pendingAlarmIntent: PendingIntent

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Screen Event: ${intent.action}. Verifying service status...")
            // Every time the screen turns on or user is present, ensure we check status
            StatusWorker.runOnce(context)
        }
    }

    companion object {
        private const val TAG = "CoreEnforcement"
        private const val NOTIFICATION_ID = 1003
        private const val CHANNEL_ID = "core_enforcement_v3"

        const val ACTION_START = "com.amirapay.mgmt.ACTION_START_ENFORCEMENT"
        const val ACTION_REFRESH = "com.amirapay.mgmt.ACTION_REFRESH_STATUS"

        fun start(context: Context) {
            val intent = Intent(context, CoreEnforcementService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Sticky CoreEnforcementService created.")
        
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val alarmIntent = Intent(this, CoreEnforcementService::class.java).apply {
            action = ACTION_REFRESH
        }
        pendingAlarmIntent = PendingIntent.getService(
            this, 0, alarmIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Register screen receiver
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        scheduleHeartbeat()
    }

    private fun scheduleHeartbeat() {
        // Schedule next heart-beat in 10 minutes
        val triggerAt = System.currentTimeMillis() + (10 * 60 * 1000)
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            pendingAlarmIntent
        )
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {}
        
        alarmManager.cancel(pendingAlarmIntent)
        serviceScope.cancel()
        
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        
        Log.w(TAG, "Sticky service destroyed! Attempting restart...")
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started with action: ${intent?.action}")

        if (intent?.action == ACTION_REFRESH) {
            Log.i(TAG, "Alarm-triggered heart-beat: checking status")
            StatusWorker.runOnce(this)
            scheduleHeartbeat() // Reschedule next
        }

        // START_STICKY ensures the OS restarts the service if it's ever killed due to memory pressure
        return START_STICKY
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, com.amirapay.mgmt.ui.MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AmiraPay Management Active")
            .setContentText("Device security is active in the background.")
            .setSmallIcon(android.R.drawable.ic_secure)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Environment Protection",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

}
