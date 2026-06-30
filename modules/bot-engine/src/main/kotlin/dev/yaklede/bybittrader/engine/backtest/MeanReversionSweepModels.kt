package dev.yaklede.bybittrader.engine.backtest

import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe

data class MeanReversionSweepConfig(
    val oversoldRsiValues: List<Double> = listOf(25.0, 30.0, 35.0),
    val overboughtRsiValues: List<Double> = listOf(65.0, 70.0, 75.0),
    val bollingerStdDevValues: List<Double> = listOf(1.8, 2.0, 2.2),
    val atrStopMultiplierValues: List<Double> = listOf(1.0, 1.2, 1.5),
    val trainRatio: Double = 0.6,
    val maxCandidates: Int = 100,
) {
    init {
        require(oversoldRsiValues.isNotEmpty()) { "Oversold RSI values must not be empty." }
        require(overboughtRsiValues.isNotEmpty()) { "Overbought RSI values must not be empty." }
        require(bollingerStdDevValues.isNotEmpty()) { "Bollinger std dev values must not be empty." }
        require(atrStopMultiplierValues.isNotEmpty()) { "ATR stop multiplier values must not be empty." }
        require(oversoldRsiValues.all { it in 0.0..100.0 }) { "Oversold RSI values must be between 0 and 100." }
        require(overboughtRsiValues.all { it in 0.0..100.0 }) { "Overbought RSI values must be between 0 and 100." }
        require(bollingerStdDevValues.all { it > 0.0 }) { "Bollinger std dev values must be positive." }
        require(atrStopMultiplierValues.all { it > 0.0 }) { "ATR stop multiplier values must be positive." }
        require(trainRatio >= 0.5 && trainRatio <= 0.8) { "Train ratio must be between 0.5 and 0.8." }
        require(maxCandidates in 1..100) { "Max candidates must be between 1 and 100." }
        require(candidateCount() <= maxCandidates) {
            "Parameter sweep candidate count must be less than or equal to max candidates."
        }
    }

    fun candidates(): List<MeanReversionCandidate> =
        oversoldRsiValues.flatMap { oversold ->
            overboughtRsiValues.flatMap { overbought ->
                bollingerStdDevValues.flatMap { stdDev ->
                    atrStopMultiplierValues.mapNotNull { stopMultiplier ->
                        if (oversold < overbought) {
                            MeanReversionCandidate(
                                oversoldRsi = oversold,
                                overboughtRsi = overbought,
                                bollingerStdDev = stdDev,
                                atrStopMultiplier = stopMultiplier,
                            )
                        } else {
                            null
                        }
                    }
                }
            }
        }

    private fun candidateCount(): Int =
        oversoldRsiValues.size *
            overboughtRsiValues.size *
            bollingerStdDevValues.size *
            atrStopMultiplierValues.size
}

data class MeanReversionCandidate(
    val oversoldRsi: Double,
    val overboughtRsi: Double,
    val bollingerStdDev: Double,
    val atrStopMultiplier: Double,
)

data class MeanReversionSweepReport(
    val symbol: Symbol,
    val timeframe: Timeframe,
    val candleCount: Int,
    val trainCandleCount: Int,
    val testCandleCount: Int,
    val results: List<MeanReversionSweepResult>,
)

data class MeanReversionSweepResult(
    val candidate: MeanReversionCandidate,
    val train: BacktestSummary,
    val test: BacktestSummary,
)

data class BacktestSummary(
    val tradeCount: Int,
    val netPnl: Double,
    val netReturnPct: Double,
    val expectedMonthlyReturnPct: Double?,
    val maxDrawdownPct: Double,
    val profitFactor: Double?,
    val expectancyR: Double,
    val maxConsecutiveLosses: Int,
    val noTradeReasonCounts: Map<String, Int>,
)

fun BacktestResult.toSummary(): BacktestSummary =
    BacktestSummary(
        tradeCount = trades.size,
        netPnl = netPnl,
        netReturnPct = netReturnPct,
        expectedMonthlyReturnPct = expectedMonthlyReturnPct,
        maxDrawdownPct = maxDrawdownPct,
        profitFactor = profitFactor,
        expectancyR = expectancyR,
        maxConsecutiveLosses = maxConsecutiveLosses,
        noTradeReasonCounts = noTradeReasonCounts,
    )
