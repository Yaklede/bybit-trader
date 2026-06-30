package dev.yaklede.bybittrader.alerts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class CompositeAlertSinkTest :
    StringSpec({
        "composite sink reports success when all sinks deliver" {
            val result =
                CompositeAlertSink(
                    listOf(NoopAlertSink(), NoopAlertSink()),
                ).send(AlertMessage(AlertSeverity.INFO, "startup", "bot started"))

            result.delivered shouldBe true
        }

        "composite sink reports failure without throwing when one sink fails" {
            val result =
                CompositeAlertSink(
                    listOf(NoopAlertSink(), FailingAlertSink()),
                ).send(AlertMessage(AlertSeverity.CRITICAL, "risk lock", "daily limit hit"))

            result.delivered shouldBe false
        }
    })

private class FailingAlertSink : AlertSink {
    override suspend fun send(message: AlertMessage): AlertDeliveryResult =
        AlertDeliveryResult(delivered = false, sinkName = "fake", failureReason = "failed")
}
