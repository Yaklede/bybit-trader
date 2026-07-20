package dev.yaklede.bybittrader.api.strategy

import dev.yaklede.bybittrader.engine.backtest.VolumeFlowAggressiveProfiles
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class StrategyProfileServiceTest :
    StringSpec({
        "strategy state exposes a runtime execution-contract mismatch" {
            val expected = VolumeFlowAggressiveProfiles.current().executionContract
            val service =
                StrategyProfileService(
                    statePath = Files.createTempDirectory("strategy-contract-test").resolve("active.txt"),
                    runtimeExecutionContract = expected.copy(maxNotional = 50.0),
                )

            val state = service.current()

            state.runtimeProfile.executionContractMatched shouldBe false
            state.runtimeProfile.expectedExecutionContractFingerprint shouldBe expected.fingerprint
            state.runtimeProfile.runtimeExecutionContractFingerprint shouldBe expected.copy(maxNotional = 50.0).fingerprint
        }
    })
