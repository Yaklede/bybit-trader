package dev.yaklede.bybittrader.app

import dev.yaklede.bybittrader.alerts.AlertMessage
import dev.yaklede.bybittrader.alerts.AlertSeverity
import java.time.Clock
import java.time.Duration
import java.time.Instant

class VolumeConfirmedTrendShadowAlertPolicy(
    private val repeatInterval: Duration = Duration.ofHours(1),
    private val clock: Clock = Clock.systemUTC(),
) {
    private var activeFingerprint: String? = null
    private var lastAlertAt: Instant? = null

    init {
        require(!repeatInterval.isNegative && !repeatInterval.isZero) {
            "Trend shadow alert repeat interval must be positive."
        }
    }

    @Synchronized
    fun shouldAlert(error: Throwable): Boolean {
        val now = Instant.now(clock)
        val fingerprint = "${error::class.qualifiedName}|${error.message.orEmpty()}"
        val changed = fingerprint != activeFingerprint
        val repeatDue = lastAlertAt?.let { Duration.between(it, now) >= repeatInterval } ?: true
        if (!changed && !repeatDue) return false
        activeFingerprint = fingerprint
        lastAlertAt = now
        return true
    }

    @Synchronized
    fun recordSuccess() {
        activeFingerprint = null
        lastAlertAt = null
    }
}

fun Throwable.toVolumeConfirmedTrendShadowFailureAlert(retryDelay: Duration): AlertMessage {
    require(!retryDelay.isNegative && !retryDelay.isZero) { "Trend shadow retry delay must be positive." }
    return AlertMessage(
        severity = AlertSeverity.WARNING,
        title = "추세 Shadow 점검 필요",
        body =
            "이번 가상 검증 주기를 완료하지 못했어요. ${retryDelay.seconds}초 후 자동으로 다시 시도해요. " +
                "오류: ${this::class.simpleName ?: "알 수 없는 오류"}. 원인: ${message ?: "상세 원인 없음"}",
    )
}
