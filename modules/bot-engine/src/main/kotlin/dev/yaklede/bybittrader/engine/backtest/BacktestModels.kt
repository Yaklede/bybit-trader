package dev.yaklede.bybittrader.engine.backtest

import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import java.time.Instant

data class BacktestConfig(
    val initialEquity: Double = 10_000.0,
    val riskFraction: Double = 0.005,
    val feeRate: Double = 0.0006,
    val slippageRate: Double = 0.0002,
    val fundingRatePer8h: Double = 0.0,
    val partialTakeProfitR: Double = 1.0,
    val partialTakeProfitFraction: Double = 0.5,
    val breakevenAfterPartialTakeProfit: Boolean = true,
    val atrTrailingPeriod: Int = 14,
    val atrTrailingMultiplier: Double = 0.0,
    val maxHoldCandles: Int = 16,
) {
    init {
        require(initialEquity > 0.0) { "Initial equity must be positive." }
        require(riskFraction > 0.0 && riskFraction <= 0.05) { "Risk fraction must be between 0 and 0.05." }
        require(feeRate >= 0.0 && feeRate <= 0.01) { "Fee rate must be between 0 and 0.01." }
        require(slippageRate >= 0.0 && slippageRate <= 0.01) { "Slippage rate must be between 0 and 0.01." }
        require(fundingRatePer8h >= -0.01 && fundingRatePer8h <= 0.01) {
            "Funding rate per 8h must be between -0.01 and 0.01."
        }
        require(partialTakeProfitR > 0.0) { "Partial take profit R must be positive." }
        require(partialTakeProfitFraction >= 0.0 && partialTakeProfitFraction < 1.0) {
            "Partial take profit fraction must be between 0 inclusive and 1 exclusive."
        }
        require(atrTrailingPeriod > 1) { "ATR trailing period must be greater than 1." }
        require(atrTrailingMultiplier >= 0.0) { "ATR trailing multiplier must not be negative." }
        require(maxHoldCandles > 0) { "Max hold candles must be positive." }
    }
}

data class BacktestResult(
    val symbol: Symbol,
    val timeframe: Timeframe,
    val candleCount: Int,
    val startAt: Instant?,
    val endAt: Instant?,
    val initialEquity: Double,
    val finalEquity: Double,
    val grossPnl: Double,
    val fees: Double,
    val fundingCost: Double,
    val netPnl: Double,
    val netReturnPct: Double,
    val expectedMonthlyReturnPct: Double?,
    val maxDrawdownPct: Double,
    val trades: List<BacktestTrade>,
    val wins: Int,
    val losses: Int,
    val maxConsecutiveLosses: Int,
    val winRatePct: Double,
    val profitFactor: Double?,
    val expectancyR: Double,
    val evaluatedWindows: Int,
    val acceptedSignals: Int,
    val skippedSignals: Int,
    val noTradeReasonCounts: Map<String, Int>,
)

data class BacktestTrade(
    val side: Side,
    val entryAt: Instant,
    val exitAt: Instant,
    val entryPrice: Double,
    val exitPrice: Double,
    val quantity: Double,
    val remainingQuantity: Double,
    val grossPnl: Double,
    val fees: Double,
    val fundingCost: Double,
    val pnl: Double,
    val returnR: Double,
    val exitReason: BacktestExitReason,
    val partialTakeProfitAt: Instant?,
    val partialExitPrice: Double?,
    val partialQuantity: Double,
)

enum class BacktestExitReason {
    TARGET,
    STOP,
    BREAKEVEN_STOP,
    TRAILING_STOP,
    TIME,
}
