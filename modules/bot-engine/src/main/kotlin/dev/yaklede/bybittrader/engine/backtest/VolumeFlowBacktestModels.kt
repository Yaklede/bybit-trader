package dev.yaklede.bybittrader.engine.backtest

import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import java.time.Instant

data class VolumeFlowBacktestConfig(
    val initialEquity: Double = 10_000.0,
    val riskFraction: Double = 0.0075,
    val feeRate: Double = 0.0006,
    val slippageRate: Double = 0.0002,
    val setupMode: VolumeFlowSetupMode = VolumeFlowSetupMode.BREAKOUT_CONTINUATION,
    val entryMode: VolumeFlowEntryMode = VolumeFlowEntryMode.RETEST_CONFIRMATION,
    val sideMode: VolumeFlowSideMode = VolumeFlowSideMode.BOTH,
    val setupTimeframe: Timeframe = Timeframe.M5,
    val volumeLookback: Int = 20,
    val relativeVolumeThreshold: Double = 5.0,
    val volumeZScoreThreshold: Double = 1.5,
    val setupRangeLookback: Int = 12,
    val requireM5Vwap: Boolean = false,
    val m5VwapLookback: Int = 12,
    val contextVwapLookback: Int = 32,
    val requireContextVwap: Boolean = true,
    val requireContextTrend: Boolean = true,
    val allowedMarketRegimes: Set<VolumeFlowMarketRegime> = defaultVolumeFlowMarketRegimes,
    val requireRegimeSideAlignment: Boolean = false,
    val minTrendMovePct: Double = 0.003,
    val minTrendEfficiency: Double = 0.35,
    val highVolatilityRangePct: Double = 0.006,
    val requireKeyLevelProximity: Boolean = false,
    val keyLevelTolerancePct: Double = 0.0025,
    val avoidRangeMiddle: Boolean = false,
    val minBodyRatio: Double = 0.45,
    val minDirectionalCloseStrength: Double = 0.70,
    val minRejectionWickRatio: Double = 0.25,
    val entryLookaheadM1Candles: Int = 5,
    val entryRetestTolerancePct: Double = 0.0015,
    val minEntryRiskPct: Double? = null,
    val maxEntryRiskPct: Double? = null,
    val maxEstimatedFeeR: Double = 0.2,
    val targetR: Double = 1.2,
    val exitMode: VolumeFlowExitMode = VolumeFlowExitMode.FIXED_TARGET,
    val runnerTrailActivationR: Double = 1.0,
    val runnerTrailDistanceR: Double = 0.5,
    val trendBreakLookbackM1Candles: Int = 5,
    val breakevenTriggerR: Double? = null,
    val maxHoldM1Candles: Int = 30,
    val dailyTargetPct: Double? = null,
    val dailyStopPct: Double = 1.0,
    val minTradesPerDay: Int = 1,
    val maxTradesPerDay: Int = 5,
    val maxConsecutiveLosses: Int = 3,
) {
    init {
        require(initialEquity > 0.0) { "Initial equity must be positive." }
        require(riskFraction > 0.0 && riskFraction <= 0.075) { "Risk fraction must be between 0 and 0.075." }
        require(feeRate >= 0.0 && feeRate <= 0.01) { "Fee rate must be between 0 and 0.01." }
        require(slippageRate >= 0.0 && slippageRate <= 0.01) { "Slippage rate must be between 0 and 0.01." }
        require(setupTimeframe == Timeframe.M1 || setupTimeframe == Timeframe.M5) {
            "Setup timeframe must be M1 or M5."
        }
        require(volumeLookback > 1) { "Volume lookback must be greater than 1." }
        require(relativeVolumeThreshold > 1.0) { "Relative volume threshold must be greater than 1." }
        require(volumeZScoreThreshold >= 0.0) { "Volume z-score threshold must not be negative." }
        require(setupRangeLookback > 1) { "Setup range lookback must be greater than 1." }
        require(m5VwapLookback > 1) { "M5 VWAP lookback must be greater than 1." }
        require(contextVwapLookback > 1) { "Context VWAP lookback must be greater than 1." }
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
        require(minEntryRiskPct == null || minEntryRiskPct in 0.0..0.05) {
            "Minimum entry risk percent must be null or between 0 and 0.05."
        }
        require(maxEntryRiskPct == null || maxEntryRiskPct in 0.0..0.05) {
            "Maximum entry risk percent must be null or between 0 and 0.05."
        }
        require(minEntryRiskPct == null || maxEntryRiskPct == null || minEntryRiskPct <= maxEntryRiskPct) {
            "Minimum entry risk percent must be less than or equal to maximum entry risk percent."
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
        require(maxHoldM1Candles > 0) { "Max hold M1 candles must be positive." }
        require(dailyTargetPct == null || dailyTargetPct > 0.0 && dailyTargetPct <= 10.0) {
            "Daily target percent must be null or between 0 and 10."
        }
        require(dailyStopPct > 0.0 && dailyStopPct <= 10.0) { "Daily stop percent must be between 0 and 10." }
        require(minTradesPerDay > 0) { "Min trades per day must be positive." }
        require(maxTradesPerDay > 0) { "Max trades per day must be positive." }
        require(minTradesPerDay <= maxTradesPerDay) { "Min trades per day must be less than or equal to max trades per day." }
        require(maxConsecutiveLosses > 0) { "Max consecutive losses must be positive." }
    }
}

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
)

enum class VolumeFlowExitReason {
    TARGET,
    TRAILING_STOP,
    TREND_BREAK,
    STOP,
    TIME,
}
