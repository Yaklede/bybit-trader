package dev.yaklede.bybittrader.engine.backtest

import dev.yaklede.bybittrader.domain.Candle
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
            result.trades.single().exitReason shouldBe VolumeFlowExitReason.TARGET
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

private fun volumeFlowM1Candles(): List<Candle> =
    (0 until 80).map { index ->
        when (index) {
            61 ->
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
            62 ->
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
