package dev.yaklede.bybittrader.engine.execution

import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.position.CausalPositionPolicyConfig
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

data class AutomaticPositionPolicy(
    val timeframe: Timeframe,
    val maxHoldCandles: Int,
    val maxTradesPerUtcDay: Int,
    val partialTakeProfitR: Double = 1.0,
    val partialTakeProfitFraction: Double = 0.0,
    val breakevenAfterPartialTakeProfit: Boolean = false,
    val atrTrailingPeriod: Int = 14,
    val atrTrailingMultiplier: Double = 0.0,
    val fixedTargetEnabled: Boolean = true,
) {
    init {
        require(maxHoldCandles > 0) { "Maximum hold candles must be positive." }
        require(maxTradesPerUtcDay > 0) { "Maximum trades per UTC day must be positive." }
        require(partialTakeProfitR > 0.0) { "Partial take-profit R must be positive." }
        require(partialTakeProfitFraction >= 0.0 && partialTakeProfitFraction < 1.0) {
            "Partial take-profit fraction must be between 0 inclusive and 1 exclusive."
        }
        require(atrTrailingPeriod > 1) { "ATR trailing period must be greater than one." }
        require(atrTrailingMultiplier >= 0.0) { "ATR trailing multiplier must not be negative." }
    }

    val candleDuration: Duration = timeframe.executionDuration
    val maxHoldingDuration: Duration = candleDuration.multipliedBy(maxHoldCandles.toLong())

    fun isExpired(
        openedAt: Instant,
        evaluatedAt: Instant,
    ): Boolean = !evaluatedAt.isBefore(openedAt.plus(maxHoldingDuration))

    fun causalConfig(feeRate: BigDecimal): CausalPositionPolicyConfig =
        CausalPositionPolicyConfig(
            feeRate = feeRate.toDouble(),
            partialTakeProfitR = partialTakeProfitR,
            partialTakeProfitFraction = partialTakeProfitFraction,
            breakevenAfterPartialTakeProfit = breakevenAfterPartialTakeProfit,
            atrTrailingMultiplier = atrTrailingMultiplier,
            fixedTargetEnabled = fixedTargetEnabled,
            maxHoldCandles = maxHoldCandles,
        )
}

internal val Timeframe.executionDuration: Duration
    get() =
        when (this) {
            Timeframe.M1 -> Duration.ofMinutes(1)
            Timeframe.M5 -> Duration.ofMinutes(5)
            Timeframe.M15 -> Duration.ofMinutes(15)
            Timeframe.H1 -> Duration.ofHours(1)
        }
