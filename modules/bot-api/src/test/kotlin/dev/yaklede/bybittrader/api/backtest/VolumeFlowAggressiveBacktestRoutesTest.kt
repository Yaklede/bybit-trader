package dev.yaklede.bybittrader.api.backtest

import dev.yaklede.bybittrader.engine.backtest.VolumeFlowAggressiveEntryMode
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowAggressiveProfiles
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowAggressiveSignalMode
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowSideMode
import dev.yaklede.bybittrader.engine.backtest.executionContract
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
                    signalMode = "macro_donchian",
                    donchianLookbackCandles = 1_440,
                    stopReferenceCandles = 288,
                    trailingAtrMultiple = 48.0,
                    entrySignalHoursUtc = setOf(14, 15),
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
            config.initialEquity shouldBe 100.0
            config.quantityStep shouldBe 0.001
            config.minQuantity shouldBe 0.001
            config.maxNotional shouldBe 100.0
            config.leverage shouldBe 15.0
            config.relativeVolumeMin shouldBe 1.5
            config.clusterCandles shouldBe 3
            config.clusterVolumeMin shouldBe 1.8
            config.stopAtr shouldBe 1.4
            config.targetR shouldBe 3.0
            config.entryLookaheadCandles shouldBe 5
            config.maxHoldCandles shouldBe 72
            config.maxTradesPerDay shouldBe 2
            config.sideMode shouldBe VolumeFlowSideMode.LONG_ONLY
            config.signalMode shouldBe VolumeFlowAggressiveSignalMode.MACRO_DONCHIAN
            config.donchianLookbackCandles shouldBe 1_440
            config.stopReferenceCandles shouldBe 288
            config.trailingAtrMultiple shouldBe 48.0
            config.entrySignalHoursUtc shouldBe setOf(14, 15)
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
            VolumeFlowAggressiveProfiles.matchesCurrentSignalDefinition(config) shouldBe false
        }

        "empty aggressive request uses the frozen runtime replay contract" {
            val config = VolumeFlowAggressiveCurrentBacktestRequest().validated().toConfig()
            val profile = VolumeFlowAggressiveProfiles.current()

            config.initialEquity shouldBe 100.0
            config.executionContract() shouldBe profile.executionContract
            VolumeFlowAggressiveProfiles.matchesCurrentSignalDefinition(config) shouldBe true
        }

        "rejects an unknown aggressive side mode" {
            shouldThrow<IllegalArgumentException> {
                VolumeFlowAggressiveCurrentBacktestRequest(sideMode = "unknown").validated()
            }
        }

        "rejects an unknown aggressive signal mode" {
            shouldThrow<IllegalArgumentException> {
                VolumeFlowAggressiveCurrentBacktestRequest(signalMode = "unknown").validated()
            }
        }
    })
