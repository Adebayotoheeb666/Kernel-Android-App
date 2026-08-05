package com.amirapay.mgmt.ui

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.amirapay.mgmt.BuildConfig
import com.amirapay.mgmt.R
import com.amirapay.mgmt.accessibility.AdminAccessibilityService
import com.amirapay.mgmt.admin.DeviceAdminManager
import com.amirapay.mgmt.databinding.ActivityMainBinding
import com.amirapay.mgmt.network.ApiClient
import com.amirapay.mgmt.network.RegisterRequest
import com.amirapay.mgmt.worker.StatusWorker
import com.amirapay.mgmt.service.LockOverlayService
import com.amirapay.mgmt.service.CoreEnforcementService
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch

/**
 * MainActivity — Dashboard
 *
 * The primary entry point for the DLS app.
 *
 * Responsibilities:
 *  1. On first launch: request Device Admin privileges.
 *  2. Register the device with the backend server.
 *  3. Display payment status, amount due, and next due date.
 *  4. Allow manual refresh.
 *  5. Request SYSTEM_ALERT_WINDOW permission if not granted.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    // Foreground polling — checks status every 5 seconds while app is on screen
    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollInterval = 60_000L
    private val pollRunnable = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences("dls_prefs", MODE_PRIVATE)
            if (prefs.getBoolean("is_registered", false)) {
                android.util.Log.d("MainActivity", "Foreground poll — checking status...")
                refreshStatusDisplay()
            }
            pollHandler.postDelayed(this, pollInterval)
        }
    }

    companion object {
        private const val REQUEST_ENABLE_ADMIN = 1
        private const val REQUEST_OVERLAY_PERMISSION = 2
        private const val REQUEST_ACCESSIBILITY = 3
        private const val REQUEST_BATTERY_OPTIMIZATION = 4
        private const val REQUEST_PHONE_STATE = 5
        private const val REQUEST_CALL_PHONE = 6
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, DeviceAdminManager::class.java)

        setupUI()
        checkAndRequestPermissions()
        registerDeviceIfNeeded()
        updateAdminStatusUI()
        // Start the permanent background persistence anchor
        CoreEnforcementService.start(this)

        StatusWorker.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        // Ensure background service is running whenever user returns to app
        CoreEnforcementService.start(this)
        
        // Start foreground polling when app comes to foreground
        pollHandler.post(pollRunnable)
        
        // Immediate sync on resume
        refreshStatusDisplay()
    }

    override fun onPause() {
        super.onPause()
        // Stop polling when app goes to background (WorkManager handles background)
        pollHandler.removeCallbacks(pollRunnable)
    }

    // Dummy closing brace placeholder — replaced by setupUI below
    @Suppress("unused") private fun _placeholder() {
    }

    private fun setupUI() {
        binding.btnRefresh.setOnClickListener {
            android.util.Log.d("MainActivity", "Refresh clicked")
            val prefs = getSharedPreferences("dls_prefs", MODE_PRIVATE)
            if (!prefs.getBoolean("is_registered", false)) {
                registerDeviceIfNeeded()
                return@setOnClickListener
            }
            binding.progressBar.visibility = View.VISIBLE
            refreshStatusDisplay()
        }

        // Populate Device Info
        binding.tvDeviceModel.text = ApiClient.getDeviceModel()
        
        val deviceId = ApiClient.getDeviceId(this)
        binding.tvDeviceId.text = deviceId
        
        val serial = ApiClient.getSerialNumber(this)
        binding.tvSerialNumber.text = serial
        
        // Setup Copy Listeners
        binding.btnCopyId.setOnClickListener {
            copyToClipboard("IMEI Number", deviceId)
        }
        
        binding.btnCopySerial.setOnClickListener {
            if (serial != "RESTRICTED" && serial != "NOT AVAILABLE") {
                copyToClipboard("Hardware Serial", serial)
            } else {
                Toast.makeText(this, "Serial Number access is restricted on this device.", Toast.LENGTH_SHORT).show()
            }
        }

        // Hide Serial section if restricted to keep UI clean (optional, but good for UX)
        if (serial == "RESTRICTED" || serial == "NOT AVAILABLE") {
             // binding.layoutSerial.visibility = View.GONE 
             // Letting it show with "RESTRICTED" is actually better so users understand why it's missing
        }
    }

    private fun checkAndRequestPermissions() {
        // 1. READ_PHONE_STATE — needed to read IMEI for Chronex registration
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_PHONE_STATE),
                REQUEST_PHONE_STATE
            )
        }
        // 1b. CALL_PHONE — needed to place direct calls from lock screen without opening dialer
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CALL_PHONE),
                REQUEST_CALL_PHONE
            )
        }
        // 2. Device Admin
        if (!dpm.isAdminActive(adminComponent)) {
            requestDeviceAdmin()
        }
        // 3. Overlay permission (SYSTEM_ALERT_WINDOW)
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
        }
        // 4. Accessibility Service (blocks uninstall + factory reset without Device Owner)
        if (!isAccessibilityServiceEnabled()) {
            requestAccessibilityPermission()
        }
        // 5. Battery Optimization
        requestBatteryOptimizationExemption()
    }

    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            android.util.Log.i("MainActivity", "Requesting battery optimization exemption...")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            try {
                startActivityForResult(intent, REQUEST_BATTERY_OPTIMIZATION)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to request battery optimization", e)
            }
        }
    }

    /**
     * Returns true if the AdminAccessibilityService is currently enabled by the user.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        val targetComponent = ComponentName(this, AdminAccessibilityService::class.java)
            .flattenToString()
        return enabledServices.any { info ->
            info.resolveInfo.serviceInfo.let {
                ComponentName(it.packageName, it.name).flattenToString() == targetComponent
            }
        }
    }

    private fun requestAccessibilityPermission() {
        Toast.makeText(
            this,
            getString(R.string.accessibility_required_warning),
            Toast.LENGTH_LONG
        ).show()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivityForResult(intent, REQUEST_ACCESSIBILITY)
    }

    private fun requestDeviceAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.admin_activation_reason)
            )
        }
        startActivityForResult(intent, REQUEST_ENABLE_ADMIN)
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
    }

    private fun showImeiInputDialog() {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 20, 60, 10)
        }

        fun makeLabel(text: String) = android.widget.TextView(this).apply {
            this.text = text
            setTextColor(android.graphics.Color.DKGRAY)
            textSize = 12f
            setPadding(0, 16, 0, 4)
        }
        fun makeField(hint: String) = android.widget.EditText(this).apply {
            this.hint = hint
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(16))
        }

        val imei1Field = makeField("e.g. 123456789012345")
        val imei2Field = makeField("e.g. 123456789012346  (leave blank if none)")

        container.addView(makeLabel("IMEI 1 — SIM Slot 1 (required)"))
        container.addView(imei1Field)
        container.addView(makeLabel("IMEI 2 — SIM Slot 2 (optional)"))
        container.addView(imei2Field)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Enter Device IMEI")
            .setMessage("Dial *#06# on this phone to find both IMEI numbers.")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("Save & Register") { _, _ ->
                val imei1 = imei1Field.text.toString().trim()
                val imei2 = imei2Field.text.toString().trim()

                if (imei1.length < 14 || !imei1.all { it.isDigit() }) {
                    Toast.makeText(this, "IMEI 1 is required and must be at least 14 digits.", Toast.LENGTH_LONG).show()
                    showImeiInputDialog()
                    return@setPositiveButton
                }
                if (imei2.isNotEmpty() && (imei2.length < 14 || !imei2.all { it.isDigit() })) {
                    Toast.makeText(this, "IMEI 2 looks invalid — enter at least 14 digits or leave it blank.", Toast.LENGTH_LONG).show()
                    showImeiInputDialog()
                    return@setPositiveButton
                }

                val prefs = getSharedPreferences("dls_prefs", MODE_PRIVATE)
                prefs.edit()
                    .putString("device_imei", imei1)
                    .putString("device_imei_2", imei2.ifEmpty { null })
                    .apply()

                val display = if (imei2.isNotEmpty()) "$imei1\n$imei2" else imei1
                binding.tvDeviceId.text = display
                Toast.makeText(this, "IMEI saved.", Toast.LENGTH_SHORT).show()
                registerDeviceIfNeeded()
            }
            .show()
    }

    private fun registerDeviceIfNeeded() {
        val prefs = getSharedPreferences("dls_prefs", MODE_PRIVATE)

        // If no IMEI has been manually configured yet, prompt before doing anything
        if (prefs.getString("device_imei", null).isNullOrEmpty()) {
            showImeiInputDialog()
            return
        }

        val isRegistered = prefs.getBoolean("is_registered", false)

        android.util.Log.i("MainActivity", "==> registerDeviceIfNeeded: isRegistered=$isRegistered")
        
        // UI feedback for first-time registration
        if (!isRegistered) {
            binding.progressBar.visibility = View.VISIBLE
            binding.tvStatusValue.text = getString(R.string.status_registering)
        }

        // ALWAYS fetch and sync FCM token on launch to ensure server has the latest.
        // This ensures that even if you reinstall or the token refreshes, the server knows.
        android.util.Log.i("MainActivity", "Fetching FCM token for sync...")
        
        FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                try {
                    val fcmToken = if (task.isSuccessful) task.result else null
                    android.util.Log.i("MainActivity", "FCM token result: success=${task.isSuccessful}, token=${fcmToken?.take(5)}...")
                    
                    if (!task.isSuccessful) {
                        android.util.Log.w("MainActivity", "FCM token retrieval failed", task.exception)
                    }
                    
                    // We always call performRegistration. 
                    // It will update the server even if we are already 'locally' registered.
                    performRegistration(fcmToken)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Error in FCM token listener", e)
                    if (!isRegistered) performRegistration(null)
                }
            }
            
        // Safety fallback for first-time registration only
        if (!isRegistered) {
            binding.root.postDelayed({
                val stillRegistering = binding.tvStatusValue.text == getString(R.string.status_registering)
                if (stillRegistering && binding.progressBar.visibility == View.VISIBLE) {
                    android.util.Log.w("MainActivity", "FCM token took too long, proceeding with registration...")
                    performRegistration(null)
                }
            }, 15000)
        } else {
            // If already registered, refresh the UI status immediately from the server
            refreshStatusDisplay()
        }
    }

    private fun performRegistration(fcmToken: String?) {
        val prefs = getSharedPreferences("dls_prefs", MODE_PRIVATE)
        
        lifecycleScope.launch {
            try {
                val deviceId = ApiClient.getDeviceId(this@MainActivity)
                android.util.Log.i("MainActivity", "==> START Registration API call for ID: $deviceId")

                val response = ApiClient.apiService.registerDevice(deviceId = deviceId, fcmToken = fcmToken)

                android.util.Log.i("MainActivity", "<== FINISH Registration API call. Code: ${response.code()}")

                if (response.isSuccessful) {
                    val body = response.body()
                    android.util.Log.i("MainActivity", "Registration response body: success=${body?.success}, message=${body?.message}")
                    
                    if (body?.success == true) {
                        android.util.Log.i("MainActivity", "Registration successful. Data: ${body.data}")
                        prefs.edit().putBoolean("is_registered", true).apply()
                        ApiClient.recordServerSuccess(this@MainActivity)
                        
                        refreshStatusDisplay()
                    } else {
                        android.util.Log.e("MainActivity", "Registration business failure: ${body?.message}")
                        showError(body?.message ?: getString(R.string.error_registration_failed))
                    }
                } else {
                    android.util.Log.e("MainActivity", "Registration server error: ${response.errorBody()?.string()}")
                    showError(getString(R.string.error_registration_failed))
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Exception during registration", e)
                showError(getString(R.string.error_network) + ": " + (e.message ?: "Unknown error"))
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun refreshStatusDisplay() {
        lifecycleScope.launch {
            try {
                val deviceId = ApiClient.getDeviceId(this@MainActivity)
                val response = ApiClient.apiService.checkStatus(deviceId)
                val body = response.body()
                
                if (response.isSuccessful && body?.success == true && body.data != null) {
                    val status = body.data
                    ApiClient.recordServerSuccess(this@MainActivity)

                    binding.tvStatusValue.text = if (status.isLocked)
                        getString(R.string.status_locked)
                    else
                        getString(R.string.status_active)

                    binding.tvStatusValue.setTextColor(
                        if (status.isLocked) getColor(R.color.lock_red)
                        else getColor(R.color.status_green)
                    )

                    binding.tvAmountDue.text = if (status.amountDue > 0)
                        getString(R.string.amount_due_format, status.amountDue)
                    else
                        getString(R.string.no_amount_due)

                    // Update next due date from server response
                    binding.tvNextDueDate.text = status.nextDueDate ?: getString(R.string.date_not_available)

                    binding.tvStatusMessage.text = status.message ?: ""
                    
                    // Fetch contract details for Next Due Date if not in status response
                    if (status.nextDueDate == null && status.contractId != null) {
                        try {
                            val contractRes = ApiClient.apiService.getContract(status.contractId)
                            if (contractRes.isSuccessful) {
                                val contractFull = contractRes.body()
                                // Check payment_summary first
                                var nextDue = contractFull?.paymentSummary?.nextDueDate
                                
                                // Fallback: try common payload field names manually (since payload is not strongly typed in GSON here)
                                if (nextDue == null && contractFull?.data != null) {
                                    // Note: The API returns payload as a Map or JsonObject. 
                                    // For now, if PaymentSummary is empty, we show N/A unless we can find it.
                                }
                                
                                binding.tvNextDueDate.text = nextDue ?: getString(R.string.date_not_available)
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("MainActivity", "Failed to fetch contract details", e)
                        }
                    }

                    // Trigger or stop the LockOverlayService based on the server status
                    val serviceIntent = Intent(this@MainActivity, LockOverlayService::class.java).apply {
                        action = if (status.isLocked) LockOverlayService.ACTION_LOCK
                        else LockOverlayService.ACTION_UNLOCK
                        putExtra(LockOverlayService.EXTRA_AMOUNT_DUE, status.amountDue)
                        putExtra(LockOverlayService.EXTRA_MESSAGE, status.message ?: "")
                        putExtra(LockOverlayService.EXTRA_PAYMENT_URL, status.paymentUrl)
                    }
                    startForegroundService(serviceIntent)

                    // Update Admin Status in UI
                    updateAdminStatusUI()
                } else {
                    showError(body?.message ?: getString(R.string.error_network))
                }
            } catch (e: Exception) {
                showError(getString(R.string.error_network))
            }
        }
    }

    private fun updateAdminStatusUI() {
        val isAdmin = dpm.isAdminActive(adminComponent)
        val isDeviceOwner = dpm.isDeviceOwnerApp(packageName)
        val isAccessibilityOn = isAccessibilityServiceEnabled()

        binding.tvAdminStatus.text = when {
            isDeviceOwner -> "DEVICE OWNER (Max Protection)"
            isAdmin && isAccessibilityOn -> "Protected (Admin + Accessibility Active)"
            isAdmin -> "Partial (Admin only — enable Accessibility)"
            isAccessibilityOn -> "Partial (Accessibility only — grant Admin)"
            else -> "⚠️ Unprotected — tap to fix"
        }

        binding.tvAdminStatus.setTextColor(
            when {
                isDeviceOwner || (isAdmin && isAccessibilityOn) -> getColor(R.color.status_green)
                else -> getColor(R.color.text_secondary)
            }
        )
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        binding.progressBar.visibility = View.GONE
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PHONE_STATE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                android.util.Log.i("MainActivity", "READ_PHONE_STATE granted — refreshing IMEI and re-registering.")
                // Re-read IMEI now that permission is granted, update UI, and sync with server
                val imei = ApiClient.getDeviceId(this)
                binding.tvDeviceId.text = imei
                registerDeviceIfNeeded()
            } else {
                android.util.Log.w("MainActivity", "READ_PHONE_STATE denied — will use ANDROID_ID fallback.")
                Toast.makeText(
                    this,
                    "Phone permission denied. Using fallback ID for registration.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_ENABLE_ADMIN -> {
                if (dpm.isAdminActive(adminComponent)) {
                    Toast.makeText(this, getString(R.string.admin_enabled_success), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.admin_required_warning), Toast.LENGTH_LONG).show()
                }
            }
            REQUEST_OVERLAY_PERMISSION -> {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, getString(R.string.overlay_required_warning), Toast.LENGTH_LONG).show()
                }
            }
            REQUEST_ACCESSIBILITY -> {
                if (isAccessibilityServiceEnabled()) {
                    Toast.makeText(this, getString(R.string.accessibility_enabled_success), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.accessibility_required_warning), Toast.LENGTH_LONG).show()
                }
                updateAdminStatusUI()
            }
        }
    }
}
