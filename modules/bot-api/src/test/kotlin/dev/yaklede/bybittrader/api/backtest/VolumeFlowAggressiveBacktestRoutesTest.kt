package dev.yaklede.bybittrader.api.backtest

import dev.yaklede.bybittrader.engine.backtest.VolumeFlowAggressiveEntryMode
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
                    entryMode = "breakout_retest",
                    breakoutRelativeVolumeMin = 1.2,
                    breakoutBodyRatioMin = 0.5,
                    breakoutDirectionalCloseMin = 0.7,
                    maxBreakoutDistanceAtr = 0.8,
                    retestLookaheadCandles = 8,
                    retestToleranceAtr = 0.2,
                    retestDirectionalCloseMin = 0.6,
                    breakEvenTriggerR = 0.8,
                    breakEvenLockR = 0.2,
                    trailingTriggerR = 1.2,
                    trailingDistanceR = 0.6,
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
            config.entryMode shouldBe VolumeFlowAggressiveEntryMode.BREAKOUT_RETEST
            config.breakoutRelativeVolumeMin shouldBe 1.2
            config.breakoutBodyRatioMin shouldBe 0.5
            config.breakoutDirectionalCloseMin shouldBe 0.7
            config.maxBreakoutDistanceAtr shouldBe 0.8
            config.retestLookaheadCandles shouldBe 8
            config.retestToleranceAtr shouldBe 0.2
            config.retestDirectionalCloseMin shouldBe 0.6
            config.breakEvenTriggerR shouldBe 0.8
            config.breakEvenLockR shouldBe 0.2
            config.trailingTriggerR shouldBe 1.2
            config.trailingDistanceR shouldBe 0.6
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
