package dev.yaklede.bybittrader.alerts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AlertingServiceTest :
    StringSpec({
        "alerting service records successful delivery" {
            val recorder = InMemoryAlertDeliveryRecorder()
            val service =
                AlertingService(
                    sink = NoopAlertSink(),
                    recorder = recorder,
                    clock = fixedClock(),
                )

            val result = service.send(AlertMessage(AlertSeverity.INFO, "startup", "bot started"))

            result.delivered shouldBe true
            recorder.records shouldHaveSize 1
            recorder.records.single().deliveryStatus shouldBe AlertDeliveryStatus.DELIVERED
        }

        "alerting service records failed delivery without throwing" {
            val recorder = InMemoryAlertDeliveryRecorder()
            val service =
                AlertingService(
                    sink = RecordingFailingAlertSink(),
                    recorder = recorder,
                    clock = fixedClock(),
                )

            val result = service.send(AlertMessage(AlertSeverity.CRITICAL, "risk lock", "daily limit hit"))

            result.delivered shouldBe false
            recorder.records shouldHaveSize 1
            recorder.records.single().deliveryStatus shouldBe AlertDeliveryStatus.FAILED
        }
    })

private fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-06-30T00:00:00Z"), ZoneOffset.UTC)

private class InMemoryAlertDeliveryRecorder : AlertDeliveryRecorder {
    val records = mutableListOf<AlertDeliveryRecord>()

    override suspend fun record(record: AlertDeliveryRecord) {
        records += record
    }
}

private class RecordingFailingAlertSink : AlertSink {
    override suspend fun send(message: AlertMessage): AlertDeliveryResult =
        AlertDeliveryResult(delivered = false, sinkName = "fake", failureReason = "failed")
}
