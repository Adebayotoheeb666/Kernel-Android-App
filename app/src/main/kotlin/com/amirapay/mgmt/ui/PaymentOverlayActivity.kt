package com.amirapay.mgmt.ui

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.amirapay.mgmt.R
import com.amirapay.mgmt.databinding.OverlayPaymentBinding
import com.amirapay.mgmt.network.ApiClient
import com.amirapay.mgmt.network.TamperRequest
import com.amirapay.mgmt.service.LockOverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PaymentOverlayActivity
 *
 * Fallback full-screen lock activity used when SYSTEM_ALERT_WINDOW permission
 * is denied and the foreground service overlay cannot be drawn.
 *
 * Shows the same red payment reminder UI but as a standard Activity.
 * The activity is excluded from recents and cannot be dismissed via Back.
 */
class PaymentOverlayActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PaymentOverlayActivity"
    }

    private lateinit var binding: OverlayPaymentBinding
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on and show over lock screen
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        binding = OverlayPaymentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val amountDue = intent.getDoubleExtra(LockOverlayService.EXTRA_AMOUNT_DUE, 0.0)
        val message = intent.getStringExtra(LockOverlayService.EXTRA_MESSAGE)
            ?: getString(R.string.lock_message_default)
        val paymentUrl = intent.getStringExtra(LockOverlayService.EXTRA_PAYMENT_URL)
            ?: getString(R.string.payment_url_placeholder)

        binding.tvLockMessage.text = message

        if (amountDue > 0) {
            binding.tvAmountDue.text = getString(R.string.amount_due_format, amountDue)
            binding.tvAmountDue.visibility = View.VISIBLE
        }

        // Pay Now — open in WebView so it works even on lock screen
        binding.btnPayNow.setOnClickListener {
            Log.d(TAG, "Pay Now clicked. URL: $paymentUrl")
            val pIntent = android.content.Intent(this, PaymentActivity::class.java).apply {
                putExtra(PaymentActivity.EXTRA_URL, paymentUrl)
            }
            startActivity(pIntent)
        }

        // Request Restore — sends a restore request to the backend
        binding.btnRequestRestore.setOnClickListener {
            Log.d(TAG, "Request Restore clicked")
            binding.btnRequestRestore.isEnabled = false
            binding.btnRequestRestore.text = getString(R.string.status_checking)

            val deviceId = ApiClient.getDeviceId(this)
            val tamperRequest = TamperRequest(deviceId, "DEVICE_RESTORE_REQUEST")

            activityScope.launch {
                try {
                    Log.d(TAG, "Sending restore request for $deviceId...")
                    val response = ApiClient.apiService.notifyTamper(tamperRequest)
                    withContext(Dispatchers.Main) {
                        Log.i(TAG, "Restore request sent. Success: ${response.isSuccessful}")
                        Toast.makeText(
                            this@PaymentOverlayActivity,
                            getString(R.string.restore_request_sent),
                            Toast.LENGTH_LONG
                        ).show()
                        binding.btnRequestRestore.text = "REQUEST SENT"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Restore request network error", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@PaymentOverlayActivity,
                            "Network error: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                        binding.btnRequestRestore.isEnabled = true
                        binding.btnRequestRestore.text = getString(R.string.btn_request_restore)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }

    /** Block the Back button — user cannot dismiss this screen. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }

    /** Prevent activity from being finished via back gesture. */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // Do nothing — intentionally blocked
    }
}
