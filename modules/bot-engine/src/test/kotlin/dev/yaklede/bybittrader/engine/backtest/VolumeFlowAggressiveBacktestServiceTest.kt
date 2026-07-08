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

class VolumeFlowAggressiveBacktestServiceTest :
    StringSpec({
        "runs an aggressive M5 absorption breakout trade" {
            val service = VolumeFlowAggressiveBacktestService(InMemoryAggressiveCandleStore(aggressiveAbsorptionCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m5Limit = 120,
                    config = VolumeFlowAggressiveProfiles.finalUsV1(),
                )

            result.profileId shouldBe "absa_final_us_v1"
            result.tradeCount shouldBe 1
            result.wins shouldBe 1
            result.losses shouldBe 0
            result.activeDays shouldBe 1
            result.trades.single().side shouldBe Side.BUY
            result.trades.single().exitReason shouldBe VolumeFlowExitReason.TARGET
            result.trades.single().openedAt shouldBe Instant.parse("2026-06-30T13:10:00Z")
            result.trades.single().closedAt shouldBe Instant.parse("2026-06-30T13:15:00Z")
            result.trades.single().targetR shouldBe 2.2
            (result.finalEquity > result.initialEquity) shouldBe true
            (result.compoundDailyReturnPct > 0.0) shouldBe true
        }

        "applies execution notional and quantity limits to aggressive trades" {
            val service = VolumeFlowAggressiveBacktestService(InMemoryAggressiveCandleStore(aggressiveAbsorptionCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m5Limit = 120,
                    config =
                        VolumeFlowAggressiveProfiles
                            .finalUsV1()
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
                        VolumeFlowAggressiveProfiles
                            .finalUsV1()
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

        "marks aggressive exits as liquidation when leverage leaves less room than the stop" {
            val service = VolumeFlowAggressiveBacktestService(InMemoryAggressiveCandleStore(aggressiveAbsorptionCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m5Limit = 120,
                    config =
                        VolumeFlowAggressiveProfiles
                            .finalUsV1()
                            .copy(
                                leverage = 100.0,
                                liquidationBufferPct = 2.0,
                            ),
                )

            result.tradeCount shouldBe 1
            result.trades.single().exitReason shouldBe VolumeFlowExitReason.LIQUIDATION
        }

        "can block aggressive entries by side regime rules" {
            val service = VolumeFlowAggressiveBacktestService(InMemoryAggressiveCandleStore(aggressiveAbsorptionCandles()))
            val blockedConfig =
                VolumeFlowAggressiveProfiles
                    .finalUsV1()
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
    })

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
                    high = "105",
                    low = "101",
                    close = "104",
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
