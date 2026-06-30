package dev.yaklede.bybittrader.strategy.indicators

import dev.yaklede.bybittrader.domain.Candle
import kotlin.math.pow
import kotlin.math.sqrt

object Indicators {
    fun ema(
        values: List<Double>,
        period: Int,
    ): Double? {
        require(period > 1) { "EMA period must be greater than 1." }
        if (values.size < period) return null
        val multiplier = 2.0 / (period + 1)
        var current = values.take(period).average()
        values.drop(period).forEach { value ->
            current = ((value - current) * multiplier) + current
        }
        return current
    }

    fun rsi(
        closes: List<Double>,
        period: Int,
    ): Double? {
        require(period > 1) { "RSI period must be greater than 1." }
        if (closes.size <= period) return null
        var gain = 0.0
        var loss = 0.0
        closes.take(period + 1).zipWithNext().forEach { (previous, current) ->
            val change = current - previous
            if (change >= 0) {
                gain += change
            } else {
                loss -= change
            }
        }
        var averageGain = gain / period
        var averageLoss = loss / period

        closes.drop(period + 1).fold(closes[period]) { previous, current ->
            val change = current - previous
            val currentGain = if (change > 0) change else 0.0
            val currentLoss = if (change < 0) -change else 0.0
            averageGain = ((averageGain * (period - 1)) + currentGain) / period
            averageLoss = ((averageLoss * (period - 1)) + currentLoss) / period
            current
        }

        if (averageLoss == 0.0) return 100.0
        val relativeStrength = averageGain / averageLoss
        return 100.0 - (100.0 / (1.0 + relativeStrength))
    }

    fun bollingerBands(
        values: List<Double>,
        period: Int,
        standardDeviationMultiplier: Double,
    ): BollingerBands? {
        require(period > 1) { "Bollinger period must be greater than 1." }
        require(standardDeviationMultiplier > 0.0) { "Standard deviation multiplier must be positive." }
        if (values.size < period) return null
        val window = values.takeLast(period)
        val middle = window.average()
        val standardDeviation =
            sqrt(
                window
                    .map { value -> (value - middle).pow(2) }
                    .average(),
            )
        return BollingerBands(
            upper = middle + (standardDeviation * standardDeviationMultiplier),
            middle = middle,
            lower = middle - (standardDeviation * standardDeviationMultiplier),
        )
    }

    fun atr(
        candles: List<Candle>,
        period: Int,
    ): Double? {
        require(period > 1) { "ATR period must be greater than 1." }
        if (candles.size <= period) return null
        val trueRanges =
            candles.zipWithNext().map { (previous, current) ->
                val high = current.high.toDouble()
                val low = current.low.toDouble()
                val previousClose = previous.close.toDouble()
                maxOf(
                    high - low,
                    kotlin.math.abs(high - previousClose),
                    kotlin.math.abs(low - previousClose),
                )
            }
        if (trueRanges.size < period) return null
        return trueRanges.takeLast(period).average()
    }
}

data class BollingerBands(
    val upper: Double,
    val middle: Double,
    val lower: Double,
)
