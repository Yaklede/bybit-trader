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

        "rejects sizing that rounds down to zero" {
            val sizing =
                ExecutionTradePlanCalculator.calculateSizing(
                    entryPrice = BigDecimal("60000"),
                    riskPerUnit = BigDecimal("600"),
                    intendedRisk = BigDecimal("0.10"),
                    accountEquity = BigDecimal("100"),
                    constraints =
                        ExecutionSizingConstraints(
                            quantityStep = BigDecimal("0.001"),
                            minQuantity = null,
                            maxQuantity = null,
                            maxNotional = null,
                            leverage = BigDecimal("15"),
                        ),
                )

            sizing shouldBe null
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

        "charges slippage on both sides of the round trip" {
            val rejection =
                ExecutionTradePlanCalculator.targetStopRejection(
                    side = Side.BUY,
                    entryPrice = BigDecimal("100"),
                    takeProfit = BigDecimal("100.15"),
                    stopLoss = BigDecimal("99"),
                    feeRate = BigDecimal("0.0006"),
                    slippageBufferRate = BigDecimal("0.0002"),
                )

            rejection shouldBe "TARGET_DOES_NOT_COVER_ROUND_TRIP_FEES"
        }

        "rejects a positive but cost-distorted net risk reward" {
            val rejection =
                ExecutionTradePlanCalculator.targetStopRejection(
                    side = Side.BUY,
                    entryPrice = BigDecimal("100"),
                    takeProfit = BigDecimal("101.20"),
                    stopLoss = BigDecimal("99"),
                    feeRate = BigDecimal("0.0006"),
                    slippageBufferRate = BigDecimal("0.0002"),
                )

            rejection shouldBe "NET_RISK_REWARD_BELOW_MINIMUM"
        }

        "includes round trip costs in risk sizing" {
            val riskPerUnit =
                ExecutionTradePlanCalculator.costAdjustedRiskPerUnit(
                    entryPrice = BigDecimal("100"),
                    riskPerUnit = BigDecimal("1"),
                    feeRate = BigDecimal("0.0006"),
                    slippageBufferRate = BigDecimal("0.0002"),
                )

            riskPerUnit.compareTo(BigDecimal("1.16")) shouldBe 0
        }

        "rejects a stop beyond the estimated liquidation boundary" {
            val rejection =
                ExecutionTradePlanCalculator.leverageStopRejection(
                    side = Side.BUY,
                    entryPrice = BigDecimal("100"),
                    stopLoss = BigDecimal("95"),
                    leverage = BigDecimal("25"),
                    liquidationBufferPct = BigDecimal("0.6"),
                )

            rejection shouldBe "STOP_REACHES_ESTIMATED_LIQUIDATION"
        }
    })
