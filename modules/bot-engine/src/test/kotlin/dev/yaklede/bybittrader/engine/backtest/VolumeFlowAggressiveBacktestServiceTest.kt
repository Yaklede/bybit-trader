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
import kotlin.math.abs

class VolumeFlowAggressiveBacktestServiceTest :
    StringSpec({
        "runs an aggressive M5 absorption breakout trade" {
            val service = VolumeFlowAggressiveBacktestService(InMemoryAggressiveCandleStore(aggressiveAbsorptionCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m5Limit = 120,
                    config = aggressiveTestConfig(),
                )

            result.profileId shouldBe "absa_final_us_v1"
            result.tradeCount shouldBe 1
            result.wins shouldBe 1
            result.losses shouldBe 0
            result.activeDays shouldBe 1
            result.trades.single().side shouldBe Side.BUY
            result.trades.single().exitReason shouldBe VolumeFlowExitReason.TARGET
            result.trades.single().signalAt shouldBe Instant.parse("2026-06-30T13:10:00Z")
            result.trades.single().openedAt shouldBe Instant.parse("2026-06-30T13:15:00Z")
            result.trades.single().closedAt shouldBe Instant.parse("2026-06-30T13:15:00Z")
            result.trades.single().entryPrice shouldBe 102.0204
            result.trades.single().targetR shouldBe 2.2
            (result.trades.single().exitPrice < result.trades.single().triggerExitPrice) shouldBe true
            result.trades.single().holdingMinutes shouldBe 1L
            (result.trades.single().mfeR > 0.0) shouldBe true
            (result.trades.single().maeR >= 0.0) shouldBe true
            (abs(result.netPnl - (result.grossPnl - result.totalFees + result.totalFundingPnl)) < 1e-8) shouldBe true
            result.liquidationCount shouldBe 0
            (result.finalEquity > result.initialEquity) shouldBe true
            (result.compoundDailyReturnPct > 0.0) shouldBe true
            (result.averageGrossR > result.averageNetR) shouldBe true
            (result.averageCostR > 0.0) shouldBe true
            result.rProfitFactor shouldBe 999.0
            result.performanceBySide.sumOf { it.tradeCount } shouldBe result.tradeCount
            result.performanceByExitReason.sumOf { it.tradeCount } shouldBe result.tradeCount
            result.performanceBySignalHourUtc.sumOf { it.tradeCount } shouldBe result.tradeCount
            result.performanceByAbsorptionRelativeVolume.sumOf { it.tradeCount } shouldBe result.tradeCount
            result.performanceBySide.single().key shouldBe "BUY"
            result.performanceByExitReason.single().key shouldBe "TARGET"
            (result.performanceBySide.single().averageCostR > 0.0) shouldBe true
            (result.performanceBySide.single().rProfitFactor ?: 0.0) shouldBe 999.0
            (result.trades.single().absorptionRelativeVolume >= 1.0) shouldBe true
            (result.trades.single().clusterVolumeRatio >= 1.2) shouldBe true
        }

        "never fills a breakout at the signal candle open" {
            val candles =
                aggressiveAbsorptionCandles().map { candle ->
                    if (candle.openedAt == Instant.parse("2026-06-30T13:15:00Z")) {
                        candle.copy(open = BigDecimal("110"), high = BigDecimal("111"), low = BigDecimal("109"), close = BigDecimal("110"))
                    } else {
                        candle
                    }
                }
            val service = VolumeFlowAggressiveBacktestService(InMemoryAggressiveCandleStore(candles))

            val result = service.run(Symbol("BTCUSDT"), 120, aggressiveTestConfig())

            result.tradeCount shouldBe 0
        }

        "rejects a signal when the next M5 candle is missing" {
            val missingEntryAt = Instant.parse("2026-06-30T13:15:00Z")
            val candles = aggressiveAbsorptionCandles().filterNot { it.openedAt == missingEntryAt }
            val service = VolumeFlowAggressiveBacktestService(InMemoryAggressiveCandleStore(candles))

            val result = service.run(Symbol("BTCUSDT"), 120, aggressiveTestConfig())

            result.tradeCount shouldBe 0
        }

        "applies execution notional and quantity limits to aggressive trades" {
            val service = VolumeFlowAggressiveBacktestService(InMemoryAggressiveCandleStore(aggressiveAbsorptionCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m5Limit = 120,
                    config =
                        aggressiveTestConfig()
                            .copy(
                                initialEquity = 660.0,
                                quantityStep = 0.001,
                                minQuantity = 0.001,
                                maxNotional = 100.0,
                            ),
                )

            result.tradeCount shouldBe 1
            result.skippedSignalCount shouldBe 0
            (result.trades.single().notional <= 100.0) shouldBe true
            (result.trades.single().quantity >= 0.001) shouldBe true
        }

        "skips aggressive trades below the minimum execution quantity" {
            val service = VolumeFlowAggressiveBacktestService(InMemoryAggressiveCandleStore(aggressiveAbsorptionCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m5Limit = 120,
                    config =
                        aggressiveTestConfig()
                            .copy(
                                initialEquity = 100.0,
                                quantityStep = 0.001,
                                minQuantity = 0.001,
                                maxNotional = 0.01,
                            ),
                )

            result.tradeCount shouldBe 0
            (result.skippedSignalCount > 0) shouldBe true
        }

        "rejects an entry whose stop is beyond the estimated liquidation boundary" {
            val service = VolumeFlowAggressiveBacktestService(InMemoryAggressiveCandleStore(aggressiveAbsorptionCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m5Limit = 120,
                    config =
                        aggressiveTestConfig()
                            .copy(
                                leverage = 100.0,
                                liquidationBufferPct = 2.0,
                            ),
                )

            result.tradeCount shouldBe 0
            result.liquidationCount shouldBe 0
        }

        "can block aggressive entries by side regime rules" {
            val service = VolumeFlowAggressiveBacktestService(InMemoryAggressiveCandleStore(aggressiveAbsorptionCandles()))
            val blockedConfig =
                aggressiveTestConfig()
                    .copy(
                        sideRegimeBlocks =
                            listOf(
                                VolumeFlowAggressiveSideRegimeBlock(
                                    side = Side.BUY,
                                    lookbackCandles = 10,
                                    returnMinPct = -1.0,
                                    returnMaxPct = 10.0,
                                ),
                            ),
                    )

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m5Limit = 120,
                    config = blockedConfig,
                )

            result.tradeCount shouldBe 0
            result.finalEquity shouldBe result.initialEquity
        }

        "loads warmup before a bounded replay without trading in the warmup range" {
            val candles = aggressiveAbsorptionCandles()
            val service = VolumeFlowAggressiveBacktestService(InMemoryAggressiveCandleStore(candles))
            val replayStartAt = candles[60].openedAt
            val replayEndAt = candles.last().openedAt

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m5Limit = 60,
                    config = aggressiveTestConfig(),
                    replayStartAt = replayStartAt,
                    replayEndAt = replayEndAt,
                )

            result.startAt shouldBe replayStartAt
            result.endAt shouldBe replayEndAt
            result.warmupCandleCount shouldBe 60
            result.m5CandleCount shouldBe 60
            result.tradeCount shouldBe 1
            result.trades
                .single()
                .openedAt
                .isBefore(replayStartAt) shouldBe false
        }

        "enters only after a breakout retest confirmation closes" {
            val candles = aggressiveRetestCandles()
            val service = VolumeFlowAggressiveBacktestService(InMemoryAggressiveCandleStore(candles))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m5Limit = 120,
                    config =
                        aggressiveTestConfig()
                            .copy(
                                entryMode = VolumeFlowAggressiveEntryMode.BREAKOUT_RETEST,
                                breakoutRelativeVolumeMin = 1.2,
                                breakoutBodyRatioMin = 0.5,
                                breakoutDirectionalCloseMin = 0.55,
                                maxBreakoutDistanceAtr = 1.0,
                            ),
                )

            result.tradeCount shouldBe 1
            result.trades.single().breakoutAt shouldBe Instant.parse("2026-06-30T13:10:00Z")
            result.trades.single().signalAt shouldBe Instant.parse("2026-06-30T13:15:00Z")
            result.trades.single().openedAt shouldBe Instant.parse("2026-06-30T13:20:00Z")
            result.trades.single().exitReason shouldBe VolumeFlowExitReason.TARGET
        }

        "uses M1 candles to resolve the execution path after entry" {
            val entryAt = Instant.parse("2026-06-30T13:15:00Z")
            val m5Candles =
                aggressiveAbsorptionCandles().map { candle ->
                    if (candle.openedAt == entryAt) candle.copy(low = BigDecimal("98")) else candle
                }
            val service = VolumeFlowAggressiveBacktestService(InMemoryAggressiveCandleStore(m5Candles))
            val m1EntryCandle =
                Candle(
                    symbol = Symbol("BTCUSDT"),
                    timeframe = Timeframe.M1,
                    openedAt = entryAt,
                    open = BigDecimal("102"),
                    high = BigDecimal("110"),
                    low = BigDecimal("101"),
                    close = BigDecimal("109"),
                    volume = BigDecimal("10"),
                )

            val m1Result =
                service.runLoadedCandles(
                    symbol = Symbol("BTCUSDT"),
                    candles = m5Candles,
                    m1Candles = listOf(m1EntryCandle),
                    config = aggressiveTestConfig().copy(executionPathMode = AggressiveExecutionPathMode.M1_REQUIRED),
                )
            val m5Result =
                service.runLoadedCandles(
                    symbol = Symbol("BTCUSDT"),
                    candles = m5Candles,
                    config = aggressiveTestConfig(),
                )

            m1Result.trades.single().exitReason shouldBe VolumeFlowExitReason.TARGET
            m1Result.trades.single().closedAt shouldBe entryAt
            m1Result.executionPathMode shouldBe AggressiveExecutionPathMode.M1_REQUIRED
            m5Result.trades.single().exitReason shouldBe VolumeFlowExitReason.STOP
        }

        "executes a reachable stop before a farther liquidation price within an M1 candle" {
            val entryAt = Instant.parse("2026-06-30T13:15:00Z")
            val m5Candles = aggressiveAbsorptionCandles()
            val service = VolumeFlowAggressiveBacktestService(InMemoryAggressiveCandleStore(m5Candles))
            val m1EntryCandle =
                Candle(
                    symbol = Symbol("BTCUSDT"),
                    timeframe = Timeframe.M1,
                    openedAt = entryAt,
                    open = BigDecimal("102"),
                    high = BigDecimal("103"),
                    low = BigDecimal("90"),
                    close = BigDecimal("91"),
                    volume = BigDecimal("10"),
                )

            val result =
                service.runLoadedCandles(
                    symbol = Symbol("BTCUSDT"),
                    candles = m5Candles,
                    m1Candles = listOf(m1EntryCandle),
                    config =
                        aggressiveTestConfig()
                            .copy(
                                executionPathMode = AggressiveExecutionPathMode.M1_REQUIRED,
                                leverage = 15.0,
                            ),
                )

            result.trades.single().exitReason shouldBe VolumeFlowExitReason.STOP
            result.liquidationCount shouldBe 0
        }

        "activates a break-even stop only from the next M1 candle" {
            val entryAt = Instant.parse("2026-06-30T13:15:00Z")
            val m5Candles = aggressiveAbsorptionCandles()
            val service = VolumeFlowAggressiveBacktestService(InMemoryAggressiveCandleStore(m5Candles))
            val m1Candles =
                listOf(
                    aggressiveCandle(
                        index = 0,
                        open = "102",
                        high = "104.2",
                        low = "101.8",
                        close = "104",
                        volume = "10",
                    ).copy(timeframe = Timeframe.M1, openedAt = entryAt),
                    aggressiveCandle(
                        index = 1,
                        open = "104",
                        high = "104",
                        low = "101",
                        close = "101.5",
                        volume = "10",
                    ).copy(timeframe = Timeframe.M1, openedAt = entryAt.plusSeconds(60)),
                )

            val result =
                service.runLoadedCandles(
                    symbol = Symbol("BTCUSDT"),
                    candles = m5Candles,
                    m1Candles = m1Candles,
                    config =
                        aggressiveTestConfig()
                            .copy(
                                executionPathMode = AggressiveExecutionPathMode.M1_REQUIRED,
                                breakEvenTriggerR = 0.5,
                                breakEvenLockR = 0.1,
                            ),
                )

            result.trades.single().exitReason shouldBe VolumeFlowExitReason.BREAKEVEN_STOP
            result.trades.single().closedAt shouldBe entryAt.plusSeconds(60)
            (result.trades.single().triggerExitPrice > result.trades.single().entryPrice) shouldBe true
        }

        "skips an M1 execution when the path starts with a data gap" {
            val candles = aggressiveAbsorptionCandles()
            val service = VolumeFlowAggressiveBacktestService(InMemoryAggressiveCandleStore(candles))

            val result =
                service.runLoadedCandles(
                    symbol = Symbol("BTCUSDT"),
                    candles = candles,
                    m1Candles = emptyList(),
                    config = aggressiveTestConfig().copy(executionPathMode = AggressiveExecutionPathMode.M1_REQUIRED),
                )

            result.tradeCount shouldBe 0
            (result.skippedDataGapCount > 0) shouldBe true
        }
    })

private fun aggressiveTestConfig(): VolumeFlowAggressiveBacktestConfig =
    VolumeFlowAggressiveProfiles
        .finalUsV1()
        .copy(
            adaptiveStop = null,
            adaptiveTarget = null,
            sideRegimeBlocks = emptyList(),
            executionPathMode = AggressiveExecutionPathMode.M5_CONSERVATIVE,
        )

private class InMemoryAggressiveCandleStore(
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

    override suspend fun candlesBefore(
        symbol: Symbol,
        timeframe: Timeframe,
        beforeAt: Instant,
        limit: Int,
    ): List<Candle> =
        candles
            .filter { it.symbol == symbol && it.timeframe == timeframe && it.openedAt.isBefore(beforeAt) }
            .sortedByDescending { it.openedAt }
            .take(limit)
}

private fun aggressiveAbsorptionCandles(): List<Candle> =
    (0 until 120).map { index ->
        when (index) {
            70 ->
                aggressiveCandle(
                    index = index,
                    open = "100",
                    high = "101",
                    low = "99",
                    close = "100",
                    volume = "20",
                )
            71 ->
                aggressiveCandle(
                    index = index,
                    open = "100",
                    high = "101",
                    low = "99",
                    close = "100",
                    volume = "20",
                )
            72 ->
                aggressiveCandle(
                    index = index,
                    open = "100",
                    high = "103",
                    low = "99.8",
                    close = "102",
                    volume = "20",
                )
            73 ->
                aggressiveCandle(
                    index = index,
                    open = "102",
                    high = "110",
                    low = "101",
                    close = "109",
                    volume = "10",
                )
            else ->
                aggressiveCandle(
                    index = index,
                    open = "100",
                    high = "101",
                    low = "99",
                    close = "100",
                    volume = "10",
                )
        }
    }

private fun aggressiveRetestCandles(): List<Candle> =
    aggressiveAbsorptionCandles().mapIndexed { index, candle ->
        when (index) {
            73 ->
                candle.copy(
                    open = BigDecimal("102"),
                    high = BigDecimal("102.4"),
                    low = BigDecimal("100.9"),
                    close = BigDecimal("102.2"),
                )
            74 ->
                candle.copy(
                    open = BigDecimal("102.2"),
                    high = BigDecimal("112"),
                    low = BigDecimal("102"),
                    close = BigDecimal("110"),
                )
            else -> candle
        }
    }

private fun aggressiveCandle(
    index: Int,
    open: String,
    high: String,
    low: String,
    close: String,
    volume: String,
): Candle =
    Candle(
        symbol = Symbol("BTCUSDT"),
        timeframe = Timeframe.M5,
        openedAt = Instant.parse("2026-06-30T07:10:00Z").plusSeconds(index * 300L),
        open = BigDecimal(open),
        high = BigDecimal(high),
        low = BigDecimal(low),
        close = BigDecimal(close),
        volume = BigDecimal(volume),
    )
