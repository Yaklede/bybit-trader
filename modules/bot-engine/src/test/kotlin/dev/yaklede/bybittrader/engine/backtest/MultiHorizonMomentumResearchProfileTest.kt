package dev.yaklede.bybittrader.engine.backtest

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class MultiHorizonMomentumResearchProfileTest :
    StringSpec({
        "research profile is unverified and cannot execute automatically" {
            val profile = MultiHorizonMomentumResearchProfiles.current()

            profile.profileId shouldBe "multi-horizon-momentum-development-v1"
            profile.executionContract shouldBe "causal-next-contiguous-open-v1"
            profile.validationStatus shouldBe StrategyValidationStatus.UNVERIFIED
            profile.automaticExecutionAllowed shouldBe false
            profile.backtestConfig().maxHoldCandles shouldBe 4_032
            profile.backtestConfig().atrTrailingMultiplier shouldBe 16.0
        }
    })
