package dev.yaklede.bybittrader.engine.backtest

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.market.MarketCandleStore
import dev.yaklede.bybittrader.engine.market.flow.AccountRatioPeriod
import dev.yaklede.bybittrader.engine.market.flow.AccountRatioSnapshot
import dev.yaklede.bybittrader.engine.market.flow.FlowMarketDataStore
import dev.yaklede.bybittrader.engine.market.flow.FundingRateSnapshot
import dev.yaklede.bybittrader.engine.market.flow.OpenInterestInterval
import dev.yaklede.bybittrader.engine.market.flow.OpenInterestSnapshot
import dev.yaklede.bybittrader.engine.market.flow.PremiumIndexBar
import dev.yaklede.bybittrader.engine.market.flow.TakerFlowBar
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class VolumeFlowBacktestServiceTest :
    StringSpec({
        "allows volume-flow tuning risk up to twenty percent" {
            VolumeFlowBacktestConfig(riskFraction = 0.20).riskFraction shouldBe 0.20

            shouldThrow<IllegalArgumentException> {
                VolumeFlowBacktestConfig(riskFraction = 0.2001)
            }.message shouldBe "Risk fraction must be between 0 and 0.20."

            shouldThrow<IllegalArgumentException> {
                VolumeFlowBacktestConfig(relativeVolumeThreshold = 5.0, maxRelativeVolumeThreshold = 5.0)
            }.message shouldBe
                "Maximum relative volume threshold must be null or greater than relative volume threshold."

            shouldThrow<IllegalArgumentException> {
                VolumeFlowBacktestConfig(relativeVolumeRiskThreshold = 1.0)
            }.message shouldBe "Relative volume risk threshold must be null or greater than 1."

            shouldThrow<IllegalArgumentException> {
                VolumeFlowBacktestConfig(relativeVolumeRiskMultiplier = 0.0)
            }.message shouldBe "Relative volume risk multiplier must be between 0 and 1."

            shouldThrow<IllegalArgumentException> {
                VolumeFlowBacktestConfig(relativeVolumeRiskMaxTrendMovePct = 0.51)
            }.message shouldBe
                "Relative volume risk maximum trend move percent must be null or between 0 and 0.50."

            shouldThrow<IllegalArgumentException> {
                VolumeFlowBacktestConfig(maxContextRangePct = 0.11)
            }.message shouldBe "Maximum context range percent must be null or between 0 and 0.10."

            shouldThrow<IllegalArgumentException> {
                VolumeFlowBacktestConfig(maxEntryRelativeVolume = 1.0)
            }.message shouldBe "Maximum entry relative volume must be null or greater than 1."

            shouldThrow<IllegalArgumentException> {
                VolumeFlowBacktestConfig(contextRangeRiskThresholdPct = 0.11)
            }.message shouldBe "Context range risk threshold percent must be null or between 0 and 0.10."

            shouldThrow<IllegalArgumentException> {
                VolumeFlowBacktestConfig(contextRangeRiskMultiplier = 0.0)
            }.message shouldBe "Context range risk multiplier must be between 0 and 1."

            shouldThrow<IllegalArgumentException> {
                VolumeFlowBacktestConfig(highContextRangeRelativeVolumeThresholdPct = 0.005)
            }.message shouldBe
                "High context range relative volume threshold and minimum must both be null or both be set."

            shouldThrow<IllegalArgumentException> {
                VolumeFlowBacktestConfig(
                    highContextRangeRelativeVolumeThresholdPct = 0.11,
                    highContextRangeRelativeVolumeMin = 4.0,
                )
            }.message shouldBe
                "High context range relative volume threshold percent must be null or between 0 and 0.10."

            shouldThrow<IllegalArgumentException> {
                VolumeFlowBacktestConfig(
                    highContextRangeRelativeVolumeThresholdPct = 0.005,
                    highContextRangeRelativeVolumeMin = 1.0,
                )
            }.message shouldBe "High context range relative volume minimum must be null or greater than 1."

            shouldThrow<IllegalArgumentException> {
                VolumeFlowBacktestConfig(highContextRangeRelativeVolumeMacroBypassMovePct = 0.08)
            }.message shouldBe
                "High context range relative volume macro bypass move and efficiency must both be null or both be set."

            shouldThrow<IllegalArgumentException> {
                VolumeFlowBacktestConfig(minContextQuoteVolume = 0.0)
            }.message shouldBe "Minimum context quote volume must be null or positive."

            shouldThrow<IllegalArgumentException> {
                VolumeFlowBacktestConfig(macroTrendLookbackM15Candles = 15)
            }.message shouldBe "Macro trend lookback must be between 16 and 2000 M15 candles."

            shouldThrow<IllegalArgumentException> {
                VolumeFlowBacktestConfig(minMacroTrendMovePct = 0.51)
            }.message shouldBe "Minimum macro trend move percent must be between 0 and 0.50."

            shouldThrow<IllegalArgumentException> {
                VolumeFlowBacktestConfig(minMacroTrendEfficiency = 1.01)
            }.message shouldBe "Minimum macro trend efficiency must be null or between 0 and 1."

            shouldThrow<IllegalArgumentException> {
                VolumeFlowBacktestConfig(macroTrendEfficiencyRelativeVolumeMin = 1.0)
            }.message shouldBe "Macro trend efficiency relative volume minimum must be null or greater than 1."

            shouldThrow<IllegalArgumentException> {
                VolumeFlowBacktestConfig(
                    macroTrendEfficiencyRelativeVolumeMin = 6.0,
                    macroTrendEfficiencyRelativeVolumeMax = 6.0,
                )
            }.message shouldBe "Macro trend efficiency relative volume minimum must be less than maximum."

            shouldThrow<IllegalArgumentException> {
                VolumeFlowBacktestConfig(macroTrendMismatchRiskMultiplier = 0.0)
            }.message shouldBe "Macro trend mismatch risk multiplier must be between 0 and 1."

            shouldThrow<IllegalArgumentException> {
                VolumeFlowBacktestConfig(minEntryBodyRatio = 1.1)
            }.message shouldBe "Minimum entry body ratio must be null or between 0 and 1."

            shouldThrow<IllegalArgumentException> {
                VolumeFlowBacktestConfig(followThroughCheckM1Candles = 3)
            }.message shouldBe "Follow-through check candles and minimum R must both be null or both be set."

            shouldThrow<IllegalArgumentException> {
                VolumeFlowBacktestConfig(followThroughMinContextRangePct = 0.11)
            }.message shouldBe "Follow-through minimum context range percent must be null or between 0 and 0.10."

            shouldThrow<IllegalArgumentException> {
                VolumeFlowBacktestConfig(adverseExitCheckM1Candles = 3)
            }.message shouldBe
                "Adverse exit check candles, maximum adverse R, and minimum favorable R must all be null or all be set."

            shouldThrow<IllegalArgumentException> {
                VolumeFlowBacktestConfig(profitProtectActivationR = 0.5)
            }.message shouldBe "Profit protect activation R and floor R must both be null or both be set."

            VolumeFlowBacktestConfig(flowLookbackM1Candles = 6).flowLookbackM1Candles shouldBe 6
            shouldThrow<IllegalArgumentException> {
                VolumeFlowBacktestConfig(
                    flowLookbackM1Candles = 6,
                    minDirectionalTakerImbalance = 0.5,
                )
            }.message shouldBe
                "Flow lookback M1 candles must be a multiple of 5 when taker flow filtering is enabled."

            shouldThrow<IllegalArgumentException> {
                VolumeFlowCompositeBacktestConfig(
                    robustnessWindowDays = 29,
                    legs = listOf(VolumeFlowCompositeBacktestLeg("primary", testVolumeFlowConfig())),
                )
            }.message shouldBe "Robustness window days must be between 30 and 3650."

            VolumeFlowCompositeBacktestConfig(
                legDrawdownRiskRules =
                    listOf(
                        VolumeFlowCompositeLegDrawdownRiskRule(
                            legId = "primary",
                            drawdownThresholdPct = 5.0,
                            riskMultiplier = 0.5,
                        ),
                    ),
                legs = listOf(VolumeFlowCompositeBacktestLeg("primary", testVolumeFlowConfig())),
            ).legDrawdownRiskRules.single().riskMultiplier shouldBe 0.5

            shouldThrow<IllegalArgumentException> {
                VolumeFlowCompositeBacktestConfig(
                    legDrawdownRiskRules =
                        listOf(
                            VolumeFlowCompositeLegDrawdownRiskRule(
                                legId = "missing",
                                drawdownThresholdPct = 5.0,
                                riskMultiplier = 0.5,
                            ),
                        ),
                    legs = listOf(VolumeFlowCompositeBacktestLeg("primary", testVolumeFlowConfig())),
                )
            }.message shouldBe "Composite leg drawdown risk rules must target existing leg ids."
        }

        "runs a long trade from 15m context 5m volume breakout and 1m retest" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig(),
                )

            result.tradeCount shouldBe 1
            result.wins shouldBe 1
            result.observedDays shouldBe 1
            result.activeDays shouldBe 1
            result.averageTradesPerDay shouldBe 1.0
            result.averageTradesPerActiveDay shouldBe 1.0
            result.tradeFrequencyTargetDays shouldBe 1
            result.belowMinTradeDays shouldBe 0
            (result.markToMarketMaxDrawdownPct >= result.maxDrawdownPct) shouldBe true
            (result.averageMaxFavorableExcursionR > 0.0) shouldBe true
            (result.averageMaxAdverseExcursionR > 0.0) shouldBe true
            result.trades.single().exitReason shouldBe VolumeFlowExitReason.TARGET
            (result.trades.single().maxFavorableExcursionR > result.trades.single().returnR) shouldBe true
            (result.trades.single().maxUnrealizedProfitPct > 0.0) shouldBe true
            (result.trades.single().maxUnrealizedDrawdownPct > 0.0) shouldBe true
            result.trades.single().setupMode shouldBe VolumeFlowSetupMode.BREAKOUT_CONTINUATION
            result.trades.single().setupAt shouldBe Instant.parse("2026-06-30T01:05:00Z")
            result.trades.single().entryAt shouldBe Instant.parse("2026-06-30T01:06:00Z")
            result.trades.single().marketRegime shouldBe VolumeFlowMarketRegime.TREND_UP
            result.trades.single().keyLevelType shouldBe VolumeFlowKeyLevelType.RANGE_HIGH
            result.trades.single().volumePattern shouldBe VolumeFlowVolumePattern.BREAKOUT_ACCEPTANCE
            (result.trades.single().contextTrendMovePct!! > 0.0) shouldBe true
            (result.trades.single().contextTrendEfficiency!! in 0.0..1.0) shouldBe true
            (result.trades.single().contextRangePct!! > 0.0) shouldBe true
            (result.trades.single().contextQuoteVolume!! > 0.0) shouldBe true
            (result.trades.single().entryDelayM1Candles >= 0) shouldBe true
            (result.trades.single().entryBodyRatio in 0.0..1.0) shouldBe true
            (result.trades.single().entryCloseLocation in 0.0..1.0) shouldBe true
            (result.trades.single().entryRelativeVolume!! > 0.0) shouldBe true
            (result.trades.single().entryRiskPct > 0.0) shouldBe true
            result.performanceBySetupMode.single().tag shouldBe "BREAKOUT_CONTINUATION"
            result.performanceByMarketRegime.single().tag shouldBe "TREND_UP"
            result.performanceByVolumePattern.single().tag shouldBe "BREAKOUT_ACCEPTANCE"
        }

        "does not load or change behavior when flow filters are disabled" {
            val flowStore = InMemoryVolumeFlowDataStore(takerFlowBars = oppositeTakerFlowBars())
            val baseline =
                VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()))
                    .run(
                        symbol = Symbol("BTCUSDT"),
                        m1Limit = 80,
                        m5Limit = 30,
                        m15Limit = 30,
                        config = testVolumeFlowConfig(),
                    )
            val result =
                VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()), flowStore)
                    .run(
                        symbol = Symbol("BTCUSDT"),
                        m1Limit = 80,
                        m5Limit = 30,
                        m15Limit = 30,
                        config = testVolumeFlowConfig(),
                    )

            result.tradeCount shouldBe baseline.tradeCount
            result.netPnl shouldBe baseline.netPnl
            result.flowFilterEnabled shouldBe false
            flowStore.takerFlowBetweenCalls shouldBe 0
        }

        "requires taker flow aligned with candidate side by default" {
            val service =
                VolumeFlowBacktestService(
                    InMemoryVolumeFlowCandleStore(volumeFlowCandles()),
                    InMemoryVolumeFlowDataStore(takerFlowBars = alignedTakerFlowBars()),
                )

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig().copy(minDirectionalTakerImbalance = 0.5),
                )

            result.tradeCount shouldBe 1
            result.flowFilterEnabled shouldBe true
            val flowMetrics = result.trades.single().flowMetrics
            flowMetrics?.directionalTakerImbalance shouldBe 0.6
        }

        "can require taker flow opposed to candidate side for absorption" {
            val flowStore = InMemoryVolumeFlowDataStore(takerFlowBars = oppositeTakerFlowBars())
            val defaultResult =
                VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()), flowStore)
                    .run(
                        symbol = Symbol("BTCUSDT"),
                        m1Limit = 80,
                        m5Limit = 30,
                        m15Limit = 30,
                        config = testVolumeFlowConfig().copy(minDirectionalTakerImbalance = 0.5),
                    )
            val absorptionResult =
                VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()), flowStore)
                    .run(
                        symbol = Symbol("BTCUSDT"),
                        m1Limit = 80,
                        m5Limit = 30,
                        m15Limit = 30,
                        config =
                            testVolumeFlowConfig().copy(
                                minDirectionalTakerImbalance = 0.5,
                                takerFlowDirectionMode = TakerFlowDirectionMode.OPPOSE_SIDE,
                            ),
                    )

            defaultResult.tradeCount shouldBe 0
            defaultResult.noTradeReasonCounts["DIRECTIONAL_TAKER_IMBALANCE_LOW"] shouldBe 1
            absorptionResult.tradeCount shouldBe 1
            val absorptionFlowMetrics = absorptionResult.trades.single().flowMetrics
            absorptionFlowMetrics?.directionalTakerImbalance shouldBe 0.6
        }

        "rejects missing and stale flow inputs with explicit reasons" {
            val settledFunding =
                FundingRateSnapshot(
                    symbol = Symbol("BTCUSDT"),
                    timestamp = Instant.parse("2026-06-30T00:00:00Z"),
                    fundingRate = BigDecimal("0.0001"),
                )
            val missingResult =
                VolumeFlowBacktestService(
                    InMemoryVolumeFlowCandleStore(volumeFlowCandles()),
                    InMemoryVolumeFlowDataStore(),
                ).run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig().copy(minDirectionalTakerImbalance = 0.5),
                )
            val staleResult =
                VolumeFlowBacktestService(
                    InMemoryVolumeFlowCandleStore(volumeFlowCandles()),
                    InMemoryVolumeFlowDataStore(
                        fundingRateSnapshots = listOf(settledFunding),
                    ),
                ).run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        testVolumeFlowConfig().copy(
                            maxAbsFundingRate = 0.01,
                            maxFundingDataStalenessMinutes = 10,
                        ),
                )
            val intervalValidResult =
                VolumeFlowBacktestService(
                    InMemoryVolumeFlowCandleStore(volumeFlowCandles()),
                    InMemoryVolumeFlowDataStore(fundingRateSnapshots = listOf(settledFunding)),
                ).run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig().copy(maxAbsFundingRate = 0.01),
                )

            missingResult.noTradeReasonCounts["MISSING_TAKER_FLOW"] shouldBe 1
            staleResult.noTradeReasonCounts["STALE_FUNDING_RATE"] shouldBe 1
            intervalValidResult.tradeCount shouldBe 1
            val fundingMetrics = intervalValidResult.trades.single().flowMetrics
            fundingMetrics?.fundingRate shouldBe 0.0001
        }

        "uses open interest snapshots as of the setup decision" {
            val result =
                VolumeFlowBacktestService(
                    InMemoryVolumeFlowCandleStore(volumeFlowCandles()),
                    InMemoryVolumeFlowDataStore(
                        openInterestSnapshots =
                            listOf(
                                openInterestSnapshot("2026-06-30T00:55:00Z", "100"),
                                openInterestSnapshot("2026-06-30T01:00:00Z", "103"),
                                openInterestSnapshot("2026-06-30T01:06:00Z", "200"),
                            ),
                    ),
                ).run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        testVolumeFlowConfig().copy(
                            minOpenInterestChangePct = 5.0,
                            openInterestLookbackSnapshots = 2,
                        ),
                )

            result.tradeCount shouldBe 0
            result.noTradeReasonCounts["OPEN_INTEREST_EXPANSION_LOW"] shouldBe 1
        }

        "excludes unclosed premium bars at the setup decision" {
            val result =
                VolumeFlowBacktestService(
                    InMemoryVolumeFlowCandleStore(volumeFlowCandles()),
                    InMemoryVolumeFlowDataStore(
                        premiumIndexBars =
                            listOf(
                                premiumIndexBar("2026-06-30T00:45:00Z", "0.001"),
                                premiumIndexBar("2026-06-30T01:00:00Z", "0.900"),
                            ),
                    ),
                ).run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig().copy(maxAbsPremiumIndex = 0.01),
                )

            result.tradeCount shouldBe 1
            val flowMetrics = result.trades.single().flowMetrics
            flowMetrics?.premiumIndex shouldBe 0.001
        }

        "rejects missing or duplicate minutes in a taker flow bucket" {
            val bucketOpenedAt = Instant.parse("2026-06-30T01:00:00Z")
            val missingMinuteResult =
                VolumeFlowBacktestService(
                    InMemoryVolumeFlowCandleStore(volumeFlowCandles()),
                    InMemoryVolumeFlowDataStore(
                        takerFlowBars =
                            (0 until 4).map { index ->
                                takerFlowBar(bucketOpenedAt.plusSeconds(index * 60L), "80", "20")
                            },
                    ),
                ).run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig().copy(minDirectionalTakerImbalance = 0.5),
                )
            val duplicateMinuteResult =
                VolumeFlowBacktestService(
                    InMemoryVolumeFlowCandleStore(volumeFlowCandles()),
                    InMemoryVolumeFlowDataStore(
                        takerFlowBars =
                            alignedTakerFlowBars() +
                                takerFlowBar(bucketOpenedAt.plusSeconds(180L), "80", "20"),
                    ),
                ).run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig().copy(minDirectionalTakerImbalance = 0.5),
                )

            missingMinuteResult.noTradeReasonCounts["MISSING_TAKER_FLOW"] shouldBe 1
            duplicateMinuteResult.noTradeReasonCounts["MISSING_TAKER_FLOW"] shouldBe 1
        }

        "pages flow context beyond the store page limit" {
            val startAt = Instant.parse("2026-06-22T00:00:00Z")
            val setupCandles =
                (0..2_001).map { index ->
                    volumeFlowCandle(
                        index = 0,
                        timeframe = Timeframe.M5,
                        seconds = 300L,
                        open = "100",
                        high = "101",
                        low = "99",
                        close = "100",
                        volume = "10",
                    ).copy(openedAt = startAt.plusSeconds(index * 300L))
                }
            val flowStore =
                InMemoryVolumeFlowDataStore(
                    takerFlowBars =
                        (0 until 10_005).map { index ->
                            takerFlowBar(startAt.plusSeconds(index * 60L), buyNotional = "80", sellNotional = "20")
                        },
                )
            val context =
                VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(emptyList()), flowStore)
                    .loadFlowContextIfEnabled(
                        symbol = Symbol("BTCUSDT"),
                        setupCandles = setupCandles,
                        config = testVolumeFlowConfig().copy(minDirectionalTakerImbalance = 0.5),
                    )

            val buckets = context!!.takerFlowBuckets.values
            buckets.size shouldBe 2_001
            buckets.sumOf { it.takerBuyNotional } shouldBe 800_400.0
            buckets.sumOf { it.takerSellNotional } shouldBe 200_100.0
            buckets.sumOf { it.buyTradeCount } shouldBe 10_005
            buckets.sumOf { it.sellTradeCount } shouldBe 10_005
            buckets.all { it.hasCompleteMinuteCoverage } shouldBe true
            flowStore.takerFlowBetweenCalls shouldBe 2
        }

        "uses bounded completed M5 taker lookups without future flow" {
            val bucketOpenedAt = Instant.parse("2026-06-30T01:00:00Z")
            val previousBucketOpenedAt = bucketOpenedAt.minusSeconds(300L)
            val futureBucketOpenedAt = bucketOpenedAt.plusSeconds(300L)
            val bucketLookup =
                ExactLookupOnlyMap(
                    mapOf(
                        previousBucketOpenedAt to completeTakerFlowBucket(previousBucketOpenedAt),
                        bucketOpenedAt to completeTakerFlowBucket(bucketOpenedAt),
                        futureBucketOpenedAt to
                            completeTakerFlowBucket(
                                openedAt = futureBucketOpenedAt,
                                takerBuyNotional = 0.0,
                                takerSellNotional = 5_000.0,
                            ),
                    ),
                )
            val context =
                VolumeFlowBacktestFlowContext(
                    takerFlowBuckets = bucketLookup,
                    openInterestSnapshots = emptyList(),
                    premiumIndexBars = emptyList(),
                    fundingRateSnapshots = emptyList(),
                )

            val decision =
                context.evaluate(
                    setupCandle = volumeFlowM5Candles().first { it.openedAt == bucketOpenedAt },
                    setupClosedAt = Instant.parse("2026-06-30T01:05:00Z"),
                    side = Side.BUY,
                    config =
                        testVolumeFlowConfig().copy(
                            flowLookbackM1Candles = 10,
                            minDirectionalTakerImbalance = 0.5,
                        ),
                )

            decision.reason shouldBe null
            decision.metrics?.directionalTakerImbalance shouldBe 0.6
            bucketLookup.getCalls shouldBe 2
        }

        "loads one shared flow context for composite legs" {
            val flowStore = InMemoryVolumeFlowDataStore(takerFlowBars = alignedTakerFlowBars())
            val service = VolumeFlowCompositeBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()), flowStore)
            val legConfig = testVolumeFlowConfig().copy(minDirectionalTakerImbalance = 0.5)

            service.run(
                symbol = Symbol("BTCUSDT"),
                m1Limit = 80,
                m5Limit = 30,
                m15Limit = 30,
                config =
                    VolumeFlowCompositeBacktestConfig(
                        legs =
                            listOf(
                                VolumeFlowCompositeBacktestLeg("primary", legConfig),
                                VolumeFlowCompositeBacktestLeg("secondary", legConfig.copy(riskFraction = 0.005)),
                            ),
                    ),
            )

            flowStore.takerFlowBetweenCalls shouldBe 1
        }

        "uses only higher timeframe candles closed before the setup decision" {
            val baselineService = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()))
            val unclosedContextChanged =
                volumeFlowCandles().map { candle ->
                    if (
                        candle.timeframe == Timeframe.M15 &&
                        candle.openedAt == Instant.parse("2026-06-30T01:00:00Z")
                    ) {
                        candle.copy(
                            high = BigDecimal("300"),
                            low = BigDecimal("1"),
                            close = BigDecimal("1"),
                            volume = BigDecimal("999999"),
                        )
                    } else {
                        candle
                    }
                }
            val changedService = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(unclosedContextChanged))

            val baseline =
                baselineService.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig(),
                )
            val changed =
                changedService.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig(),
                )

            changed.tradeCount shouldBe baseline.tradeCount
            changed.trades.single().marketRegime shouldBe baseline.trades.single().marketRegime
            changed.trades.single().contextTrendMovePct shouldBe baseline.trades.single().contextTrendMovePct
            changed.trades.single().contextRangePct shouldBe baseline.trades.single().contextRangePct
            changed.trades.single().contextQuoteVolume shouldBe baseline.trades.single().contextQuoteVolume
        }

        "does not count warmup setups as replay trades" {
            val candles = volumeFlowCandles()
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(candles))

            val result =
                service.runLoadedCandles(
                    symbol = Symbol("BTCUSDT"),
                    m1Candles = candles.filter { it.timeframe == Timeframe.M1 },
                    m5Candles = candles.filter { it.timeframe == Timeframe.M5 },
                    m15Candles = candles.filter { it.timeframe == Timeframe.M15 },
                    config = testVolumeFlowConfig(),
                    replayStartAt = Instant.parse("2026-06-30T01:10:00Z"),
                    replayEndAt = Instant.parse("2026-06-30T01:19:00Z"),
                )

            result.tradeCount shouldBe 0
        }

        "fills a confirmed signal at the next contiguous m1 open" {
            val nextOpen = 114.0
            val shiftedEntryCandles =
                volumeFlowCandles().map { candle ->
                    if (
                        candle.timeframe == Timeframe.M1 &&
                        candle.openedAt == Instant.parse("2026-06-30T01:06:00Z")
                    ) {
                        candle.copy(open = BigDecimal(nextOpen))
                    } else {
                        candle
                    }
                }
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(shiftedEntryCandles))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig(),
                )

            result.tradeCount shouldBe 1
            result.trades.single().entryAt shouldBe Instant.parse("2026-06-30T01:06:00Z")
            result.trades.single().entryPrice shouldBe nextOpen * (1.0 + testVolumeFlowConfig().slippageRate)
        }

        "skips a confirmed signal when its next m1 candle is missing" {
            val candlesWithEntryGap =
                volumeFlowCandles().filterNot { candle ->
                    candle.timeframe == Timeframe.M1 &&
                        candle.openedAt == Instant.parse("2026-06-30T01:06:00Z")
                }
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(candlesWithEntryGap))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 79,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig(),
                )

            result.tradeCount shouldBe 0
        }

        "evaluates stop before target inside the entry m1 candle" {
            val ambiguousEntryCandle =
                volumeFlowCandles().map { candle ->
                    if (
                        candle.timeframe == Timeframe.M1 &&
                        candle.openedAt == Instant.parse("2026-06-30T01:06:00Z")
                    ) {
                        candle.copy(
                            high = BigDecimal("130"),
                            low = BigDecimal("100"),
                        )
                    } else {
                        candle
                    }
                }
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(ambiguousEntryCandle))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig(),
                )

            result.tradeCount shouldBe 1
            result.trades.single().exitReason shouldBe VolumeFlowExitReason.STOP
            result.trades.single().exitAt shouldBe Instant.parse("2026-06-30T01:07:00Z")
        }

        "caps risk sizing at the configured account leverage" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        testVolumeFlowConfig().copy(
                            initialEquity = 100.0,
                            riskFraction = 0.20,
                            leverage = 2.0,
                        ),
                )

            result.tradeCount shouldBe 1
            (result.trades.single().quantity * result.trades.single().entryPrice <= 200.0) shouldBe true
        }

        "rejects a stop that lies beyond the estimated liquidation boundary" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig().copy(leverage = 15.0),
                )

            result.tradeCount shouldBe 0
            result.noTradeReasonCounts["STOP_REACHES_ESTIMATED_LIQUIDATION"] shouldBe 1
        }

        "reports liquidation when a candle gaps beyond both stop and liquidation" {
            val gapCandles =
                volumeFlowCandles().map { candle ->
                    when {
                        candle.timeframe == Timeframe.M1 &&
                            candle.openedAt == Instant.parse("2026-06-30T01:06:00Z") ->
                            candle.copy(
                                high = BigDecimal("113"),
                                low = BigDecimal("112"),
                                close = BigDecimal("112.5"),
                            )
                        candle.timeframe == Timeframe.M1 &&
                            candle.openedAt == Instant.parse("2026-06-30T01:07:00Z") ->
                            candle.copy(
                                open = BigDecimal("40"),
                                low = BigDecimal("40"),
                            )
                        else -> candle
                    }
                }
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(gapCandles))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        testVolumeFlowConfig().copy(
                            leverage = 2.0,
                            targetR = 3.0,
                        ),
                )

            result.tradeCount shouldBe 1
            result.liquidationCount shouldBe 1
            result.trades.single().exitReason shouldBe VolumeFlowExitReason.LIQUIDATION
        }

        "runs a long trade from volume follow-through continuation and 1m close confirmation" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        testVolumeFlowConfig().copy(
                            setupMode = VolumeFlowSetupMode.VOLUME_FOLLOW_THROUGH_CONTINUATION,
                            entryMode = VolumeFlowEntryMode.CLOSE_CONFIRMATION,
                        ),
                )

            result.tradeCount shouldBe 1
            result.trades.single().setupMode shouldBe VolumeFlowSetupMode.VOLUME_FOLLOW_THROUGH_CONTINUATION
            result.trades.single().volumePattern shouldBe VolumeFlowVolumePattern.BREAKOUT_ACCEPTANCE
            result.trades.single().exitReason shouldBe VolumeFlowExitReason.TARGET
            result.performanceBySetupMode.single().tag shouldBe "VOLUME_FOLLOW_THROUGH_CONTINUATION"
        }

        "can reduce risk when higher timeframe context range is wide" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()))
            val base =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig(),
                )

            val reduced =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        testVolumeFlowConfig().copy(
                            contextRangeRiskThresholdPct = 0.0,
                            contextRangeRiskMultiplier = 0.5,
                        ),
                )

            reduced.tradeCount shouldBe 1
            reduced.trades.single().riskMultiplier shouldBe 0.5
            reduced.trades.single().quantity shouldBe base.trades.single().quantity * 0.5
            reduced.trades.single().pnl shouldBe base.trades.single().pnl * 0.5
        }

        "can reduce risk when setup relative volume reaches an exhaustion threshold" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()))
            val base =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig(),
                )

            val reduced =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        testVolumeFlowConfig().copy(
                            relativeVolumeRiskThreshold = 2.0,
                            relativeVolumeRiskMultiplier = 0.5,
                        ),
                )
            val gated =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        testVolumeFlowConfig().copy(
                            relativeVolumeRiskThreshold = 2.0,
                            relativeVolumeRiskMultiplier = 0.5,
                            relativeVolumeRiskMaxTrendMovePct = 0.0,
                        ),
                )

            reduced.tradeCount shouldBe 1
            reduced.trades.single().riskMultiplier shouldBe 0.5
            reduced.trades.single().quantity shouldBe base.trades.single().quantity * 0.5
            reduced.trades.single().pnl shouldBe base.trades.single().pnl * 0.5
            gated.tradeCount shouldBe 1
            gated.trades.single().riskMultiplier shouldBe 1.0
            gated.trades.single().quantity shouldBe base.trades.single().quantity
            gated.trades.single().pnl shouldBe base.trades.single().pnl
        }

        "can require stronger setup volume only when higher timeframe context range is wide" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()))

            val rejected =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        testVolumeFlowConfig().copy(
                            highContextRangeRelativeVolumeThresholdPct = 0.0,
                            highContextRangeRelativeVolumeMin = 100.0,
                        ),
                )

            rejected.tradeCount shouldBe 0
            rejected.noTradeReasonCounts["HIGH_CONTEXT_RANGE_RELATIVE_VOLUME_LOW"] shouldBe 1
        }

        "can bypass high context range volume requirement when macro trend is strong" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(lateMacroUpVolumeFlowCandles()))

            val rejected =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        testVolumeFlowConfig().copy(
                            highContextRangeRelativeVolumeThresholdPct = 0.0,
                            highContextRangeRelativeVolumeMin = 100.0,
                            macroTrendLookbackM15Candles = 16,
                        ),
                )
            val accepted =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        testVolumeFlowConfig().copy(
                            highContextRangeRelativeVolumeThresholdPct = 0.0,
                            highContextRangeRelativeVolumeMin = 100.0,
                            highContextRangeRelativeVolumeMacroBypassMovePct = 0.03,
                            highContextRangeRelativeVolumeMacroBypassEfficiency = 0.5,
                            macroTrendLookbackM15Candles = 16,
                        ),
                )

            rejected.tradeCount shouldBe 0
            rejected.noTradeReasonCounts["HIGH_CONTEXT_RANGE_RELATIVE_VOLUME_LOW"] shouldBe 1
            accepted.tradeCount shouldBe 1
        }

        "macro trend alignment rejects setups against the longer M15 direction" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(lateMacroDownVolumeFlowCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        testVolumeFlowConfig()
                            .copy(
                                requireContextVwap = false,
                                requireContextTrend = false,
                                requireMacroTrendAlignment = true,
                                macroTrendLookbackM15Candles = 16,
                            ),
                )

            result.setupCount shouldBe 1
            result.tradeCount shouldBe 0
            result.noTradeReasonCounts["MACRO_TREND_REJECTED"] shouldBe 1
        }

        "macro trend mismatch can reduce risk without rejecting the setup" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(lateMacroDownVolumeFlowCandles()))
            val baseConfig =
                testVolumeFlowConfig()
                    .copy(
                        requireContextVwap = false,
                        requireContextTrend = false,
                        macroTrendLookbackM15Candles = 16,
                    )
            val reducedConfig = baseConfig.copy(macroTrendMismatchRiskMultiplier = 0.5)

            val base =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = baseConfig,
                )
            val reduced =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = reducedConfig,
                )

            base.tradeCount shouldBe 1
            reduced.tradeCount shouldBe 1
            reduced.trades.single().riskMultiplier shouldBe 0.5
            (reduced.trades.single().macroTrendMovePct!! < 0.0) shouldBe true
            (reduced.trades.single().macroTrendEfficiency!! in 0.0..1.0) shouldBe true
            (reduced.trades.single().quantity < base.trades.single().quantity) shouldBe true
            (reduced.trades.single().pnl < base.trades.single().pnl) shouldBe true
        }

        "does not enter when 1m data starts after the setup entry window" {
            val delayedM1Candles = volumeFlowM1Candles().map { candle -> candle.copy(openedAt = candle.openedAt.plusSeconds(86_400L)) }
            val service =
                VolumeFlowBacktestService(
                    InMemoryVolumeFlowCandleStore(
                        delayedM1Candles + volumeFlowM5Candles() + volumeFlowM15Candles(),
                    ),
                )

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig(),
                )

            result.setupCount shouldBe 1
            result.tradeCount shouldBe 0
            result.activeDays shouldBe 0
            result.belowMinTradeDays shouldBe 1
            result.noTradeReasonCounts["NO_M1_RETEST_TRIGGER"] shouldBe 1
        }

        "rejects trades when estimated fees are too large relative to risk" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig().copy(maxEstimatedFeeR = 0.01),
                )

            result.setupCount shouldBe 1
            result.tradeCount shouldBe 0
            result.noTradeReasonCounts["ESTIMATED_FEE_R_TOO_HIGH"] shouldBe 1
        }

        "can reject entries when the stop distance is outside configured bounds" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig().copy(maxEntryRiskPct = 0.01),
                )

            result.setupCount shouldBe 1
            result.tradeCount shouldBe 0
            result.noTradeReasonCounts["ENTRY_RISK_TOO_LARGE"] shouldBe 1
        }

        "can reject setups when the directional close is too weak" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig().copy(minDirectionalCloseStrength = 0.99),
                )

            result.tradeCount shouldBe 0
            result.noTradeReasonCounts["WEAK_CLOSE_LOCATION"] shouldBe 1
        }

        "can reject setups when relative volume is above a configured exhaustion cap" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()))

            val rejected =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig().copy(maxRelativeVolumeThreshold = 5.0),
                )
            val accepted =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig().copy(maxRelativeVolumeThreshold = 10.0),
                )

            rejected.tradeCount shouldBe 0
            rejected.noTradeReasonCounts["RELATIVE_VOLUME_TOO_HIGH"] shouldBe 1
            accepted.tradeCount shouldBe 1
        }

        "can reject entries when entry relative volume is above a configured chase cap" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()))

            val rejected =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig().copy(maxEntryRelativeVolume = 2.0),
                )
            val accepted =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig().copy(maxEntryRelativeVolume = 3.0),
                )

            rejected.tradeCount shouldBe 0
            rejected.noTradeReasonCounts["ENTRY_RELATIVE_VOLUME_TOO_HIGH"] shouldBe 1
            accepted.tradeCount shouldBe 1
        }

        "can reject setups when the higher timeframe context range is too wide" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()))

            val rejected =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig().copy(maxContextRangePct = 0.03),
                )
            val accepted =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig().copy(maxContextRangePct = 0.05),
                )

            rejected.tradeCount shouldBe 0
            rejected.noTradeReasonCounts["CONTEXT_RANGE_TOO_WIDE"] shouldBe 1
            accepted.tradeCount shouldBe 1
        }

        "can reject setups when higher timeframe quote volume is too low" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()))

            val rejected =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig().copy(minContextQuoteVolume = 3_000.0),
                )
            val accepted =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig().copy(minContextQuoteVolume = 1_000.0),
                )

            rejected.tradeCount shouldBe 0
            rejected.noTradeReasonCounts["CONTEXT_QUOTE_VOLUME_TOO_LOW"] shouldBe 1
            accepted.tradeCount shouldBe 1
        }

        "can require m5 vwap alignment before entering volume flow trades" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig().copy(requireM5Vwap = true, m5VwapLookback = 3),
                )

            result.tradeCount shouldBe 1
            result.noTradeReasonCounts["M5_CONTEXT_REJECTED"] shouldBe null
        }

        "can enter after setup candle close without waiting for a later 1m confirmation" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = testVolumeFlowConfig().copy(entryMode = VolumeFlowEntryMode.SETUP_CLOSE_CONFIRMATION),
                )

            result.tradeCount shouldBe 1
            result.trades.single().entryAt shouldBe Instant.parse("2026-06-30T01:05:00Z")
            result.trades.single().entryPrice shouldBe 112.0 * (1.0 + testVolumeFlowConfig().slippageRate)
            result.trades.single().exitReason shouldBe VolumeFlowExitReason.TARGET
        }

        "runner exit can trail a move beyond the fixed target reference" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(runnerVolumeFlowCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        testVolumeFlowConfig().copy(
                            exitMode = VolumeFlowExitMode.RUNNER,
                            runnerTrailActivationR = 0.5,
                            runnerTrailDistanceR = 0.05,
                        ),
                )

            result.tradeCount shouldBe 1
            result.trades.single().exitReason shouldBe VolumeFlowExitReason.TRAILING_STOP
            (result.trades.single().exitPrice > result.trades.single().targetPrice) shouldBe true
            (result.trades.single().maxFavorableExcursionR > result.trades.single().returnR) shouldBe true
        }

        "trend break exit holds a profitable move until recent structure breaks" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(trendBreakVolumeFlowCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        testVolumeFlowConfig().copy(
                            exitMode = VolumeFlowExitMode.TREND_BREAK,
                            runnerTrailActivationR = 0.5,
                            trendBreakLookbackM1Candles = 2,
                            maxHoldM1Candles = 8,
                        ),
                )

            result.tradeCount shouldBe 1
            result.trades.single().exitReason shouldBe VolumeFlowExitReason.TREND_BREAK
            (result.trades.single().exitPrice > result.trades.single().targetPrice) shouldBe true
            (result.trades.single().mfeCapturePct != null) shouldBe true
        }

        "follow-through check exits stagnant trades before time expiry" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(followThroughFailVolumeFlowCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        testVolumeFlowConfig().copy(
                            followThroughCheckM1Candles = 1,
                            minFollowThroughR = 0.2,
                        ),
                )

            result.tradeCount shouldBe 1
            result.trades.single().exitReason shouldBe VolumeFlowExitReason.FOLLOW_THROUGH_FAIL
            (result.trades.single().maxFavorableExcursionR < 0.2) shouldBe true
            result.performanceByExitReason.single().tag shouldBe "FOLLOW_THROUGH_FAIL"
        }

        "follow-through check can be armed only for wide higher timeframe context" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(followThroughFailVolumeFlowCandles()))
            val baseConfig =
                testVolumeFlowConfig().copy(
                    followThroughCheckM1Candles = 1,
                    minFollowThroughR = 0.2,
                )

            val disarmed =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = baseConfig.copy(followThroughMinContextRangePct = 0.10),
                )
            val armed =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config = baseConfig.copy(followThroughMinContextRangePct = 0.0),
                )

            disarmed.tradeCount shouldBe 1
            disarmed.trades.single().exitReason shouldBe VolumeFlowExitReason.STOP
            armed.tradeCount shouldBe 1
            armed.trades.single().exitReason shouldBe VolumeFlowExitReason.FOLLOW_THROUGH_FAIL
        }

        "adverse invalidation exits weak trades that move against entry before time expiry" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(followThroughFailVolumeFlowCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        testVolumeFlowConfig().copy(
                            adverseExitCheckM1Candles = 1,
                            maxAdverseRBeforeExit = 0.05,
                            minFavorableRBeforeAdverseExit = 0.2,
                        ),
                )

            result.tradeCount shouldBe 1
            result.trades.single().exitReason shouldBe VolumeFlowExitReason.ADVERSE_INVALIDATION
            (result.trades.single().maxAdverseExcursionR >= 0.05) shouldBe true
            result.performanceByExitReason.single().tag shouldBe "ADVERSE_INVALIDATION"
        }

        "profit protection exits after favorable movement is given back" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(profitProtectVolumeFlowCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        testVolumeFlowConfig().copy(
                            targetR = 3.0,
                            profitProtectActivationR = 0.5,
                            profitProtectFloorR = 0.1,
                            maxHoldM1Candles = 8,
                        ),
                )

            result.tradeCount shouldBe 1
            result.trades.single().exitReason shouldBe VolumeFlowExitReason.PROFIT_PROTECT
            (result.trades.single().maxFavorableExcursionR >= 0.5) shouldBe true
            result.performanceByExitReason.single().tag shouldBe "PROFIT_PROTECT"
        }

        "breakeven trigger reports breakeven stop separately from full stop" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        testVolumeFlowConfig().copy(
                            targetR = 3.0,
                            breakevenTriggerR = 0.2,
                        ),
                )

            result.tradeCount shouldBe 1
            result.trades.single().exitReason shouldBe VolumeFlowExitReason.BREAKEVEN_STOP
            result.maxConsecutiveLosses shouldBe 0
            result.performanceByExitReason.single().tag shouldBe "BREAKEVEN_STOP"
        }

        "runs a short trade from failed breakout reversal and close confirmation" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(failedBreakReversalCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        testVolumeFlowConfig().copy(
                            setupMode = VolumeFlowSetupMode.FAILED_BREAK_REVERSAL,
                            entryMode = VolumeFlowEntryMode.CLOSE_CONFIRMATION,
                            requireContextVwap = false,
                            requireContextTrend = false,
                            minRejectionWickRatio = 0.25,
                            targetR = 0.25,
                        ),
                )

            result.tradeCount shouldBe 1
            result.trades.single().side shouldBe Side.SELL
            result.trades.single().keyLevelType shouldBe VolumeFlowKeyLevelType.RANGE_HIGH
            result.trades.single().volumePattern shouldBe VolumeFlowVolumePattern.FAILED_BREAK
        }

        "can reject trades that fight the 15m market regime" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(failedBreakReversalCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        testVolumeFlowConfig().copy(
                            setupMode = VolumeFlowSetupMode.FAILED_BREAK_REVERSAL,
                            entryMode = VolumeFlowEntryMode.CLOSE_CONFIRMATION,
                            requireContextVwap = false,
                            requireContextTrend = false,
                            requireRegimeSideAlignment = true,
                            minRejectionWickRatio = 0.25,
                            targetR = 0.25,
                        ),
                )

            result.tradeCount shouldBe 0
            result.noTradeReasonCounts["MARKET_REGIME_SIDE_MISMATCH"] shouldBe 1
        }

        "runs a short trade from volume rejection reversal without requiring a range break" {
            val service = VolumeFlowBacktestService(InMemoryVolumeFlowCandleStore(failedBreakReversalCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        testVolumeFlowConfig().copy(
                            setupMode = VolumeFlowSetupMode.VOLUME_REJECTION_REVERSAL,
                            entryMode = VolumeFlowEntryMode.SETUP_CLOSE_CONFIRMATION,
                            requireContextVwap = false,
                            requireContextTrend = false,
                            minRejectionWickRatio = 0.25,
                        ),
                )

            result.tradeCount shouldBe 1
            result.trades.single().side shouldBe Side.SELL
        }

        "composite backtest replays leg signals on one equity curve and skips overlapping positions" {
            val service = VolumeFlowCompositeBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()))
            val legConfig = testVolumeFlowConfig()

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        VolumeFlowCompositeBacktestConfig(
                            legs =
                                listOf(
                                    VolumeFlowCompositeBacktestLeg("primary", legConfig),
                                    VolumeFlowCompositeBacktestLeg("duplicate", legConfig),
                                ),
                        ),
                )

            result.signalCount shouldBe 2
            result.tradeCount shouldBe 1
            result.skippedSignalCount shouldBe 1
            result.noTradeReasonCounts["OVERLAPPING_POSITION"] shouldBe 1
            result.performanceByLeg.single().tag shouldBe "primary"
            result.performanceByLegExit.single().legId shouldBe "primary"
            result.performanceByLegExit.single().exitReason shouldBe VolumeFlowExitReason.TARGET
            result.performanceByLegExit
                .single()
                .summary.tradeCount shouldBe 1
            result.monthlyPerformance.single().tradeCount shouldBe 1
            result.walkForwardPerformance.size shouldBe 4
            result.walkForwardPerformance.sumOf { it.tradeCount } shouldBe 1
            result.robustnessSummary.windowDays shouldBe 365
            result.robustnessSummary.stepDays shouldBe 90
            result.robustnessSummary.windowCount shouldBe 0
            (result.markToMarketMaxDrawdownPct >= result.maxDrawdownPct) shouldBe true
            (result.averageMaxFavorableExcursionR > 0.0) shouldBe true
            (result.trades.single().maxUnrealizedProfitPct > 0.0) shouldBe true
            (result.trades.single().entryDelayM1Candles >= 0) shouldBe true
            (result.trades.single().entryBodyRatio in 0.0..1.0) shouldBe true
            (result.trades.single().entryCloseLocation in 0.0..1.0) shouldBe true
            (result.trades.single().entryRiskPct > 0.0) shouldBe true
            result.equityCurve.single().sequence shouldBe 1
            result.equityCurve.single().endingEquity shouldBe result.finalEquity
            result.equityCurve.single().legId shouldBe "primary"
            (result.equityCurve.single().markToMarketDrawdownPct > 0.0) shouldBe true
            (result.finalEquity > result.initialEquity) shouldBe true
        }

        "composite backtest applies portfolio leverage to replayed leg sizing" {
            val service = VolumeFlowCompositeBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()))

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        VolumeFlowCompositeBacktestConfig(
                            initialEquity = 100.0,
                            quantityStep = 0.001,
                            minQuantity = 0.001,
                            leverage = 2.0,
                            maxConcurrentPositions = 2,
                            legs =
                                listOf(
                                    VolumeFlowCompositeBacktestLeg(
                                        "primary",
                                        testVolumeFlowConfig().copy(riskFraction = 0.20),
                                    ),
                                    VolumeFlowCompositeBacktestLeg(
                                        "duplicate",
                                        testVolumeFlowConfig().copy(riskFraction = 0.20),
                                    ),
                                ),
                        ),
                )

            result.tradeCount shouldBe 1
            (result.trades.single().quantity * result.trades.single().entryPrice <= 200.0) shouldBe true
            result.noTradeReasonCounts["EXECUTION_SIZE_UNAVAILABLE"] shouldBe 1
        }

        "composite replay loads warmup candles without expanding reported coverage" {
            val service = VolumeFlowCompositeBacktestService(InMemoryVolumeFlowCandleStore(twoSignalThrottleCandles()))
            val replayStartAt = Instant.parse("2026-06-30T01:00:00Z")
            val replayEndAt = Instant.parse("2026-06-30T01:59:00Z")

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 120,
                    m5Limit = 40,
                    m15Limit = 30,
                    config =
                        VolumeFlowCompositeBacktestConfig(
                            dailyStopPct = 10.0,
                            maxConsecutiveLosses = 10,
                            legs = listOf(VolumeFlowCompositeBacktestLeg("primary", testVolumeFlowConfig())),
                        ),
                    replayStartAt = replayStartAt,
                    replayEndAt = replayEndAt,
                )

            result.replayCoverage.single { it.timeframe == Timeframe.M1 }.warmupCount shouldBe 4
            result.replayCoverage.single { it.timeframe == Timeframe.M1 }.actualCount shouldBe 60
            result.replayCoverage.single { it.timeframe == Timeframe.M5 }.warmupCount shouldBe 12
            result.replayCoverage.single { it.timeframe == Timeframe.M15 }.warmupCount shouldBe 3
            result.startAt shouldBe replayStartAt
            result.endAt shouldBe Instant.parse("2026-06-30T01:45:00Z")
            result.trades.all { trade -> !trade.setupAt.isBefore(replayStartAt) } shouldBe true
        }

        "composite backtest can admit overlapping positions when concurrency is configured" {
            val service = VolumeFlowCompositeBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()))
            val legConfig = testVolumeFlowConfig()

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        VolumeFlowCompositeBacktestConfig(
                            maxConcurrentPositions = 2,
                            legs =
                                listOf(
                                    VolumeFlowCompositeBacktestLeg("primary", legConfig),
                                    VolumeFlowCompositeBacktestLeg("duplicate", legConfig),
                                ),
                        ),
                )

            result.signalCount shouldBe 2
            result.tradeCount shouldBe 2
            result.skippedSignalCount shouldBe 0
            result.noTradeReasonCounts["OVERLAPPING_POSITION"] shouldBe null
            result.performanceByLeg.map { it.tag } shouldBe listOf("duplicate", "primary")
            result.monthlyPerformance.single().tradeCount shouldBe 2
            result.walkForwardPerformance.sumOf { it.tradeCount } shouldBe 2
            (result.finalEquity > result.initialEquity) shouldBe true
        }

        "composite backtest does not count breakeven stops as consecutive losses" {
            val service = VolumeFlowCompositeBacktestService(InMemoryVolumeFlowCandleStore(volumeFlowCandles()))
            val legConfig =
                testVolumeFlowConfig().copy(
                    targetR = 3.0,
                    breakevenTriggerR = 0.2,
                )

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        VolumeFlowCompositeBacktestConfig(
                            maxConsecutiveLosses = 1,
                            legs = listOf(VolumeFlowCompositeBacktestLeg("primary", legConfig)),
                        ),
                )

            result.tradeCount shouldBe 1
            result.trades.single().exitReason shouldBe VolumeFlowExitReason.BREAKEVEN_STOP
            result.maxConsecutiveLosses shouldBe 0
            result.performanceByLegExit.single().exitReason shouldBe VolumeFlowExitReason.BREAKEVEN_STOP
        }

        "composite backtest reduces risk after portfolio drawdown throttle is reached" {
            val service = VolumeFlowCompositeBacktestService(InMemoryVolumeFlowCandleStore(twoSignalThrottleCandles()))
            val legConfig = testVolumeFlowConfig().copy(riskFraction = 0.05)

            val base =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 120,
                    m5Limit = 40,
                    m15Limit = 40,
                    config =
                        VolumeFlowCompositeBacktestConfig(
                            dailyStopPct = 10.0,
                            maxConsecutiveLosses = 10,
                            legs = listOf(VolumeFlowCompositeBacktestLeg("primary", legConfig)),
                        ),
                )
            val throttled =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 120,
                    m5Limit = 40,
                    m15Limit = 40,
                    config =
                        VolumeFlowCompositeBacktestConfig(
                            dailyStopPct = 10.0,
                            maxConsecutiveLosses = 10,
                            portfolioDrawdownThrottlePct = 1.0,
                            portfolioDrawdownRiskMultiplier = 0.5,
                            legs = listOf(VolumeFlowCompositeBacktestLeg("primary", legConfig)),
                        ),
                )

            base.tradeCount shouldBe 2
            throttled.tradeCount shouldBe 2
            (base.trades[0].pnl < 0.0) shouldBe true
            (base.trades[1].pnl > 0.0) shouldBe true
            (throttled.trades[1].quantity < base.trades[1].quantity) shouldBe true
            (throttled.trades[1].pnl < base.trades[1].pnl) shouldBe true
        }

        "composite backtest can reduce risk for a leg after its own drawdown threshold is reached" {
            val service = VolumeFlowCompositeBacktestService(InMemoryVolumeFlowCandleStore(twoSignalThrottleCandles()))
            val legConfig = testVolumeFlowConfig().copy(riskFraction = 0.05)

            val base =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 120,
                    m5Limit = 40,
                    m15Limit = 40,
                    config =
                        VolumeFlowCompositeBacktestConfig(
                            dailyStopPct = 10.0,
                            maxConsecutiveLosses = 10,
                            legs = listOf(VolumeFlowCompositeBacktestLeg("primary", legConfig)),
                        ),
                )
            val throttled =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 120,
                    m5Limit = 40,
                    m15Limit = 40,
                    config =
                        VolumeFlowCompositeBacktestConfig(
                            dailyStopPct = 10.0,
                            maxConsecutiveLosses = 10,
                            legDrawdownRiskRules =
                                listOf(
                                    VolumeFlowCompositeLegDrawdownRiskRule(
                                        legId = "primary",
                                        drawdownThresholdPct = 1.0,
                                        riskMultiplier = 0.5,
                                    ),
                                ),
                            legs = listOf(VolumeFlowCompositeBacktestLeg("primary", legConfig)),
                        ),
                )

            base.tradeCount shouldBe 2
            throttled.tradeCount shouldBe 2
            throttled.trades[0].riskMultiplier shouldBe 1.0
            throttled.trades[1].riskMultiplier shouldBe 0.5
            (base.trades[0].pnl < 0.0) shouldBe true
            (base.trades[1].pnl > 0.0) shouldBe true
            (throttled.trades[1].quantity < base.trades[1].quantity) shouldBe true
            (throttled.trades[1].pnl < base.trades[1].pnl) shouldBe true
        }

        "composite backtest preserves source trade risk multiplier" {
            val service = VolumeFlowCompositeBacktestService(InMemoryVolumeFlowCandleStore(lateMacroDownVolumeFlowCandles()))
            val baseLegConfig =
                testVolumeFlowConfig()
                    .copy(
                        requireContextVwap = false,
                        requireContextTrend = false,
                        macroTrendLookbackM15Candles = 16,
                    )
            val reducedLegConfig = baseLegConfig.copy(macroTrendMismatchRiskMultiplier = 0.5)

            val base =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        VolumeFlowCompositeBacktestConfig(
                            legs = listOf(VolumeFlowCompositeBacktestLeg("primary", baseLegConfig)),
                        ),
                )
            val reduced =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 80,
                    m5Limit = 30,
                    m15Limit = 30,
                    config =
                        VolumeFlowCompositeBacktestConfig(
                            legs = listOf(VolumeFlowCompositeBacktestLeg("primary", reducedLegConfig)),
                        ),
                )

            base.tradeCount shouldBe 1
            reduced.tradeCount shouldBe 1
            reduced.trades.single().riskMultiplier shouldBe 0.5
            (reduced.trades.single().quantity < base.trades.single().quantity) shouldBe true
            (reduced.trades.single().pnl < base.trades.single().pnl) shouldBe true
        }

        "composite backtest cooldown skips entries after portfolio drawdown throttle is reached" {
            val service = VolumeFlowCompositeBacktestService(InMemoryVolumeFlowCandleStore(twoSignalThrottleCandles()))
            val legConfig = testVolumeFlowConfig().copy(riskFraction = 0.05)

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 120,
                    m5Limit = 40,
                    m15Limit = 40,
                    config =
                        VolumeFlowCompositeBacktestConfig(
                            dailyStopPct = 10.0,
                            maxConsecutiveLosses = 10,
                            portfolioDrawdownThrottlePct = 1.0,
                            portfolioDrawdownCooldownDays = 1,
                            legs = listOf(VolumeFlowCompositeBacktestLeg("primary", legConfig)),
                        ),
                )

            result.tradeCount shouldBe 1
            result.noTradeReasonCounts["PORTFOLIO_DRAWDOWN_COOLDOWN"] shouldBe 1
        }

        "composite backtest skips new entries for the rest of a month after monthly stop is reached" {
            VolumeFlowCompositeBacktestConfig(
                monthlyStopPct = 5.0,
                legs = listOf(VolumeFlowCompositeBacktestLeg("primary", testVolumeFlowConfig())),
            ).monthlyStopPct shouldBe 5.0

            shouldThrow<IllegalArgumentException> {
                VolumeFlowCompositeBacktestConfig(
                    monthlyStopPct = 0.0,
                    legs = listOf(VolumeFlowCompositeBacktestLeg("primary", testVolumeFlowConfig())),
                )
            }.message shouldBe "Monthly stop percent must be null or between 0 and 95."

            val service = VolumeFlowCompositeBacktestService(InMemoryVolumeFlowCandleStore(twoSignalThrottleCandles()))
            val legConfig = testVolumeFlowConfig().copy(riskFraction = 0.05)

            val result =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    m1Limit = 120,
                    m5Limit = 40,
                    m15Limit = 40,
                    config =
                        VolumeFlowCompositeBacktestConfig(
                            dailyStopPct = 10.0,
                            monthlyStopPct = 1.0,
                            maxConsecutiveLosses = 10,
                            legs = listOf(VolumeFlowCompositeBacktestLeg("primary", legConfig)),
                        ),
                )

            result.tradeCount shouldBe 1
            (result.trades.single().pnl < 0.0) shouldBe true
            result.noTradeReasonCounts["MONTHLY_STOP_HIT"] shouldBe 1
        }
    })

private fun testVolumeFlowConfig(): VolumeFlowBacktestConfig =
    VolumeFlowBacktestConfig(
        volumeLookback = 3,
        relativeVolumeThreshold = 2.0,
        volumeZScoreThreshold = 0.5,
        setupRangeLookback = 3,
        contextVwapLookback = 3,
        minBodyRatio = 0.4,
        entryLookaheadM1Candles = 3,
        entryRetestTolerancePct = 0.01,
        targetR = 0.5,
        maxHoldM1Candles = 5,
    )

private class InMemoryVolumeFlowCandleStore(
    private val candles: List<Candle>,
) : MarketCandleStore {
    override suspend fun upsert(candles: List<Candle>) = Unit

    override suspend fun recentCandles(
        symbol: Symbol,
        timeframe: Timeframe,
        limit: Int,
    ): List<Candle> =
        candles
            .filter { it.symbol == symbol && it.timeframe == timeframe }
            .sortedByDescending { it.openedAt }
            .take(limit)

    override suspend fun candlesBetween(
        symbol: Symbol,
        timeframe: Timeframe,
        startAt: Instant,
        endAt: Instant,
        limit: Int,
    ): List<Candle> =
        candles
            .filter { candle ->
                candle.symbol == symbol &&
                    candle.timeframe == timeframe &&
                    !candle.openedAt.isBefore(startAt) &&
                    !candle.openedAt.isAfter(endAt)
            }.sortedBy { it.openedAt }
            .take(limit)

    override suspend fun candlesBefore(
        symbol: Symbol,
        timeframe: Timeframe,
        beforeAt: Instant,
        limit: Int,
    ): List<Candle> =
        candles
            .filter { candle ->
                candle.symbol == symbol && candle.timeframe == timeframe && candle.openedAt.isBefore(beforeAt)
            }.sortedByDescending { it.openedAt }
            .take(limit)
}

private class InMemoryVolumeFlowDataStore(
    private val takerFlowBars: List<TakerFlowBar> = emptyList(),
    private val openInterestSnapshots: List<OpenInterestSnapshot> = emptyList(),
    private val accountRatioSnapshots: List<AccountRatioSnapshot> = emptyList(),
    private val premiumIndexBars: List<PremiumIndexBar> = emptyList(),
    private val fundingRateSnapshots: List<FundingRateSnapshot> = emptyList(),
) : FlowMarketDataStore {
    var takerFlowBetweenCalls: Int = 0
        private set

    override suspend fun upsertTakerFlowBars(bars: List<TakerFlowBar>) = Unit

    override suspend fun takerFlowBarsBetween(
        symbol: Symbol,
        startAt: Instant,
        endAt: Instant,
        limit: Int,
    ): List<TakerFlowBar> {
        takerFlowBetweenCalls += 1
        return takerFlowBars
            .filter { it.symbol == symbol && !it.openedAt.isBefore(startAt) && !it.openedAt.isAfter(endAt) }
            .sortedBy { it.openedAt }
            .take(limit)
    }

    override suspend fun takerFlowBarsBefore(
        symbol: Symbol,
        beforeAt: Instant,
        limit: Int,
    ): List<TakerFlowBar> =
        takerFlowBars
            .filter { it.symbol == symbol && it.openedAt.isBefore(beforeAt) }
            .sortedByDescending { it.openedAt }
            .take(limit)

    override suspend fun upsertOpenInterestSnapshots(snapshots: List<OpenInterestSnapshot>) = Unit

    override suspend fun openInterestSnapshotsBetween(
        symbol: Symbol,
        interval: OpenInterestInterval,
        startAt: Instant,
        endAt: Instant,
        limit: Int,
    ): List<OpenInterestSnapshot> =
        openInterestSnapshots
            .filter {
                it.symbol == symbol &&
                    it.interval == interval &&
                    !it.timestamp.isBefore(startAt) &&
                    !it.timestamp.isAfter(endAt)
            }.sortedBy { it.timestamp }
            .take(limit)

    override suspend fun openInterestSnapshotsBefore(
        symbol: Symbol,
        interval: OpenInterestInterval,
        beforeAt: Instant,
        limit: Int,
    ): List<OpenInterestSnapshot> =
        openInterestSnapshots
            .filter { it.symbol == symbol && it.interval == interval && it.timestamp.isBefore(beforeAt) }
            .sortedByDescending { it.timestamp }
            .take(limit)

    override suspend fun upsertAccountRatioSnapshots(snapshots: List<AccountRatioSnapshot>) = Unit

    override suspend fun accountRatioSnapshotsBetween(
        symbol: Symbol,
        period: AccountRatioPeriod,
        startAt: Instant,
        endAt: Instant,
        limit: Int,
    ): List<AccountRatioSnapshot> =
        accountRatioSnapshots
            .filter {
                it.symbol == symbol &&
                    it.period == period &&
                    !it.timestamp.isBefore(startAt) &&
                    !it.timestamp.isAfter(endAt)
            }.sortedBy { it.timestamp }
            .take(limit)

    override suspend fun accountRatioSnapshotsBefore(
        symbol: Symbol,
        period: AccountRatioPeriod,
        beforeAt: Instant,
        limit: Int,
    ): List<AccountRatioSnapshot> =
        accountRatioSnapshots
            .filter { it.symbol == symbol && it.period == period && it.timestamp.isBefore(beforeAt) }
            .sortedByDescending { it.timestamp }
            .take(limit)

    override suspend fun upsertPremiumIndexBars(bars: List<PremiumIndexBar>) = Unit

    override suspend fun premiumIndexBarsBetween(
        symbol: Symbol,
        timeframe: Timeframe,
        startAt: Instant,
        endAt: Instant,
        limit: Int,
    ): List<PremiumIndexBar> =
        premiumIndexBars
            .filter {
                it.symbol == symbol &&
                    it.timeframe == timeframe &&
                    !it.openedAt.isBefore(startAt) &&
                    !it.openedAt.isAfter(endAt)
            }.sortedBy { it.openedAt }
            .take(limit)

    override suspend fun premiumIndexBarsBefore(
        symbol: Symbol,
        timeframe: Timeframe,
        beforeAt: Instant,
        limit: Int,
    ): List<PremiumIndexBar> =
        premiumIndexBars
            .filter { it.symbol == symbol && it.timeframe == timeframe && it.openedAt.isBefore(beforeAt) }
            .sortedByDescending { it.openedAt }
            .take(limit)

    override suspend fun upsertFundingRateSnapshots(snapshots: List<FundingRateSnapshot>) = Unit

    override suspend fun fundingRateSnapshotsBetween(
        symbol: Symbol,
        startAt: Instant,
        endAt: Instant,
        limit: Int,
    ): List<FundingRateSnapshot> =
        fundingRateSnapshots
            .filter { it.symbol == symbol && !it.timestamp.isBefore(startAt) && !it.timestamp.isAfter(endAt) }
            .sortedBy { it.timestamp }
            .take(limit)

    override suspend fun fundingRateSnapshotsBefore(
        symbol: Symbol,
        beforeAt: Instant,
        limit: Int,
    ): List<FundingRateSnapshot> =
        fundingRateSnapshots
            .filter { it.symbol == symbol && it.timestamp.isBefore(beforeAt) }
            .sortedByDescending { it.timestamp }
            .take(limit)
}

private class ExactLookupOnlyMap<K, V>(
    private val delegate: Map<K, V>,
) : Map<K, V> {
    var getCalls: Int = 0
        private set

    override val size: Int
        get() = delegate.size

    override val entries: Set<Map.Entry<K, V>>
        get() = error("Entry iteration is not allowed in this lookup-only test map.")

    override val keys: Set<K>
        get() = error("Key iteration is not allowed in this lookup-only test map.")

    override val values: Collection<V>
        get() = error("Value iteration is not allowed in this lookup-only test map.")

    override fun containsKey(key: K): Boolean = delegate.containsKey(key)

    override fun containsValue(value: V): Boolean = error("Value scans are not allowed in this lookup-only test map.")

    override fun get(key: K): V? {
        getCalls += 1
        return delegate[key]
    }

    override fun isEmpty(): Boolean = delegate.isEmpty()
}

private fun completeTakerFlowBucket(
    openedAt: Instant,
    takerBuyNotional: Double = 400.0,
    takerSellNotional: Double = 100.0,
): TakerFlowM5Bucket =
    TakerFlowM5Bucket(
        openedAt = openedAt,
        takerBuyNotional = takerBuyNotional,
        takerSellNotional = takerSellNotional,
        buyTradeCount = 5,
        sellTradeCount = 5,
        minuteCoverageMask = FULL_TAKER_FLOW_M5_MINUTE_COVERAGE_MASK,
        minuteRecordCount = 5,
    )

private fun alignedTakerFlowBars(): List<TakerFlowBar> =
    (0 until 5).map { index ->
        takerFlowBar(Instant.parse("2026-06-30T01:00:00Z").plusSeconds(index * 60L), "80", "20")
    }

private fun oppositeTakerFlowBars(): List<TakerFlowBar> =
    (0 until 5).map { index ->
        takerFlowBar(Instant.parse("2026-06-30T01:00:00Z").plusSeconds(index * 60L), "20", "80")
    }

private fun takerFlowBar(
    openedAt: Instant,
    buyNotional: String,
    sellNotional: String,
): TakerFlowBar =
    TakerFlowBar(
        symbol = Symbol("BTCUSDT"),
        openedAt = openedAt,
        takerBuyBase = BigDecimal.ONE,
        takerBuyNotional = BigDecimal(buyNotional),
        takerSellBase = BigDecimal.ONE,
        takerSellNotional = BigDecimal(sellNotional),
        buyTradeCount = 1,
        sellTradeCount = 1,
    )

private fun openInterestSnapshot(
    timestamp: String,
    openInterest: String,
): OpenInterestSnapshot =
    OpenInterestSnapshot(
        symbol = Symbol("BTCUSDT"),
        interval = OpenInterestInterval.M5,
        timestamp = Instant.parse(timestamp),
        openInterest = BigDecimal(openInterest),
    )

private fun premiumIndexBar(
    openedAt: String,
    close: String,
): PremiumIndexBar =
    PremiumIndexBar(
        symbol = Symbol("BTCUSDT"),
        timeframe = Timeframe.M15,
        openedAt = Instant.parse(openedAt),
        open = BigDecimal(close),
        high = BigDecimal(close),
        low = BigDecimal(close),
        close = BigDecimal(close),
    )

private fun volumeFlowCandles(): List<Candle> = volumeFlowM1Candles() + volumeFlowM5Candles() + volumeFlowM15Candles()

private fun macroDownVolumeFlowCandles(): List<Candle> = volumeFlowM1Candles() + volumeFlowM5Candles() + macroDownM15Candles()

private fun lateMacroUpVolumeFlowCandles(): List<Candle> =
    shiftedVolumeFlowM1Candles() + shiftedVolumeFlowM5Candles() + strongMacroUpM15Candles()

private fun lateMacroDownVolumeFlowCandles(): List<Candle> =
    shiftedVolumeFlowM1Candles() + shiftedVolumeFlowM5Candles() + macroDownM15Candles()

private fun runnerVolumeFlowCandles(): List<Candle> = runnerVolumeFlowM1Candles() + volumeFlowM5Candles() + volumeFlowM15Candles()

private fun trendBreakVolumeFlowCandles(): List<Candle> = trendBreakVolumeFlowM1Candles() + volumeFlowM5Candles() + volumeFlowM15Candles()

private fun followThroughFailVolumeFlowCandles(): List<Candle> =
    followThroughFailVolumeFlowM1Candles() + volumeFlowM5Candles() + volumeFlowM15Candles()

private fun profitProtectVolumeFlowCandles(): List<Candle> =
    profitProtectVolumeFlowM1Candles() + volumeFlowM5Candles() + volumeFlowM15Candles()

private fun failedBreakReversalCandles(): List<Candle> =
    failedBreakReversalM1Candles() + failedBreakReversalM5Candles() + volumeFlowM15Candles()

private fun twoSignalThrottleCandles(): List<Candle> = twoSignalThrottleM1Candles() + twoSignalThrottleM5Candles() + volumeFlowM15Candles()

private fun runnerVolumeFlowM1Candles(): List<Candle> =
    volumeFlowM1Candles().map { candle ->
        when (candle.openedAt) {
            Instant.parse("2026-06-30T01:06:00Z") ->
                candle.copy(
                    high = BigDecimal("130.0"),
                    close = BigDecimal("128.0"),
                )
            Instant.parse("2026-06-30T01:07:00Z") ->
                candle.copy(
                    open = BigDecimal("128.0"),
                    high = BigDecimal("130.0"),
                    low = BigDecimal("127.0"),
                    close = BigDecimal("129.0"),
                )
            else -> candle
        }
    }

private fun trendBreakVolumeFlowM1Candles(): List<Candle> =
    volumeFlowM1Candles().map { candle ->
        when (candle.openedAt) {
            Instant.parse("2026-06-30T01:06:00Z") ->
                candle.copy(
                    open = BigDecimal("112.9"),
                    high = BigDecimal("130.0"),
                    low = BigDecimal("112.8"),
                    close = BigDecimal("128.0"),
                )
            Instant.parse("2026-06-30T01:07:00Z") ->
                candle.copy(
                    open = BigDecimal("128.0"),
                    high = BigDecimal("131.0"),
                    low = BigDecimal("126.0"),
                    close = BigDecimal("130.0"),
                )
            Instant.parse("2026-06-30T01:08:00Z") ->
                candle.copy(
                    open = BigDecimal("130.0"),
                    high = BigDecimal("132.0"),
                    low = BigDecimal("127.0"),
                    close = BigDecimal("131.0"),
                )
            Instant.parse("2026-06-30T01:09:00Z") ->
                candle.copy(
                    open = BigDecimal("131.0"),
                    high = BigDecimal("131.0"),
                    low = BigDecimal("122.0"),
                    close = BigDecimal("123.0"),
                )
            else -> candle
        }
    }

private fun followThroughFailVolumeFlowM1Candles(): List<Candle> =
    volumeFlowM1Candles().map { candle ->
        if (candle.openedAt == Instant.parse("2026-06-30T01:06:00Z")) {
            candle.copy(
                open = BigDecimal("112.9"),
                high = BigDecimal("113.0"),
                low = BigDecimal("112.4"),
                close = BigDecimal("112.7"),
            )
        } else {
            candle
        }
    }

private fun profitProtectVolumeFlowM1Candles(): List<Candle> =
    volumeFlowM1Candles().map { candle ->
        if (candle.openedAt == Instant.parse("2026-06-30T01:06:00Z")) {
            candle.copy(
                open = BigDecimal("112.9"),
                high = BigDecimal("118.0"),
                low = BigDecimal("112.8"),
                close = BigDecimal("113.0"),
            )
        } else {
            candle
        }
    }

private fun volumeFlowM1Candles(): List<Candle> =
    (0 until 80).map { index ->
        when (index) {
            65 ->
                volumeFlowCandle(
                    index = index,
                    timeframe = Timeframe.M1,
                    seconds = 60L,
                    open = "112.0",
                    high = "113.0",
                    low = "111.8",
                    close = "112.9",
                    volume = "30",
                )
            66 ->
                volumeFlowCandle(
                    index = index,
                    timeframe = Timeframe.M1,
                    seconds = 60L,
                    open = "112.9",
                    high = "118.0",
                    low = "112.8",
                    close = "117.5",
                    volume = "40",
                )
            else ->
                volumeFlowCandle(
                    index = index,
                    timeframe = Timeframe.M1,
                    seconds = 60L,
                    open = "105",
                    high = "106",
                    low = "104",
                    close = "105",
                    volume = "10",
                )
        }
    }

private fun volumeFlowM5Candles(): List<Candle> =
    (0 until 30).map { index ->
        if (index == 12) {
            volumeFlowCandle(
                index = index,
                timeframe = Timeframe.M5,
                seconds = 300L,
                open = "105",
                high = "112",
                low = "104",
                close = "111.5",
                volume = "60",
            )
        } else {
            val close = 100 + index.coerceAtMost(10)
            val volume =
                when (index % 3) {
                    0 -> "8"
                    1 -> "10"
                    else -> "12"
                }
            volumeFlowCandle(
                index = index,
                timeframe = Timeframe.M5,
                seconds = 300L,
                open = close.toString(),
                high = (close + 1).toString(),
                low = (close - 1).toString(),
                close = close.toString(),
                volume = volume,
            )
        }
    }

private fun shiftedVolumeFlowM1Candles(): List<Candle> =
    volumeFlowM1Candles().map { candle -> candle.copy(openedAt = candle.openedAt.plusSeconds(10_800L)) }

private fun shiftedVolumeFlowM5Candles(): List<Candle> =
    volumeFlowM5Candles().map { candle -> candle.copy(openedAt = candle.openedAt.plusSeconds(10_800L)) }

private fun twoSignalThrottleM1Candles(): List<Candle> =
    (0 until 120).map { index ->
        when (index) {
            65 ->
                volumeFlowCandle(
                    index = index,
                    timeframe = Timeframe.M1,
                    seconds = 60L,
                    open = "112.0",
                    high = "113.0",
                    low = "111.8",
                    close = "112.9",
                    volume = "30",
                )
            66 ->
                volumeFlowCandle(
                    index = index,
                    timeframe = Timeframe.M1,
                    seconds = 60L,
                    open = "112.9",
                    high = "113.0",
                    low = "103.0",
                    close = "104.0",
                    volume = "40",
                )
            80 ->
                volumeFlowCandle(
                    index = index,
                    timeframe = Timeframe.M1,
                    seconds = 60L,
                    open = "122.0",
                    high = "123.0",
                    low = "121.8",
                    close = "122.9",
                    volume = "30",
                )
            81 ->
                volumeFlowCandle(
                    index = index,
                    timeframe = Timeframe.M1,
                    seconds = 60L,
                    open = "122.9",
                    high = "128.0",
                    low = "122.8",
                    close = "127.5",
                    volume = "40",
                )
            else ->
                volumeFlowCandle(
                    index = index,
                    timeframe = Timeframe.M1,
                    seconds = 60L,
                    open = "105",
                    high = "106",
                    low = "104",
                    close = "105",
                    volume = "10",
                )
        }
    }

private fun twoSignalThrottleM5Candles(): List<Candle> =
    (0 until 40).map { index ->
        when (index) {
            12 ->
                volumeFlowCandle(
                    index = index,
                    timeframe = Timeframe.M5,
                    seconds = 300L,
                    open = "105",
                    high = "112",
                    low = "104",
                    close = "111.5",
                    volume = "60",
                )
            15 ->
                volumeFlowCandle(
                    index = index,
                    timeframe = Timeframe.M5,
                    seconds = 300L,
                    open = "115",
                    high = "122",
                    low = "114",
                    close = "121.5",
                    volume = "300",
                )
            else -> {
                val close = 100 + index.coerceAtMost(10)
                val volume =
                    when (index % 3) {
                        0 -> "8"
                        1 -> "10"
                        else -> "12"
                    }
                volumeFlowCandle(
                    index = index,
                    timeframe = Timeframe.M5,
                    seconds = 300L,
                    open = close.toString(),
                    high = (close + 1).toString(),
                    low = (close - 1).toString(),
                    close = close.toString(),
                    volume = volume,
                )
            }
        }
    }

private fun failedBreakReversalM1Candles(): List<Candle> =
    (0 until 80).map { index ->
        when (index) {
            65 ->
                volumeFlowCandle(
                    index = index,
                    timeframe = Timeframe.M1,
                    seconds = 60L,
                    open = "110.8",
                    high = "111.1",
                    low = "109.0",
                    close = "109.1",
                    volume = "30",
                )
            66 ->
                volumeFlowCandle(
                    index = index,
                    timeframe = Timeframe.M1,
                    seconds = 60L,
                    open = "109.1",
                    high = "109.2",
                    low = "106.0",
                    close = "106.2",
                    volume = "40",
                )
            else ->
                volumeFlowCandle(
                    index = index,
                    timeframe = Timeframe.M1,
                    seconds = 60L,
                    open = "110",
                    high = "111",
                    low = "109",
                    close = "110",
                    volume = "10",
                )
        }
    }

private fun failedBreakReversalM5Candles(): List<Candle> =
    volumeFlowM5Candles().map { candle ->
        if (candle.openedAt == Instant.parse("2026-06-30T01:00:00Z")) {
            candle.copy(
                open = BigDecimal("112"),
                high = BigDecimal("114"),
                low = BigDecimal("106"),
                close = BigDecimal("108"),
                volume = BigDecimal("60"),
            )
        } else {
            candle
        }
    }

private fun volumeFlowM15Candles(): List<Candle> =
    (0 until 30).map { index ->
        val close = 100 + index.coerceAtMost(5)
        volumeFlowCandle(
            index = index,
            timeframe = Timeframe.M15,
            seconds = 900L,
            open = close.toString(),
            high = (close + 2).toString(),
            low = (close - 2).toString(),
            close = close.toString(),
            volume = "20",
        )
    }

private fun macroDownM15Candles(): List<Candle> =
    (0 until 30).map { index ->
        val close = 130 - index
        volumeFlowCandle(
            index = index,
            timeframe = Timeframe.M15,
            seconds = 900L,
            open = close.toString(),
            high = (close + 2).toString(),
            low = (close - 2).toString(),
            close = close.toString(),
            volume = "20",
        )
    }

private fun strongMacroUpM15Candles(): List<Candle> =
    (0 until 30).map { index ->
        val close = 100 + index
        volumeFlowCandle(
            index = index,
            timeframe = Timeframe.M15,
            seconds = 900L,
            open = close.toString(),
            high = (close + 2).toString(),
            low = (close - 2).toString(),
            close = close.toString(),
            volume = "20",
        )
    }

private fun volumeFlowCandle(
    index: Int,
    timeframe: Timeframe,
    seconds: Long,
    open: String,
    high: String,
    low: String,
    close: String,
    volume: String,
): Candle =
    Candle(
        symbol = Symbol("BTCUSDT"),
        timeframe = timeframe,
        openedAt = Instant.parse("2026-06-30T00:00:00Z").plusSeconds(index * seconds),
        open = BigDecimal(open),
        high = BigDecimal(high),
        low = BigDecimal(low),
        close = BigDecimal(close),
        volume = BigDecimal(volume),
    )
