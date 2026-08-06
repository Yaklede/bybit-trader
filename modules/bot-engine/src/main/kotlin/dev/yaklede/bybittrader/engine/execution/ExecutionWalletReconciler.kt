package dev.yaklede.bybittrader.engine.execution

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

enum class ExecutionWalletReconciliationStatus {
    BASELINE,
    MATCHED,
    MISMATCH,
    SYNC_ERROR,
    DATA_UNAVAILABLE,
}

data class ExecutionWalletReconciliationState(
    val mode: ExecutionRuntimeMode,
    val currency: String,
    val status: ExecutionWalletReconciliationStatus,
    val baselineSnapshotId: Long?,
    val baselineCapturedAt: Instant?,
    val baselineWalletBalance: BigDecimal?,
    val currentSnapshotId: Long?,
    val currentCapturedAt: Instant?,
    val currentWalletBalance: BigDecimal?,
    val observedWalletChange: BigDecimal?,
    val ledgerChange: BigDecimal?,
    val difference: BigDecimal?,
    val tolerance: BigDecimal,
    val consecutiveMismatches: Int,
    val lastMatchedAt: Instant?,
    val reconciledAt: Instant,
)

data class ExecutionWalletReconciliationDecision(
    val reasonCodes: List<String>,
) {
    val allowsEntry: Boolean = reasonCodes.isEmpty()
}

internal object ExecutionWalletReconciler {
    fun update(
        previous: ExecutionWalletReconciliationState?,
        current: ExecutionAccountSnapshot,
        transactions: List<ExecutionAccountTransactionEvent>,
        currency: String,
        tolerance: BigDecimal,
        transactionSyncSucceeded: Boolean,
        reconciledAt: Instant,
    ): ExecutionWalletReconciliationState {
        val currentWallet =
            current.trackedCoinWalletBalance
                ?.takeIf { current.trackedCoin.equals(currency, ignoreCase = true) }
        val baselineId = previous?.baselineSnapshotId ?: current.id.takeIf { it > 0 }
        val baselineAt = previous?.baselineCapturedAt ?: current.capturedAt
        val baselineWallet = previous?.baselineWalletBalance ?: currentWallet
        if (!transactionSyncSucceeded) {
            return state(
                previous = previous,
                current = current,
                currentWallet = currentWallet,
                currency = currency,
                tolerance = tolerance,
                status = ExecutionWalletReconciliationStatus.SYNC_ERROR,
                baselineId = baselineId,
                baselineAt = baselineAt,
                baselineWallet = baselineWallet,
                reconciledAt = reconciledAt,
            )
        }
        if (currentWallet == null || baselineWallet == null || current.id <= 0) {
            return state(
                previous = previous,
                current = current,
                currentWallet = currentWallet,
                currency = currency,
                tolerance = tolerance,
                status = ExecutionWalletReconciliationStatus.DATA_UNAVAILABLE,
                baselineId = baselineId,
                baselineAt = baselineAt,
                baselineWallet = baselineWallet,
                reconciledAt = reconciledAt,
            )
        }
        if (previous == null || previous.baselineSnapshotId == null) {
            return state(
                previous = null,
                current = current,
                currentWallet = currentWallet,
                currency = currency,
                tolerance = tolerance,
                status = ExecutionWalletReconciliationStatus.BASELINE,
                baselineId = current.id,
                baselineAt = current.capturedAt,
                baselineWallet = currentWallet,
                reconciledAt = reconciledAt,
            )
        }

        val ledgerChange =
            transactions
                .asSequence()
                .filter { event -> event.mode == current.mode }
                .filter { event -> event.transaction.currency.equals(currency, ignoreCase = true) }
                .filter { event -> event.transaction.transactionAt.isAfter(baselineAt) }
                .filter { event -> !event.transaction.transactionAt.isAfter(current.capturedAt) }
                .fold(BigDecimal.ZERO) { total, event -> total + event.transaction.change }
        val observedChange = currentWallet - baselineWallet
        val difference = observedChange - ledgerChange
        val matched = difference.abs() <= tolerance
        val consecutiveMismatches =
            if (matched) {
                0
            } else if (previous.status == ExecutionWalletReconciliationStatus.MISMATCH) {
                previous.consecutiveMismatches + 1
            } else {
                1
            }
        return ExecutionWalletReconciliationState(
            mode = current.mode,
            currency = currency,
            status =
                if (matched) {
                    ExecutionWalletReconciliationStatus.MATCHED
                } else {
                    ExecutionWalletReconciliationStatus.MISMATCH
                },
            baselineSnapshotId = if (matched) current.id else baselineId,
            baselineCapturedAt = if (matched) current.capturedAt else baselineAt,
            baselineWalletBalance = if (matched) currentWallet else baselineWallet,
            currentSnapshotId = current.id,
            currentCapturedAt = current.capturedAt,
            currentWalletBalance = currentWallet,
            observedWalletChange = observedChange,
            ledgerChange = ledgerChange,
            difference = difference,
            tolerance = tolerance,
            consecutiveMismatches = consecutiveMismatches,
            lastMatchedAt = if (matched) reconciledAt else previous.lastMatchedAt,
            reconciledAt = reconciledAt,
        )
    }

    fun evaluate(
        state: ExecutionWalletReconciliationState?,
        now: Instant,
        maximumAge: Duration,
        confirmedMismatchCount: Int,
    ): ExecutionWalletReconciliationDecision {
        if (state == null) return ExecutionWalletReconciliationDecision(listOf("ACCOUNT_RECONCILIATION_UNAVAILABLE"))
        if (state.reconciledAt.isAfter(now.plus(WALLET_RECONCILIATION_CLOCK_SKEW_TOLERANCE))) {
            return ExecutionWalletReconciliationDecision(listOf("ACCOUNT_RECONCILIATION_CLOCK_SKEW"))
        }
        if (Duration.between(state.reconciledAt, now) > maximumAge) {
            return ExecutionWalletReconciliationDecision(listOf("ACCOUNT_RECONCILIATION_STALE"))
        }
        val reason =
            when (state.status) {
                ExecutionWalletReconciliationStatus.MATCHED -> null
                ExecutionWalletReconciliationStatus.BASELINE -> "ACCOUNT_RECONCILIATION_BASELINE_PENDING"
                ExecutionWalletReconciliationStatus.SYNC_ERROR -> "ACCOUNT_TRANSACTION_SYNC_UNAVAILABLE"
                ExecutionWalletReconciliationStatus.DATA_UNAVAILABLE -> "ACCOUNT_WALLET_DATA_UNAVAILABLE"
                ExecutionWalletReconciliationStatus.MISMATCH ->
                    if (state.consecutiveMismatches >= confirmedMismatchCount) {
                        "ACCOUNT_LEDGER_MISMATCH_CONFIRMED"
                    } else {
                        "ACCOUNT_LEDGER_MISMATCH_PENDING"
                    }
            }
        return ExecutionWalletReconciliationDecision(listOfNotNull(reason))
    }

    private fun state(
        previous: ExecutionWalletReconciliationState?,
        current: ExecutionAccountSnapshot,
        currentWallet: BigDecimal?,
        currency: String,
        tolerance: BigDecimal,
        status: ExecutionWalletReconciliationStatus,
        baselineId: Long?,
        baselineAt: Instant?,
        baselineWallet: BigDecimal?,
        reconciledAt: Instant,
    ): ExecutionWalletReconciliationState =
        ExecutionWalletReconciliationState(
            mode = current.mode,
            currency = currency,
            status = status,
            baselineSnapshotId = baselineId,
            baselineCapturedAt = baselineAt,
            baselineWalletBalance = baselineWallet,
            currentSnapshotId = current.id.takeIf { it > 0 },
            currentCapturedAt = current.capturedAt,
            currentWalletBalance = currentWallet,
            observedWalletChange = null,
            ledgerChange = null,
            difference = null,
            tolerance = tolerance,
            consecutiveMismatches = previous?.consecutiveMismatches ?: 0,
            lastMatchedAt = previous?.lastMatchedAt,
            reconciledAt = reconciledAt,
        )
}

private val WALLET_RECONCILIATION_CLOCK_SKEW_TOLERANCE: Duration = Duration.ofSeconds(5)
