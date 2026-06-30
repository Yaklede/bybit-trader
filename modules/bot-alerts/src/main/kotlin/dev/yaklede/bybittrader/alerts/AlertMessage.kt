package dev.yaklede.bybittrader.alerts

enum class AlertSeverity {
    INFO,
    WARNING,
    CRITICAL,
}

data class AlertMessage(
    val severity: AlertSeverity,
    val title: String,
    val body: String,
) {
    init {
        require(title.isNotBlank()) { "Alert title must not be blank." }
        require(body.isNotBlank()) { "Alert body must not be blank." }
    }
}
