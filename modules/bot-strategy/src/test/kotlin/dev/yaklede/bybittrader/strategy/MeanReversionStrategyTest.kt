package dev.yaklede.bybittrader.strategy

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class MeanReversionStrategyTest :
    StringSpec({
        "emits a buy signal when price closes below lower band with weak RSI" {
            val strategy =
                MeanReversionStrategy(
                    MeanReversionParameters(
                        bollingerPeriod = 5,
                        bollingerStdDev = 1.0,
                        rsiPeriod = 3,
                        atrPeriod = 3,
                        oversoldRsi = 45.0,
                    ),
                )
            val candles = candlesFromCloses(100, 99, 98, 97, 96, 82)

            val decision = strategy.evaluate(candles)

            decision.intent?.side shouldBe Side.BUY
            decision.intent?.score?.reasonCodes shouldBe listOf("LOWER_BAND_REVERSION", "RSI_OVERSOLD")
        }

        "does not trade without a band or RSI edge" {
            val strategy =
                MeanReversionStrategy(
                    MeanReversionParameters(
                        bollingerPeriod = 5,
                        rsiPeriod = 3,
                        atrPeriod = 3,
                    ),
                )

            strategy.evaluate(candlesFromCloses(100, 101, 100, 101, 100, 101)).intent shouldBe null
        }
    })

private fun candlesFromCloses(vararg closes: Int): List<Candle> =
    closes.mapIndexed { index, close ->
        Candle(
            symbol = Symbol("BTCUSDT"),
            timeframe = Timeframe.M15,
            openedAt = Instant.parse("2026-06-30T00:00:00Z").plusSeconds(index * 900L),
            open = BigDecimal(close),
            high = BigDecimal(close + 2),
            low = BigDecimal(close - 2),
            close = BigDecimal(close),
            volume = BigDecimal("10"),
        )
    }
