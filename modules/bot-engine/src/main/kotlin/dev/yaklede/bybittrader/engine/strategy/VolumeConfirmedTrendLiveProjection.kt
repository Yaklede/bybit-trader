package dev.yaklede.bybittrader.engine.strategy

import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.execution.ExchangeAccountBalance
import dev.yaklede.bybittrader.engine.execution.ExchangeAccountTransaction
import dev.yaklede.bybittrader.engine.execution.ExchangeClosedPnl
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionFill
import dev.yaklede.bybittrader.engine.execution.ExecutionAccountTransactionEvent
import dev.yaklede.bybittrader.engine.execution.ExecutionFillEvent
import dev.yaklede.bybittrader.engine.execution.ExecutionProjectionStore
import dev.yaklede.bybittrader.engine.execution.ExecutionRiskCircuitBreaker
import dev.yaklede.bybittrader.engine.execution.ExecutionRiskState
import dev.yaklede.bybittrader.engine.execution.ExecutionRuntimeMode
import dev.yaklede.bybittrader.engine.execution.ExecutionWalletReconciler
import dev.yaklede.bybittrader.engine.execution.LivePerformanceWindow
import dev.yaklede.bybittrader.engine.execution.resolveExitReason
import dev.yaklede.bybittrader.engine.execution.startAt
import dev.yaklede.bybittrader.engine.execution.toExecutionAccountSnapshot
import dev.yaklede.bybittrader.engine.execution.toPerformanceSnapshot
import dev.yaklede.bybittrader.engine.execution.toTradeClosure
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

data class VolumeConfirmedTrendLiveAccountingRequest(
    val requestedAt: Instant,
    val closuresDue: Boolean,
    val transactionsDue: Boolean,
    val transactionStartAt: Instant?,
) {
    init {
        require(closuresDue || transactionsDue) { "A trend accounting request must contain work." }
        require(transactionsDue == (transactionStartAt != null)) {
            "Trend accounting transaction range must match the transaction sync flag."
        }
        require(transactionStartAt == null || !transactionStartAt.isAfter(requestedAt)) {
            "Trend accounting transaction start must not be after the request time."
        }
    }
}

data class VolumeConfirmedTrendLiveAccountingObservation(
    val request: VolumeConfirmedTrendLiveAccountingRequest,
    val executions: List<ExchangeExecutionFill>,
    val closedPnls: List<ExchangeClosedPnl>,
    val accountTransactions: List<ExchangeAccountTransaction>,
    val receivedAt: Instant,
)

data class VolumeConfirmedTrendLiveRiskAssessment(
    val state: ExecutionRiskState?,
    val reasonCodes: List<String>,
) {
    val allowsEntry: Boolean = reasonCodes.isEmpty()
}

interface VolumeConfirmedTrendLiveProjectionSink {
    suspend fun accountSnapshotDue(now: Instant): Boolean

    suspend fun recordAccountBalance(balance: ExchangeAccountBalance)

    suspend fun recordExecutionFills(
        fills: List<ExchangeExecutionFill>,
        receivedAt: Instant,
    )

    suspend fun reserveAccountingRequest(now: Instant): VolumeConfirmedTrendLiveAccountingRequest? = null

    suspend fun recordAccounting(observation: VolumeConfirmedTrendLiveAccountingObservation) = Unit

    suspend fun recordAccountingFailure(
        request: VolumeConfirmedTrendLiveAccountingRequest,
        failedAt: Instant,
    ) = Unit

    suspend fun assessEntryRisk(
        previous: ExecutionRiskState?,
        now: Instant,
        policy: VolumeConfirmedTrendLiveRiskPolicy,
    ): VolumeConfirmedTrendLiveRiskAssessment = VolumeConfirmedTrendLiveRiskAssessment(previous, emptyList())
}

object NoopVolumeConfirmedTrendLiveProjectionSink : VolumeConfirmedTrendLiveProjectionSink {
    override suspend fun accountSnapshotDue(now: Instant): Boolean = false

    override suspend fun recordAccountBalance(balance: ExchangeAccountBalance) = Unit

    override suspend fun recordExecutionFills(
        fills: List<ExchangeExecutionFill>,
        receivedAt: Instant,
    ) = Unit
}

class LedgerVolumeConfirmedTrendLiveProjectionSink(
    private val store: ExecutionProjectionStore,
    private val runtimeMode: ExecutionRuntimeMode,
    private val tradingSymbol: Symbol = Symbol("BTCUSDT"),
    private val accountCoin: String = "USDT",
    private val orderIdPrefix: String = "vct-",
    private val sessionStartedAt: Instant = Instant.now(),
    private val accountSnapshotInterval: Duration = Duration.ofMinutes(1),
    private val closureSyncInterval: Duration = Duration.ofMinutes(1),
    private val transactionSyncInterval: Duration = Duration.ofMinutes(5),
    private val transactionBootstrapRange: Duration = Duration.ofHours(24),
    private val transactionOverlap: Duration = Duration.ofMinutes(5),
    private val transactionMaximumRange: Duration = Duration.ofDays(7),
    private val walletReconciliationTolerance: BigDecimal = BigDecimal("0.01"),
) : VolumeConfirmedTrendLiveProjectionSink {
    private val logger = LoggerFactory.getLogger(javaClass)
    private var lastClosureSyncAttemptAt: Instant? = null
    private var lastTransactionSyncAttemptAt: Instant? = null
    private var performanceInitialized = false

    init {
        require(tradingSymbol.value == "BTCUSDT") { "Trend live projection supports BTCUSDT only." }
        require(accountCoin == "USDT") { "Trend live projection supports the USDT account ledger only." }
        require(orderIdPrefix.isNotBlank()) { "Trend live projection order prefix must not be blank." }
        listOf(
            accountSnapshotInterval,
            closureSyncInterval,
            transactionSyncInterval,
            transactionBootstrapRange,
            transactionOverlap,
            transactionMaximumRange,
        ).forEach { duration ->
            require(!duration.isNegative && !duration.isZero) {
                "Trend live projection durations must be positive."
            }
        }
        require(transactionBootstrapRange <= transactionMaximumRange) {
            "Trend live transaction bootstrap range must not exceed the maximum range."
        }
        require(transactionOverlap < transactionMaximumRange) {
            "Trend live transaction overlap must be shorter than the maximum range."
        }
        require(walletReconciliationTolerance >= BigDecimal.ZERO) {
            "Trend live wallet reconciliation tolerance must not be negative."
        }
    }

    override suspend fun accountSnapshotDue(now: Instant): Boolean {
        val latest = store.latestAccountSnapshot(runtimeMode, now) ?: return true
        val age = Duration.between(latest.capturedAt, now)
        return age.isNegative || age >= accountSnapshotInterval
    }

    override suspend fun recordAccountBalance(balance: ExchangeAccountBalance) {
        val snapshot = balance.toExecutionAccountSnapshot(runtimeMode)
        store.recordAccountSnapshot(snapshot)
        refreshPerformanceSnapshots(balance.capturedAt, force = false)
    }

    override suspend fun recordExecutionFills(
        fills: List<ExchangeExecutionFill>,
        receivedAt: Instant,
    ) {
        fills.filter(::isOwnedExecution).forEach { fill ->
            store.recordExecutionFill(
                ExecutionFillEvent(
                    mode = runtimeMode,
                    fill = fill,
                    receivedAt = receivedAt,
                ),
            )
        }
    }

    override suspend fun reserveAccountingRequest(now: Instant): VolumeConfirmedTrendLiveAccountingRequest? {
        val closuresDue = syncDue(lastClosureSyncAttemptAt, now, closureSyncInterval)
        val transactionsDue = syncDue(lastTransactionSyncAttemptAt, now, transactionSyncInterval)
        if (!closuresDue && !transactionsDue) return null

        if (closuresDue) lastClosureSyncAttemptAt = now
        if (transactionsDue) lastTransactionSyncAttemptAt = now
        return VolumeConfirmedTrendLiveAccountingRequest(
            requestedAt = now,
            closuresDue = closuresDue,
            transactionsDue = transactionsDue,
            transactionStartAt = if (transactionsDue) transactionStartAt(now) else null,
        )
    }

    override suspend fun recordAccounting(observation: VolumeConfirmedTrendLiveAccountingObservation) {
        val request = observation.request
        val ownedExecutions = observation.executions.filter(::isOwnedExecution)
        recordExecutionFills(ownedExecutions, observation.receivedAt)

        var newClosureCount = 0
        if (request.closuresDue) {
            observation.closedPnls
                .mapNotNull { closedPnl -> closedPnl.ownedBy(ownedExecutions) }
                .forEach { closedPnl ->
                    val closure = closedPnl.resolveExitReason(ownedExecutions).toTradeClosure(runtimeMode)
                    val suppressedAt = sessionStartedAt.takeIf { closure.closedAt.isBefore(it) }
                    if (store.recordTradeClosure(closure, suppressedAt) != null) newClosureCount += 1
                }
        }

        var newTransactionCount = 0
        if (request.transactionsDue) {
            val transactionStartAt = requireNotNull(request.transactionStartAt)
            observation.accountTransactions
                .filter { transaction -> transaction.currency.equals(accountCoin, ignoreCase = true) }
                .filter { transaction -> !transaction.transactionAt.isBefore(transactionStartAt) }
                .filter { transaction -> !transaction.transactionAt.isAfter(request.requestedAt) }
                .forEach { transaction ->
                    val event =
                        ExecutionAccountTransactionEvent(
                            mode = runtimeMode,
                            transaction = transaction,
                            receivedAt = observation.receivedAt,
                        )
                    if (store.recordAccountTransaction(event) != null) newTransactionCount += 1
                }
            reconcileWallet(request.requestedAt, transactionSyncSucceeded = true)
        }
        if (newClosureCount > 0) refreshPerformanceSnapshots(request.requestedAt, force = true)
        logger.info(
            "trend live accounting persisted mode={} symbol={} executions={} newClosures={} newTransactions={} transactionsSynced={}",
            runtimeMode.name,
            tradingSymbol.value,
            ownedExecutions.size,
            newClosureCount,
            newTransactionCount,
            request.transactionsDue,
        )
    }

    override suspend fun recordAccountingFailure(
        request: VolumeConfirmedTrendLiveAccountingRequest,
        failedAt: Instant,
    ) {
        if (request.transactionsDue) reconcileWallet(failedAt, transactionSyncSucceeded = false)
    }

    override suspend fun assessEntryRisk(
        previous: ExecutionRiskState?,
        now: Instant,
        policy: VolumeConfirmedTrendLiveRiskPolicy,
    ): VolumeConfirmedTrendLiveRiskAssessment {
        val snapshot = store.latestAccountSnapshot(runtimeMode, now)
        val updated =
            snapshot?.let { current ->
                val closures =
                    store
                        .performanceClosures(runtimeMode, null)
                        .asSequence()
                        .filter(::isOwnedClosure)
                        .filter { closure -> previous?.lastClosureId?.let { closure.id > it } ?: true }
                        .toList()
                val accountTransactions =
                    store.accountTransactionsAfterId(
                        mode = runtimeMode,
                        currency = accountCoin,
                        afterId = previous?.lastAccountTransactionId,
                        transactionAtOrBefore = current.capturedAt,
                    )
                ExecutionRiskCircuitBreaker.update(
                    previous = previous,
                    snapshot = current,
                    newClosures = closures,
                    accountTransactions = accountTransactions,
                )
            } ?: previous
        val riskDecision =
            ExecutionRiskCircuitBreaker.evaluateAccountDrawdown(
                state = updated,
                now = now,
                maximumAge = policy.riskStateMaximumAge,
                maximumAccountDrawdownFraction = policy.maximumAccountDrawdownFraction,
            )
        val walletDecision =
            ExecutionWalletReconciler.evaluate(
                state = store.walletReconciliationState(runtimeMode, accountCoin),
                now = now,
                maximumAge = policy.walletReconciliationMaximumAge,
                confirmedMismatchCount = policy.walletReconciliationConfirmedMismatchCount,
            )
        return VolumeConfirmedTrendLiveRiskAssessment(
            state = updated,
            reasonCodes = (riskDecision.reasonCodes + walletDecision.reasonCodes).distinct(),
        )
    }

    private suspend fun transactionStartAt(now: Instant): Instant {
        val latest = store.latestAccountTransaction(runtimeMode, accountCoin)
        val requestedStart = latest?.transaction?.transactionAt?.minus(transactionOverlap) ?: now.minus(transactionBootstrapRange)
        val maximumStart = now.minus(transactionMaximumRange)
        return maxOf(requestedStart, maximumStart)
    }

    private suspend fun reconcileWallet(
        reconciledAt: Instant,
        transactionSyncSucceeded: Boolean,
    ) {
        val snapshot = store.latestAccountSnapshot(runtimeMode, reconciledAt) ?: return
        val previous = store.walletReconciliationState(runtimeMode, accountCoin)
        val transactions =
            store.accountTransactions(
                mode = runtimeMode,
                currency = accountCoin,
                transactionAtOrAfter = previous?.baselineCapturedAt,
                transactionAtOrBefore = snapshot.capturedAt,
            )
        store.upsertWalletReconciliationState(
            ExecutionWalletReconciler.update(
                previous = previous,
                current = snapshot,
                transactions = transactions,
                currency = accountCoin,
                tolerance = walletReconciliationTolerance,
                transactionSyncSucceeded = transactionSyncSucceeded,
                reconciledAt = reconciledAt,
            ),
        )
    }

    private suspend fun refreshPerformanceSnapshots(
        capturedAt: Instant,
        force: Boolean,
    ) {
        if (performanceInitialized && !force) return
        LivePerformanceWindow.values().forEach { window ->
            val startAt = window.startAt(capturedAt, sessionStartedAt)
            val closures =
                store
                    .performanceClosures(runtimeMode, startAt)
                    .filter(::isOwnedClosure)
            val accountSnapshots = store.accountSnapshots(runtimeMode, startAt)
            val accountBaseline = startAt?.let { store.latestAccountSnapshot(runtimeMode, it) }
            store.recordLivePerformanceSnapshot(
                closures.toPerformanceSnapshot(
                    mode = runtimeMode,
                    window = window,
                    capturedAt = capturedAt,
                    accountSnapshots = accountSnapshots,
                    accountBaseline = accountBaseline,
                ),
            )
        }
        performanceInitialized = true
    }

    private fun ExchangeClosedPnl.ownedBy(executions: List<ExchangeExecutionFill>): ExchangeClosedPnl? {
        if (symbol != tradingSymbol) return null
        if (clientOrderId?.startsWith(orderIdPrefix) == true) return this
        val matchingExecution =
            executions.firstOrNull { execution ->
                !exchangeOrderId.isNullOrBlank() && execution.exchangeOrderId == exchangeOrderId
            } ?: return null
        return copy(clientOrderId = matchingExecution.clientOrderId)
    }

    private fun isOwnedExecution(fill: ExchangeExecutionFill): Boolean =
        fill.symbol == tradingSymbol && fill.clientOrderId?.startsWith(orderIdPrefix) == true

    private fun isOwnedClosure(closure: dev.yaklede.bybittrader.engine.execution.ExecutionTradeClosure): Boolean =
        closure.symbol == tradingSymbol && closure.clientOrderId?.startsWith(orderIdPrefix) == true

    private fun syncDue(
        lastAttemptAt: Instant?,
        now: Instant,
        interval: Duration,
    ): Boolean {
        if (lastAttemptAt == null) return true
        val age = Duration.between(lastAttemptAt, now)
        return age.isNegative || age >= interval
    }
}
