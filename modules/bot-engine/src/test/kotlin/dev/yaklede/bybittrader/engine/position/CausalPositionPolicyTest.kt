package dev.yaklede.bybittrader.engine.position

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class CausalPositionPolicyTest :
    StringSpec({
        "stop wins when one candle touches both stop and target" {
            val policy = policy(fixedTargetEnabled = true)
            val state = policy.open(openRequest())

            val step = policy.onCandle(state, candleAt(0, high = 115.0, low = 85.0), trailingAtr = null)

            step.exit?.reason shouldBe CausalPositionExitReason.STOP
            step.exit?.triggerPrice shouldBe (90.0 plusOrMinus 0.000001)
            step.partialExit shouldBe null
        }

        "partial target moves the remaining position to breakeven before the next candle" {
            val policy =
                policy(
                    fixedTargetEnabled = false,
                    partialTakeProfitFraction = 0.5,
                    breakevenAfterPartialTakeProfit = true,
                )
            val opened = policy.open(openRequest(quantity = 2.0))

            val partial = policy.onCandle(opened, candleAt(0, high = 111.0, low = 95.0), trailingAtr = null)
            val stopped = policy.onCandle(partial.state, candleAt(1, high = 105.0, low = 99.0), trailingAtr = null)

            partial.partialExit?.quantity shouldBe (1.0 plusOrMinus 0.000001)
            partial.state.currentStopPrice shouldBe (100.0 plusOrMinus 0.000001)
            stopped.exit?.reason shouldBe CausalPositionExitReason.BREAKEVEN_STOP
            stopped.exit?.remainingQuantity shouldBe (1.0 plusOrMinus 0.000001)
        }

        "trailing stop calculated after a candle becomes active on the next candle" {
            val policy = policy(fixedTargetEnabled = false, atrTrailingMultiplier = 1.0)
            val opened = policy.open(openRequest())

            val advanced = policy.onCandle(opened, candleAt(0, high = 120.0, low = 95.0, close = 119.0), trailingAtr = 2.0)
            val stopped = policy.onCandle(advanced.state, candleAt(1, high = 119.0, low = 117.0), trailingAtr = 2.0)

            advanced.exit shouldBe null
            advanced.state.currentStopPrice shouldBe (118.0 plusOrMinus 0.000001)
            stopped.exit?.reason shouldBe CausalPositionExitReason.TRAILING_STOP
            stopped.exit?.triggerPrice shouldBe (118.0 plusOrMinus 0.000001)
        }

        "trailing stop beyond the closed price exits at the observable close" {
            val policy = policy(fixedTargetEnabled = false, atrTrailingMultiplier = 1.0)
            val opened = policy.open(openRequest())

            val reversed = policy.onCandle(opened, candleAt(0, high = 120.0, low = 95.0, close = 100.0), trailingAtr = 2.0)

            reversed.exit?.reason shouldBe CausalPositionExitReason.TRAILING_STOP
            reversed.exit?.triggerPrice shouldBe (100.0 plusOrMinus 0.000001)
        }

        "maximum hold duration counts from the entry candle without an early forced close" {
            val policy = policy(fixedTargetEnabled = false, maxHoldCandles = 2)
            var state = policy.open(openRequest())

            val entryCandle = policy.onCandle(state, candleAt(0), trailingAtr = null)
            state = entryCandle.state
            val nextCandle = policy.onCandle(state, candleAt(1), trailingAtr = null)
            state = nextCandle.state
            val timeExit = policy.onCandle(state, candleAt(2, close = 103.0), trailingAtr = null)

            entryCandle.exit shouldBe null
            nextCandle.exit shouldBe null
            timeExit.exit?.reason shouldBe CausalPositionExitReason.TIME
            timeExit.exit?.triggerPrice shouldBe (103.0 plusOrMinus 0.000001)
        }
    })

private fun policy(
    fixedTargetEnabled: Boolean,
    partialTakeProfitFraction: Double = 0.0,
    breakevenAfterPartialTakeProfit: Boolean = false,
    atrTrailingMultiplier: Double = 0.0,
    maxHoldCandles: Int = 10,
): CausalPositionPolicy =
    CausalPositionPolicy(
        CausalPositionPolicyConfig(
            feeRate = 0.0006,
            partialTakeProfitR = 1.0,
            partialTakeProfitFraction = partialTakeProfitFraction,
            breakevenAfterPartialTakeProfit = breakevenAfterPartialTakeProfit,
            atrTrailingMultiplier = atrTrailingMultiplier,
            fixedTargetEnabled = fixedTargetEnabled,
            maxHoldCandles = maxHoldCandles,
        ),
    )

private fun openRequest(quantity: Double = 1.0): CausalPositionOpenRequest =
    CausalPositionOpenRequest(
        side = Side.BUY,
        entryAt = Instant.parse("2026-06-30T00:00:00Z"),
        entryPrice = 100.0,
        initialStopPrice = 90.0,
        riskPerUnit = 10.0,
        expectedR = 1.0,
        quantity = quantity,
    )

private fun candleAt(
    index: Int,
    open: Double = 100.0,
    high: Double = 105.0,
    low: Double = 95.0,
    close: Double = 100.0,
): Candle =
    Candle(
        symbol = Symbol("BTCUSDT"),
        timeframe = Timeframe.M5,
        openedAt = Instant.parse("2026-06-30T00:00:00Z").plusSeconds(index * 300L),
        open = BigDecimal.valueOf(open),
        high = BigDecimal.valueOf(high),
        low = BigDecimal.valueOf(low),
        close = BigDecimal.valueOf(close),
        volume = BigDecimal.TEN,
    )
