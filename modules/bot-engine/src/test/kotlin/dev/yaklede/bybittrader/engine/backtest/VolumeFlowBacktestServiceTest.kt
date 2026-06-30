package dev.yaklede.bybittrader.engine.backtest

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.market.MarketCandleStore
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class VolumeFlowBacktestServiceTest :
    StringSpec({
        "runs a long trade from 15m context 5m volume breakout and 1m retest" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig(),
                )

            result.tradeCount shouldBe 1
            result.wins shouldBe 1
            result.observedDays shouldBe 1
            result.activeDays shouldBe 1
            result.averageTradesPerDay shouldBe 1.0
            result.averageTradesPerActiveDay shouldBe 1.0
            result.tradeFrequencyTargetDays shouldBe 1
            result.belowMinTradeDays shouldBe 0
            result.trades.single().exitReason shouldBe VolumeFlowExitReason.TARGET
            result.trades.single().setupMode shouldBe VolumeFlowSetupMode.BREAKOUT_CONTINUATION
            result.trades.single().marketRegime shouldBe VolumeFlowMarketRegime.TREND_UP
            result.trades.single().keyLevelType shouldBe VolumeFlowKeyLevelType.RANGE_HIGH
            result.trades.single().volumePattern shouldBe VolumeFlowVolumePattern.BREAKOUT_ACCEPTANCE
            result.performanceBySetupMode.single().tag shouldBe "BREAKOUT_CONTINUATION"
            result.performanceByMarketRegime.single().tag shouldBe "TREND_UP"
            result.performanceByVolumePattern.single().tag shouldBe "BREAKOUT_ACCEPTANCE"
        }

        "does not enter when 1m data starts after the setup entry window" {
            val delayedM1Candles = volumeFlowM1Candles().map { candle -> candle.copy(openedAt = candle.openedAt.plusSeconds(86_400L)) }
            val service =
                VolumeFlowBacktestService(
                    InMemoryVolumeFlowCandleStore(
                        delayedM1Candles + volumeFlowM5Candles() + volumeFlowM15Candles(),
                    ),
                )

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig(),
                )

            result.setupCount shouldBe 1
            result.tradeCount shouldBe 0
            result.activeDays shouldBe 0
            result.belowMinTradeDays shouldBe 1
            result.noTradeReasonCounts["NO_M1_RETEST_TRIGGER"] shouldBe 1
        }

        "rejects trades when estimated fees are too large relative to risk" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig().copy(maxEstimatedFeeR = 0.01),
                )

            result.setupCount shouldBe 1
            result.tradeCount shouldBe 0
            result.noTradeReasonCounts["ESTIMATED_FEE_R_TOO_HIGH"] shouldBe 1
        }

        "can reject entries when the stop distance is outside configured bounds" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig().copy(maxEntryRiskPct = 0.01),
                )

            result.setupCount shouldBe 1
            result.tradeCount shouldBe 0
            result.noTradeReasonCounts["ENTRY_RISK_TOO_LARGE"] shouldBe 1
        }

        "can reject setups when the directional close is too weak" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig().copy(minDirectionalCloseStrength = 0.99),
                )

            result.tradeCount shouldBe 0
            result.noTradeReasonCounts["WEAK_CLOSE_LOCATION"] shouldBe 1
        }

        "can require m5 vwap alignment before entering volume flow trades" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig().copy(requireM5Vwap = true, m5VwapLookback = 3),
                )

            result.tradeCount shouldBe 1
            result.noTradeReasonCounts["M5_CONTEXT_REJECTED"] shouldBe null
        }

        "can enter after setup candle close without waiting for a later 1m confirmation" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig().copy(entryMode = VolumeFlowEntryMode.SETUP_CLOSE_CONFIRMATION),
                )

            result.tradeCount shouldBe 1
            result.trades.single().entryAt shouldBe Instant.parse("2026-06-30T01:05:00Z")
            result.trades.single().exitReason shouldBe VolumeFlowExitReason.TARGET
        }

        "runner exit can trail a move beyond the fixed target reference" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(runnerVolumeFlowCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        testVolumeFlowConfig().copy(
                            exitMode = VolumeFlowExitMode.RUNNER,
                            runnerTrailActivationR = 0.5,
                            runnerTrailDistanceR = 0.05,
                        ),
                )

            result.tradeCount shouldBe 1
            result.trades.single().exitReason shouldBe VolumeFlowExitReason.TRAILING_STOP
            (result.trades.single().exitPrice > result.trades.single().targetPrice) shouldBe true
        }

        "runs a short trade from failed breakout reversal and close confirmation" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(failedBreakReversalCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        testVolumeFlowConfig().copy(
                            setupMode = VolumeFlowSetupMode.FAILED_BREAK_REVERSAL,
                            entryMode = VolumeFlowEntryMode.CLOSE_CONFIRMATION,
                            requireContextVwap = false,
                            requireContextTrend = false,
                            minRejectionWickRatio = 0.25,
                            targetR = 0.25,
                        ),
                )

            result.tradeCount shouldBe 1
            result.trades.single().side shouldBe Side.SELL
            result.trades.single().keyLevelType shouldBe VolumeFlowKeyLevelType.RANGE_HIGH
            result.trades.single().volumePattern shouldBe VolumeFlowVolumePattern.FAILED_BREAK
        }

        "can reject trades that fight the 15m market regime" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(failedBreakReversalCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        testVolumeFlowConfig().copy(
                            setupMode = VolumeFlowSetupMode.FAILED_BREAK_REVERSAL,
                            entryMode = VolumeFlowEntryMode.CLOSE_CONFIRMATION,
                            requireContextVwap = false,
                            requireContextTrend = false,
                            requireRegimeSideAlignment = true,
                            minRejectionWickRatio = 0.25,
                            targetR = 0.25,
                        ),
                )

            result.tradeCount shouldBe 0
            result.noTradeReasonCounts["MARKET_REGIME_SIDE_MISMATCH"] shouldBe 1
        }

        "runs a short trade from volume rejection reversal without requiring a range break" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(failedBreakReversalCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        testVolumeFlowConfig().copy(
                            setupMode = VolumeFlowSetupMode.VOLUME_REJECTION_REVERSAL,
                            entryMode = VolumeFlowEntryMode.SETUP_CLOSE_CONFIRMATION,
                            requireContextVwap = false,
                            requireContextTrend = false,
                            minRejectionWickRatio = 0.25,
                        ),
                )

            result.tradeCount shouldBe 1
            result.trades.single().side shouldBe Side.SELL
        }

        "composite backtest replays leg signals on one equity curve and skips overlapping positions" {
            val service = VolumeFlowCompositeBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()))
            val legConfig = testVolumeFlowConfig()

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        VolumeFlowCompositeBacktestConfig(
                            legs =
                                listOf(
                                    VolumeFlowCompositeBacktestLeg("primary", legConfig),
                                    VolumeFlowCompositeBacktestLeg("duplicate", legConfig),
                                ),
                        ),
                )

            result.signalCount shouldBe 2
            result.tradeCount shouldBe 1
            result.skippedSignalCount shouldBe 1
            result.noTradeReasonCounts["OVERLAPPING_POSITION"] shouldBe 1
            result.performanceByLeg.single().tag shouldBe "primary"
            result.monthlyPerformance.single().tradeCount shouldBe 1
            result.walkForwardPerformance.size shouldBe 4
            result.walkForwardPerformance.sumOf { it.tradeCount } shouldBe 1
            (result.finalEquity > result.initialEquity) shouldBe true
        }

        "composite backtest can admit overlapping positions when concurrency is configured" {
            val service = VolumeFlowCompositeBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()))
            val legConfig = testVolumeFlowConfig()

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        VolumeFlowCompositeBacktestConfig(
                            maxConcurrentPositions = 2,
                            legs =
                                listOf(
                                    VolumeFlowCompositeBacktestLeg("primary", legConfig),
                                    VolumeFlowCompositeBacktestLeg("duplicate", legConfig),
                                ),
                        ),
                )

            result.signalCount shouldBe 2
            result.tradeCount shouldBe 2
            result.skippedSignalCount shouldBe 0
            result.noTradeReasonCounts["OVERLAPPING_POSITION"] shouldBe null
            result.performanceByLeg.map { it.tag } shouldBe listOf("duplicate", "primary")
            result.monthlyPerformance.single().tradeCount shouldBe 2
            result.walkForwardPerformance.sumOf { it.tradeCount } shouldBe 2
            (result.finalEquity > result.initialEquity) shouldBe true
        }
    })

private fun testVolumeFlowConfig(): VolumeFlowBacktestConfig =
    VolumeFlowBacktestConfig(
        volumeLookback = 3,
        relativeVolumeThreshold = 2.0,
        volumeZScoreThreshold = 0.5,
        setupRangeLookback = 3,
        contextVwapLookback = 3,
        minBodyRatio = 0.4,
        entryLookaheadM1Candles = 3,
        entryRetestTolerancePct = 0.01,
        targetR = 0.5,
        maxHoldM1Candles = 5,
    )

private class InMemoryVolumeFlowCandleStore(
    private val candles: List<Candle>,
) : MarketCandleStore {
    override suspend fun upsert(candles: List<Candle>) = Unit

    override suspend fun recentCandles(
        symbol: Symbol,
        timeframe: Timeframe,
        limit: Int,
    ): List<Candle> =
        candles
            .filter { it.symbol == symbol && it.timeframe == timeframe }
            .sortedByDescending { it.openedAt }
            .take(limit)
}

private fun volumeFlowCandles(): List<Candle> = volumeFlowM1Candles() + volumeFlowM5Candles() + volumeFlowM15Candles()

private fun runnerVolumeFlowCandles(): List<Candle> = runnerVolumeFlowM1Candles() + volumeFlowM5Candles() + volumeFlowM15Candles()

private fun failedBreakReversalCandles(): List<Candle> =
    failedBreakReversalM1Candles() + failedBreakReversalM5Candles() + volumeFlowM15Candles()

private fun runnerVolumeFlowM1Candles(): List<Candle> =
    volumeFlowM1Candles().map { candle ->
        if (candle.openedAt == Instant.parse("2026-06-30T01:06:00Z")) {
            candle.copy(
                high = BigDecimal("130.0"),
                close = BigDecimal("128.0"),
            )
        } else {
            candle
        }
    }

private fun volumeFlowM1Candles(): List<Candle> =
    (0 until 80).map { index ->
        when (index) {
            65 ->
                volumeFlowCandle(
                    index = index,
                    timeframe = Timeframe.M1,
                    seconds = 60L,
                    open = "112.0",
                    high = "113.0",
                    low = "111.8",
                    close = "112.9",
                    volume = "30",
                )
            66 ->
                volumeFlowCandle(
                    index = index,
                    timeframe = Timeframe.M1,
                    seconds = 60L,
                    open = "112.9",
                    high = "118.0",
                    low = "112.8",
                    close = "117.5",
                    volume = "40",
                )
            else ->
                volumeFlowCandle(
                    index = index,
                    timeframe = Timeframe.M1,
                    seconds = 60L,
                    open = "105",
                    high = "106",
                    low = "104",
                    close = "105",
                    volume = "10",
                )
        }
    }

private fun volumeFlowM5Candles(): List<Candle> =
    (0 until 30).map { index ->
        if (index == 12) {
            volumeFlowCandle(
                index = index,
                timeframe = Timeframe.M5,
                seconds = 300L,
                open = "105",
                high = "112",
                low = "104",
                close = "111.5",
                volume = "60",
            )
        } else {
            val close = 100 + index.coerceAtMost(10)
            val volume =
                when (index % 3) {
                    0 -> "8"
                    1 -> "10"
                    else -> "12"
                }
            volumeFlowCandle(
                index = index,
                timeframe = Timeframe.M5,
                seconds = 300L,
                open = close.toString(),
                high = (close + 1).toString(),
                low = (close - 1).toString(),
                close = close.toString(),
                volume = volume,
            )
        }
    }

private fun failedBreakReversalM1Candles(): List<Candle> =
    (0 until 80).map { index ->
        when (index) {
            65 ->
                volumeFlowCandle(
                    index = index,
                    timeframe = Timeframe.M1,
                    seconds = 60L,
                    open = "110.8",
                    high = "111.1",
                    low = "109.0",
                    close = "109.1",
                    volume = "30",
                )
            66 ->
                volumeFlowCandle(
                    index = index,
                    timeframe = Timeframe.M1,
                    seconds = 60L,
                    open = "109.1",
                    high = "109.2",
                    low = "106.0",
                    close = "106.2",
                    volume = "40",
                )
            else ->
                volumeFlowCandle(
                    index = index,
                    timeframe = Timeframe.M1,
                    seconds = 60L,
                    open = "110",
                    high = "111",
                    low = "109",
                    close = "110",
                    volume = "10",
                )
        }
    }

private fun failedBreakReversalM5Candles(): List<Candle> =
    volumeFlowM5Candles().map { candle ->
        if (candle.openedAt == Instant.parse("2026-06-30T01:00:00Z")) {
            candle.copy(
                open = BigDecimal("112"),
                high = BigDecimal("114"),
                low = BigDecimal("106"),
                close = BigDecimal("108"),
                volume = BigDecimal("60"),
            )
        } else {
            candle
        }
    }

private fun volumeFlowM15Candles(): List<Candle> =
    (0 until 30).map { index ->
        val close = 100 + index.coerceAtMost(5)
        volumeFlowCandle(
            index = index,
            timeframe = Timeframe.M15,
            seconds = 900L,
            open = close.toString(),
            high = (close + 2).toString(),
            low = (close - 2).toString(),
            close = close.toString(),
            volume = "20",
        )
    }

private fun volumeFlowCandle(
    index: Int,
    timeframe: Timeframe,
    seconds: Long,
    open: String,
    high: String,
    low: String,
    close: String,
    volume: String,
): Candle =
    Candle(
        symbol = Symbol("BTCUSDT"),
        timeframe = timeframe,
        openedAt = Instant.parse("2026-06-30T00:00:00Z").plusSeconds(index * seconds),
        open = BigDecimal(open),
        high = BigDecimal(high),
        low = BigDecimal(low),
        close = BigDecimal(close),
        volume = BigDecimal(volume),
    )
