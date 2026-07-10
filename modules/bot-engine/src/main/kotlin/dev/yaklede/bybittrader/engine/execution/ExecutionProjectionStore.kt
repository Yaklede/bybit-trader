package dev.yaklede.bybittrader.engine.execution

import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import java.math.BigDecimal
import java.time.Instant

data class ExecutionTradeClosure(
    val id: Long = 0,
    val mode: ExecutionRuntimeMode,
    val symbol: Symbol,
    val side: Side,
    val openedAt: Instant,
    val closedAt: Instant,
    val entryPrice: BigDecimal,
    val exitPrice: BigDecimal,
    val quantity: BigDecimal,
    val grossPnl: BigDecimal,
    val fees: BigDecimal,
    val netPnl: BigDecimal,
    val exitReason: String,
    val exchangeOrderId: String?,
    val clientOrderId: String?,
)

data class LivePerformanceSnapshot(
    val id: Long = 0,
    val mode: ExecutionRuntimeMode,
    val window: LivePerformanceWindow,
    val tradeCount: Int,
    val winRatePct: BigDecimal,
    val grossProfit: BigDecimal,
    val grossLoss: BigDecimal,
    val fees: BigDecimal,
    val netPnl: BigDecimal,
    val profitFactor: BigDecimal?,
    val expectancy: BigDecimal?,
    val maxClosedTradeDrawdownPct: BigDecimal,
    val lastClosedAt: Instant?,
    val capturedAt: Instant,
)

data class PendingExecutionClosureAlert(
    val closure: ExecutionTradeClosure,
    val attemptCount: Int,
    val lastAttemptAt: Instant?,
)

enum class ExecutionRuntimeMode {
    TESTNET,
    LIVE,
}

enum class LivePerformanceWindow {
    SESSION,
    SEVEN_DAYS,
    THIRTY_DAYS,
    ALL,
}

interface ExecutionProjectionStore {
    suspend fun recordTradeClosure(
        closure: ExecutionTradeClosure,
        suppressedAt: Instant? = null,
    ): Long?

    suspend fun closedTrades(
        symbol: Symbol?,
        mode: ExecutionRuntimeMode?,
        limit: Int,
        cursor: Long?,
    ): List<ExecutionTradeClosure>

    suspend fun latestClosedTrade(symbol: Symbol): ExecutionTradeClosure?

    suspend fun performanceClosures(
        mode: ExecutionRuntimeMode,
        closedAtOrAfter: Instant?,
    ): List<ExecutionTradeClosure>

    suspend fun hasClosureHistory(
        mode: ExecutionRuntimeMode,
        symbol: Symbol,
    ): Boolean

    suspend fun pendingClosureAlerts(
        mode: ExecutionRuntimeMode,
        symbol: Symbol,
        limit: Int,
    ): List<PendingExecutionClosureAlert>

    suspend fun recordClosureAlertAttempt(
        closureId: Long,
        attemptedAt: Instant,
        delivered: Boolean,
    )

    suspend fun recordLivePerformanceSnapshot(snapshot: LivePerformanceSnapshot): Long

    suspend fun latestLivePerformanceSummary(
        mode: ExecutionRuntimeMode?,
        window: LivePerformanceWindow,
    ): LivePerformanceSnapshot?
}
