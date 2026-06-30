package dev.yaklede.bybittrader.alerts

import java.time.Clock
import java.time.Instant

class AlertingService(
    private val sink: AlertSink,
    private val recorder: AlertDeliveryRecorder,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun send(message: AlertMessage): AlertDeliveryResult {
        val result = sink.send(message)
        recorder.record(
            AlertDeliveryRecord(
                sinkName = result.sinkName,
                severity = message.severity,
                title = message.title,
                deliveryStatus =
                    if (result.delivered) {
                        AlertDeliveryStatus.DELIVERED
                    } else {
                        AlertDeliveryStatus.FAILED
                    },
                failureReason = result.failureReason,
                createdAt = Instant.now(clock),
            ),
        )
        return result
    }
}
