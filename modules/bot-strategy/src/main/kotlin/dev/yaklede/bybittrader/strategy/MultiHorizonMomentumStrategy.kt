package dev.yaklede.bybittrader.strategy

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Price
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.SignalIntent
import dev.yaklede.bybittrader.domain.SignalScore
import dev.yaklede.bybittrader.strategy.indicators.Indicators
import java.math.BigDecimal
import kotlin.math.max

/** Research-only port of the predeclared multi-horizon momentum candidate. */
class MultiHorizonMomentumStrategy(
    private val parameters: MultiHorizonMomentumParameters = MultiHorizonMomentumParameters(),
) : TradingStrategy {
    override val name: String = "multi-horizon-momentum-research-v1"
    override val warmupCandles: Int = parameters.minimumCandles

    override fun evaluate(candles: List<Candle>): StrategyDecision {
        if (candles.size < parameters.minimumCandles + 1) {
            return StrategyDecision.noTrade("INSUFFICIENT_HISTORY")
        }

        val direction = directionAt(candles)
        val previousDirection = directionAt(candles.dropLast(1))
        if (direction == null || direction == previousDirection) {
            return StrategyDecision.noTrade("NO_MOMENTUM_TRANSITION")
        }
        if (!parameters.sideMode.allows(direction)) {
            return StrategyDecision.noTrade("SIDE_MODE_BLOCKED")
        }

        val latest = candles.last()
        val atr = Indicators.atr(candles, parameters.atrPeriod) ?: return StrategyDecision.noTrade("NO_ATR")
        if (atr <= 0.0) return StrategyDecision.noTrade("INVALID_ATR")

        val atrDistance = atr * parameters.stopAtr
        val structuralStop =
            when (direction) {
                Side.BUY -> latest.low.toDouble()
                Side.SELL -> latest.high.toDouble()
            }
        if (structuralStop <= 0.0) return StrategyDecision.noTrade("INVALID_STOP_PRICE")

        val reasons =
            parameters.momentumLookbackCandles.mapIndexed { index, lookback ->
                "MOMENTUM_${lookback}_CANDLES"
            } + listOf("EMA_REGIME_ALIGNED", "MOMENTUM_TRANSITION")
        return StrategyDecision(
            intent =
                SignalIntent(
                    symbol = latest.symbol,
                    side = direction,
                    strategy = name,
                    score = SignalScore(total = 85, reasonCodes = reasons),
                    invalidationPrice = Price(BigDecimal.valueOf(structuralStop)),
                    expectedR = BigDecimal.valueOf(parameters.expectedR),
                    entryAnchoredStopDistance = BigDecimal.valueOf(atrDistance),
                ),
            reasonCodes = reasons,
        )
    }

    private fun directionAt(candles: List<Candle>): Side? {
        if (candles.size < parameters.minimumCandles + 1) return null
        val currentFastEma = emaAt(candles, parameters.emaFastCandles) ?: return null
        val currentSlowEma = emaAt(candles, parameters.emaSlowCandles) ?: return null
        val slopeReference =
            emaAt(
                candles,
                parameters.emaFastCandles,
                endExclusive = candles.size - parameters.emaSlopeLookbackCandles,
            ) ?: return null

        var votes = 0
        parameters.momentumLookbackCandles.forEachIndexed { index, lookback ->
            val startIndex = candles.size - lookback
            if (startIndex < 0) return null
            val firstOpen = candles[startIndex].open.toDouble()
            val lastClose = candles.last().close.toDouble()
            if (firstOpen <= 0.0) return null
            val returnPct = ((lastClose / firstOpen) - 1.0) * 100.0
            val threshold = parameters.baseReturnThresholdPct[index] * parameters.thresholdScale
            when {
                returnPct >= threshold -> votes += 1
                returnPct <= -threshold -> votes -= 1
            }
        }

        return when {
            votes >= parameters.minimumConsensusVotes &&
                currentFastEma > currentSlowEma &&
                currentFastEma > slopeReference -> Side.BUY
            votes <= -parameters.minimumConsensusVotes &&
                currentFastEma < currentSlowEma &&
                currentFastEma < slopeReference -> Side.SELL
            else -> null
        }
    }

    private fun emaAt(
        candles: List<Candle>,
        period: Int,
        endExclusive: Int = candles.size,
    ): Double? {
        if (endExclusive < period || endExclusive > candles.size) return null
        return Indicators.emaFromFirstClose(
            values = candles.subList(0, endExclusive).map { it.close.toDouble() },
            period = period,
        )
    }
}

data class MultiHorizonMomentumParameters(
    val momentumLookbackCandles: List<Int> = listOf(288, 2_016, 8_640),
    val baseReturnThresholdPct: List<Double> = listOf(1.0, 3.0, 8.0),
    val thresholdScale: Double = 0.75,
    val minimumConsensusVotes: Int = 3,
    val emaFastCandles: Int = 288,
    val emaSlowCandles: Int = 1_152,
    val emaSlopeLookbackCandles: Int = 288,
    val atrPeriod: Int = 20,
    val stopAtr: Double = 8.0,
    val expectedR: Double = 12.0,
    val sideMode: MultiHorizonMomentumSideMode = MultiHorizonMomentumSideMode.LONG_ONLY,
) {
    val minimumCandles: Int = max(momentumLookbackCandles.maxOrNull() ?: 0, emaSlowCandles + emaSlopeLookbackCandles)

    init {
        require(momentumLookbackCandles.size == baseReturnThresholdPct.size) {
            "Momentum lookbacks and thresholds must have the same size."
        }
        require(momentumLookbackCandles.all { it > 1 }) { "Momentum lookbacks must be greater than 1." }
        require(baseReturnThresholdPct.all { it > 0.0 }) { "Momentum thresholds must be positive." }
        require(thresholdScale > 0.0) { "Momentum threshold scale must be positive." }
        require(minimumConsensusVotes in 1..momentumLookbackCandles.size) {
            "Minimum consensus votes must be within the number of horizons."
        }
        require(emaFastCandles > 1) { "Fast EMA period must be greater than 1." }
        require(emaSlowCandles > emaFastCandles) { "Slow EMA period must exceed fast EMA period." }
        require(emaSlopeLookbackCandles > 0) { "EMA slope lookback must be positive." }
        require(atrPeriod > 1) { "ATR period must be greater than 1." }
        require(stopAtr > 0.0) { "Stop ATR multiplier must be positive." }
        require(expectedR > 0.0) { "Expected R must be positive." }
    }
}

enum class MultiHorizonMomentumSideMode {
    BOTH,
    LONG_ONLY,
    SHORT_ONLY,
    ;

    fun allows(side: Side): Boolean =
        when (this) {
            BOTH -> true
            LONG_ONLY -> side == Side.BUY
            SHORT_ONLY -> side == Side.SELL
        }
}
