package com.kernel.mgmt.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.kernel.mgmt.R
import com.kernel.mgmt.admin.DeviceAdminManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * LockOverlayService
 *
 * A ForegroundService that draws a non-dismissible full-screen payment reminder
 * directly over all other apps using TYPE_APPLICATION_OVERLAY.
 *
 * Lock Levels:
 *   1. Soft Lock (ACTION_LOCK): Shows the red overlay, disables Back/Recents.
 *      Also calls dpm.lockNow() for Device Owner deployments.
 *   2. Grace Period (ACTION_GRACE_PERIOD): Shows a softer warning overlay.
 *   3. Unlock (ACTION_UNLOCK): Removes the overlay.
 */
class LockOverlayService : Service() {

    companion object {
        private const val TAG = "LockOverlayService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "dls_lock_channel"

        const val ACTION_LOCK = "com.kernel.mgmt.ACTION_LOCK"
        const val ACTION_UNLOCK = "com.kernel.mgmt.ACTION_UNLOCK"
        const val ACTION_GRACE_PERIOD = "com.kernel.mgmt.ACTION_GRACE_PERIOD"
        const val ACTION_DELETE = "com.kernel.mgmt.ACTION_DELETE"
        const val ACTION_RESTORE_OVERLAY = "com.kernel.mgmt.ACTION_RESTORE_OVERLAY"

        const val EXTRA_AMOUNT_DUE = "extra_amount_due"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_PAYMENT_URL = "extra_payment_url"
    }

    // Foreground polling — detects unlock from dashboard even when screen is locked
    private val pollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pollInterval = 60_000L
    private val pollRunnable = object : Runnable {
        override fun run() {
            serviceScope.launch {
                try {
                    val deviceId = com.kernel.mgmt.network.ApiClient.getDeviceId(this@LockOverlayService)
                    android.util.Log.d(TAG, "Lock polling — checking status...")
                    val response = com.kernel.mgmt.network.ApiClient.apiService.checkStatus(deviceId)
                    if (response.isSuccessful && response.body()?.success == true) {
                        val status = response.body()?.data
                        if (status != null && !status.isLocked) {
                            Log.i(TAG, "Device unlocked on server — removing overlay.")
                            removeOverlay()
                            stopSelf()
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Lock poll failed: ${e.message}")
                }
            }
            pollHandler.postDelayed(this, pollInterval)
        }
    }

    private var overlayView: View? = null
    private lateinit var windowManager: WindowManager
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Tracks whether we hid the overlay to let a call happen
    private var isCallDialing = false

    // Re-shows the overlay when a call ends. Registered only while a call is in progress.
    @Suppress("DEPRECATION")
    private val callStateListener = object : android.telephony.PhoneStateListener() {
        override fun onCallStateChanged(state: Int, incomingNumber: String?) {
            if (state == android.telephony.TelephonyManager.CALL_STATE_IDLE && isCallDialing) {
                isCallDialing = false
                @Suppress("DEPRECATION")
                (getSystemService(TELEPHONY_SERVICE) as android.telephony.TelephonyManager)
                    .listen(this, android.telephony.PhoneStateListener.LISTEN_NONE)
                // Re-create and re-add the overlay — removeOverlay() set overlayView to null
                // so visibility changes won't work; we must call showLockOverlay() instead.
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    showLockOverlay(lastAmountDue, lastMessage, lastPaymentUrl, lastIsGracePeriod)
                }, 1000L)
            }
        }
    }

    // Stored so the overlay can be restored when PaymentActivity returns
    private var lastAmountDue: Double = 0.0
    private var lastMessage: String = ""
    private var lastPaymentUrl: String = ""
    private var lastIsGracePeriod: Boolean = false

    /**
     * Receiver that restores the overlay when the user navigates away
     * from PaymentActivity (e.g. presses Back or finishes it).
     */
    private val resumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_RESTORE_OVERLAY) {
                Log.d(TAG, "Restoring overlay after PaymentActivity")
                showLockOverlay(lastAmountDue, lastMessage, lastPaymentUrl, lastIsGracePeriod)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, DeviceAdminManager::class.java)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        registerReceiver(resumeReceiver, IntentFilter(ACTION_RESTORE_OVERLAY))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LOCK -> {
                val amountDue = intent.getDoubleExtra(EXTRA_AMOUNT_DUE, 0.0)
                val message = intent.getStringExtra(EXTRA_MESSAGE) ?: getString(R.string.lock_message_default)
                val paymentUrl = intent.getStringExtra(EXTRA_PAYMENT_URL) ?: getString(R.string.payment_url_placeholder)
                showLockOverlay(amountDue, message, paymentUrl, isGracePeriod = false)
                hardLock()
            }
            ACTION_GRACE_PERIOD -> {
                val message = getString(R.string.grace_period_message)
                showLockOverlay(0.0, message, "", isGracePeriod = true)
            }
            ACTION_UNLOCK -> {
                removeOverlay()
                stopSelf()
            }
            ACTION_DELETE -> {
                selfDestruct()
            }
        }

        // Start polling if overlay is shown
        if (intent?.action != ACTION_UNLOCK && intent?.action != ACTION_DELETE) {
            pollHandler.removeCallbacks(pollRunnable)
            pollHandler.postDelayed(pollRunnable, pollInterval)
        }

        return START_STICKY
    }

    /**
     * Draws the full-screen overlay using WindowManager.
     */
    private fun showLockOverlay(
        amountDue: Double,
        message: String,
        paymentUrl: String,
        isGracePeriod: Boolean
    ) {
        // Cache so the overlay can be restored after PaymentActivity
        lastAmountDue = amountDue
        lastMessage = message
        lastPaymentUrl = paymentUrl
        lastIsGracePeriod = isGracePeriod

        removeOverlay() // Remove any existing overlay first

        val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_DLS_LockScreen)
        val inflater = LayoutInflater.from(themedContext)
        val layout = if (isGracePeriod) R.layout.overlay_grace_period else R.layout.overlay_payment
        overlayView = inflater.inflate(layout, null)

        // Set dynamic content
        overlayView?.let { view ->
            val tvMessage = view.findViewById<android.widget.TextView>(R.id.tv_lock_message)
            val tvAmount = view.findViewById<android.widget.TextView>(R.id.tv_amount_due)
            val btnPayNow = view.findViewById<android.widget.Button>(R.id.btn_pay_now)
            val btnRestore = view.findViewById<android.widget.Button>(R.id.btn_request_restore)

            Log.d(TAG, "Views found: tvMessage=${tvMessage!=null}, btnPayNow=${btnPayNow!=null}, btnRestore=${btnRestore!=null}")

            tvMessage?.text = message
            if (amountDue > 0) {
                tvAmount?.text = getString(R.string.amount_due_format, amountDue)
                tvAmount?.visibility = View.VISIBLE
            }

            btnPayNow?.setOnClickListener {
                Log.d(TAG, "Pay Now clicked. URL: $paymentUrl")
                if (paymentUrl.isNotEmpty()) {
                    // Temporarily hide the overlay so PaymentActivity is visible
                    overlayView?.visibility = View.GONE
                    val pIntent = Intent(this, com.kernel.mgmt.ui.PaymentActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra(com.kernel.mgmt.ui.PaymentActivity.EXTRA_URL, paymentUrl)
                        putExtra(com.kernel.mgmt.ui.PaymentActivity.EXTRA_RESTORE_ACTION, ACTION_RESTORE_OVERLAY)
                    }
                    startActivity(pIntent)
                }
            }

            btnRestore?.setOnClickListener {
                Log.d(TAG, "Request Restore clicked")
                btnRestore.isEnabled = false
                btnRestore.text = getString(R.string.status_checking)
                
                val deviceId = com.kernel.mgmt.network.ApiClient.getDeviceId(this)
                val tamperRequest = com.kernel.mgmt.network.TamperRequest(deviceId, "DEVICE_RESTORE_REQUEST")
                
                serviceScope.launch {
                    try {
                        Log.d(TAG, "Sending restore request for $deviceId...")
                        val response = com.kernel.mgmt.network.ApiClient.apiService.notifyTamper(tamperRequest)
                        withContext(Dispatchers.Main) {
                            Log.i(TAG, "Restore request result: ${response.isSuccessful}")
                            android.widget.Toast.makeText(this@LockOverlayService, getString(R.string.restore_request_sent), android.widget.Toast.LENGTH_LONG).show()
                            btnRestore.text = "REQUEST SENT"
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending restore request", e)
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(this@LockOverlayService, "Network error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                            btnRestore.isEnabled = true
                            btnRestore.text = getString(R.string.btn_request_restore)
                        }
                    }
                }
            }

            // ── Emergency & Store Call Buttons ────────────────────────────────
            // ACTION_CALL places the call directly — the dialer never opens so the user
            // cannot change the number. The overlay is hidden for the duration of the call
            // and restored automatically via callStateListener when the call ends.
            val btn911 = view.findViewById<android.widget.Button>(R.id.btn_call_911)
            val btnStore1 = view.findViewById<android.widget.Button>(R.id.btn_call_store_1)
            val btnStore2 = view.findViewById<android.widget.Button>(R.id.btn_call_store_2)

            fun dialNumber(number: String) {
                // Always remove the overlay first so the call screen is never blocked.
                removeOverlay()
                isCallDialing = true

                fun registerStateListener() {
                    // Delay 800ms to skip the synchronous IDLE callback fired at listen() time.
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (isCallDialing) {
                            @Suppress("DEPRECATION")
                            (getSystemService(TELEPHONY_SERVICE) as android.telephony.TelephonyManager)
                                .listen(callStateListener, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
                        }
                    }, 800L)
                }

                // Try ACTION_CALL first (direct call — user cannot change the number).
                // Falls back to ACTION_DIAL (pre-filled dialer) if CALL_PHONE is not
                // yet granted so the button always does something visible.
                try {
                    startActivity(Intent(Intent.ACTION_CALL).apply {
                        data = android.net.Uri.parse("tel:$number")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                    Log.i(TAG, "Direct call placed to: $number")
                    registerStateListener()
                } catch (sec: SecurityException) {
                    Log.w(TAG, "CALL_PHONE not granted — opening pre-filled dialler for $number")
                    try {
                        startActivity(Intent(Intent.ACTION_DIAL).apply {
                            data = android.net.Uri.parse("tel:$number")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        })
                        registerStateListener()
                    } catch (e: Exception) {
                        Log.e(TAG, "Dialler also failed for $number", e)
                        isCallDialing = false
                        showLockOverlay(lastAmountDue, lastMessage, lastPaymentUrl, lastIsGracePeriod)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initiate call to $number", e)
                    isCallDialing = false
                    showLockOverlay(lastAmountDue, lastMessage, lastPaymentUrl, lastIsGracePeriod)
                }
            }

            btn911?.setOnClickListener    { dialNumber("911") }
            btnStore1?.setOnClickListener { dialNumber("2398785651") }
            btnStore2?.setOnClickListener { dialNumber("3344067147") }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )

        // Override key events to block Back/Recents
        overlayView?.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_BACK,
                    KeyEvent.KEYCODE_APP_SWITCH,
                    KeyEvent.KEYCODE_HOME -> {
                        Log.d(TAG, "Blocked key: $keyCode")
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }

        try {
            windowManager.addView(overlayView, params)
            Log.i(TAG, "Lock overlay shown.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay — falling back to PaymentOverlayActivity", e)
            val fallbackIntent = Intent(this, Class.forName("com.kernel.mgmt.ui.PaymentOverlayActivity")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(EXTRA_AMOUNT_DUE, amountDue)
                putExtra(EXTRA_MESSAGE, message)
                putExtra(EXTRA_PAYMENT_URL, paymentUrl)
            }
            startActivity(fallbackIntent)
        }
    }

    /**
     * Level 2 (Hard Lock): Calls dpm.lockNow() — works reliably when Device Owner.
     */
    private fun hardLock() {
        try {
            if (dpm.isDeviceOwnerApp(packageName) || dpm.isAdminActive(adminComponent)) {
                dpm.lockNow()
                Log.i(TAG, "dpm.lockNow() called successfully.")
            } else {
                Log.w(TAG, "Not Device Owner/Admin — skipping dpm.lockNow().")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to call dpm.lockNow()", e)
        }
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
                Log.i(TAG, "Lock overlay removed.")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay", e)
            }
            overlayView = null
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Device Lock Service",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "DLS payment enforcement notification"
            setShowBadge(true)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildForegroundNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_lock_title))
            .setContentText(getString(R.string.notif_lock_body))
            .setSmallIcon(R.drawable.ic_lock_check)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .build()

    private fun selfDestruct() {
        Log.w(TAG, "!!! SELF-DESTRUCT TRIGGERED !!! De-provisioning device...")

        try {
            // 1. Remove overlay
            removeOverlay()

            // 2. Clear local data (SharedPreferences)
            Log.i(TAG, "Wiping local SharedPreferences and setting uninstallation flag...")
            getSharedPreferences("dls_prefs", MODE_PRIVATE).edit()
                .putBoolean("allow_uninstallation", true)
                .commit()

            // 3. Handle uninstallation/de-provisioning
            if (dpm.isDeviceOwnerApp(packageName)) {
                Log.i(TAG, "App is Device Owner — de-provisioning...")
                try {
                    // Remove restrictions first
                    dpm.clearUserRestriction(adminComponent, android.os.UserManager.DISALLOW_UNINSTALL_APPS)
                    dpm.clearUserRestriction(adminComponent, android.os.UserManager.DISALLOW_FACTORY_RESET)
                    
                    // Clear Device Owner status
                    dpm.clearDeviceOwnerApp(packageName)
                    Log.i(TAG, "Device Owner cleared.")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to de-provision device owner", e)
                }
            }
            
            if (dpm.isAdminActive(adminComponent)) {
                Log.i(TAG, "Removing active admin...")
                dpm.removeActiveAdmin(adminComponent)
            }

            Log.i(TAG, "Self-destruct sequence initiated. Stopping service.")
            stopForeground(true)
            stopSelf()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during self-destruct", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        pollHandler.removeCallbacks(pollRunnable)
        try { unregisterReceiver(resumeReceiver) } catch (_: Exception) {}
        if (isCallDialing) {
            @Suppress("DEPRECATION")
            (getSystemService(TELEPHONY_SERVICE) as android.telephony.TelephonyManager)
                .listen(callStateListener, android.telephony.PhoneStateListener.LISTEN_NONE)
        }
        removeOverlay()
    }
}
