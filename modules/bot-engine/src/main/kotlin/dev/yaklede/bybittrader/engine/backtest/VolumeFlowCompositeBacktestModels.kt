package dev.yaklede.bybittrader.engine.backtest

import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import java.time.Instant

data class VolumeFlowCompositeBacktestConfig(
    val initialEquity: Double = 10_000.0,
    val dailyTargetPct: Double? = null,
    val dailyStopPct: Double = 1.0,
    val monthlyStopPct: Double? = null,
    val minTradesPerDay: Int = 1,
    val maxTradesPerDay: Int = 5,
    val maxConsecutiveLosses: Int = 3,
    val maxConcurrentPositions: Int = 1,
    val dedupeSameSetupSignals: Boolean = false,
    val portfolioDrawdownThrottlePct: Double? = null,
    val portfolioDrawdownRiskMultiplier: Double = 1.0,
    val portfolioDrawdownCooldownDays: Int = 0,
    val legs: List<VolumeFlowCompositeBacktestLeg>,
) {
    init {
        require(initialEquity > 0.0) { "Initial equity must be positive." }
        require(dailyTargetPct == null || dailyTargetPct > 0.0 && dailyTargetPct <= 10.0) {
            "Daily target percent must be null or between 0 and 10."
        }
        require(dailyStopPct > 0.0 && dailyStopPct <= 10.0) { "Daily stop percent must be between 0 and 10." }
        require(monthlyStopPct == null || monthlyStopPct > 0.0 && monthlyStopPct <= 95.0) {
            "Monthly stop percent must be null or between 0 and 95."
        }
        require(minTradesPerDay > 0) { "Min trades per day must be positive." }
        require(maxTradesPerDay > 0) { "Max trades per day must be positive." }
        require(minTradesPerDay <= maxTradesPerDay) { "Min trades per day must be less than or equal to max trades per day." }
        require(maxConsecutiveLosses > 0) { "Max consecutive losses must be positive." }
        require(maxConcurrentPositions in 1..10) { "Max concurrent positions must be between 1 and 10." }
        require(
            portfolioDrawdownThrottlePct != null ||
                (portfolioDrawdownRiskMultiplier == 1.0 && portfolioDrawdownCooldownDays == 0),
        ) {
            "Portfolio drawdown throttle percent must be set when throttle multiplier or cooldown is configured."
        }
        require(portfolioDrawdownThrottlePct == null || portfolioDrawdownThrottlePct > 0.0 && portfolioDrawdownThrottlePct <= 95.0) {
            "Portfolio drawdown throttle percent must be null or between 0 and 95."
        }
        require(portfolioDrawdownRiskMultiplier > 0.0 && portfolioDrawdownRiskMultiplier <= 1.0) {
            "Portfolio drawdown risk multiplier must be between 0 and 1."
        }
        require(portfolioDrawdownCooldownDays in 0..365) {
            "Portfolio drawdown cooldown days must be between 0 and 365."
        }
        require(legs.isNotEmpty()) { "Composite volume-flow legs must not be empty." }
        require(legs.size <= 10) { "Composite volume-flow legs must be less than or equal to 10." }
        require(legs.map { it.id }.distinct().size == legs.size) { "Composite volume-flow leg ids must be unique." }
    }
}

data class VolumeFlowCompositeBacktestLeg(
    val id: String,
    val config: VolumeFlowBacktestConfig,
) {
    init {
        require(id.isNotBlank()) { "Composite volume-flow leg id must not be blank." }
    }
}

data class VolumeFlowCompositeBacktestReport(
    val symbol: Symbol,
    val m1CandleCount: Int,
    val m5CandleCount: Int,
    val m15CandleCount: Int,
    val startAt: Instant?,
    val endAt: Instant?,
    val replayCoverage: List<VolumeFlowReplayCoverage>,
    val commonReplayWindow: VolumeFlowCommonReplayWindow,
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
    val signalCount: Int,
    val skippedSignalCount: Int,
    val noTradeReasonCounts: Map<String, Int>,
    val performanceByLeg: List<VolumeFlowTagSummary>,
    val performanceByLegExit: List<VolumeFlowLegExitSummary>,
    val performanceBySetupMode: List<VolumeFlowTagSummary>,
    val performanceBySide: List<VolumeFlowTagSummary>,
    val performanceByExitReason: List<VolumeFlowTagSummary>,
    val performanceByMarketRegime: List<VolumeFlowTagSummary>,
    val performanceByVolumePattern: List<VolumeFlowTagSummary>,
    val monthlyPerformance: List<VolumeFlowPeriodSummary>,
    val walkForwardPerformance: List<VolumeFlowPeriodSummary>,
    val equityCurve: List<VolumeFlowEquityCurvePoint>,
    val trades: List<VolumeFlowCompositeBacktestTrade>,
)

data class VolumeFlowReplayCoverage(
    val timeframe: Timeframe,
    val requestedLimit: Int,
    val requestedStartAt: Instant?,
    val requestedEndAt: Instant?,
    val actualCount: Int,
    val startAt: Instant?,
    val endAt: Instant?,
)

data class VolumeFlowCommonReplayWindow(
    val startAt: Instant?,
    val endAt: Instant?,
)

data class VolumeFlowLegExitSummary(
    val legId: String,
    val exitReason: VolumeFlowExitReason,
    val summary: VolumeFlowTagSummary,
)

data class VolumeFlowPeriodSummary(
    val period: String,
    val tradeCount: Int,
    val wins: Int,
    val losses: Int,
    val startingEquity: Double,
    val endingEquity: Double,
    val netPnl: Double,
    val returnPct: Double,
    val maxDrawdownPct: Double,
    val profitFactor: Double?,
    val expectancyR: Double,
    val averageWinR: Double,
    val averageLossR: Double,
    val payoffRatio: Double?,
    val breakevenWinRatePct: Double?,
    val winRateEdgePct: Double?,
)

data class VolumeFlowEquityCurvePoint(
    val sequence: Int,
    val at: Instant,
    val legId: String,
    val side: Side,
    val exitReason: VolumeFlowExitReason,
    val startingEquity: Double,
    val endingEquity: Double,
    val peakEquity: Double,
    val pnl: Double,
    val returnR: Double,
    val realizedDrawdownPct: Double,
    val markToMarketLowEquity: Double,
    val markToMarketDrawdownPct: Double,
    val maxUnrealizedDrawdownPct: Double,
    val maxAdverseExcursionR: Double,
)

data class VolumeFlowCompositeBacktestTrade(
    val legId: String,
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
