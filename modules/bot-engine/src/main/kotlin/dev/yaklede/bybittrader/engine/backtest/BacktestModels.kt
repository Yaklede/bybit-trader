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
    val maxHoldCandles: Int = 16,
) {
    init {
        require(initialEquity > 0.0) { "Initial equity must be positive." }
        require(riskFraction > 0.0 && riskFraction <= 0.05) { "Risk fraction must be between 0 and 0.05." }
        require(feeRate >= 0.0 && feeRate <= 0.01) { "Fee rate must be between 0 and 0.01." }
        require(slippageRate >= 0.0 && slippageRate <= 0.01) { "Slippage rate must be between 0 and 0.01." }
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
    val netPnl: Double,
    val netReturnPct: Double,
    val expectedMonthlyReturnPct: Double?,
    val maxDrawdownPct: Double,
    val trades: List<BacktestTrade>,
    val wins: Int,
    val losses: Int,
    val winRatePct: Double,
    val profitFactor: Double?,
    val expectancyR: Double,
)

data class BacktestTrade(
    val side: Side,
    val entryAt: Instant,
    val exitAt: Instant,
    val entryPrice: Double,
    val exitPrice: Double,
    val quantity: Double,
    val pnl: Double,
    val returnR: Double,
    val exitReason: BacktestExitReason,
)

enum class BacktestExitReason {
    TARGET,
    STOP,
    TIME,
}
