package dev.yaklede.bybittrader.strategy.volume

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class VolumeFlowIndicatorsTest :
    StringSpec({
        "relative volume compares latest volume against previous lookback average" {
            val candles =
                listOf(
                    candle(0, close = "100", volume = "10"),
                    candle(1, close = "101", volume = "10"),
                    candle(2, close = "102", volume = "10"),
                    candle(3, close = "103", volume = "40"),
                )

            VolumeFlowIndicators.relativeVolume(candles, lookback = 3)!! shouldBeExactly 4.0
        }

        "volume z score uses previous lookback volumes" {
            val candles =
                listOf(
                    candle(0, close = "100", volume = "10"),
                    candle(1, close = "101", volume = "12"),
                    candle(2, close = "102", volume = "14"),
                    candle(3, close = "103", volume = "22"),
                )

            VolumeFlowIndicators.volumeZScore(candles, lookback = 3)!! shouldBe (6.12 plusOrMinus 0.01)
        }

        "candle shape measures body close location and wick ratios" {
            val shape =
                VolumeFlowIndicators.candleShape(
                    candle(
                        index = 0,
                        open = "100",
                        high = "112",
                        low = "96",
                        close = "110",
                        volume = "20",
                    ),
                )

            shape.direction shouldBe Side.BUY
            shape.bodyRatio shouldBe (0.625 plusOrMinus 0.0001)
            shape.closeLocation shouldBe (0.875 plusOrMinus 0.0001)
            shape.upperWickRatio shouldBe (0.125 plusOrMinus 0.0001)
            shape.closesStronglyFor(Side.BUY) shouldBe true
            shape.closesStronglyFor(Side.SELL) shouldBe false
        }

        "vwap uses volume weighted typical price" {
            val candles =
                listOf(
                    candle(0, high = "12", low = "9", close = "9", volume = "10"),
                    candle(1, high = "24", low = "18", close = "18", volume = "30"),
                )

            VolumeFlowIndicators.vwap(candles) shouldBe (17.5 plusOrMinus 0.0001)
        }

        "breakout side compares latest close with previous range" {
            val candles =
                listOf(
                    candle(0, high = "105", low = "95", close = "100", volume = "10"),
                    candle(1, high = "106", low = "96", close = "101", volume = "10"),
                    candle(2, high = "107", low = "97", close = "108", volume = "20"),
                )

            VolumeFlowIndicators.breakoutSide(candles, lookback = 2) shouldBe Side.BUY
        }
    })

private fun candle(
    index: Int,
    open: String = "100",
    high: String = "110",
    low: String = "90",
    close: String,
    volume: String,
): Candle =
    Candle(
        symbol = Symbol("BTCUSDT"),
        timeframe = Timeframe.M5,
        openedAt = Instant.parse("2026-06-30T00:00:00Z").plusSeconds(index * 300L),
        open = BigDecimal(open),
        high = BigDecimal(high),
        low = BigDecimal(low),
        close = BigDecimal(close),
        volume = BigDecimal(volume),
    )
