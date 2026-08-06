package dev.yaklede.bybittrader.engine.paper

import dev.yaklede.bybittrader.domain.OrderStatus
import dev.yaklede.bybittrader.domain.OrderType
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import java.math.BigDecimal
import java.time.Instant

data class PaperTradingConfig(
    val initialEquity: BigDecimal = BigDecimal("10000"),
    val riskFraction: BigDecimal = BigDecimal("0.005"),
    val feeRate: BigDecimal = BigDecimal("0.0006"),
    val entrySlippageRate: BigDecimal = BigDecimal("0.0002"),
    val exitSlippageRate: BigDecimal = entrySlippageRate,
    val fundingRatePer8h: BigDecimal = BigDecimal.ZERO,
    val partialTakeProfitR: BigDecimal = BigDecimal.ONE,
    val partialTakeProfitFraction: BigDecimal = BigDecimal("0.5"),
    val breakevenAfterPartialTakeProfit: Boolean = true,
    val atrTrailingPeriod: Int = 14,
    val atrTrailingMultiplier: BigDecimal = BigDecimal.ZERO,
    val fixedTargetEnabled: Boolean = true,
    val maxHoldCandles: Int = 16,
    val maxTradesPerUtcDay: Int? = null,
    val minimumEntryRiskFraction: BigDecimal? = null,
    val maximumEntryRiskFraction: BigDecimal? = null,
) {
    init {
        require(initialEquity > BigDecimal.ZERO) { "Initial equity must be positive." }
        require(riskFraction > BigDecimal.ZERO && riskFraction <= BigDecimal("0.05")) {
            "Risk fraction must be between 0 and 0.05."
        }
        require(feeRate >= BigDecimal.ZERO && feeRate <= BigDecimal("0.01")) {
            "Fee rate must be between 0 and 0.01."
        }
        require(entrySlippageRate >= BigDecimal.ZERO && entrySlippageRate <= BigDecimal("0.01")) {
            "Entry slippage rate must be between 0 and 0.01."
        }
        require(exitSlippageRate >= BigDecimal.ZERO && exitSlippageRate <= BigDecimal("0.01")) {
            "Exit slippage rate must be between 0 and 0.01."
        }
        require(fundingRatePer8h.abs() <= BigDecimal("0.01")) {
            "Funding rate per 8h must be between -0.01 and 0.01."
        }
        require(partialTakeProfitR > BigDecimal.ZERO) { "Partial take-profit R must be positive." }
        require(partialTakeProfitFraction >= BigDecimal.ZERO && partialTakeProfitFraction < BigDecimal.ONE) {
            "Partial take-profit fraction must be between 0 inclusive and 1 exclusive."
        }
        require(atrTrailingPeriod > 1) { "ATR trailing period must be greater than one." }
        require(atrTrailingMultiplier >= BigDecimal.ZERO) { "ATR trailing multiplier must not be negative." }
        require(maxHoldCandles > 0) { "Maximum hold candles must be positive." }
        require(maxTradesPerUtcDay == null || maxTradesPerUtcDay > 0) {
            "Maximum daily trades must be positive when configured."
        }
        require(minimumEntryRiskFraction == null || minimumEntryRiskFraction > BigDecimal.ZERO) {
            "Minimum entry risk must be positive when configured."
        }
        require(maximumEntryRiskFraction == null || maximumEntryRiskFraction > BigDecimal.ZERO) {
            "Maximum entry risk must be positive when configured."
        }
        require(
            minimumEntryRiskFraction == null ||
                maximumEntryRiskFraction == null ||
                minimumEntryRiskFraction <= maximumEntryRiskFraction,
        ) { "Minimum entry risk must not exceed maximum entry risk." }
    }
}

data class PaperSignalRecord(
    val id: Long = 0,
    val strategy: String,
    val symbol: Symbol,
    val side: Side,
    val score: Int,
    val grade: String,
    val reasonCodes: List<String>,
    val accepted: Boolean,
    val rejectionReason: String?,
    val createdAt: Instant,
)

data class PaperOrderRecord(
    val id: Long = 0,
    val exchangeOrderId: String? = null,
    val clientOrderId: String,
    val signalId: Long,
    val side: Side,
    val orderType: OrderType,
    val orderStatus: OrderStatus,
    val intendedRisk: BigDecimal,
    val createdAt: Instant,
)

data class PaperFillRecord(
    val id: Long = 0,
    val orderId: Long,
    val fillPrice: BigDecimal,
    val quantity: BigDecimal,
    val fee: BigDecimal,
    val liquidityRole: String?,
    val filledAt: Instant,
)

data class PaperPositionRecord(
    val id: Long = 0,
    val symbol: Symbol,
    val side: Side,
    val quantity: BigDecimal,
    val entryPrice: BigDecimal,
    val realizedPnl: BigDecimal,
    val unrealizedPnl: BigDecimal,
    val capturedAt: Instant,
)

data class PaperPerformanceSnapshot(
    val id: Long = 0,
    val period: String,
    val netPnl: BigDecimal,
    val profitFactor: BigDecimal?,
    val expectancy: BigDecimal?,
    val maxDrawdown: BigDecimal,
    val capturedAt: Instant,
)

data class PaperTradeRecord(
    val orderId: Long,
    val clientOrderId: String,
    val signalId: Long?,
    val side: Side,
    val orderType: OrderType,
    val orderStatus: OrderStatus,
    val intendedRisk: BigDecimal,
    val orderCreatedAt: Instant,
    val fillId: Long?,
    val fillPrice: BigDecimal?,
    val quantity: BigDecimal?,
    val fee: BigDecimal?,
    val filledAt: Instant?,
)

data class PaperEvaluationResult(
    val symbol: Symbol,
    val timeframe: Timeframe,
    val mode: String,
    val status: PaperEvaluationStatus,
    val evaluatedAt: Instant,
    val candleCount: Int,
    val reasonCodes: List<String>,
    val signalId: Long?,
    val orderId: Long?,
    val fillPrice: BigDecimal?,
    val quantity: BigDecimal?,
    val fee: BigDecimal?,
    val phase: PaperRuntimePhase = PaperRuntimePhase.FLAT,
    val exitReason: String? = null,
    val realizedPnl: BigDecimal? = null,
    val equity: BigDecimal? = null,
)

enum class PaperEvaluationStatus {
    SKIPPED_BY_MODE,
    NO_TRADE,
    ENTRY_PENDING,
    REJECTED,
    FILLED,
    POSITION_UPDATED,
    CLOSED,
}

interface PaperTradingReportStore {
    suspend fun latestPerformanceSummary(): PaperPerformanceSnapshot?

    suspend fun recentSignals(limit: Int): List<PaperSignalRecord>

    suspend fun recentTrades(limit: Int): List<PaperTradeRecord>
}

interface PaperTradingStore : PaperTradingReportStore {
    suspend fun recordSignal(signal: PaperSignalRecord): Long

    suspend fun recordOrder(order: PaperOrderRecord): Long

    suspend fun recordFill(fill: PaperFillRecord): Long

    suspend fun recordPosition(position: PaperPositionRecord): Long

    suspend fun recordPerformanceSnapshot(snapshot: PaperPerformanceSnapshot): Long
}

object EmptyPaperTradingReportStore : PaperTradingReportStore {
    override suspend fun latestPerformanceSummary(): PaperPerformanceSnapshot? = null

    override suspend fun recentSignals(limit: Int): List<PaperSignalRecord> = emptyList()

    override suspend fun recentTrades(limit: Int): List<PaperTradeRecord> = emptyList()
}
