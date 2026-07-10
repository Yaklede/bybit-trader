package dev.yaklede.bybittrader.engine.backtest

import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import java.time.Instant

const val VOLUME_FLOW_BACKTEST_ENGINE_VERSION = "2.0.0"
const val VOLUME_FLOW_FILL_MODEL_VERSION = "causal-next-m1-open-v2"

data class VolumeFlowBacktestConfig(
    val initialEquity: Double = 10_000.0,
    val riskFraction: Double = 0.0075,
    val feeRate: Double = 0.0006,
    val slippageRate: Double = 0.0002,
    val quantityStep: Double? = null,
    val minQuantity: Double? = null,
    val maxQuantity: Double? = null,
    val maxNotional: Double? = null,
    val leverage: Double? = null,
    val liquidationBufferPct: Double = 0.6,
    val setupMode: VolumeFlowSetupMode = VolumeFlowSetupMode.BREAKOUT_CONTINUATION,
    val entryMode: VolumeFlowEntryMode = VolumeFlowEntryMode.RETEST_CONFIRMATION,
    val sideMode: VolumeFlowSideMode = VolumeFlowSideMode.BOTH,
    val setupTimeframe: Timeframe = Timeframe.M5,
    val volumeLookback: Int = 20,
    val relativeVolumeThreshold: Double = 5.0,
    val maxRelativeVolumeThreshold: Double? = null,
    val relativeVolumeRiskThreshold: Double? = null,
    val relativeVolumeRiskMultiplier: Double = 1.0,
    val relativeVolumeRiskMaxTrendMovePct: Double? = null,
    val volumeZScoreThreshold: Double = 1.5,
    val setupRangeLookback: Int = 12,
    val requireM5Vwap: Boolean = false,
    val m5VwapLookback: Int = 12,
    val contextVwapLookback: Int = 32,
    val requireContextVwap: Boolean = true,
    val requireContextTrend: Boolean = true,
    val requireMacroTrendAlignment: Boolean = false,
    val macroTrendLookbackM15Candles: Int = 192,
    val minMacroTrendMovePct: Double = 0.0,
    val minMacroTrendEfficiency: Double? = null,
    val macroTrendEfficiencyRelativeVolumeMin: Double? = null,
    val macroTrendEfficiencyRelativeVolumeMax: Double? = null,
    val macroTrendMismatchRiskMultiplier: Double = 1.0,
    val allowedMarketRegimes: Set<VolumeFlowMarketRegime> = defaultVolumeFlowMarketRegimes,
    val requireRegimeSideAlignment: Boolean = false,
    val minTrendMovePct: Double = 0.003,
    val minTrendEfficiency: Double = 0.35,
    val highVolatilityRangePct: Double = 0.006,
    val contextRangeRiskThresholdPct: Double? = null,
    val contextRangeRiskMultiplier: Double = 1.0,
    val highContextRangeRelativeVolumeThresholdPct: Double? = null,
    val highContextRangeRelativeVolumeMin: Double? = null,
    val highContextRangeRelativeVolumeMacroBypassMovePct: Double? = null,
    val highContextRangeRelativeVolumeMacroBypassEfficiency: Double? = null,
    val maxContextRangePct: Double? = null,
    val minContextQuoteVolume: Double? = null,
    val requireKeyLevelProximity: Boolean = false,
    val keyLevelTolerancePct: Double = 0.0025,
    val avoidRangeMiddle: Boolean = false,
    val minBodyRatio: Double = 0.45,
    val minDirectionalCloseStrength: Double = 0.70,
    val minRejectionWickRatio: Double = 0.25,
    val entryLookaheadM1Candles: Int = 5,
    val entryRetestTolerancePct: Double = 0.0015,
    val minEntryBodyRatio: Double? = null,
    val minEntryRiskPct: Double? = null,
    val maxEntryRiskPct: Double? = null,
    val maxEntryRelativeVolume: Double? = null,
    val maxEstimatedFeeR: Double = 0.2,
    val targetR: Double = 1.2,
    val exitMode: VolumeFlowExitMode = VolumeFlowExitMode.FIXED_TARGET,
    val runnerTrailActivationR: Double = 1.0,
    val runnerTrailDistanceR: Double = 0.5,
    val trendBreakLookbackM1Candles: Int = 5,
    val breakevenTriggerR: Double? = null,
    val followThroughCheckM1Candles: Int? = null,
    val minFollowThroughR: Double? = null,
    val followThroughMinContextRangePct: Double? = null,
    val adverseExitCheckM1Candles: Int? = null,
    val maxAdverseRBeforeExit: Double? = null,
    val minFavorableRBeforeAdverseExit: Double? = null,
    val profitProtectActivationR: Double? = null,
    val profitProtectFloorR: Double? = null,
    val maxHoldM1Candles: Int = 30,
    val dailyTargetPct: Double? = null,
    val dailyStopPct: Double = 1.0,
    val minTradesPerDay: Int = 1,
    val maxTradesPerDay: Int = 5,
    val maxConsecutiveLosses: Int = 3,
    val flowLookbackM1Candles: Int = 5,
    val takerFlowDirectionMode: TakerFlowDirectionMode = TakerFlowDirectionMode.ALIGN_WITH_SIDE,
    val minDirectionalTakerImbalance: Double? = null,
    val orderBookImbalanceDirectionMode: OrderBookImbalanceDirectionMode = OrderBookImbalanceDirectionMode.ALIGN_WITH_SIDE,
    val minDirectionalOrderBookImbalance: Double? = null,
    val maxMeanOrderBookSpreadBps: Double? = null,
    val minOpenInterestChangePct: Double? = null,
    val openInterestLookbackSnapshots: Int = 3,
    val maxAbsPremiumIndex: Double? = null,
    val maxAbsFundingRate: Double? = null,
    val maxFlowDataStalenessMinutes: Long = 10,
    val maxFundingDataStalenessMinutes: Long = 480,
) {
    init {
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
        require(setupTimeframe == Timeframe.M1 || setupTimeframe == Timeframe.M5) {
            "Setup timeframe must be M1 or M5."
        }
        require(volumeLookback > 1) { "Volume lookback must be greater than 1." }
        require(relativeVolumeThreshold > 1.0) { "Relative volume threshold must be greater than 1." }
        require(maxRelativeVolumeThreshold == null || maxRelativeVolumeThreshold > relativeVolumeThreshold) {
            "Maximum relative volume threshold must be null or greater than relative volume threshold."
        }
        require(relativeVolumeRiskThreshold == null || relativeVolumeRiskThreshold > 1.0) {
            "Relative volume risk threshold must be null or greater than 1."
        }
        require(relativeVolumeRiskMultiplier > 0.0 && relativeVolumeRiskMultiplier <= 1.0) {
            "Relative volume risk multiplier must be between 0 and 1."
        }
        require(relativeVolumeRiskMaxTrendMovePct == null || relativeVolumeRiskMaxTrendMovePct in 0.0..0.50) {
            "Relative volume risk maximum trend move percent must be null or between 0 and 0.50."
        }
        require(volumeZScoreThreshold >= 0.0) { "Volume z-score threshold must not be negative." }
        require(setupRangeLookback > 1) { "Setup range lookback must be greater than 1." }
        require(m5VwapLookback > 1) { "M5 VWAP lookback must be greater than 1." }
        require(contextVwapLookback > 1) { "Context VWAP lookback must be greater than 1." }
        require(macroTrendLookbackM15Candles in 16..2_000) {
            "Macro trend lookback must be between 16 and 2000 M15 candles."
        }
        require(minMacroTrendMovePct >= 0.0 && minMacroTrendMovePct <= 0.50) {
            "Minimum macro trend move percent must be between 0 and 0.50."
        }
        require(minMacroTrendEfficiency == null || minMacroTrendEfficiency in 0.0..1.0) {
            "Minimum macro trend efficiency must be null or between 0 and 1."
        }
        require(macroTrendEfficiencyRelativeVolumeMin == null || macroTrendEfficiencyRelativeVolumeMin > 1.0) {
            "Macro trend efficiency relative volume minimum must be null or greater than 1."
        }
        require(macroTrendEfficiencyRelativeVolumeMax == null || macroTrendEfficiencyRelativeVolumeMax > 1.0) {
            "Macro trend efficiency relative volume maximum must be null or greater than 1."
        }
        require(
            macroTrendEfficiencyRelativeVolumeMin == null ||
                macroTrendEfficiencyRelativeVolumeMax == null ||
                macroTrendEfficiencyRelativeVolumeMin < macroTrendEfficiencyRelativeVolumeMax,
        ) {
            "Macro trend efficiency relative volume minimum must be less than maximum."
        }
        require(macroTrendMismatchRiskMultiplier > 0.0 && macroTrendMismatchRiskMultiplier <= 1.0) {
            "Macro trend mismatch risk multiplier must be between 0 and 1."
        }
        require(allowedMarketRegimes.isNotEmpty()) { "Allowed market regimes must not be empty." }
        require(minTrendMovePct >= 0.0 && minTrendMovePct <= 0.05) {
            "Minimum trend move percent must be between 0 and 0.05."
        }
        require(minTrendEfficiency >= 0.0 && minTrendEfficiency <= 1.0) {
            "Minimum trend efficiency must be between 0 and 1."
        }
        require(highVolatilityRangePct > 0.0 && highVolatilityRangePct <= 0.05) {
            "High volatility range percent must be between 0 and 0.05."
        }
        require(contextRangeRiskThresholdPct == null || contextRangeRiskThresholdPct in 0.0..0.10) {
            "Context range risk threshold percent must be null or between 0 and 0.10."
        }
        require(contextRangeRiskMultiplier > 0.0 && contextRangeRiskMultiplier <= 1.0) {
            "Context range risk multiplier must be between 0 and 1."
        }
        require(
            (highContextRangeRelativeVolumeThresholdPct == null) ==
                (highContextRangeRelativeVolumeMin == null),
        ) {
            "High context range relative volume threshold and minimum must both be null or both be set."
        }
        require(
            highContextRangeRelativeVolumeThresholdPct == null ||
                highContextRangeRelativeVolumeThresholdPct in 0.0..0.10,
        ) {
            "High context range relative volume threshold percent must be null or between 0 and 0.10."
        }
        require(highContextRangeRelativeVolumeMin == null || highContextRangeRelativeVolumeMin > 1.0) {
            "High context range relative volume minimum must be null or greater than 1."
        }
        require(
            (highContextRangeRelativeVolumeMacroBypassMovePct == null) ==
                (highContextRangeRelativeVolumeMacroBypassEfficiency == null),
        ) {
            "High context range relative volume macro bypass move and efficiency must both be null or both be set."
        }
        require(
            highContextRangeRelativeVolumeMacroBypassMovePct == null ||
                highContextRangeRelativeVolumeMacroBypassMovePct in 0.0..0.50,
        ) {
            "High context range relative volume macro bypass move percent must be null or between 0 and 0.50."
        }
        require(
            highContextRangeRelativeVolumeMacroBypassEfficiency == null ||
                highContextRangeRelativeVolumeMacroBypassEfficiency in 0.0..1.0,
        ) {
            "High context range relative volume macro bypass efficiency must be null or between 0 and 1."
        }
        require(maxContextRangePct == null || maxContextRangePct in 0.0..0.10) {
            "Maximum context range percent must be null or between 0 and 0.10."
        }
        require(minContextQuoteVolume == null || minContextQuoteVolume > 0.0) {
            "Minimum context quote volume must be null or positive."
        }
        require(keyLevelTolerancePct >= 0.0 && keyLevelTolerancePct <= 0.02) {
            "Key level tolerance must be between 0 and 0.02."
        }
        require(minBodyRatio in 0.0..1.0) { "Minimum body ratio must be between 0 and 1." }
        require(minDirectionalCloseStrength in 0.5..1.0) {
            "Minimum directional close strength must be between 0.5 and 1."
        }
        require(minRejectionWickRatio in 0.0..1.0) { "Minimum rejection wick ratio must be between 0 and 1." }
        require(entryLookaheadM1Candles in 1..30) { "Entry lookahead must be between 1 and 30 candles." }
        require(entryRetestTolerancePct in 0.0..0.02) { "Entry retest tolerance must be between 0 and 0.02." }
        require(minEntryBodyRatio == null || minEntryBodyRatio in 0.0..1.0) {
            "Minimum entry body ratio must be null or between 0 and 1."
        }
        require(minEntryRiskPct == null || minEntryRiskPct in 0.0..0.05) {
            "Minimum entry risk percent must be null or between 0 and 0.05."
        }
        require(maxEntryRiskPct == null || maxEntryRiskPct in 0.0..0.05) {
            "Maximum entry risk percent must be null or between 0 and 0.05."
        }
        require(minEntryRiskPct == null || maxEntryRiskPct == null || minEntryRiskPct <= maxEntryRiskPct) {
            "Minimum entry risk percent must be less than or equal to maximum entry risk percent."
        }
        require(maxEntryRelativeVolume == null || maxEntryRelativeVolume > 1.0) {
            "Maximum entry relative volume must be null or greater than 1."
        }
        require(maxEstimatedFeeR > 0.0 && maxEstimatedFeeR <= 5.0) {
            "Max estimated fee R must be between 0 and 5."
        }
        require(targetR > 0.0) { "Target R must be positive." }
        require(runnerTrailActivationR > 0.0) { "Runner trail activation R must be positive." }
        require(runnerTrailDistanceR > 0.0) { "Runner trail distance R must be positive." }
        require(trendBreakLookbackM1Candles in 2..60) {
            "Trend break lookback must be between 2 and 60 candles."
        }
        require(breakevenTriggerR == null || breakevenTriggerR > 0.0) { "Breakeven trigger R must be positive." }
        require((followThroughCheckM1Candles == null) == (minFollowThroughR == null)) {
            "Follow-through check candles and minimum R must both be null or both be set."
        }
        require(followThroughCheckM1Candles == null || followThroughCheckM1Candles in 1..60) {
            "Follow-through check candles must be null or between 1 and 60."
        }
        require(minFollowThroughR == null || minFollowThroughR > 0.0 && minFollowThroughR <= 5.0) {
            "Minimum follow-through R must be null or between 0 and 5."
        }
        require(followThroughMinContextRangePct == null || followThroughMinContextRangePct in 0.0..0.10) {
            "Follow-through minimum context range percent must be null or between 0 and 0.10."
        }
        val adverseExitFields =
            listOf(
                adverseExitCheckM1Candles,
                maxAdverseRBeforeExit,
                minFavorableRBeforeAdverseExit,
            )
        require(adverseExitFields.all { it == null } || adverseExitFields.all { it != null }) {
            "Adverse exit check candles, maximum adverse R, and minimum favorable R must all be null or all be set."
        }
        require(adverseExitCheckM1Candles == null || adverseExitCheckM1Candles in 1..60) {
            "Adverse exit check candles must be null or between 1 and 60."
        }
        require(maxAdverseRBeforeExit == null || maxAdverseRBeforeExit > 0.0 && maxAdverseRBeforeExit <= 5.0) {
            "Maximum adverse R before exit must be null or between 0 and 5."
        }
        require(
            minFavorableRBeforeAdverseExit == null ||
                (
                    minFavorableRBeforeAdverseExit >= 0.0 &&
                        minFavorableRBeforeAdverseExit <= 5.0
                ),
        ) {
            "Minimum favorable R before adverse exit must be null or between 0 and 5."
        }
        require((profitProtectActivationR == null) == (profitProtectFloorR == null)) {
            "Profit protect activation R and floor R must both be null or both be set."
        }
        require(
            profitProtectActivationR == null ||
                (
                    profitProtectActivationR > 0.0 &&
                        profitProtectActivationR <= 5.0
                ),
        ) {
            "Profit protect activation R must be null or between 0 and 5."
        }
        require(
            profitProtectFloorR == null ||
                (
                    profitProtectFloorR >= -1.0 &&
                        profitProtectFloorR <= 5.0
                ),
        ) {
            "Profit protect floor R must be null or between -1 and 5."
        }
        require(
            profitProtectActivationR == null ||
                profitProtectFloorR == null ||
                profitProtectFloorR < profitProtectActivationR,
        ) {
            "Profit protect floor R must be less than activation R."
        }
        require(maxHoldM1Candles > 0) { "Max hold M1 candles must be positive." }
        require(followThroughCheckM1Candles == null || followThroughCheckM1Candles <= maxHoldM1Candles) {
            "Follow-through check candles must be less than or equal to max hold M1 candles."
        }
        require(adverseExitCheckM1Candles == null || adverseExitCheckM1Candles <= maxHoldM1Candles) {
            "Adverse exit check candles must be less than or equal to max hold M1 candles."
        }
        require(dailyTargetPct == null || dailyTargetPct > 0.0 && dailyTargetPct <= 10.0) {
            "Daily target percent must be null or between 0 and 10."
        }
        require(dailyStopPct > 0.0 && dailyStopPct <= 10.0) { "Daily stop percent must be between 0 and 10." }
        require(minTradesPerDay > 0) { "Min trades per day must be positive." }
        require(maxTradesPerDay > 0) { "Max trades per day must be positive." }
        require(minTradesPerDay <= maxTradesPerDay) { "Min trades per day must be less than or equal to max trades per day." }
        require(maxConsecutiveLosses > 0) { "Max consecutive losses must be positive." }
        require(flowLookbackM1Candles in 1..120) { "Flow lookback M1 candles must be between 1 and 120." }
        require(minDirectionalTakerImbalance == null || flowLookbackM1Candles % 5 == 0) {
            "Flow lookback M1 candles must be a multiple of 5 when taker flow filtering is enabled."
        }
        require(minDirectionalTakerImbalance == null || minDirectionalTakerImbalance in 0.0..1.0) {
            "Minimum directional taker imbalance must be null or between 0 and 1."
        }
        require(
            !orderBookFlowFiltersEnabled() ||
                flowLookbackM1Candles % 5 == 0,
        ) {
            "Flow lookback M1 candles must be a multiple of 5 when order book flow filtering is enabled."
        }
        require(minDirectionalOrderBookImbalance == null || minDirectionalOrderBookImbalance in 0.0..1.0) {
            "Minimum directional order book imbalance must be null or between 0 and 1."
        }
        require(maxMeanOrderBookSpreadBps == null || maxMeanOrderBookSpreadBps >= 0.0) {
            "Maximum mean order book spread must be null or non-negative."
        }
        require(minOpenInterestChangePct == null || minOpenInterestChangePct > 0.0) {
            "Minimum open interest change percent must be null or positive."
        }
        require(openInterestLookbackSnapshots in 2..1_000) {
            "Open interest lookback snapshots must be between 2 and 1000."
        }
        require(maxAbsPremiumIndex == null || maxAbsPremiumIndex >= 0.0) {
            "Maximum absolute premium index must be null or non-negative."
        }
        require(maxAbsFundingRate == null || maxAbsFundingRate >= 0.0) {
            "Maximum absolute funding rate must be null or non-negative."
        }
        require(maxFlowDataStalenessMinutes > 0) {
            "Maximum flow data staleness minutes must be positive."
        }
        require(maxFundingDataStalenessMinutes > 0) {
            "Maximum funding data staleness minutes must be positive."
        }
        require(!flowFiltersEnabled() || setupTimeframe == Timeframe.M5) {
            "Flow filters currently require M5 setup timeframe."
        }
    }
}

fun VolumeFlowBacktestConfig.flowFiltersEnabled(): Boolean = historicalFlowFiltersEnabled() || orderBookFlowFiltersEnabled()

fun VolumeFlowBacktestConfig.historicalFlowFiltersEnabled(): Boolean =
    minDirectionalTakerImbalance != null ||
        minOpenInterestChangePct != null ||
        maxAbsPremiumIndex != null ||
        maxAbsFundingRate != null

fun VolumeFlowBacktestConfig.orderBookFlowFiltersEnabled(): Boolean =
    minDirectionalOrderBookImbalance != null || maxMeanOrderBookSpreadBps != null

val defaultVolumeFlowMarketRegimes: Set<VolumeFlowMarketRegime> =
    setOf(
        VolumeFlowMarketRegime.TREND_UP,
        VolumeFlowMarketRegime.TREND_DOWN,
        VolumeFlowMarketRegime.RANGE,
        VolumeFlowMarketRegime.HIGH_VOLATILITY_CHOP,
        VolumeFlowMarketRegime.UNKNOWN,
    )

enum class VolumeFlowSetupMode {
    BREAKOUT_CONTINUATION,
    VOLUME_FOLLOW_THROUGH_CONTINUATION,
    FAILED_BREAK_REVERSAL,
    VOLUME_REJECTION_REVERSAL,
}

enum class VolumeFlowEntryMode {
    RETEST_CONFIRMATION,
    CLOSE_CONFIRMATION,
    SETUP_CLOSE_CONFIRMATION,
}

enum class VolumeFlowSideMode {
    BOTH,
    LONG_ONLY,
    SHORT_ONLY,
}

enum class VolumeFlowExitMode {
    FIXED_TARGET,
    RUNNER,
    TREND_BREAK,
}

enum class TakerFlowDirectionMode {
    ALIGN_WITH_SIDE,
    OPPOSE_SIDE,
}

enum class OrderBookImbalanceDirectionMode {
    ALIGN_WITH_SIDE,
    OPPOSE_SIDE,
}

enum class VolumeFlowMarketRegime {
    TREND_UP,
    TREND_DOWN,
    RANGE,
    HIGH_VOLATILITY_CHOP,
    UNKNOWN,
}

enum class VolumeFlowKeyLevelType {
    RANGE_HIGH,
    RANGE_LOW,
    RANGE_MIDDLE,
    RANGE_INTERIOR,
    UNKNOWN,
}

enum class VolumeFlowVolumePattern {
    BREAKOUT_ACCEPTANCE,
    FAILED_BREAK,
    CLIMAX_REVERSAL,
}

data class VolumeFlowBacktestReport(
    val engineVersion: String = VOLUME_FLOW_BACKTEST_ENGINE_VERSION,
    val fillModelVersion: String = VOLUME_FLOW_FILL_MODEL_VERSION,
    val validationStatus: StrategyValidationStatus = StrategyValidationStatus.UNVERIFIED,
    val symbol: Symbol,
    val m1CandleCount: Int,
    val m5CandleCount: Int,
    val m15CandleCount: Int,
    val startAt: Instant?,
    val endAt: Instant?,
    val initialEquity: Double,
    val finalEquity: Double,
    val netPnl: Double,
    val netReturnPct: Double,
    val maxDrawdownPct: Double,
    val markToMarketMaxDrawdownPct: Double,
    val averageMaxFavorableExcursionR: Double,
    val averageMaxAdverseExcursionR: Double,
    val averageMfeCapturePct: Double?,
    val tradeCount: Int,
    val wins: Int,
    val losses: Int,
    val liquidationCount: Int,
    val winRatePct: Double,
    val profitFactor: Double?,
    val expectancyR: Double,
    val averageWinR: Double,
    val averageLossR: Double,
    val payoffRatio: Double?,
    val breakevenWinRatePct: Double?,
    val winRateEdgePct: Double?,
    val maxConsecutiveLosses: Int,
    val observedDays: Int,
    val activeDays: Int,
    val activeDayCoveragePct: Double,
    val averageTradesPerDay: Double,
    val averageTradesPerActiveDay: Double,
    val tradeFrequencyTargetDays: Int,
    val tradeFrequencyTargetPct: Double,
    val belowMinTradeDays: Int,
    val aboveMaxTradeDays: Int,
    val targetHitDays: Int,
    val stopHitDays: Int,
    val setupCount: Int,
    val rejectedSetupCount: Int,
    val noTradeReasonCounts: Map<String, Int>,
    val flowFilterEnabled: Boolean = false,
    val performanceBySetupMode: List<VolumeFlowTagSummary>,
    val performanceBySide: List<VolumeFlowTagSummary>,
    val performanceByExitReason: List<VolumeFlowTagSummary>,
    val performanceByMarketRegime: List<VolumeFlowTagSummary>,
    val performanceByVolumePattern: List<VolumeFlowTagSummary>,
    val trades: List<VolumeFlowBacktestTrade>,
)

data class VolumeFlowTagSummary(
    val tag: String,
    val tradeCount: Int,
    val netPnl: Double,
    val winRatePct: Double,
    val profitFactor: Double?,
    val expectancyR: Double,
    val averageWinR: Double,
    val averageLossR: Double,
    val payoffRatio: Double?,
    val breakevenWinRatePct: Double?,
    val winRateEdgePct: Double?,
    val averageMaxFavorableExcursionR: Double,
    val averageMaxAdverseExcursionR: Double,
    val averageMfeCapturePct: Double?,
)

data class VolumeFlowBacktestTrade(
    val side: Side,
    val setupMode: VolumeFlowSetupMode,
    val setupAt: Instant,
    val entryAt: Instant,
    val exitAt: Instant,
    val entryPrice: Double,
    val stopPrice: Double,
    val targetPrice: Double,
    val exitPrice: Double,
    val quantity: Double,
    val grossPnl: Double,
    val fees: Double,
    val pnl: Double,
    val returnR: Double,
    val riskMultiplier: Double = 1.0,
    val macroTrendMovePct: Double?,
    val macroTrendEfficiency: Double?,
    val contextTrendMovePct: Double?,
    val contextTrendEfficiency: Double?,
    val contextRangePct: Double?,
    val contextQuoteVolume: Double?,
    val entryDelayM1Candles: Int,
    val entryBodyRatio: Double,
    val entryCloseLocation: Double,
    val entryRelativeVolume: Double?,
    val entryVolumeZScore: Double?,
    val entryRiskPct: Double,
    val maxFavorableExcursionR: Double,
    val maxAdverseExcursionR: Double,
    val mfeCapturePct: Double?,
    val maxFavorablePrice: Double,
    val maxAdversePrice: Double,
    val maxUnrealizedProfitPct: Double,
    val maxUnrealizedDrawdownPct: Double,
    val exitReason: VolumeFlowExitReason,
    val marketRegime: VolumeFlowMarketRegime,
    val keyLevelType: VolumeFlowKeyLevelType,
    val keyLevelDistancePct: Double,
    val volumePattern: VolumeFlowVolumePattern,
    val relativeVolume: Double,
    val volumeZScore: Double,
    val setupBodyRatio: Double,
    val setupCloseLocation: Double,
    val flowMetrics: VolumeFlowFilterMetrics? = null,
)

data class VolumeFlowFilterMetrics(
    val directionalTakerImbalance: Double?,
    val directionalOrderBookImbalance: Double?,
    val meanOrderBookSpreadBps: Double?,
    val openInterestChangePct: Double?,
    val premiumIndex: Double?,
    val fundingRate: Double?,
)

enum class VolumeFlowExitReason {
    TARGET,
    TRAILING_STOP,
    TREND_BREAK,
    STOP,
    LIQUIDATION,
    BREAKEVEN_STOP,
    FOLLOW_THROUGH_FAIL,
    ADVERSE_INVALIDATION,
    PROFIT_PROTECT,
    TIME,
}
