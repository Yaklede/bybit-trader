package dev.yaklede.bybittrader.engine.execution

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class ExecutionWalletReconcilerTest :
    StringSpec({
        "creates a blocking baseline before comparing wallet changes" {
            val now = Instant.parse("2026-08-06T12:00:00Z")
            val baseline =
                reconcileWallet(
                    previous = null,
                    snapshot = walletSnapshot(id = 1, balance = "100", capturedAt = now),
                    reconciledAt = now,
                )

            baseline.status shouldBe ExecutionWalletReconciliationStatus.BASELINE
            baseline.baselineSnapshotId shouldBe 1L
            baseline.baselineWalletBalance shouldBe BigDecimal("100")
            walletDecision(baseline, now).reasonCodes shouldBe listOf("ACCOUNT_RECONCILIATION_BASELINE_PENDING")
        }

        "matches the observed wallet delta to account transaction changes" {
            val baselineAt = Instant.parse("2026-08-06T12:00:00Z")
            val baseline =
                reconcileWallet(
                    previous = null,
                    snapshot = walletSnapshot(id = 1, balance = "100", capturedAt = baselineAt),
                    reconciledAt = baselineAt,
                )
            val currentAt = baselineAt.plusSeconds(60)
            val matched =
                reconcileWallet(
                    previous = baseline,
                    snapshot = walletSnapshot(id = 2, balance = "98.75", capturedAt = currentAt),
                    transactions =
                        listOf(
                            walletTransaction("at-baseline", "99", baselineAt),
                            walletTransaction("trade", "-1.25", baselineAt.plusSeconds(30)),
                            walletTransaction("future", "7", currentAt.plusSeconds(1)),
                        ),
                    reconciledAt = currentAt,
                )

            matched.status shouldBe ExecutionWalletReconciliationStatus.MATCHED
            matched.observedWalletChange shouldBe BigDecimal("-1.25")
            matched.ledgerChange shouldBe BigDecimal("-1.25")
            matched.difference shouldBe BigDecimal("0.00")
            matched.baselineSnapshotId shouldBe 2L
            walletDecision(matched, currentAt).allowsEntry shouldBe true
        }

        "retains the last matched baseline and confirms repeated mismatches" {
            val baselineAt = Instant.parse("2026-08-06T12:00:00Z")
            val baseline =
                reconcileWallet(
                    previous = null,
                    snapshot = walletSnapshot(id = 1, balance = "100", capturedAt = baselineAt),
                    reconciledAt = baselineAt,
                )
            val first =
                reconcileWallet(
                    previous = baseline,
                    snapshot = walletSnapshot(id = 2, balance = "99", capturedAt = baselineAt.plusSeconds(60)),
                    reconciledAt = baselineAt.plusSeconds(60),
                )
            val second =
                reconcileWallet(
                    previous = first,
                    snapshot = walletSnapshot(id = 3, balance = "99", capturedAt = baselineAt.plusSeconds(120)),
                    reconciledAt = baselineAt.plusSeconds(120),
                )

            first.status shouldBe ExecutionWalletReconciliationStatus.MISMATCH
            first.baselineSnapshotId shouldBe 1L
            first.consecutiveMismatches shouldBe 1
            walletDecision(first, first.reconciledAt).reasonCodes shouldBe listOf("ACCOUNT_LEDGER_MISMATCH_PENDING")
            second.baselineSnapshotId shouldBe 1L
            second.consecutiveMismatches shouldBe 2
            walletDecision(second, second.reconciledAt).reasonCodes shouldBe listOf("ACCOUNT_LEDGER_MISMATCH_CONFIRMED")
        }

        "fails closed on transaction sync errors and stale reconciliation" {
            val now = Instant.parse("2026-08-06T12:00:00Z")
            val syncError =
                reconcileWallet(
                    previous = null,
                    snapshot = walletSnapshot(id = 1, balance = "100", capturedAt = now),
                    transactionSyncSucceeded = false,
                    reconciledAt = now,
                )

            walletDecision(syncError, now).reasonCodes shouldBe listOf("ACCOUNT_TRANSACTION_SYNC_UNAVAILABLE")
            walletDecision(syncError.copy(status = ExecutionWalletReconciliationStatus.MATCHED), now.plusSeconds(181)).reasonCodes shouldBe
                listOf("ACCOUNT_RECONCILIATION_STALE")
        }
    })

private fun reconcileWallet(
    previous: ExecutionWalletReconciliationState?,
    snapshot: ExecutionAccountSnapshot,
    transactions: List<ExecutionAccountTransactionEvent> = emptyList(),
    transactionSyncSucceeded: Boolean = true,
    reconciledAt: Instant,
): ExecutionWalletReconciliationState =
    ExecutionWalletReconciler.update(
        previous = previous,
        current = snapshot,
        transactions = transactions,
        currency = "USDT",
        tolerance = BigDecimal("0.01"),
        transactionSyncSucceeded = transactionSyncSucceeded,
        reconciledAt = reconciledAt,
    )

private fun walletDecision(
    state: ExecutionWalletReconciliationState?,
    now: Instant,
): ExecutionWalletReconciliationDecision =
    ExecutionWalletReconciler.evaluate(
        state = state,
        now = now,
        maximumAge = Duration.ofSeconds(180),
        confirmedMismatchCount = 2,
    )

private fun walletSnapshot(
    id: Long,
    balance: String,
    capturedAt: Instant,
): ExecutionAccountSnapshot =
    ExecutionAccountSnapshot(
        id = id,
        mode = ExecutionRuntimeMode.LIVE,
        accountType = "UNIFIED",
        totalEquity = BigDecimal(balance),
        totalWalletBalance = BigDecimal(balance),
        totalMarginBalance = BigDecimal(balance),
        totalAvailableBalance = BigDecimal(balance),
        totalPerpUnrealizedPnl = BigDecimal.ZERO,
        capturedAt = capturedAt,
        trackedCoin = "USDT",
        trackedCoinEquity = BigDecimal(balance),
        trackedCoinWalletBalance = BigDecimal(balance),
        trackedCoinUnrealizedPnl = BigDecimal.ZERO,
        trackedCoinCumulativeRealizedPnl = BigDecimal.ZERO,
    )

private fun walletTransaction(
    id: String,
    change: String,
    transactionAt: Instant,
): ExecutionAccountTransactionEvent =
    ExecutionAccountTransactionEvent(
        mode = ExecutionRuntimeMode.LIVE,
        transaction =
            ExchangeAccountTransaction(
                transactionId = id,
                symbol = null,
                category = "linear",
                side = null,
                transactionAt = transactionAt,
                type = "TRADE",
                subtype = null,
                quantity = null,
                size = null,
                currency = "USDT",
                tradePrice = null,
                funding = BigDecimal.ZERO,
                fee = BigDecimal.ZERO,
                cashFlow = BigDecimal.ZERO,
                change = BigDecimal(change),
                cashBalance = null,
                feeRate = null,
                tradeId = null,
                exchangeOrderId = null,
                clientOrderId = null,
            ),
        receivedAt = transactionAt,
    )
