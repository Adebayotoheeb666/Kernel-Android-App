package com.amirapay.mgmt.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.amirapay.mgmt.service.CoreEnforcementService
import com.amirapay.mgmt.worker.StatusWorker

/**
 * BootReceiver
 *
 * Captures device boot and app update events to re-initialize
 * background listeners and the permanent persistence service.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("BootReceiver", "Boot or Update detected. Action: ${intent.action}")

        // 1. Initialise the permanent background persistence anchor.
        // This ensures the app process stays active even if the user didn't open it.
        CoreEnforcementService.start(context)

        // 2. Schedule the WorkManager periodic status checks.
        StatusWorker.schedule(context)
    }
}
