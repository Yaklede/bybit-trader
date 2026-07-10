package dev.yaklede.bybittrader.api.backtest

import dev.yaklede.bybittrader.engine.backtest.VolumeFlowSideMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class VolumeFlowAggressiveBacktestRoutesTest :
    StringSpec({
        "maps aggressive research controls without changing the live profile" {
            val config =
                VolumeFlowAggressiveCurrentBacktestRequest(
                    relativeVolumeMin = 1.5,
                    clusterCandles = 3,
                    clusterVolumeMin = 1.8,
                    stopAtr = 1.4,
                    targetR = 3.0,
                    entryLookaheadCandles = 5,
                    maxHoldCandles = 72,
                    maxTradesPerDay = 2,
                    sideMode = "long_only",
                    adaptiveRulesEnabled = false,
                    sideRegimeRulesEnabled = false,
                ).validated().toConfig()

            config.profileId shouldBe "absa_final_us_v1"
            config.relativeVolumeMin shouldBe 1.5
            config.clusterCandles shouldBe 3
            config.clusterVolumeMin shouldBe 1.8
            config.stopAtr shouldBe 1.4
            config.targetR shouldBe 3.0
            config.entryLookaheadCandles shouldBe 5
            config.maxHoldCandles shouldBe 72
            config.maxTradesPerDay shouldBe 2
            config.sideMode shouldBe VolumeFlowSideMode.LONG_ONLY
            config.adaptiveStop shouldBe null
            config.adaptiveTarget shouldBe null
            config.sideRegimeBlocks shouldBe emptyList()
        }

        "rejects an unknown aggressive side mode" {
            shouldThrow<IllegalArgumentException> {
                VolumeFlowAggressiveCurrentBacktestRequest(sideMode = "unknown").validated()
            }
        }
    })
