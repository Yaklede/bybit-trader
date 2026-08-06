package dev.yaklede.bybittrader.engine.execution

import dev.yaklede.bybittrader.domain.Timeframe
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class AutomaticPositionPolicyTest :
    StringSpec({
        "causal config preserves every shared position-policy setting" {
            val policy =
                AutomaticPositionPolicy(
                    timeframe = Timeframe.M5,
                    maxHoldCandles = 4_032,
                    maxTradesPerUtcDay = 1,
                    partialTakeProfitR = 1.25,
                    partialTakeProfitFraction = 0.0,
                    breakevenAfterPartialTakeProfit = false,
                    atrTrailingPeriod = 20,
                    atrTrailingMultiplier = 16.0,
                    fixedTargetEnabled = false,
                )

            val causal = policy.causalConfig(BigDecimal("0.0006"))

            causal.feeRate shouldBe 0.0006
            causal.partialTakeProfitR shouldBe 1.25
            causal.partialTakeProfitFraction shouldBe 0.0
            causal.breakevenAfterPartialTakeProfit shouldBe false
            causal.atrTrailingMultiplier shouldBe 16.0
            causal.fixedTargetEnabled shouldBe false
            causal.maxHoldCandles shouldBe 4_032
            policy.atrTrailingPeriod shouldBe 20
        }
    })
