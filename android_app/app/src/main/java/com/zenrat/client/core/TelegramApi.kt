package com.zenrat.client.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val TELEGRAM_API_URL = "https://api.telegram.org/bot"
const val BOT_TOKEN_PLACEHOLDER = "YOUR_TELEGRAM_BOT_TOKEN"

@Serializable
data class ApiResponse<T>(
    val ok: Boolean,
    val result: T
)

@Serializable
data class Update(
    @SerialName("update_id")
    val updateId: Long,
    val message: Message? = null
)

@Serializable
data class Message(
    @SerialName("message_id")
    val messageId: Long,
    val chat: Chat,
    val text: String? = null
)

@Serializable
data class Chat(
    val id: Long
)
