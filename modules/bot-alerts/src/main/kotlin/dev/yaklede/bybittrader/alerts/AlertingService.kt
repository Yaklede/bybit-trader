package dev.yaklede.bybittrader.alerts

import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant

class AlertingService(
    private val sink: AlertSink,
    private val recorder: AlertDeliveryRecorder,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(AlertingService::class.java)

    suspend fun send(message: AlertMessage): AlertDeliveryResult {
        logger.info("alert delivery requested title={} severity={}", message.title, message.severity.name)
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
        if (result.delivered) {
            logger.info("alert delivery completed sink={} title={}", result.sinkName, message.title)
        } else {
            logger.warn(
                "alert delivery failed sink={} title={} reason={}",
                result.sinkName,
                message.title,
                result.failureReason,
            )
        }
        return result
    }
}
