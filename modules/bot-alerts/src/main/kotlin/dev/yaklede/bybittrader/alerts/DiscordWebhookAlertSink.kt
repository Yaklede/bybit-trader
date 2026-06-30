package dev.yaklede.bybittrader.alerts

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

class DiscordWebhookAlertSink(
    private val client: HttpClient,
    private val webhookUrl: String,
) : AlertSink {
    override suspend fun send(message: AlertMessage): AlertDeliveryResult =
        runCatching {
            val response =
                client.post(webhookUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(
                        DiscordWebhookRequest(
                            content = "[${message.severity}] ${message.title}\n${message.body}",
                        ),
                    )
                }
            AlertDeliveryResult(
                delivered = response.status.isSuccess(),
                sinkName = "discord",
                failureReason = response.status.takeUnless { it.isSuccess() }?.toString(),
            )
        }.getOrElse { failure ->
            AlertDeliveryResult(
                delivered = false,
                sinkName = "discord",
                failureReason = failure::class.simpleName,
            )
        }
}

@Serializable
private data class DiscordWebhookRequest(
    val content: String,
)
