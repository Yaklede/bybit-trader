package dev.yaklede.bybittrader.engine.backtest

import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe

data class VolumeFlowSweepConfig(
    val initialEquity: Double = 10_000.0,
    val riskFractionValues: List<Double> = listOf(0.0025, 0.005, 0.0075),
    val feeRate: Double = 0.0006,
    val slippageRate: Double = 0.0002,
    val setupModes: List<VolumeFlowSetupMode> = listOf(VolumeFlowSetupMode.BREAKOUT_CONTINUATION),
    val entryModes: List<VolumeFlowEntryMode> = listOf(VolumeFlowEntryMode.RETEST_CONFIRMATION),
    val sideModes: List<VolumeFlowSideMode> = listOf(VolumeFlowSideMode.BOTH),
    val setupTimeframes: List<Timeframe> = listOf(Timeframe.M5),
    val volumeLookback: Int = 20,
    val relativeVolumeThresholdValues: List<Double> = listOf(5.0, 7.0),
    val volumeZScoreThresholdValues: List<Double> = listOf(1.5),
    val setupRangeLookbackValues: List<Int> = listOf(12),
    val requireM5VwapValues: List<Boolean> = listOf(false, true),
    val m5VwapLookback: Int = 12,
    val contextVwapLookback: Int = 32,
    val requireContextVwapValues: List<Boolean> = listOf(true),
    val requireContextTrendValues: List<Boolean> = listOf(true, false),
    val allowedMarketRegimeValues: List<Set<VolumeFlowMarketRegime>> = listOf(defaultVolumeFlowMarketRegimes),
    val requireRegimeSideAlignmentValues: List<Boolean> = listOf(false),
    val minTrendMovePct: Double = 0.003,
    val minTrendEfficiency: Double = 0.35,
    val highVolatilityRangePct: Double = 0.006,
    val requireKeyLevelProximityValues: List<Boolean> = listOf(false),
    val keyLevelTolerancePctValues: List<Double> = listOf(0.0025),
    val avoidRangeMiddleValues: List<Boolean> = listOf(false),
    val minBodyRatioValues: List<Double> = listOf(0.45),
    val minRejectionWickRatioValues: List<Double> = listOf(0.25),
    val entryLookaheadM1CandlesValues: List<Int> = listOf(5),
    val entryRetestTolerancePct: Double = 0.0015,
    val minEntryRiskPctValues: List<Double?> = listOf(null),
    val maxEntryRiskPctValues: List<Double?> = listOf(null),
    val maxEstimatedFeeRValues: List<Double> = listOf(0.2),
    val targetRValues: List<Double> = listOf(1.0, 1.2),
    val exitModes: List<VolumeFlowExitMode> = listOf(VolumeFlowExitMode.FIXED_TARGET),
    val runnerTrailActivationRValues: List<Double> = listOf(1.0),
    val runnerTrailDistanceRValues: List<Double> = listOf(0.5),
    val breakevenTriggerRValues: List<Double?> = listOf(null),
    val maxHoldM1CandlesValues: List<Int> = listOf(15, 30),
    val dailyTargetPct: Double? = null,
    val dailyStopPct: Double = 1.0,
    val minTradesPerDay: Int = 1,
    val maxTradesPerDay: Int = 5,
    val maxConsecutiveLosses: Int = 3,
    val trainRatio: Double = 0.6,
    val maxCandidates: Int = 200,
) {
    init {
        require(riskFractionValues.isNotEmpty()) { "Risk fraction values must not be empty." }
        require(setupModes.isNotEmpty()) { "Setup mode values must not be empty." }
        require(entryModes.isNotEmpty()) { "Entry mode values must not be empty." }
        require(sideModes.isNotEmpty()) { "Side mode values must not be empty." }
        require(setupTimeframes.isNotEmpty()) { "Setup timeframes must not be empty." }
        require(setupTimeframes.all { it == Timeframe.M1 || it == Timeframe.M5 }) {
            "Setup timeframes must contain only M1 or M5."
        }
        require(relativeVolumeThresholdValues.isNotEmpty()) { "Relative volume threshold values must not be empty." }
        require(volumeZScoreThresholdValues.isNotEmpty()) { "Volume z-score threshold values must not be empty." }
        require(setupRangeLookbackValues.isNotEmpty()) { "Setup range lookback values must not be empty." }
        require(requireM5VwapValues.isNotEmpty()) { "M5 VWAP requirement values must not be empty." }
        require(requireContextVwapValues.isNotEmpty()) { "Context VWAP requirement values must not be empty." }
        require(requireContextTrendValues.isNotEmpty()) { "Context trend requirement values must not be empty." }
        require(allowedMarketRegimeValues.isNotEmpty()) { "Allowed market regime values must not be empty." }
        require(allowedMarketRegimeValues.all { it.isNotEmpty() }) { "Allowed market regime sets must not be empty." }
        require(requireRegimeSideAlignmentValues.isNotEmpty()) { "Regime side alignment values must not be empty." }
        require(requireKeyLevelProximityValues.isNotEmpty()) { "Key-level proximity values must not be empty." }
        require(keyLevelTolerancePctValues.isNotEmpty()) { "Key-level tolerance values must not be empty." }
        require(avoidRangeMiddleValues.isNotEmpty()) { "Avoid range-middle values must not be empty." }
        require(minBodyRatioValues.isNotEmpty()) { "Minimum body ratio values must not be empty." }
        require(minRejectionWickRatioValues.isNotEmpty()) { "Minimum rejection wick ratio values must not be empty." }
        require(entryLookaheadM1CandlesValues.isNotEmpty()) { "Entry lookahead values must not be empty." }
        require(minEntryRiskPctValues.isNotEmpty()) { "Minimum entry risk values must not be empty." }
        require(maxEntryRiskPctValues.isNotEmpty()) { "Maximum entry risk values must not be empty." }
        require(maxEstimatedFeeRValues.isNotEmpty()) { "Max estimated fee R values must not be empty." }
        require(targetRValues.isNotEmpty()) { "Target R values must not be empty." }
        require(exitModes.isNotEmpty()) { "Exit modes must not be empty." }
        require(runnerTrailActivationRValues.isNotEmpty()) { "Runner trail activation values must not be empty." }
        require(runnerTrailDistanceRValues.isNotEmpty()) { "Runner trail distance values must not be empty." }
        require(breakevenTriggerRValues.isNotEmpty()) { "Breakeven trigger R values must not be empty." }
        require(maxHoldM1CandlesValues.isNotEmpty()) { "Max hold values must not be empty." }
        require(trainRatio >= 0.5 && trainRatio <= 0.8) { "Train ratio must be between 0.5 and 0.8." }
        require(maxCandidates in 1..1_000) { "Max candidates must be between 1 and 1000." }
        require(candidateCount() <= maxCandidates) {
            "Volume-flow sweep candidate count must be less than or equal to max candidates."
        }
        candidates().forEach { candidate -> candidate.toBacktestConfig(this) }
    }

    fun candidates(): List<VolumeFlowSweepCandidate> =
        buildList {
            for (riskFraction in riskFractionValues) {
                for (setupMode in setupModes) {
                    for (entryMode in entryModes) {
                        for (sideMode in sideModes) {
                            for (setupTimeframe in setupTimeframes) {
                                for (relativeVolumeThreshold in relativeVolumeThresholdValues) {
                                    for (volumeZScoreThreshold in volumeZScoreThresholdValues) {
                                        for (setupRangeLookback in setupRangeLookbackValues) {
                                            for (requireM5Vwap in requireM5VwapValues) {
                                                for (requireContextVwap in requireContextVwapValues) {
                                                    for (requireContextTrend in requireContextTrendValues) {
                                                        addFilteredCandidates(
                                                            riskFraction = riskFraction,
                                                            setupMode = setupMode,
                                                            entryMode = entryMode,
                                                            sideMode = sideMode,
                                                            setupTimeframe = setupTimeframe,
                                                            relativeVolumeThreshold = relativeVolumeThreshold,
                                                            volumeZScoreThreshold = volumeZScoreThreshold,
                                                            setupRangeLookback = setupRangeLookback,
                                                            requireM5Vwap = requireM5Vwap,
                                                            requireContextVwap = requireContextVwap,
                                                            requireContextTrend = requireContextTrend,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    private fun MutableList<VolumeFlowSweepCandidate>.addFilteredCandidates(
        riskFraction: Double,
        setupMode: VolumeFlowSetupMode,
        entryMode: VolumeFlowEntryMode,
        sideMode: VolumeFlowSideMode,
        setupTimeframe: Timeframe,
        relativeVolumeThreshold: Double,
        volumeZScoreThreshold: Double,
        setupRangeLookback: Int,
        requireM5Vwap: Boolean,
        requireContextVwap: Boolean,
        requireContextTrend: Boolean,
    ) {
        for (allowedMarketRegimes in allowedMarketRegimeValues) {
            for (requireRegimeSideAlignment in requireRegimeSideAlignmentValues) {
                for (requireKeyLevelProximity in requireKeyLevelProximityValues) {
                    for (keyLevelTolerancePct in keyLevelTolerancePctValues) {
                        for (avoidRangeMiddle in avoidRangeMiddleValues) {
                            addExecutionCandidates(
                                riskFraction = riskFraction,
                                setupMode = setupMode,
                                entryMode = entryMode,
                                sideMode = sideMode,
                                setupTimeframe = setupTimeframe,
                                relativeVolumeThreshold = relativeVolumeThreshold,
                                volumeZScoreThreshold = volumeZScoreThreshold,
                                setupRangeLookback = setupRangeLookback,
                                requireM5Vwap = requireM5Vwap,
                                requireContextVwap = requireContextVwap,
                                requireContextTrend = requireContextTrend,
                                allowedMarketRegimes = allowedMarketRegimes,
                                requireRegimeSideAlignment = requireRegimeSideAlignment,
                                requireKeyLevelProximity = requireKeyLevelProximity,
                                keyLevelTolerancePct = keyLevelTolerancePct,
                                avoidRangeMiddle = avoidRangeMiddle,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun MutableList<VolumeFlowSweepCandidate>.addExecutionCandidates(
        riskFraction: Double,
        setupMode: VolumeFlowSetupMode,
        entryMode: VolumeFlowEntryMode,
        sideMode: VolumeFlowSideMode,
        setupTimeframe: Timeframe,
        relativeVolumeThreshold: Double,
        volumeZScoreThreshold: Double,
        setupRangeLookback: Int,
        requireM5Vwap: Boolean,
        requireContextVwap: Boolean,
        requireContextTrend: Boolean,
        allowedMarketRegimes: Set<VolumeFlowMarketRegime>,
        requireRegimeSideAlignment: Boolean,
        requireKeyLevelProximity: Boolean,
        keyLevelTolerancePct: Double,
        avoidRangeMiddle: Boolean,
    ) {
        for (minBodyRatio in minBodyRatioValues) {
            for (minRejectionWickRatio in minRejectionWickRatioValues) {
                for (entryLookaheadM1Candles in entryLookaheadM1CandlesValues) {
                    for (minEntryRiskPct in minEntryRiskPctValues) {
                        for (maxEntryRiskPct in maxEntryRiskPctValues) {
                            for (maxEstimatedFeeR in maxEstimatedFeeRValues) {
                                for (targetR in targetRValues) {
                                    for (exitMode in exitModes) {
                                        for (runnerTrailActivationR in runnerTrailActivationRValues) {
                                            for (runnerTrailDistanceR in runnerTrailDistanceRValues) {
                                                for (breakevenTriggerR in breakevenTriggerRValues) {
                                                    for (maxHoldM1Candles in maxHoldM1CandlesValues) {
                                                        add(
                                                            VolumeFlowSweepCandidate(
                                                                riskFraction = riskFraction,
                                                                setupMode = setupMode,
                                                                entryMode = entryMode,
                                                                sideMode = sideMode,
                                                                setupTimeframe = setupTimeframe,
                                                                relativeVolumeThreshold = relativeVolumeThreshold,
                                                                volumeZScoreThreshold = volumeZScoreThreshold,
                                                                setupRangeLookback = setupRangeLookback,
                                                                requireM5Vwap = requireM5Vwap,
                                                                requireContextVwap = requireContextVwap,
                                                                requireContextTrend = requireContextTrend,
                                                                allowedMarketRegimes = allowedMarketRegimes,
                                                                requireRegimeSideAlignment = requireRegimeSideAlignment,
                                                                requireKeyLevelProximity = requireKeyLevelProximity,
                                                                keyLevelTolerancePct = keyLevelTolerancePct,
                                                                avoidRangeMiddle = avoidRangeMiddle,
                                                                minBodyRatio = minBodyRatio,
                                                                minRejectionWickRatio = minRejectionWickRatio,
                                                                entryLookaheadM1Candles = entryLookaheadM1Candles,
                                                                minEntryRiskPct = minEntryRiskPct,
                                                                maxEntryRiskPct = maxEntryRiskPct,
                                                                maxEstimatedFeeR = maxEstimatedFeeR,
                                                                targetR = targetR,
                                                                exitMode = exitMode,
                                                                runnerTrailActivationR = runnerTrailActivationR,
                                                                runnerTrailDistanceR = runnerTrailDistanceR,
                                                                breakevenTriggerR = breakevenTriggerR,
                                                                maxHoldM1Candles = maxHoldM1Candles,
                                                            ),
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun candidateCount(): Int =
        riskFractionValues.size *
            setupModes.size *
            entryModes.size *
            sideModes.size *
            setupTimeframes.size *
            relativeVolumeThresholdValues.size *
            volumeZScoreThresholdValues.size *
            setupRangeLookbackValues.size *
            requireM5VwapValues.size *
            requireContextVwapValues.size *
            requireContextTrendValues.size *
            allowedMarketRegimeValues.size *
            requireRegimeSideAlignmentValues.size *
            requireKeyLevelProximityValues.size *
            keyLevelTolerancePctValues.size *
            avoidRangeMiddleValues.size *
            minBodyRatioValues.size *
            minRejectionWickRatioValues.size *
            entryLookaheadM1CandlesValues.size *
            minEntryRiskPctValues.size *
            maxEntryRiskPctValues.size *
            maxEstimatedFeeRValues.size *
            targetRValues.size *
            exitModes.size *
            runnerTrailActivationRValues.size *
            runnerTrailDistanceRValues.size *
            breakevenTriggerRValues.size *
            maxHoldM1CandlesValues.size
}

data class VolumeFlowSweepCandidate(
    val riskFraction: Double,
    val setupMode: VolumeFlowSetupMode,
    val entryMode: VolumeFlowEntryMode,
    val sideMode: VolumeFlowSideMode,
    val setupTimeframe: Timeframe,
    val relativeVolumeThreshold: Double,
    val volumeZScoreThreshold: Double,
    val setupRangeLookback: Int,
    val requireM5Vwap: Boolean,
    val requireContextVwap: Boolean,
    val requireContextTrend: Boolean,
    val allowedMarketRegimes: Set<VolumeFlowMarketRegime>,
    val requireRegimeSideAlignment: Boolean,
    val requireKeyLevelProximity: Boolean,
    val keyLevelTolerancePct: Double,
    val avoidRangeMiddle: Boolean,
    val minBodyRatio: Double,
    val minRejectionWickRatio: Double,
    val entryLookaheadM1Candles: Int,
    val minEntryRiskPct: Double?,
    val maxEntryRiskPct: Double?,
    val maxEstimatedFeeR: Double,
    val targetR: Double,
    val exitMode: VolumeFlowExitMode,
    val runnerTrailActivationR: Double,
    val runnerTrailDistanceR: Double,
    val breakevenTriggerR: Double?,
    val maxHoldM1Candles: Int,
) {
    fun toBacktestConfig(config: VolumeFlowSweepConfig): VolumeFlowBacktestConfig =
        VolumeFlowBacktestConfig(
            initialEquity = config.initialEquity,
            riskFraction = riskFraction,
            feeRate = config.feeRate,
            slippageRate = config.slippageRate,
            setupMode = setupMode,
            entryMode = entryMode,
            sideMode = sideMode,
            setupTimeframe = setupTimeframe,
            volumeLookback = config.volumeLookback,
            relativeVolumeThreshold = relativeVolumeThreshold,
            volumeZScoreThreshold = volumeZScoreThreshold,
            setupRangeLookback = setupRangeLookback,
            requireM5Vwap = requireM5Vwap,
            m5VwapLookback = config.m5VwapLookback,
            contextVwapLookback = config.contextVwapLookback,
            requireContextVwap = requireContextVwap,
            requireContextTrend = requireContextTrend,
            allowedMarketRegimes = allowedMarketRegimes,
            requireRegimeSideAlignment = requireRegimeSideAlignment,
            minTrendMovePct = config.minTrendMovePct,
            minTrendEfficiency = config.minTrendEfficiency,
            highVolatilityRangePct = config.highVolatilityRangePct,
            requireKeyLevelProximity = requireKeyLevelProximity,
            keyLevelTolerancePct = keyLevelTolerancePct,
            avoidRangeMiddle = avoidRangeMiddle,
            minBodyRatio = minBodyRatio,
            minRejectionWickRatio = minRejectionWickRatio,
            entryLookaheadM1Candles = entryLookaheadM1Candles,
            entryRetestTolerancePct = config.entryRetestTolerancePct,
            minEntryRiskPct = minEntryRiskPct,
            maxEntryRiskPct = maxEntryRiskPct,
            maxEstimatedFeeR = maxEstimatedFeeR,
            targetR = targetR,
            exitMode = exitMode,
            runnerTrailActivationR = runnerTrailActivationR,
            runnerTrailDistanceR = runnerTrailDistanceR,
            breakevenTriggerR = breakevenTriggerR,
            maxHoldM1Candles = maxHoldM1Candles,
            dailyTargetPct = config.dailyTargetPct,
            dailyStopPct = config.dailyStopPct,
            minTradesPerDay = config.minTradesPerDay,
            maxTradesPerDay = config.maxTradesPerDay,
            maxConsecutiveLosses = config.maxConsecutiveLosses,
        )
}

data class VolumeFlowSweepReport(
    val symbol: Symbol,
    val candidateCount: Int,
    val m1CandleCount: Int,
    val m5CandleCount: Int,
    val m15CandleCount: Int,
    val trainM1CandleCount: Int,
    val trainM5CandleCount: Int,
    val trainM15CandleCount: Int,
    val testM1CandleCount: Int,
    val testM5CandleCount: Int,
    val testM15CandleCount: Int,
    val results: List<VolumeFlowSweepResult>,
)

data class VolumeFlowSweepResult(
    val candidate: VolumeFlowSweepCandidate,
    val train: VolumeFlowBacktestSummary,
    val test: VolumeFlowBacktestSummary,
    val testPassesProfitabilityGate: Boolean,
    val testPassesCompoundingGate: Boolean,
    val testPassesFrequencyGate: Boolean,
    val score: Double,
)

data class VolumeFlowBacktestSummary(
    val tradeCount: Int,
    val netPnl: Double,
    val netReturnPct: Double,
    val maxDrawdownPct: Double,
    val profitFactor: Double?,
    val expectancyR: Double,
    val returnDrawdownRatio: Double,
    val winRatePct: Double,
    val maxConsecutiveLosses: Int,
    val observedDays: Int,
    val activeDays: Int,
    val averageTradesPerDay: Double,
    val averageTradesPerActiveDay: Double,
    val tradeFrequencyTargetPct: Double,
    val belowMinTradeDays: Int,
    val aboveMaxTradeDays: Int,
    val setupCount: Int,
    val rejectedSetupCount: Int,
    val noTradeReasonCounts: Map<String, Int>,
)

fun VolumeFlowBacktestReport.toSweepSummary(): VolumeFlowBacktestSummary =
    VolumeFlowBacktestSummary(
        tradeCount = tradeCount,
        netPnl = netPnl,
        netReturnPct = netReturnPct,
        maxDrawdownPct = maxDrawdownPct,
        profitFactor = profitFactor,
        expectancyR = expectancyR,
        returnDrawdownRatio = netReturnPct / maxOf(maxDrawdownPct, 1.0),
        winRatePct = winRatePct,
        maxConsecutiveLosses = maxConsecutiveLosses,
        observedDays = observedDays,
        activeDays = activeDays,
        averageTradesPerDay = averageTradesPerDay,
        averageTradesPerActiveDay = averageTradesPerActiveDay,
        tradeFrequencyTargetPct = tradeFrequencyTargetPct,
        belowMinTradeDays = belowMinTradeDays,
        aboveMaxTradeDays = aboveMaxTradeDays,
        setupCount = setupCount,
        rejectedSetupCount = rejectedSetupCount,
        noTradeReasonCounts = noTradeReasonCounts,
    )
