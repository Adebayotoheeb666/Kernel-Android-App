package com.amirapay.mgmt.network

import android.content.Context
import android.provider.Settings
import android.telephony.TelephonyManager
import com.amirapay.mgmt.BuildConfig
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// ─────────────────────────────────────────────
// Data Models
// ─────────────────────────────────────────────

data class ChronexResponse<T>(
    @SerializedName("status") val success: Boolean,
    @SerializedName("message") val message: String,
    @SerializedName("data") val data: T?
)

data class RegisterRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("model") val model: String,
    @SerializedName("fcm_token") val fcmToken: String?
)

data class RegisterResponse(
    @SerializedName("contract_id") val contractId: Int?,
    @SerializedName("contract_status") val contractStatus: String?,
    @SerializedName("serial_number") val serialNumber: String?
)

data class StatusData(
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("isLocked") val isLocked: Boolean,
    @SerializedName("amountDue") val amountDue: Double,
    @SerializedName("next_due_date") val nextDueDate: String?,
    @SerializedName("message") val message: String?,
    @SerializedName("paymentUrl") val paymentUrl: String,
    @SerializedName("contractId") val contractId: Int?
)

data class TamperRequest(
    @SerializedName("imei_number") val deviceId: String,
    @SerializedName("event") val event: String
)

data class FcmTokenUpdateRequest(
    @SerializedName("imei_number") val deviceId: String,
    @SerializedName("fcm_token") val fcmToken: String
)

data class ChronexStatusOnlyResponse(
    @SerializedName("status") val success: Boolean,
    @SerializedName("message") val message: String
)

data class ContractResponse(
    @SerializedName("contract_id") val contractId: Int,
    @SerializedName("contract_status") val contractStatus: String,
    @SerializedName("devices_lock") val devicesLock: Int
)

data class ContractFullResponse(
    @SerializedName("status") val success: Boolean,
    @SerializedName("message") val message: String?,
    @SerializedName("data") val data: ContractResponseData?,
    @SerializedName("payment_summary") val paymentSummary: PaymentSummary?
)

data class ContractResponseData(
    @SerializedName("contract_id") val contractId: Int,
    @SerializedName("contract_status") val contractStatus: String,
    @SerializedName("devices_lock") val devicesLock: Int?
)

data class PaymentSummary(
    @SerializedName("next_due_date") val nextDueDate: String?,
    @SerializedName("next_total_due") val nextTotalDue: Double?,
    @SerializedName("status") val status: String?
)

data class DeviceLockRequest(
    @SerializedName("contract_id") val contractId: Int,
    @SerializedName("devices_lock") val devicesLock: Int
)

data class DeviceLockData(
    @SerializedName("contract_id") val contractId: Int?,
    @SerializedName("devices_lock") val devicesLock: Int?,
    @SerializedName("devices_lock_label") val devicesLockLabel: String?,
    @SerializedName("updated_at") val updatedAt: String?
)

// ─────────────────────────────────────────────
// Retrofit API Interface
// ─────────────────────────────────────────────

interface ChronexApiService {

    @GET("registration")
    suspend fun registerDevice(
        // Server expects imei_number; "imedi_numlber" caused a 302 HTML redirect
        @Query("imei_number") deviceId: String,
        @Query("fcm_token") fcmToken: String? = null
    ): Response<ChronexResponse<RegisterResponse>>

    @GET("status/{device_id}")
    suspend fun checkStatus(
        @Path("device_id") deviceId: String
    ): Response<ChronexResponse<StatusData>>

    @POST("tempered")
    suspend fun notifyTamper(
        @Body request: TamperRequest
    ): Response<ChronexStatusOnlyResponse>

    @POST("update-fcm")
    suspend fun updateFcmToken(
        @Body request: FcmTokenUpdateRequest
    ): Response<ChronexStatusOnlyResponse>

    @GET("contracts/{contract_id}")
    suspend fun getContract(
        @Path("contract_id") contractId: Int
    ): Response<ContractFullResponse>

    @POST("contracts/device-lock")
    suspend fun updateDeviceLock(
        @Body request: DeviceLockRequest
    ): Response<ChronexResponse<DeviceLockData>>
}

// ─────────────────────────────────────────────
// ApiClient Singleton
// ─────────────────────────────────────────────

object ApiClient {

    private const val AMIRA_BASE_URL = "https://amirapay.com/api/"
    private const val PREFS_NAME = "dls_prefs"
    private const val KEY_DEVICE_IMEI = "device_imei"
    private const val KEY_LAST_SUCCESS = "last_server_success_ms"

    val apiService: ChronexApiService by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG)
                HttpLoggingInterceptor.Level.BODY
            else
                HttpLoggingInterceptor.Level.NONE
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()

        Retrofit.Builder()
            .baseUrl(AMIRA_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ChronexApiService::class.java)
    }

    /**
     * Returns the device IMEI number for use as the unique registration ID with Chronex.
     *
     * Strategy (in priority order):
     *  1. Cached IMEI from SharedPreferences (avoids repeated TelephonyManager calls).
     *  2. Live IMEI via TelephonyManager (requires READ_PHONE_STATE permission).
     *  3. Fallback: ANDROID_ID — used only if permission is not yet granted or the
     *     device has no SIM/IMEI (e.g. Wi-Fi-only tablets).
     *
     * The value is cached after the first successful IMEI read so that subsequent
     * calls are instant and permission-agnostic.
     */
    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Return cached IMEI if we already have it
        val cached = prefs.getString(KEY_DEVICE_IMEI, null)
        if (!cached.isNullOrEmpty() && cached != "UNKNOWN") return cached

        // Attempt to read the real IMEI
        val imei = readImei(context)
        if (!imei.isNullOrEmpty()) {
            prefs.edit().putString(KEY_DEVICE_IMEI, imei).apply()
            android.util.Log.i("ApiClient", "IMEI acquired and cached: ${imei.take(4)}****")
            return imei
        }

        // Fallback: ANDROID_ID (not a real IMEI but better than nothing)
        val fallback = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "UNKNOWN"
        android.util.Log.w("ApiClient", "IMEI unavailable — using ANDROID_ID fallback")
        return fallback
    }

    /**
     * Attempts to read the IMEI from TelephonyManager (slot 0).
     * Returns null if READ_PHONE_STATE permission is denied or IMEI is not accessible.
     */
    @Suppress("MissingPermission")
    private fun readImei(context: Context): String? {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                // Try slot 0 first, then slot 1 for dual-SIM devices where slot 0 may be empty
                val slot0 = tm.getImei(0)
                val imei = if (!slot0.isNullOrBlank() && slot0 != "000000000000000") slot0
                           else tm.getImei(1)
                if (!imei.isNullOrBlank() && imei != "000000000000000") imei else null
            } else {
                @Suppress("DEPRECATION")
                val imei = tm.deviceId
                if (!imei.isNullOrBlank() && imei != "000000000000000") imei else null
            }
        } catch (e: SecurityException) {
            android.util.Log.w("ApiClient", "READ_PHONE_STATE not granted — cannot read IMEI")
            null
        } catch (e: Exception) {
            android.util.Log.e("ApiClient", "Unexpected error reading IMEI", e)
            null
        }
    }

    /**
     * Attempts to get the hardware Serial Number.
     * Note: On Android 10+, this requires the app to be the Device Owner.
     */
    fun getSerialNumber(context: Context): String {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                // This will fail on Android 10+ if not a privileged app
                android.os.Build.getSerial()
            } else {
                @Suppress("DEPRECATION")
                android.os.Build.SERIAL
            }
        } catch (e: SecurityException) {
            "RESTRICTED"
        } catch (e: Exception) {
            "NOT AVAILABLE"
        }
    }

    /**
     * Returns the device manufacturer and model.
     */
    fun getDeviceModel(): String {
        return "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
    }

    /**
     * Records the timestamp of the last successful server communication.
     * Used by StatusWorker to detect prolonged offline periods (>48 hrs).
     */
    fun recordServerSuccess(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_SUCCESS, System.currentTimeMillis())
            .apply()
    }

    /**
     * Returns true if the device has not reached the server for more than 48 hours.
     */
    fun isOfflineTooLong(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSuccess = prefs.getLong(KEY_LAST_SUCCESS, System.currentTimeMillis())
        val elapsedMs = System.currentTimeMillis() - lastSuccess
        val fortyEightHoursMs = 48 * 60 * 60 * 1000L
        return elapsedMs > fortyEightHoursMs
    }
}
