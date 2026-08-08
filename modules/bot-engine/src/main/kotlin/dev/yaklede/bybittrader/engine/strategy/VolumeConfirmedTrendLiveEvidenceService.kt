package dev.yaklede.bybittrader.engine.strategy

import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.execution.ExecutionAccountSnapshot
import dev.yaklede.bybittrader.engine.execution.ExecutionAccountTransactionEvent
import dev.yaklede.bybittrader.engine.execution.ExecutionFillEvent
import dev.yaklede.bybittrader.engine.execution.ExecutionProjectionStore
import dev.yaklede.bybittrader.engine.execution.ExecutionRuntimeMode
import dev.yaklede.bybittrader.engine.execution.ExecutionTradeClosure
import dev.yaklede.bybittrader.engine.execution.ExecutionWalletReconciliationState
import dev.yaklede.bybittrader.engine.execution.LivePerformanceSnapshot
import dev.yaklede.bybittrader.engine.execution.LivePerformanceWindow
import dev.yaklede.bybittrader.engine.execution.startAt
import dev.yaklede.bybittrader.engine.execution.toPerformanceSnapshot
import java.math.BigDecimal
import java.time.Instant

data class VolumeConfirmedTrendLivePerformanceEvidence(
    val snapshot: LivePerformanceSnapshot,
    val btcFundingPnl: BigDecimal,
    val strategyTransactionFees: BigDecimal,
)

data class VolumeConfirmedTrendLiveEvidence(
    val accountSnapshot: ExecutionAccountSnapshot?,
    val walletReconciliation: ExecutionWalletReconciliationState?,
    val performance: List<VolumeConfirmedTrendLivePerformanceEvidence>,
    val recentClosures: List<ExecutionTradeClosure>,
    val recentExecutionFills: List<ExecutionFillEvent>,
    val recentAccountTransactions: List<ExecutionAccountTransactionEvent>,
)

class VolumeConfirmedTrendLiveEvidenceService(
    private val store: ExecutionProjectionStore,
    private val runtimeMode: ExecutionRuntimeMode,
    private val sessionStartedAt: Instant,
    private val ownedClientOrderIds: suspend () -> Set<String>,
    private val tradingSymbol: Symbol = Symbol("BTCUSDT"),
    private val accountCoin: String = "USDT",
) {
    init {
        require(tradingSymbol.value == "BTCUSDT") { "Trend live evidence supports BTCUSDT only." }
        require(accountCoin == "USDT") { "Trend live evidence supports the USDT account ledger only." }
    }

    suspend fun read(
        now: Instant,
        limit: Int,
    ): VolumeConfirmedTrendLiveEvidence {
        require(limit in 1..100) { "Trend live evidence limit must be between 1 and 100." }
        val ownedOrderIds = ownedClientOrderIds()
        val closures =
            store
                .performanceClosures(runtimeMode, null)
                .filter { closure -> isOwnedClosure(closure, ownedOrderIds) }
        val accountTransactions =
            store.accountTransactions(
                mode = runtimeMode,
                currency = accountCoin,
                transactionAtOrAfter = null,
                transactionAtOrBefore = now,
            )
        val performance =
            LivePerformanceWindow.values().map { window ->
                val startAt = window.startAt(now, sessionStartedAt)
                val windowClosures = closures.filter { closure -> startAt == null || !closure.closedAt.isBefore(startAt) }
                val windowTransactions =
                    accountTransactions.filter { event ->
                        startAt == null || !event.transaction.transactionAt.isBefore(startAt)
                    }
                val accountSnapshots = store.accountSnapshots(runtimeMode, startAt)
                val accountBaseline = startAt?.let { store.latestAccountSnapshot(runtimeMode, it) }
                VolumeConfirmedTrendLivePerformanceEvidence(
                    snapshot =
                        windowClosures.toPerformanceSnapshot(
                            mode = runtimeMode,
                            window = window,
                            capturedAt = now,
                            accountSnapshots = accountSnapshots,
                            accountBaseline = accountBaseline,
                        ),
                    btcFundingPnl =
                        windowTransactions
                            .asSequence()
                            .filter { event -> event.transaction.symbol == tradingSymbol }
                            .map { event -> event.transaction.funding }
                            .fold(BigDecimal.ZERO, BigDecimal::add),
                    strategyTransactionFees =
                        windowTransactions
                            .asSequence()
                            .filter { event -> isOwnedTransaction(event, ownedOrderIds) }
                            .map { event -> event.transaction.fee }
                            .fold(BigDecimal.ZERO, BigDecimal::add),
                )
            }
        val fillQueryLimit = (limit * 4).coerceAtMost(1_000)
        return VolumeConfirmedTrendLiveEvidence(
            accountSnapshot = store.latestAccountSnapshot(runtimeMode, now),
            walletReconciliation = store.walletReconciliationState(runtimeMode, accountCoin),
            performance = performance,
            recentClosures = closures.sortedByDescending(ExecutionTradeClosure::closedAt).take(limit),
            recentExecutionFills =
                store
                    .executionFills(runtimeMode, tradingSymbol, null, fillQueryLimit)
                    .filter { event -> isOwnedExecution(event, ownedOrderIds) }
                    .take(limit),
            recentAccountTransactions =
                accountTransactions
                    .sortedByDescending { event -> event.transaction.transactionAt }
                    .take(limit),
        )
    }

    private fun isOwnedClosure(
        closure: ExecutionTradeClosure,
        ownedOrderIds: Set<String>,
    ): Boolean = closure.symbol == tradingSymbol && closure.clientOrderId in ownedOrderIds

    private fun isOwnedExecution(
        event: ExecutionFillEvent,
        ownedOrderIds: Set<String>,
    ): Boolean = event.fill.symbol == tradingSymbol && event.fill.clientOrderId in ownedOrderIds

    private fun isOwnedTransaction(
        event: ExecutionAccountTransactionEvent,
        ownedOrderIds: Set<String>,
    ): Boolean = event.transaction.symbol == tradingSymbol && event.transaction.clientOrderId in ownedOrderIds
}
