package dev.yaklede.bybittrader.app

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
