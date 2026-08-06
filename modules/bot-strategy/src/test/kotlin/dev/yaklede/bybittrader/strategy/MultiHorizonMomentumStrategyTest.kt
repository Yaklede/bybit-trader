package dev.yaklede.bybittrader.strategy

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class MultiHorizonMomentumStrategyTest :
    StringSpec({
        "emits a long signal only when momentum changes into consensus" {
            val candles = momentumCandles()
            val strategy = MultiHorizonMomentumStrategy()

            val decision = strategy.evaluate(candles)

            decision.intent?.side shouldBe Side.BUY
            decision.intent?.expectedR shouldBe BigDecimal.valueOf(12.0)
            decision.intent?.invalidationPrice?.value shouldBe candles.last().low
            (decision.intent?.entryAnchoredStopDistance != null) shouldBe true
            decision.reasonCodes shouldBe
                listOf(
                    "MOMENTUM_288_CANDLES",
                    "MOMENTUM_2016_CANDLES",
                    "MOMENTUM_8640_CANDLES",
                    "EMA_REGIME_ALIGNED",
                    "MOMENTUM_TRANSITION",
                )
        }

        "does not re-enter while the consensus direction is unchanged" {
            val candles = momentumCandles()
            val strategy = MultiHorizonMomentumStrategy()

            strategy.evaluate(candles).intent?.side shouldBe Side.BUY
            strategy.evaluate(candles + candle(candles.last().openedAt.plusSeconds(300), 112.0, 113.0, 111.5, 112.5)).intent shouldBe null
        }

        "rejects invalid candidate dimensions" {
            shouldThrow<IllegalArgumentException> {
                MultiHorizonMomentumParameters(
                    momentumLookbackCandles = listOf(288, 2_016),
                    baseReturnThresholdPct = listOf(1.0),
                )
            }.message shouldBe "Momentum lookbacks and thresholds must have the same size."
        }
    })

private fun momentumCandles(): List<Candle> {
    val base =
        (0 until 8_640).map { index ->
            val price = 100.0 + (index.toDouble() / 8_640.0) * 10.0
            candle(
                openedAt = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(index * 300L),
                open = price,
                high = price + 0.15,
                low = price - 0.15,
                close = price + 0.03,
            )
        }
    val previous = base.last()
    return base +
        candle(
            openedAt = previous.openedAt.plusSeconds(300),
            open = previous.close.toDouble(),
            high = previous.close.toDouble() + 1.2,
            low = previous.close.toDouble() - 0.05,
            close = previous.close.toDouble() + 1.0,
        )
}

private fun candle(
    openedAt: Instant,
    open: Double,
    high: Double,
    low: Double,
    close: Double,
): Candle =
    Candle(
        symbol = Symbol("BTCUSDT"),
        timeframe = Timeframe.M5,
        openedAt = openedAt,
        open = BigDecimal.valueOf(open),
        high = BigDecimal.valueOf(high),
        low = BigDecimal.valueOf(low),
        close = BigDecimal.valueOf(close),
        volume = BigDecimal.TEN,
    )
