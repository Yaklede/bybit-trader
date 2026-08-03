package dev.yaklede.bybittrader.engine.backtest

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class CausalReplayTest :
    StringSpec({
        "closed candle view hides candles after the decision time" {
            val candles =
                listOf(
                    candle("2026-08-01T00:00:00Z", 100, 101, 99, 100),
                    candle("2026-08-01T00:05:00Z", 100, 102, 98, 101),
                    candle("2026-08-01T00:10:00Z", 101, 103, 100, 102),
                )

            CausalReplay.closedCandlesAt(candles, Instant.parse("2026-08-01T00:10:00Z")).size shouldBe 2
        }

        "missing interval rejects the next entry instead of bridging the gap" {
            val candles =
                listOf(
                    candle("2026-08-01T00:00:00Z", 100, 101, 99, 100),
                    candle("2026-08-01T00:10:00Z", 100, 102, 98, 101),
                )

            CausalReplay.nextContiguousEntry(candles, 0, Side.BUY, 0.001) shouldBe null
        }

        "same candle stop is resolved before target for both directions" {
            val longCandle = candle("2026-08-01T00:00:00Z", 100, 110, 90, 105)
            val shortCandle = candle("2026-08-01T00:00:00Z", 100, 110, 90, 95)

            CausalReplay.resolveExitTouch(longCandle, Side.BUY, 95.0, 105.0) shouldBe CausalExitTouch.STOP
            CausalReplay.resolveExitTouch(shortCandle, Side.SELL, 105.0, 95.0) shouldBe CausalExitTouch.STOP
        }

        "entry and exit slippage are adverse by side" {
            val candles =
                listOf(
                    candle("2026-08-01T00:00:00Z", 100, 101, 99, 100),
                    candle("2026-08-01T00:05:00Z", 100, 101, 99, 100),
                )

            CausalReplay.nextContiguousEntry(candles, 0, Side.BUY, 0.01)?.effectivePrice shouldBe 101.0
            CausalReplay.nextContiguousEntry(candles, 0, Side.SELL, 0.01)?.effectivePrice shouldBe 99.0
            CausalReplay.applyExitSlippage(Side.BUY, 100.0, 0.01) shouldBe 99.0
            CausalReplay.applyExitSlippage(Side.SELL, 100.0, 0.01) shouldBe 101.0
        }
    })

private fun candle(
    openedAt: String,
    open: Int,
    high: Int,
    low: Int,
    close: Int,
): Candle =
    Candle(
        symbol = Symbol("BTCUSDT"),
        timeframe = Timeframe.M5,
        openedAt = Instant.parse(openedAt),
        open = BigDecimal.valueOf(open.toLong()),
        high = BigDecimal.valueOf(high.toLong()),
        low = BigDecimal.valueOf(low.toLong()),
        close = BigDecimal.valueOf(close.toLong()),
        volume = BigDecimal.TEN,
    )
