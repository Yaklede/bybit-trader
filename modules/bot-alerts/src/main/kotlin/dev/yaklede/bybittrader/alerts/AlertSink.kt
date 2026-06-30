package dev.yaklede.bybittrader.alerts

interface AlertSink {
    suspend fun send(message: AlertMessage): AlertDeliveryResult
}

data class AlertDeliveryResult(
    val delivered: Boolean,
    val sinkName: String,
    val failureReason: String? = null,
)

interface AlertDeliveryRecorder {
    suspend fun record(record: AlertDeliveryRecord)
}

data class AlertDeliveryRecord(
    val sinkName: String,
    val severity: AlertSeverity,
    val title: String,
    val deliveryStatus: AlertDeliveryStatus,
    val failureReason: String?,
    val createdAt: java.time.Instant,
)

enum class AlertDeliveryStatus {
    DELIVERED,
    FAILED,
}
