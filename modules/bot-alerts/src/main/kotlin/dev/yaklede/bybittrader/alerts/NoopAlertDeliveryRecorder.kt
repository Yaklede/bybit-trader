package dev.yaklede.bybittrader.alerts

class NoopAlertDeliveryRecorder : AlertDeliveryRecorder {
    override suspend fun record(record: AlertDeliveryRecord) = Unit
}
