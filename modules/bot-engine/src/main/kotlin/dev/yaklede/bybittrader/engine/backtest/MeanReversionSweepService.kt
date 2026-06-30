package dev.yaklede.bybittrader.engine.backtest

import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.market.MarketCandleStore
import dev.yaklede.bybittrader.strategy.MeanReversionParameters
import dev.yaklede.bybittrader.strategy.MeanReversionStrategy

class MeanReversionSweepService(
    private val candleStore: MarketCandleStore,
) {
    suspend fun run(
        symbol: Symbol,
        timeframe: Timeframe,
        candleLimit: Int,
        backtestConfig: BacktestConfig,
        sweepConfig: MeanReversionSweepConfig,
    ): MeanReversionSweepReport {
        require(candleLimit in 60..1000) { "Candle limit must be between 60 and 1000." }
        val candles =
            candleStore
                .recentCandles(symbol, timeframe, candleLimit)
                .sortedBy { it.openedAt }
        require(candles.size >= 60) { "At least 60 stored candles are required for parameter sweep." }

        val splitIndex = (candles.size * sweepConfig.trainRatio).toInt().coerceIn(30, candles.size - 30)
        val trainCandles = candles.take(splitIndex)
        val testCandles = candles.drop(splitIndex)
        val results =
            sweepConfig
                .candidates()
                .map { candidate ->
                    val strategy = MeanReversionStrategy(candidate.toParameters())
                    val runner = BacktestRunner(strategy)
                    MeanReversionSweepResult(
                        candidate = candidate,
                        train = runner.run(trainCandles, backtestConfig).toSummary(),
                        test = runner.run(testCandles, backtestConfig).toSummary(),
                    )
                }.sortedWith(
                    compareByDescending<MeanReversionSweepResult> { it.test.expectancyR }
                        .thenByDescending { it.test.netReturnPct }
                        .thenBy { it.test.maxDrawdownPct },
                )

        return MeanReversionSweepReport(
            symbol = symbol,
            timeframe = timeframe,
            candleCount = candles.size,
            trainCandleCount = trainCandles.size,
            testCandleCount = testCandles.size,
            results = results,
        )
    }
}

private fun MeanReversionCandidate.toParameters(): MeanReversionParameters =
    MeanReversionParameters(
        bollingerStdDev = bollingerStdDev,
        oversoldRsi = oversoldRsi,
        overboughtRsi = overboughtRsi,
        atrStopMultiplier = atrStopMultiplier,
    )
