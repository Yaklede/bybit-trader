package dev.yaklede.bybittrader.engine.backtest

import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import java.time.Instant

data class VolumeFlowBacktestConfig(
    val initialEquity: Double = 10_000.0,
    val riskFraction: Double = 0.0025,
    val feeRate: Double = 0.0006,
    val slippageRate: Double = 0.0002,
    val volumeLookback: Int = 20,
    val relativeVolumeThreshold: Double = 7.0,
    val volumeZScoreThreshold: Double = 1.5,
    val setupRangeLookback: Int = 12,
    val contextVwapLookback: Int = 32,
    val requireContextTrend: Boolean = true,
    val minBodyRatio: Double = 0.45,
    val entryLookaheadM1Candles: Int = 5,
    val entryRetestTolerancePct: Double = 0.0015,
    val maxEstimatedFeeR: Double = 0.2,
    val targetR: Double = 1.2,
    val maxHoldM1Candles: Int = 15,
    val dailyTargetPct: Double = 1.0,
    val dailyStopPct: Double = 1.0,
    val maxTradesPerDay: Int = 5,
    val maxConsecutiveLosses: Int = 3,
) {
    init {
        require(initialEquity > 0.0) { "Initial equity must be positive." }
        require(riskFraction > 0.0 && riskFraction <= 0.02) { "Risk fraction must be between 0 and 0.02." }
        require(feeRate >= 0.0 && feeRate <= 0.01) { "Fee rate must be between 0 and 0.01." }
        require(slippageRate >= 0.0 && slippageRate <= 0.01) { "Slippage rate must be between 0 and 0.01." }
        require(volumeLookback > 1) { "Volume lookback must be greater than 1." }
        require(relativeVolumeThreshold > 1.0) { "Relative volume threshold must be greater than 1." }
        require(volumeZScoreThreshold >= 0.0) { "Volume z-score threshold must not be negative." }
        require(setupRangeLookback > 1) { "Setup range lookback must be greater than 1." }
        require(contextVwapLookback > 1) { "Context VWAP lookback must be greater than 1." }
        require(minBodyRatio in 0.0..1.0) { "Minimum body ratio must be between 0 and 1." }
        require(entryLookaheadM1Candles in 1..30) { "Entry lookahead must be between 1 and 30 candles." }
        require(entryRetestTolerancePct in 0.0..0.02) { "Entry retest tolerance must be between 0 and 0.02." }
        require(maxEstimatedFeeR > 0.0 && maxEstimatedFeeR <= 5.0) {
            "Max estimated fee R must be between 0 and 5."
        }
        require(targetR > 0.0) { "Target R must be positive." }
        require(maxHoldM1Candles > 0) { "Max hold M1 candles must be positive." }
        require(dailyTargetPct > 0.0 && dailyTargetPct <= 10.0) { "Daily target percent must be between 0 and 10." }
        require(dailyStopPct > 0.0 && dailyStopPct <= 10.0) { "Daily stop percent must be between 0 and 10." }
        require(maxTradesPerDay > 0) { "Max trades per day must be positive." }
        require(maxConsecutiveLosses > 0) { "Max consecutive losses must be positive." }
    }
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
    val tradeCount: Int,
    val wins: Int,
    val losses: Int,
    val winRatePct: Double,
    val profitFactor: Double?,
    val expectancyR: Double,
    val maxConsecutiveLosses: Int,
    val activeDays: Int,
    val targetHitDays: Int,
    val stopHitDays: Int,
    val setupCount: Int,
    val rejectedSetupCount: Int,
    val noTradeReasonCounts: Map<String, Int>,
    val trades: List<VolumeFlowBacktestTrade>,
)

data class VolumeFlowBacktestTrade(
    val side: Side,
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
    val exitReason: VolumeFlowExitReason,
    val relativeVolume: Double,
    val volumeZScore: Double,
    val setupBodyRatio: Double,
    val setupCloseLocation: Double,
)

enum class VolumeFlowExitReason {
    TARGET,
    STOP,
    TIME,
}
