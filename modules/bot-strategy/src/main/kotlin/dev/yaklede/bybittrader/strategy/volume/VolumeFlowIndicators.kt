package dev.yaklede.bybittrader.strategy.volume

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Side
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

object VolumeFlowIndicators {
    fun relativeVolume(
        candles: List<Candle>,
        lookback: Int,
    ): Double? {
        require(lookback > 1) { "Volume lookback must be greater than 1." }
        if (candles.size <= lookback) return null
        val currentVolume = candles.last().volume.toDouble()
        val baseline =
            candles
                .dropLast(1)
                .takeLast(lookback)
                .map { it.volume.toDouble() }
                .average()
        if (baseline <= 0.0) return null
        return currentVolume / baseline
    }

    fun volumeZScore(
        candles: List<Candle>,
        lookback: Int,
    ): Double? {
        require(lookback > 1) { "Volume lookback must be greater than 1." }
        if (candles.size <= lookback) return null
        val volumes =
            candles
                .dropLast(1)
                .takeLast(lookback)
                .map { it.volume.toDouble() }
        val average = volumes.average()
        val standardDeviation =
            sqrt(
                volumes
                    .map { volume -> (volume - average).pow(2) }
                    .average(),
            )
        if (standardDeviation <= 0.0) return null
        return (candles.last().volume.toDouble() - average) / standardDeviation
    }

    fun candleShape(candle: Candle): CandleShape {
        val open = candle.open.toDouble()
        val high = candle.high.toDouble()
        val low = candle.low.toDouble()
        val close = candle.close.toDouble()
        val range = high - low
        if (range <= 0.0) {
            return CandleShape(
                bodyRatio = 0.0,
                closeLocation = 0.5,
                upperWickRatio = 0.0,
                lowerWickRatio = 0.0,
                direction = null,
            )
        }

        return CandleShape(
            bodyRatio = abs(close - open) / range,
            closeLocation = (close - low) / range,
            upperWickRatio = (high - maxOf(open, close)) / range,
            lowerWickRatio = (minOf(open, close) - low) / range,
            direction =
                when {
                    close > open -> Side.BUY
                    close < open -> Side.SELL
                    else -> null
                },
        )
    }

    fun vwap(candles: List<Candle>): Double? {
        if (candles.isEmpty()) return null
        val totalVolume = candles.sumOf { it.volume.toDouble() }
        if (totalVolume <= 0.0) return null
        val weightedPrice =
            candles.sumOf { candle ->
                val typicalPrice =
                    (
                        candle.high.toDouble() +
                            candle.low.toDouble() +
                            candle.close.toDouble()
                    ) / 3.0
                typicalPrice * candle.volume.toDouble()
            }
        return weightedPrice / totalVolume
    }

    fun recentRange(
        candles: List<Candle>,
        lookback: Int,
    ): PriceRange? {
        require(lookback > 1) { "Range lookback must be greater than 1." }
        if (candles.size <= lookback) return null
        val window = candles.dropLast(1).takeLast(lookback)
        return PriceRange(
            high = window.maxOf { it.high.toDouble() },
            low = window.minOf { it.low.toDouble() },
        )
    }

    fun breakoutSide(
        candles: List<Candle>,
        lookback: Int,
    ): Side? {
        val range = recentRange(candles, lookback) ?: return null
        val close = candles.last().close.toDouble()
        return when {
            close > range.high -> Side.BUY
            close < range.low -> Side.SELL
            else -> null
        }
    }
}

data class CandleShape(
    val bodyRatio: Double,
    val closeLocation: Double,
    val upperWickRatio: Double,
    val lowerWickRatio: Double,
    val direction: Side?,
) {
    fun closesStronglyFor(side: Side): Boolean =
        when (side) {
            Side.BUY -> closeLocation >= 0.70 && upperWickRatio <= 0.35
            Side.SELL -> closeLocation <= 0.30 && lowerWickRatio <= 0.35
        }
}

data class PriceRange(
    val high: Double,
    val low: Double,
)
