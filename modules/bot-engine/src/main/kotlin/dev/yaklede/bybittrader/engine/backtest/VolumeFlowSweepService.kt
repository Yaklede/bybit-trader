package dev.yaklede.bybittrader.engine.backtest

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.market.MarketCandleStore
import kotlin.math.abs

class VolumeFlowSweepService(
    private val candleStore: MarketCandleStore,
) {
    suspend fun run(
        symbol: Symbol,
        m1Limit: Int,
        m5Limit: Int,
        m15Limit: Int,
        sweepConfig: VolumeFlowSweepConfig,
    ): VolumeFlowSweepReport {
        require(m1Limit in 120..1_600_000) { "M1 candle limit must be between 120 and 1600000." }
        require(m5Limit in 60..320_000) { "M5 candle limit must be between 60 and 320000." }
        require(m15Limit in 60..110_000) { "M15 candle limit must be between 60 and 110000." }

        val m1Candles = candleStore.recentCandles(symbol, Timeframe.M1, m1Limit).sortedBy { it.openedAt }
        val m5Candles = candleStore.recentCandles(symbol, Timeframe.M5, m5Limit).sortedBy { it.openedAt }
        val m15Candles = candleStore.recentCandles(symbol, Timeframe.M15, m15Limit).sortedBy { it.openedAt }
        require(m1Candles.size >= 120) { "At least 120 M1 candles are required." }
        require(m5Candles.size >= 60) { "At least 60 M5 candles are required." }
        require(m15Candles.size >= 60) { "At least 60 M15 candles are required." }

        val trainM1Candles = m1Candles.trainSlice(sweepConfig.trainRatio, minTrainSize = 60)
        val trainM5Candles = m5Candles.trainSlice(sweepConfig.trainRatio, minTrainSize = 30)
        val trainM15Candles = m15Candles.trainSlice(sweepConfig.trainRatio, minTrainSize = 30)
        val testM1Candles = m1Candles.testSlice(sweepConfig.trainRatio, minTestSize = 60)
        val testM5Candles = m5Candles.testSlice(sweepConfig.trainRatio, minTestSize = 30)
        val testM15Candles = m15Candles.testSlice(sweepConfig.trainRatio, minTestSize = 30)

        val backtestService = VolumeFlowBacktestService(candleStore)
        val results =
            sweepConfig
                .candidates()
                .map { candidate ->
                    val backtestConfig = candidate.toBacktestConfig(sweepConfig)
                    val train =
                        backtestService
                            .runLoadedCandles(symbol, trainM1Candles, trainM5Candles, trainM15Candles, backtestConfig)
                            .toSweepSummary()
                    val test =
                        backtestService
                            .runLoadedCandles(symbol, testM1Candles, testM5Candles, testM15Candles, backtestConfig)
                            .toSweepSummary()
                    VolumeFlowSweepResult(
                        candidate = candidate,
                        train = train,
                        test = test,
                        testPassesProfitabilityGate = test.passesProfitabilityGate(),
                        testPassesCompoundingGate = test.passesCompoundingGate(),
                        testPassesFrequencyGate = test.passesFrequencyGate(sweepConfig),
                        score = train.stabilityAdjustedCompoundingScore(test),
                    )
                }.sortedWith(
                    compareByDescending<VolumeFlowSweepResult> {
                        it.train.passesCompoundingGate() && it.testPassesCompoundingGate
                    }.thenByDescending {
                        it.train.passesProfitabilityGate() && it.testPassesProfitabilityGate
                    }.thenByDescending { it.testPassesCompoundingGate }
                        .thenByDescending { it.testPassesProfitabilityGate }
                        .thenByDescending { it.score }
                        .thenByDescending { minOf(it.train.netReturnPct, it.test.netReturnPct) }
                        .thenByDescending { it.test.netReturnPct }
                        .thenBy { it.test.maxDrawdownPct },
                )

        return VolumeFlowSweepReport(
            symbol = symbol,
            candidateCount = results.size,
            m1CandleCount = m1Candles.size,
            m5CandleCount = m5Candles.size,
            m15CandleCount = m15Candles.size,
            trainM1CandleCount = trainM1Candles.size,
            trainM5CandleCount = trainM5Candles.size,
            trainM15CandleCount = trainM15Candles.size,
            testM1CandleCount = testM1Candles.size,
            testM5CandleCount = testM5Candles.size,
            testM15CandleCount = testM15Candles.size,
            results = results,
        )
    }
}

private const val MIN_PAYOFF_EDGE_PCT = 0.0

private fun List<Candle>.trainSlice(
    trainRatio: Double,
    minTrainSize: Int,
): List<Candle> {
    val splitIndex = splitIndex(trainRatio, minTrainSize)
    return take(splitIndex)
}

private fun List<Candle>.testSlice(
    trainRatio: Double,
    minTestSize: Int,
): List<Candle> {
    val splitIndex = splitIndex(trainRatio, minTestSize)
    return drop(splitIndex)
}

private fun List<Candle>.splitIndex(
    trainRatio: Double,
    minSize: Int,
): Int =
    (size * trainRatio)
        .toInt()
        .coerceIn(minSize, size - minSize)

private fun VolumeFlowBacktestSummary.passesProfitabilityGate(): Boolean =
    tradeCount > 0 &&
        netReturnPct > 0.0 &&
        expectancyR > 0.0 &&
        hasPositivePayoffEdge() &&
        maxDrawdownPct <= 10.0

private fun VolumeFlowBacktestSummary.hasPositivePayoffEdge(): Boolean =
    winRateEdgePct?.let { it >= MIN_PAYOFF_EDGE_PCT }
        ?: (averageWinR > 0.0 && averageLossR == 0.0)

private fun VolumeFlowBacktestSummary.passesCompoundingGate(): Boolean =
    passesProfitabilityGate() &&
        returnDrawdownRatio >= 0.25 &&
        maxConsecutiveLosses <= 3

private fun VolumeFlowBacktestSummary.passesFrequencyGate(config: VolumeFlowSweepConfig): Boolean =
    activeDayCoveragePct >= config.minActiveDayCoveragePct &&
        averageTradesPerActiveDay <= config.maxTradesPerDay

private fun VolumeFlowBacktestSummary.compoundingScore(): Double {
    val profitFactorScore = ((profitFactor ?: 1.0) - 1.0) * 2.0
    val expectancyScore = expectancyR * 20.0
    val payoffScore = ((winRateEdgePct ?: -25.0) * 2.0) + ((payoffRatio ?: 0.0) * 5.0)
    val coverageScore = activeDayCoveragePct * 0.5
    val drawdownPenalty = maxDrawdownPct * 0.75
    val lossStreakPenalty = maxConsecutiveLosses * 0.5
    return netReturnPct +
        profitFactorScore +
        expectancyScore +
        payoffScore +
        coverageScore +
        (returnDrawdownRatio * 5.0) -
        drawdownPenalty -
        lossStreakPenalty
}

private fun VolumeFlowBacktestSummary.stabilityAdjustedCompoundingScore(test: VolumeFlowBacktestSummary): Double {
    val trainScore = compoundingScore()
    val testScore = test.compoundingScore()
    val weakestReturn = minOf(netReturnPct, test.netReturnPct)
    val weakestScore = minOf(trainScore, testScore)
    val returnDivergencePenalty = abs(netReturnPct - test.netReturnPct) * 0.5
    val negativeReturnPenalty = listOf(netReturnPct, test.netReturnPct).count { it <= 0.0 } * 20.0
    val drawdownPenalty = maxOf(maxDrawdownPct, test.maxDrawdownPct) * 0.5
    return weakestScore +
        weakestReturn +
        ((trainScore + testScore) * 0.25) -
        returnDivergencePenalty -
        negativeReturnPenalty -
        drawdownPenalty
}
