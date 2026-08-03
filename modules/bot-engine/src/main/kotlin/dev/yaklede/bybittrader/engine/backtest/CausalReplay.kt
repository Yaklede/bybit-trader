package dev.yaklede.bybittrader.engine.backtest

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Timeframe
import java.time.Duration
import java.time.Instant

/** Shared historical execution rules. Runtime adapters must provide the same effective prices. */
internal object CausalReplay {
    fun closedCandlesAt(
        candles: List<Candle>,
        decisionAt: Instant,
    ): List<Candle> {
        val sorted = candles.sortedBy { it.openedAt }
        require(sorted.map { it.timeframe }.distinct().size <= 1) {
            "Causal replay candles must use a single timeframe."
        }
        val timeframe = sorted.firstOrNull()?.timeframe ?: return emptyList()
        return sorted.filter { !it.openedAt.plus(timeframe.replayDuration()).isAfter(decisionAt) }
    }

    fun nextContiguousEntry(
        candles: List<Candle>,
        decisionIndex: Int,
        side: Side,
        slippageRate: Double,
    ): CausalEntryFill? {
        if (decisionIndex < 0 || decisionIndex >= candles.lastIndex) return null
        val decisionCandle = candles[decisionIndex]
        val entryCandle = candles[decisionIndex + 1]
        if (entryCandle.timeframe != decisionCandle.timeframe ||
            entryCandle.symbol != decisionCandle.symbol ||
            entryCandle.openedAt != decisionCandle.openedAt.plus(decisionCandle.timeframe.replayDuration())
        ) {
            return null
        }
        val rawPrice = entryCandle.open.toDouble()
        val effectivePrice =
            when (side) {
                Side.BUY -> rawPrice * (1.0 + slippageRate)
                Side.SELL -> rawPrice * (1.0 - slippageRate)
            }
        return CausalEntryFill(
            candle = entryCandle,
            rawPrice = rawPrice,
            effectivePrice = effectivePrice,
        )
    }

    fun resolveExitTouch(
        candle: Candle,
        side: Side,
        stopPrice: Double,
        targetPrice: Double,
    ): CausalExitTouch {
        val stopHit =
            when (side) {
                Side.BUY -> candle.low.toDouble() <= stopPrice
                Side.SELL -> candle.high.toDouble() >= stopPrice
            }
        if (stopHit) return CausalExitTouch.STOP

        val targetHit =
            when (side) {
                Side.BUY -> candle.high.toDouble() >= targetPrice
                Side.SELL -> candle.low.toDouble() <= targetPrice
            }
        return if (targetHit) CausalExitTouch.TARGET else CausalExitTouch.NONE
    }

    fun applyExitSlippage(
        side: Side,
        triggerPrice: Double,
        slippageRate: Double,
    ): Double =
        when (side) {
            Side.BUY -> triggerPrice * (1.0 - slippageRate)
            Side.SELL -> triggerPrice * (1.0 + slippageRate)
        }
}

internal data class CausalEntryFill(
    val candle: Candle,
    val rawPrice: Double,
    val effectivePrice: Double,
)

internal enum class CausalExitTouch {
    NONE,
    STOP,
    TARGET,
}

internal fun Timeframe.replayDuration(): Duration =
    when (this) {
        Timeframe.M1 -> Duration.ofMinutes(1)
        Timeframe.M5 -> Duration.ofMinutes(5)
        Timeframe.M15 -> Duration.ofMinutes(15)
        Timeframe.H1 -> Duration.ofHours(1)
    }
