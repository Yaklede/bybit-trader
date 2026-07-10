package dev.yaklede.bybittrader.engine.execution

import dev.yaklede.bybittrader.domain.Side
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class ExecutionTradePlanCalculatorTest :
    StringSpec({
        "applies leverage notional and quantity-step limits deterministically" {
            val sizing =
                ExecutionTradePlanCalculator.calculateSizing(
                    entryPrice = BigDecimal("60000"),
                    riskPerUnit = BigDecimal("600"),
                    intendedRisk = BigDecimal("5.5"),
                    accountEquity = BigDecimal("100"),
                    constraints =
                        ExecutionSizingConstraints(
                            quantityStep = BigDecimal("0.001"),
                            minQuantity = BigDecimal("0.001"),
                            maxQuantity = null,
                            maxNotional = BigDecimal("100"),
                            leverage = BigDecimal("15"),
                        ),
                )

            sizing?.quantity shouldBe BigDecimal("0.001")
        }

        "calculates symmetric long and short targets" {
            val longTarget =
                ExecutionTradePlanCalculator.calculateTakeProfit(
                    Side.BUY,
                    BigDecimal("100"),
                    BigDecimal("2"),
                    BigDecimal("2.2"),
                )
            val shortTarget =
                ExecutionTradePlanCalculator.calculateTakeProfit(
                    Side.SELL,
                    BigDecimal("100"),
                    BigDecimal("2"),
                    BigDecimal("2.2"),
                )

            longTarget shouldBe BigDecimal("104.4")
            shortTarget shouldBe BigDecimal("95.6")
        }

        "rejects targets that cannot cover configured costs" {
            val rejection =
                ExecutionTradePlanCalculator.targetStopRejection(
                    side = Side.BUY,
                    entryPrice = BigDecimal("100"),
                    takeProfit = BigDecimal("100.10"),
                    stopLoss = BigDecimal("99"),
                    feeRate = BigDecimal("0.0006"),
                    slippageBufferRate = BigDecimal("0.0002"),
                )

            rejection shouldBe "TARGET_DOES_NOT_COVER_ROUND_TRIP_FEES"
        }
    })
