package com.amirapay.mgmt.ui

import android.content.Intent
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.amirapay.mgmt.databinding.ActivityPaymentBinding

/**
 * PaymentActivity
 *
 * An activity that displays the payment gateway in a WebView.
 * Configured to show over the lock screen and wake up the device.
 */
class PaymentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPaymentBinding
    private var restoreAction: String? = null

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_RESTORE_ACTION = "extra_restore_action"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Ensure this activity shows over the lock screen
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        
        binding = ActivityPaymentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        restoreAction = intent.getStringExtra(EXTRA_RESTORE_ACTION)
        val url = intent.getStringExtra(EXTRA_URL) ?: "https://amirapay.com"

        setupWebView(url)
        
        binding.btnClose.setOnClickListener {
            finish()
        }
    }

    private fun setupWebView(url: String) {
        binding.webView.apply {
            webViewClient = WebViewClient()
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            loadUrl(url)
        }
    }

    override fun onStop() {
        super.onStop()
        // Restore the lock overlay when the user navigates away from this activity
        restoreAction?.let { action ->
            sendBroadcast(Intent(action))
        }
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
