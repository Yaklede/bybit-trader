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
    val duplicateSignalLookback: Int = 50,
) {
    init {
        require(initialEquity > BigDecimal.ZERO) { "Initial equity must be positive." }
        require(riskFraction > BigDecimal.ZERO && riskFraction <= BigDecimal("0.20")) {
            "Risk fraction must be between 0 and 0.20."
        }
        require(feeRate >= BigDecimal.ZERO && feeRate <= BigDecimal("0.01")) {
            "Fee rate must be between 0 and 0.01."
        }
        require(duplicateSignalLookback in 1..100) { "Duplicate signal lookback must be between 1 and 100." }
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
)

enum class PaperEvaluationStatus {
    SKIPPED_BY_MODE,
    NO_TRADE,
    REJECTED,
    FILLED,
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
