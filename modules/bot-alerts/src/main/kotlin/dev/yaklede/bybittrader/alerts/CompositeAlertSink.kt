package dev.yaklede.bybittrader.alerts

class CompositeAlertSink(
    private val sinks: List<AlertSink>,
) : AlertSink {
    override suspend fun send(message: AlertMessage): AlertDeliveryResult {
        val results = sinks.map { sink -> sink.send(message) }
        val failed = results.filterNot { it.delivered }
        return if (failed.isEmpty()) {
            AlertDeliveryResult(delivered = true, sinkName = "composite")
        } else {
            AlertDeliveryResult(
                delivered = false,
                sinkName = "composite",
                failureReason = failed.joinToString("; ") { "${it.sinkName}:${it.failureReason}" },
            )
        }
    }
}
