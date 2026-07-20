package dev.yaklede.bybittrader.engine.backtest

import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import java.security.MessageDigest
import java.time.Instant

const val AGGRESSIVE_BACKTEST_ENGINE_VERSION = "2.0.0"
const val AGGRESSIVE_FILL_MODEL_VERSION = "causal-m1-path-v2"

enum class StrategyValidationStatus {
    UNVERIFIED,
    REJECTED,
    VERIFIED,
}

data class VolumeFlowAggressiveExecutionContract(
    val riskFraction: Double,
    val feeRate: Double,
    val entrySlippageRate: Double,
    val exitSlippageRate: Double,
    val fundingRatePer8h: Double,
    val quantityStep: Double?,
    val minQuantity: Double?,
    val maxQuantity: Double?,
    val maxNotional: Double?,
    val leverage: Double?,
    val liquidationBufferPct: Double,
) {
    val fingerprint: String
        get() =
            MessageDigest
                .getInstance("SHA-256")
                .digest(canonicalValue().toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }

    private fun canonicalValue(): String =
        listOf(
            "riskFraction=${riskFraction.canonicalNumber()}",
            "feeRate=${feeRate.canonicalNumber()}",
            "entrySlippageRate=${entrySlippageRate.canonicalNumber()}",
            "exitSlippageRate=${exitSlippageRate.canonicalNumber()}",
            "fundingRatePer8h=${fundingRatePer8h.canonicalNumber()}",
            "quantityStep=${quantityStep.canonicalNumber()}",
            "minQuantity=${minQuantity.canonicalNumber()}",
            "maxQuantity=${maxQuantity.canonicalNumber()}",
            "maxNotional=${maxNotional.canonicalNumber()}",
            "leverage=${leverage.canonicalNumber()}",
            "liquidationBufferPct=${liquidationBufferPct.canonicalNumber()}",
        ).joinToString("|")
}

data class VolumeFlowAggressiveRuntimeProfile(
    val contractVersion: String,
    val strategyConfig: VolumeFlowAggressiveBacktestConfig,
    val executionContract: VolumeFlowAggressiveExecutionContract,
    val validationStatus: StrategyValidationStatus,
) {
    init {
        require(contractVersion.isNotBlank()) { "Aggressive runtime contract version must not be blank." }
    }

    val profileId: String
        get() = strategyConfig.profileId

    val strategyName: String
        get() = "volume-flow-aggressive-$profileId"
}

enum class AggressiveExecutionPathMode {
    M1_REQUIRED,
    M5_CONSERVATIVE,
}

enum class VolumeFlowAggressiveEntryMode {
    BREAKOUT_NEXT_OPEN,
    BREAKOUT_RETEST,
    FAILED_BREAK_REVERSAL,
}

enum class VolumeFlowAggressiveSignalMode {
    ABSORPTION_BREAKOUT,
    MACRO_DONCHIAN,
}

data class VolumeFlowAggressiveBacktestConfig(
    val profileId: String = "absa_final_us_v1",
    val initialEquity: Double = 1_000_000.0,
    val riskFraction: Double = 0.055,
    val feeRate: Double = 0.0006,
    val slippageRate: Double = 0.0002,
    val exitSlippageRate: Double = 0.0002,
    val fundingRatePer8h: Double = 0.0,
    val quantityStep: Double? = null,
    val minQuantity: Double? = null,
    val maxQuantity: Double? = null,
    val maxNotional: Double? = null,
    val leverage: Double? = null,
    val liquidationBufferPct: Double = 0.6,
    val sessionHoursUtc: Set<Int> = setOf(13, 14, 15, 16, 17, 18, 19, 20, 21),
    val entrySignalHoursUtc: Set<Int>? = null,
    val volumeLookback: Int = 20,
    val atrLookback: Int = 20,
    val relativeVolumeMin: Double = 1.0,
    val clusterCandles: Int = 2,
    val clusterVolumeMin: Double = 1.2,
    val maxDisplacementAtr: Double = 1.2,
    val maxRangeAtr: Double = 3.0,
    val stopAtr: Double = 1.0,
    val adaptiveStop: VolumeFlowAggressiveAdaptiveStop? = null,
    val targetR: Double = 2.2,
    val adaptiveTarget: VolumeFlowAggressiveAdaptiveTarget? = null,
    val sideRegimeBlocks: List<VolumeFlowAggressiveSideRegimeBlock> = emptyList(),
    val entryLookaheadCandles: Int = 3,
    val maxHoldCandles: Int = 36,
    val maxTradesPerDay: Int = 5,
    val sideMode: VolumeFlowSideMode = VolumeFlowSideMode.BOTH,
    val signalMode: VolumeFlowAggressiveSignalMode = VolumeFlowAggressiveSignalMode.ABSORPTION_BREAKOUT,
    val donchianLookbackCandles: Int = 1_440,
    val stopReferenceCandles: Int = 288,
    val trailingAtrMultiple: Double? = null,
    val entryMode: VolumeFlowAggressiveEntryMode = VolumeFlowAggressiveEntryMode.BREAKOUT_NEXT_OPEN,
    val breakoutRelativeVolumeMin: Double = 0.0,
    val breakoutBodyRatioMin: Double = 0.0,
    val breakoutDirectionalCloseMin: Double = 0.0,
    val maxBreakoutDistanceAtr: Double? = null,
    val retestLookaheadCandles: Int = 6,
    val retestToleranceAtr: Double = 0.15,
    val retestDirectionalCloseMin: Double = 0.55,
    val breakEvenTriggerR: Double? = null,
    val breakEvenLockR: Double = 0.0,
    val trailingTriggerR: Double? = null,
    val trailingDistanceR: Double? = null,
    val executionPathMode: AggressiveExecutionPathMode = AggressiveExecutionPathMode.M1_REQUIRED,
) {
    init {
        require(profileId.isNotBlank()) { "Aggressive profile id must not be blank." }
        require(initialEquity > 0.0) { "Initial equity must be positive." }
        require(riskFraction > 0.0 && riskFraction <= 0.20) { "Risk fraction must be between 0 and 0.20." }
        require(feeRate >= 0.0 && feeRate <= 0.01) { "Fee rate must be between 0 and 0.01." }
        require(slippageRate >= 0.0 && slippageRate <= 0.01) { "Slippage rate must be between 0 and 0.01." }
        require(exitSlippageRate >= 0.0 && exitSlippageRate <= 0.01) {
            "Exit slippage rate must be between 0 and 0.01."
        }
        require(fundingRatePer8h in -0.01..0.01) { "Funding rate per 8h must be between -0.01 and 0.01." }
        require(quantityStep == null || quantityStep > 0.0) { "Quantity step must be positive." }
        require(minQuantity == null || minQuantity > 0.0) { "Minimum quantity must be positive." }
        require(maxQuantity == null || maxQuantity > 0.0) { "Maximum quantity must be positive." }
        require(maxQuantity == null || minQuantity == null || maxQuantity >= minQuantity) {
            "Maximum quantity must be greater than or equal to minimum quantity."
        }
        require(maxNotional == null || maxNotional > 0.0) { "Maximum notional must be positive." }
        require(leverage == null || leverage > 1.0) { "Leverage must be greater than 1." }
        require(liquidationBufferPct in 0.0..10.0) { "Liquidation buffer must be between 0 and 10 percent." }
        require(sessionHoursUtc.isNotEmpty()) { "Session hours must not be empty." }
        require(sessionHoursUtc.all { it in 0..23 }) { "Session hours must be between 0 and 23." }
        require(entrySignalHoursUtc == null || entrySignalHoursUtc.isNotEmpty()) {
            "Entry signal hours must be omitted or non-empty."
        }
        require(entrySignalHoursUtc == null || entrySignalHoursUtc.all { it in 0..23 }) {
            "Entry signal hours must be between 0 and 23."
        }
        require(volumeLookback > 1) { "Volume lookback must be greater than 1." }
        require(atrLookback > 1) { "ATR lookback must be greater than 1." }
        require(relativeVolumeMin >= 1.0) { "Relative volume minimum must be at least 1." }
        require(clusterCandles in 1..12) { "Cluster candles must be between 1 and 12." }
        require(clusterVolumeMin > 0.0) { "Cluster volume minimum must be positive." }
        require(maxDisplacementAtr > 0.0) { "Maximum displacement ATR must be positive." }
        require(maxRangeAtr > 0.0) { "Maximum range ATR must be positive." }
        require(stopAtr > 0.0) { "Stop ATR must be positive." }
        require(targetR > 0.0) { "Target R must be positive." }
        require(entryLookaheadCandles in 1..24) { "Entry lookahead candles must be between 1 and 24." }
        require(maxHoldCandles in 1..17_280) { "Max hold candles must be between 1 and 17280." }
        require(maxTradesPerDay > 0) { "Max trades per day must be positive." }
        require(donchianLookbackCandles in 288..17_280) {
            "Donchian lookback candles must be between 288 and 17280."
        }
        require(stopReferenceCandles in 12..2_016) { "Stop reference candles must be between 12 and 2016." }
        require(trailingAtrMultiple == null || trailingAtrMultiple > 0.0) {
            "Trailing ATR multiple must be positive."
        }
        require(breakoutRelativeVolumeMin >= 0.0) { "Breakout relative volume minimum must not be negative." }
        require(breakoutBodyRatioMin in 0.0..1.0) { "Breakout body ratio minimum must be between 0 and 1." }
        require(breakoutDirectionalCloseMin in 0.0..1.0) {
            "Breakout directional close minimum must be between 0 and 1."
        }
        require(maxBreakoutDistanceAtr == null || maxBreakoutDistanceAtr > 0.0) {
            "Maximum breakout distance ATR must be positive."
        }
        require(retestLookaheadCandles in 1..24) { "Retest lookahead candles must be between 1 and 24." }
        require(retestToleranceAtr in 0.0..2.0) { "Retest tolerance ATR must be between 0 and 2." }
        require(retestDirectionalCloseMin in 0.0..1.0) {
            "Retest directional close minimum must be between 0 and 1."
        }
        require(breakEvenTriggerR == null || breakEvenTriggerR > 0.0) { "Break-even trigger R must be positive." }
        require(breakEvenLockR >= 0.0) { "Break-even lock R must not be negative." }
        require(breakEvenTriggerR == null || breakEvenLockR < breakEvenTriggerR) {
            "Break-even lock R must be below its trigger."
        }
        require((trailingTriggerR == null) == (trailingDistanceR == null)) {
            "Trailing trigger and distance R must both be set or both be omitted."
        }
        require(trailingTriggerR == null || trailingTriggerR > 0.0) { "Trailing trigger R must be positive." }
        require(trailingDistanceR == null || trailingDistanceR > 0.0) { "Trailing distance R must be positive." }
    }
}

fun VolumeFlowAggressiveBacktestConfig.requiredWarmupCandles(): Int {
    val sideLookback =
        sideRegimeBlocks
            .flatMap { listOf(it.lookbackCandles, it.confirmLookbackCandles ?: 0) }
            .maxOrNull()
            ?: 0
    val adaptiveLookback = maxOf(adaptiveStop?.lookbackCandles ?: 0, adaptiveTarget?.lookbackCandles ?: 0)
    val signalWarmup =
        when (signalMode) {
            VolumeFlowAggressiveSignalMode.ABSORPTION_BREAKOUT -> 0
            VolumeFlowAggressiveSignalMode.MACRO_DONCHIAN ->
                maxOf(donchianLookbackCandles + 1, stopReferenceCandles + 1, 1_153)
        }
    return maxOf(
        60,
        volumeLookback + 1,
        atrLookback + 1,
        sideLookback + 1,
        adaptiveLookback + 1,
        clusterCandles + entryLookaheadCandles + retestLookaheadCandles + 1,
        signalWarmup,
    )
}

data class VolumeFlowAggressiveAdaptiveStop(
    val stopAtr: Double,
    val lookbackCandles: Int,
    val returnMinPct: Double,
    val avgVolumeMin: Double,
    val avgRangePctMin: Double,
) {
    init {
        require(stopAtr > 0.0) { "Adaptive stop ATR must be positive." }
        require(lookbackCandles > 0) { "Adaptive stop lookback candles must be positive." }
        require(avgVolumeMin >= 0.0) { "Adaptive stop average volume minimum must not be negative." }
        require(avgRangePctMin >= 0.0) { "Adaptive stop average range minimum must not be negative." }
    }
}

data class VolumeFlowAggressiveAdaptiveTarget(
    val targetR: Double,
    val lookbackCandles: Int,
    val returnMaxPct: Double,
    val avgVolumeMin: Double,
    val avgRangePctMin: Double,
) {
    init {
        require(targetR > 0.0) { "Adaptive target R must be positive." }
        require(lookbackCandles > 0) { "Adaptive target lookback candles must be positive." }
        require(avgVolumeMin >= 0.0) { "Adaptive target average volume minimum must not be negative." }
        require(avgRangePctMin >= 0.0) { "Adaptive target average range minimum must not be negative." }
    }
}

data class VolumeFlowAggressiveSideRegimeBlock(
    val side: Side,
    val lookbackCandles: Int,
    val returnMinPct: Double? = null,
    val returnMaxPct: Double? = null,
    val avgVolumeMin: Double? = null,
    val avgVolumeMax: Double? = null,
    val avgRangePctMin: Double? = null,
    val confirmLookbackCandles: Int? = null,
    val confirmReturnMinPct: Double? = null,
    val confirmReturnMaxPct: Double? = null,
    val confirmAvgVolumeMin: Double? = null,
    val confirmAvgVolumeMax: Double? = null,
    val confirmAvgRangePctMin: Double? = null,
) {
    init {
        require(lookbackCandles > 0) { "Side regime lookback candles must be positive." }
        require(avgVolumeMin == null || avgVolumeMin >= 0.0) { "Side regime average volume minimum must not be negative." }
        require(avgVolumeMax == null || avgVolumeMax >= 0.0) { "Side regime average volume maximum must not be negative." }
        require(avgRangePctMin == null || avgRangePctMin >= 0.0) { "Side regime average range minimum must not be negative." }
        require(confirmLookbackCandles == null || confirmLookbackCandles > 0) {
            "Side regime confirm lookback candles must be positive."
        }
        require(confirmAvgVolumeMin == null || confirmAvgVolumeMin >= 0.0) {
            "Side regime confirm average volume minimum must not be negative."
        }
        require(confirmAvgVolumeMax == null || confirmAvgVolumeMax >= 0.0) {
            "Side regime confirm average volume maximum must not be negative."
        }
        require(confirmAvgRangePctMin == null || confirmAvgRangePctMin >= 0.0) {
            "Side regime confirm average range minimum must not be negative."
        }
    }
}

object VolumeFlowAggressiveProfiles {
    fun current(): VolumeFlowAggressiveRuntimeProfile =
        VolumeFlowAggressiveRuntimeProfile(
            contractVersion = "aggressive-runtime-v1",
            strategyConfig = finalUsV1(),
            executionContract =
                VolumeFlowAggressiveExecutionContract(
                    riskFraction = 0.055,
                    feeRate = 0.0006,
                    entrySlippageRate = 0.0002,
                    exitSlippageRate = 0.0002,
                    fundingRatePer8h = 0.0,
                    quantityStep = 0.001,
                    minQuantity = 0.001,
                    maxQuantity = null,
                    maxNotional = 100.0,
                    leverage = 15.0,
                    liquidationBufferPct = 0.6,
                ),
            validationStatus = StrategyValidationStatus.REJECTED,
        )

    fun matchesCurrentSignalDefinition(config: VolumeFlowAggressiveBacktestConfig): Boolean =
        config.normalizedSignalDefinition() == current().strategyConfig.normalizedSignalDefinition()

    fun currentReplayConfig(initialEquity: Double = 100.0): VolumeFlowAggressiveBacktestConfig {
        val profile = current()
        return profile.strategyConfig
            .withExecutionContract(profile.executionContract)
            .copy(initialEquity = initialEquity)
    }

    fun finalUsV1(): VolumeFlowAggressiveBacktestConfig =
        VolumeFlowAggressiveBacktestConfig(
            sessionHoursUtc = setOf(13, 14, 15, 16, 17, 18, 19, 21),
            adaptiveStop =
                VolumeFlowAggressiveAdaptiveStop(
                    stopAtr = 1.2,
                    lookbackCandles = 8_640,
                    returnMinPct = 25.0,
                    avgVolumeMin = 200.0,
                    avgRangePctMin = 0.12,
                ),
            adaptiveTarget =
                VolumeFlowAggressiveAdaptiveTarget(
                    targetR = 1.5,
                    lookbackCandles = 4_032,
                    returnMaxPct = -10.0,
                    avgVolumeMin = 300.0,
                    avgRangePctMin = 0.10,
                ),
            sideRegimeBlocks =
                listOf(
                    VolumeFlowAggressiveSideRegimeBlock(
                        side = Side.SELL,
                        lookbackCandles = 17_280,
                        returnMaxPct = -25.0,
                    ),
                    VolumeFlowAggressiveSideRegimeBlock(
                        side = Side.BUY,
                        lookbackCandles = 17_280,
                        returnMinPct = -12.0,
                        returnMaxPct = 2.0,
                        avgVolumeMin = 200.0,
                        avgVolumeMax = 300.0,
                    ),
                    VolumeFlowAggressiveSideRegimeBlock(
                        side = Side.BUY,
                        lookbackCandles = 4_032,
                        returnMinPct = 0.0,
                        returnMaxPct = 10.0,
                        confirmLookbackCandles = 17_280,
                        confirmReturnMinPct = 20.0,
                        confirmAvgVolumeMax = 300.0,
                    ),
                    VolumeFlowAggressiveSideRegimeBlock(
                        side = Side.BUY,
                        lookbackCandles = 17_280,
                        returnMinPct = 10.0,
                        returnMaxPct = 20.0,
                        avgVolumeMax = 300.0,
                        confirmLookbackCandles = 4_032,
                        confirmReturnMinPct = 0.0,
                        confirmReturnMaxPct = 10.0,
                    ),
                    VolumeFlowAggressiveSideRegimeBlock(
                        side = Side.SELL,
                        lookbackCandles = 17_280,
                        returnMinPct = 0.0,
                        returnMaxPct = 10.0,
                        avgVolumeMin = 300.0,
                        confirmLookbackCandles = 4_032,
                        confirmReturnMinPct = 4.0,
                    ),
                    VolumeFlowAggressiveSideRegimeBlock(
                        side = Side.BUY,
                        lookbackCandles = 17_280,
                        returnMinPct = 20.0,
                        confirmLookbackCandles = 4_032,
                        confirmReturnMinPct = 20.0,
                    ),
                ),
        )
}

fun VolumeFlowAggressiveBacktestConfig.executionContract(): VolumeFlowAggressiveExecutionContract =
    VolumeFlowAggressiveExecutionContract(
        riskFraction = riskFraction,
        feeRate = feeRate,
        entrySlippageRate = slippageRate,
        exitSlippageRate = exitSlippageRate,
        fundingRatePer8h = fundingRatePer8h,
        quantityStep = quantityStep,
        minQuantity = minQuantity,
        maxQuantity = maxQuantity,
        maxNotional = maxNotional,
        leverage = leverage,
        liquidationBufferPct = liquidationBufferPct,
    )

fun VolumeFlowAggressiveBacktestConfig.withExecutionContract(
    contract: VolumeFlowAggressiveExecutionContract,
): VolumeFlowAggressiveBacktestConfig =
    copy(
        riskFraction = contract.riskFraction,
        feeRate = contract.feeRate,
        slippageRate = contract.entrySlippageRate,
        exitSlippageRate = contract.exitSlippageRate,
        fundingRatePer8h = contract.fundingRatePer8h,
        quantityStep = contract.quantityStep,
        minQuantity = contract.minQuantity,
        maxQuantity = contract.maxQuantity,
        maxNotional = contract.maxNotional,
        leverage = contract.leverage,
        liquidationBufferPct = contract.liquidationBufferPct,
    )

private fun VolumeFlowAggressiveBacktestConfig.normalizedSignalDefinition(): VolumeFlowAggressiveBacktestConfig =
    copy(
        initialEquity = 1.0,
        riskFraction = 0.01,
        feeRate = 0.0,
        slippageRate = 0.0,
        exitSlippageRate = 0.0,
        fundingRatePer8h = 0.0,
        quantityStep = null,
        minQuantity = null,
        maxQuantity = null,
        maxNotional = null,
        leverage = null,
        liquidationBufferPct = 0.0,
    )

private fun Double?.canonicalNumber(): String = this?.let(java.math.BigDecimal::valueOf)?.stripTrailingZeros()?.toPlainString() ?: "null"

data class VolumeFlowAggressiveBacktestReport(
    val engineVersion: String = AGGRESSIVE_BACKTEST_ENGINE_VERSION,
    val fillModelVersion: String = AGGRESSIVE_FILL_MODEL_VERSION,
    val validationStatus: StrategyValidationStatus = StrategyValidationStatus.UNVERIFIED,
    val strategyContractVersion: String,
    val runtimeSignalProfileMatched: Boolean,
    val executionContract: VolumeFlowAggressiveExecutionContract,
    val symbol: Symbol,
    val profileId: String,
    val m5CandleCount: Int,
    val m1CandleCount: Int,
    val warmupCandleCount: Int,
    val executionPathMode: AggressiveExecutionPathMode,
    val startAt: Instant?,
    val endAt: Instant?,
    val initialEquity: Double,
    val finalEquity: Double,
    val netPnl: Double,
    val grossPnl: Double,
    val totalFees: Double,
    val totalFundingPnl: Double,
    val totalSlippageCost: Double,
    val netReturnPct: Double,
    val compoundDailyReturnPct: Double,
    val maxDrawdownPct: Double,
    val tradeCount: Int,
    val activeDays: Int,
    val observedDays: Int,
    val activeDayCoveragePct: Double,
    val skippedSignalCount: Int,
    val skippedDataGapCount: Int,
    val liquidationCount: Int,
    val wins: Int,
    val losses: Int,
    val winRatePct: Double,
    val profitFactor: Double?,
    val rProfitFactor: Double?,
    val averageGrossR: Double,
    val averageNetR: Double,
    val averageCostR: Double,
    val performanceBySide: List<VolumeFlowAggressivePerformanceSlice>,
    val performanceByExitReason: List<VolumeFlowAggressivePerformanceSlice>,
    val performanceBySignalHourUtc: List<VolumeFlowAggressivePerformanceSlice>,
    val performanceByAbsorptionRelativeVolume: List<VolumeFlowAggressivePerformanceSlice>,
    val trades: List<VolumeFlowAggressiveBacktestTrade>,
)

data class VolumeFlowAggressivePerformanceSlice(
    val key: String,
    val tradeCount: Int,
    val wins: Int,
    val losses: Int,
    val netPnl: Double,
    val winRatePct: Double,
    val profitFactor: Double?,
    val rProfitFactor: Double?,
    val averageGrossR: Double,
    val averageNetR: Double,
    val averageCostR: Double,
    val averageMfeR: Double,
    val averageMaeR: Double,
)

data class VolumeFlowAggressiveBacktestTrade(
    val signalAt: Instant,
    val openedAt: Instant,
    val closedAt: Instant,
    val side: Side,
    val exitReason: VolumeFlowExitReason,
    val entryPrice: Double,
    val stopPrice: Double,
    val targetPrice: Double,
    val exitPrice: Double,
    val triggerExitPrice: Double,
    val riskPerUnit: Double,
    val riskFraction: Double,
    val quantity: Double,
    val notional: Double,
    val stopAtr: Double,
    val targetR: Double,
    val rMultipleGross: Double,
    val rMultipleNet: Double,
    val pnl: Double,
    val fees: Double,
    val fundingPnl: Double,
    val slippageCost: Double,
    val holdingMinutes: Long,
    val mfeR: Double,
    val maeR: Double,
    val returnPct: Double,
    val equityAfter: Double,
    val drawdownPct: Double,
    val entryRelativeVolume: Double,
    val entryRangePct: Double,
    val entryBodyRatio: Double,
    val entryCloseLocation: Double,
    val absorptionAt: Instant,
    val absorptionRelativeVolume: Double,
    val clusterVolumeRatio: Double,
    val clusterDisplacementAtr: Double,
    val clusterRangeAtr: Double,
    val breakoutAt: Instant,
    val breakoutSide: Side,
    val breakoutRelativeVolume: Double,
    val breakoutBodyRatio: Double,
    val breakoutDirectionalClose: Double,
    val breakoutDistanceAtr: Double,
    val signalRelativeVolume: Double,
    val signalRangePct: Double,
    val signalBodyRatio: Double,
    val signalCloseLocation: Double,
)
