package dev.yaklede.bybittrader.alerts

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class TelegramAlertSink(
    private val client: HttpClient,
    private val botCredential: String,
    private val chatId: String,
) : AlertSink {
    override suspend fun send(message: AlertMessage): AlertDeliveryResult {
        val endpoint = "https://api.telegram.org/bot$botCredential/sendMessage"
        return runCatching {
            val response =
                client.post(endpoint) {
                    contentType(ContentType.Application.Json)
                    setBody(
                        TelegramSendMessageRequest(
                            chatId = chatId,
                            text = "[${message.severity}] ${message.title}\n${message.body}",
                        ),
                    )
                }
            val body = response.body<TelegramSendMessageResponse>()
            AlertDeliveryResult(
                delivered = body.ok,
                sinkName = "telegram",
                failureReason = body.description,
            )
        }.getOrElse { failure ->
            AlertDeliveryResult(
                delivered = false,
                sinkName = "telegram",
                failureReason = failure::class.simpleName,
            )
        }
    }
}

@Serializable
private data class TelegramSendMessageRequest(
    @SerialName("chat_id")
    val chatId: String,
    val text: String,
)

@Serializable
private data class TelegramSendMessageResponse(
    val ok: Boolean,
    val description: String? = null,
)
