package dev.yaklede.bybittrader.engine.backtest

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class VolumeFlowAggressiveRuntimeProfileTest :
    StringSpec({
        "current replay config is derived from the runtime execution contract" {
            val profile = VolumeFlowAggressiveProfiles.current()
            val replay = VolumeFlowAggressiveProfiles.currentReplayConfig()

            profile.contractVersion shouldBe "aggressive-runtime-v1"
            profile.validationStatus shouldBe StrategyValidationStatus.REJECTED
            profile.strategyConfig
                .positionPolicy()
                .maxHoldingDuration
                .toHours() shouldBe 3
            profile.strategyConfig.positionPolicy().maxTradesPerUtcDay shouldBe 5
            replay.profileId shouldBe profile.profileId
            replay.initialEquity shouldBe 100.0
            replay.executionContract() shouldBe profile.executionContract
            profile.executionContract.fingerprint.length shouldBe 64
        }

        "execution overrides preserve signal identity but signal overrides do not" {
            val base = VolumeFlowAggressiveProfiles.currentReplayConfig()

            VolumeFlowAggressiveProfiles.matchesCurrentSignalDefinition(base) shouldBe true
            VolumeFlowAggressiveProfiles.matchesCurrentSignalDefinition(
                base.copy(initialEquity = 660.0, maxNotional = 50.0),
            ) shouldBe true
            VolumeFlowAggressiveProfiles.matchesCurrentSignalDefinition(base.copy(targetR = base.targetR + 0.1)) shouldBe false
        }
    })
