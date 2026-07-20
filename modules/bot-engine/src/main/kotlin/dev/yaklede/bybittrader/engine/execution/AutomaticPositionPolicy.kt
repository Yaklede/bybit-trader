package dev.yaklede.bybittrader.engine.execution

import dev.yaklede.bybittrader.domain.Timeframe
import java.time.Duration
import java.time.Instant

data class AutomaticPositionPolicy(
    val timeframe: Timeframe,
    val maxHoldCandles: Int,
    val maxTradesPerUtcDay: Int,
) {
    init {
        require(maxHoldCandles > 0) { "Maximum hold candles must be positive." }
        require(maxTradesPerUtcDay > 0) { "Maximum trades per UTC day must be positive." }
    }

    val maxHoldingDuration: Duration = timeframe.duration.multipliedBy(maxHoldCandles.toLong())

    fun isExpired(
        openedAt: Instant,
        evaluatedAt: Instant,
    ): Boolean = !evaluatedAt.isBefore(openedAt.plus(maxHoldingDuration))
}

private val Timeframe.duration: Duration
    get() =
        when (this) {
            Timeframe.M1 -> Duration.ofMinutes(1)
            Timeframe.M5 -> Duration.ofMinutes(5)
            Timeframe.M15 -> Duration.ofMinutes(15)
            Timeframe.H1 -> Duration.ofHours(1)
        }
