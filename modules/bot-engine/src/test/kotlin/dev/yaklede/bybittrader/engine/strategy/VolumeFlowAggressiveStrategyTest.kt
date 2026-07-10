package dev.yaklede.bybittrader.engine.strategy

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowAggressiveEntryMode
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowAggressiveProfiles
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class VolumeFlowAggressiveStrategyTest :
    StringSpec({
        "emits a buy signal when the latest M5 candle confirms an absorption breakout" {
            val strategy = VolumeFlowAggressiveStrategy()

            val decision = strategy.evaluate(aggressiveBreakoutCandles())
            val reasonCodes =
                decision.intent
                    ?.score
                    ?.reasonCodes
                    .orEmpty()

            decision.intent?.side shouldBe Side.BUY
            decision.intent?.strategy shouldBe "volume-flow-aggressive-absa_final_us_v1"
            reasonCodes shouldContain "AGGRESSIVE_ABSORPTION_BREAKOUT"
            reasonCodes shouldContain "SIGNAL_AT_2026-06-01T14:00:00Z"
            decision.intent?.expectedR?.toPlainString() shouldBe "2.2"
            decision.intent
                ?.invalidationPrice
                ?.value
                ?.let { it < BigDecimal("102") } shouldBe true
        }

        "does not trade without enough M5 history for the regime rules" {
            val strategy = VolumeFlowAggressiveStrategy()

            val decision = strategy.evaluate(aggressiveBreakoutCandles().take(200))

            decision.intent shouldBe null
            decision.reasonCodes shouldBe listOf("INSUFFICIENT_AGGRESSIVE_HISTORY")
        }

        "can filter the final entry signal hour independently" {
            val strategy =
                VolumeFlowAggressiveStrategy(
                    VolumeFlowAggressiveProfiles.finalUsV1().copy(entrySignalHoursUtc = setOf(15)),
                )

            val decision = strategy.evaluate(aggressiveBreakoutCandles())

            decision.intent shouldBe null
        }

        "emits a retest signal only after the latest confirmation candle closes" {
            val config =
                VolumeFlowAggressiveProfiles
                    .finalUsV1()
                    .copy(
                        entryMode = VolumeFlowAggressiveEntryMode.BREAKOUT_RETEST,
                        breakoutRelativeVolumeMin = 1.2,
                        breakoutBodyRatioMin = 0.5,
                        breakoutDirectionalCloseMin = 0.55,
                        maxBreakoutDistanceAtr = 1.0,
                    )
            val strategy = VolumeFlowAggressiveStrategy(config)

            val decision = strategy.evaluate(aggressiveRetestBreakoutCandles())

            decision.intent?.side shouldBe Side.BUY
            decision.reasonCodes shouldContain "SIGNAL_AT_2026-06-01T14:00:00Z"
            decision.reasonCodes shouldContain "BREAKOUT_AT_2026-06-01T13:55:00Z"

            val beforeRetest = strategy.evaluate(aggressiveRetestBreakoutCandles().dropLast(1))
            beforeRetest.intent shouldBe null
        }

        "emits the opposite side only after a failed breakout confirmation" {
            val config =
                VolumeFlowAggressiveProfiles
                    .finalUsV1()
                    .copy(
                        entryMode = VolumeFlowAggressiveEntryMode.FAILED_BREAK_REVERSAL,
                        breakoutRelativeVolumeMin = 1.2,
                        breakoutBodyRatioMin = 0.5,
                        breakoutDirectionalCloseMin = 0.55,
                        maxBreakoutDistanceAtr = 1.0,
                    )
            val strategy = VolumeFlowAggressiveStrategy(config)

            val decision = strategy.evaluate(aggressiveFailedBreakoutCandles())

            decision.intent?.side shouldBe Side.SELL
            decision.reasonCodes shouldContain "ENTRY_MODE_FAILED_BREAK_REVERSAL"
            val beforeFailure = strategy.evaluate(aggressiveFailedBreakoutCandles().dropLast(1))
            beforeFailure.intent shouldBe null
        }
    })

private fun aggressiveBreakoutCandles(): List<Candle> {
    val total = 18_000
    val latestAt = Instant.parse("2026-06-01T14:00:00Z")
    val firstAt = latestAt.minus(Duration.ofMinutes((total - 1) * 5L))
    val candles =
        MutableList(total) { index ->
            testCandle(
                openedAt = firstAt.plus(Duration.ofMinutes(index * 5L)),
                open = "100",
                high = "100.5",
                low = "99.5",
                close = "100",
                volume = "100",
            )
        }

    candles[total - 3] =
        testCandle(
            openedAt = candles[total - 3].openedAt,
            open = "100",
            high = "101",
            low = "99",
            close = "100.3",
            volume = "150",
        )
    candles[total - 2] =
        testCandle(
            openedAt = candles[total - 2].openedAt,
            open = "100.2",
            high = "101",
            low = "99",
            close = "100.4",
            volume = "150",
        )
    candles[total - 1] =
        testCandle(
            openedAt = latestAt,
            open = "100.8",
            high = "103",
            low = "100.5",
            close = "102",
            volume = "130",
        )
    return candles
}

private fun aggressiveRetestBreakoutCandles(): List<Candle> {
    val candles = aggressiveBreakoutCandles().toMutableList()
    val latestAt = candles.last().openedAt
    candles[candles.lastIndex - 3] =
        testCandle(latestAt.minus(Duration.ofMinutes(15)), "100", "101", "99", "100.3", "150")
    candles[candles.lastIndex - 2] =
        testCandle(latestAt.minus(Duration.ofMinutes(10)), "100.2", "101", "99", "100.4", "150")
    candles[candles.lastIndex - 1] =
        testCandle(latestAt.minus(Duration.ofMinutes(5)), "100.5", "103", "100.5", "102", "130")
    candles[candles.lastIndex] =
        testCandle(latestAt, "102", "102.4", "100.9", "102.2", "100")
    return candles
}

private fun aggressiveFailedBreakoutCandles(): List<Candle> {
    val candles = aggressiveRetestBreakoutCandles().toMutableList()
    val latestAt = candles.last().openedAt
    candles[candles.lastIndex] =
        testCandle(latestAt, "102", "103.2", "100", "100.5", "100")
    return candles
}

private fun testCandle(
    openedAt: Instant,
    open: String,
    high: String,
    low: String,
    close: String,
    volume: String,
): Candle =
    Candle(
        symbol = Symbol("BTCUSDT"),
        timeframe = Timeframe.M5,
        openedAt = openedAt,
        open = BigDecimal(open),
        high = BigDecimal(high),
        low = BigDecimal(low),
        close = BigDecimal(close),
        volume = BigDecimal(volume),
    )
