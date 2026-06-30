package dev.yaklede.bybittrader.alerts

class NoopAlertSink : AlertSink {
    override suspend fun send(message: AlertMessage): AlertDeliveryResult = AlertDeliveryResult(delivered = true, sinkName = "noop")
}
