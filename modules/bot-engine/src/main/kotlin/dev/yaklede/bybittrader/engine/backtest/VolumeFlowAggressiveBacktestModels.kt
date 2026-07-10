package dev.yaklede.bybittrader.engine.backtest

import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import java.time.Instant

const val AGGRESSIVE_BACKTEST_ENGINE_VERSION = "2.0.0"
const val AGGRESSIVE_FILL_MODEL_VERSION = "causal-next-open-v1"

enum class StrategyValidationStatus {
    UNVERIFIED,
    VERIFIED,
}

data class VolumeFlowAggressiveBacktestConfig(
    val profileId: String = "absa_final_us_v1",
    val initialEquity: Double = 1_000_000.0,
    val riskFraction: Double = 0.055,
    val feeRate: Double = 0.0006,
    val slippageRate: Double = 0.0002,
    val quantityStep: Double? = null,
    val minQuantity: Double? = null,
    val maxQuantity: Double? = null,
    val maxNotional: Double? = null,
    val leverage: Double? = null,
    val liquidationBufferPct: Double = 0.6,
    val sessionHoursUtc: Set<Int> = setOf(13, 14, 15, 16, 17, 18, 19, 20, 21),
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
) {
    init {
        require(profileId.isNotBlank()) { "Aggressive profile id must not be blank." }
        require(initialEquity > 0.0) { "Initial equity must be positive." }
        require(riskFraction > 0.0 && riskFraction <= 0.20) { "Risk fraction must be between 0 and 0.20." }
        require(feeRate >= 0.0 && feeRate <= 0.01) { "Fee rate must be between 0 and 0.01." }
        require(slippageRate >= 0.0 && slippageRate <= 0.01) { "Slippage rate must be between 0 and 0.01." }
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
        require(maxHoldCandles in 1..288) { "Max hold candles must be between 1 and 288." }
        require(maxTradesPerDay > 0) { "Max trades per day must be positive." }
    }
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

data class VolumeFlowAggressiveBacktestReport(
    val engineVersion: String = AGGRESSIVE_BACKTEST_ENGINE_VERSION,
    val fillModelVersion: String = AGGRESSIVE_FILL_MODEL_VERSION,
    val validationStatus: StrategyValidationStatus = StrategyValidationStatus.UNVERIFIED,
    val symbol: Symbol,
    val profileId: String,
    val m5CandleCount: Int,
    val startAt: Instant?,
    val endAt: Instant?,
    val initialEquity: Double,
    val finalEquity: Double,
    val netPnl: Double,
    val netReturnPct: Double,
    val compoundDailyReturnPct: Double,
    val maxDrawdownPct: Double,
    val tradeCount: Int,
    val activeDays: Int,
    val observedDays: Int,
    val activeDayCoveragePct: Double,
    val skippedSignalCount: Int,
    val wins: Int,
    val losses: Int,
    val winRatePct: Double,
    val profitFactor: Double?,
    val trades: List<VolumeFlowAggressiveBacktestTrade>,
)

data class VolumeFlowAggressiveBacktestTrade(
    val openedAt: Instant,
    val closedAt: Instant,
    val side: Side,
    val exitReason: VolumeFlowExitReason,
    val entryPrice: Double,
    val stopPrice: Double,
    val targetPrice: Double,
    val exitPrice: Double,
    val riskPerUnit: Double,
    val riskFraction: Double,
    val quantity: Double,
    val notional: Double,
    val stopAtr: Double,
    val targetR: Double,
    val rMultipleGross: Double,
    val rMultipleNet: Double,
    val pnl: Double,
    val returnPct: Double,
    val equityAfter: Double,
    val drawdownPct: Double,
    val entryRelativeVolume: Double,
    val entryRangePct: Double,
    val entryBodyRatio: Double,
    val entryCloseLocation: Double,
)
