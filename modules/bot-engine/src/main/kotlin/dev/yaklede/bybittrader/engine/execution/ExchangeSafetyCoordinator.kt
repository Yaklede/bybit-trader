package dev.yaklede.bybittrader.engine.execution

import dev.yaklede.bybittrader.domain.BotMode
import dev.yaklede.bybittrader.domain.OrderStatus
import dev.yaklede.bybittrader.domain.Symbol
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

internal class ExchangeSafetyCoordinator(
    private val gateway: ExchangeExecutionGateway,
    private val lifecycleStore: ExecutionLifecycleStore?,
    private val runtimeMode: ExecutionRuntimeMode,
    private val config: ExchangeExecutionConfig,
    private val submitClose: suspend (ExchangePosition, String) -> Unit,
    private val clock: Clock,
) {
    suspend fun enforce(
        mode: BotMode,
        symbol: Symbol,
    ): ExchangeSafetyResult {
        val action = mode.toSafetyAction()
        val requestedAt = Instant.now(clock)
        val initialSnapshot =
            try {
                snapshot(symbol)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                return failedSnapshotResult(action, mode, symbol, requestedAt)
            }
        val attempt = enforceOnce(mode, initialSnapshot, requestedAt)
        var latestSnapshot = initialSnapshot
        repeat(config.safetyVerificationAttempts) { index ->
            latestSnapshot =
                try {
                    snapshot(symbol)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    return ExchangeSafetyResult(
                        action = action,
                        status = ExchangeSafetyStatus.FAILED,
                        mode = mode.name,
                        symbol = symbol,
                        requestedAt = requestedAt,
                        verifiedAt = Instant.now(clock),
                        cancelledEntryOrderCount = attempt.cancelledEntryOrderCount,
                        submittedCloseOrderCount = attempt.submittedCloseOrderCount,
                        protectedPositionCount = attempt.protectedPositionCount,
                        remainingOpenOrderCount = null,
                        remainingPositionCount = null,
                        issueCodes = attempt.issueCodes + "SAFETY_VERIFICATION_UNAVAILABLE",
                    )
                }
            if (latestSnapshot.isConfirmed(action)) {
                return ExchangeSafetyResult(
                    action = action,
                    status = ExchangeSafetyStatus.CONFIRMED,
                    mode = mode.name,
                    symbol = symbol,
                    requestedAt = requestedAt,
                    verifiedAt = Instant.now(clock),
                    cancelledEntryOrderCount = attempt.cancelledEntryOrderCount,
                    submittedCloseOrderCount = attempt.submittedCloseOrderCount,
                    protectedPositionCount = latestSnapshot.protectedPositionCount,
                    remainingOpenOrderCount = latestSnapshot.activeOrders.size,
                    remainingPositionCount = latestSnapshot.activePositions.size,
                    issueCodes = emptyList(),
                )
            }
            if (index < config.safetyVerificationAttempts - 1) {
                delay(config.safetyVerificationInterval.toMillis())
            }
        }
        return ExchangeSafetyResult(
            action = action,
            status = if (attempt.issueCodes.isEmpty()) ExchangeSafetyStatus.PENDING else ExchangeSafetyStatus.FAILED,
            mode = mode.name,
            symbol = symbol,
            requestedAt = requestedAt,
            verifiedAt = Instant.now(clock),
            cancelledEntryOrderCount = attempt.cancelledEntryOrderCount,
            submittedCloseOrderCount = attempt.submittedCloseOrderCount,
            protectedPositionCount = latestSnapshot.protectedPositionCount,
            remainingOpenOrderCount = latestSnapshot.activeOrders.size,
            remainingPositionCount = latestSnapshot.activePositions.size,
            issueCodes = attempt.issueCodes.ifEmpty { listOf("SAFETY_VERIFICATION_PENDING") },
        )
    }

    suspend fun enforceOnce(
        mode: BotMode,
        openOrders: List<ExchangeOpenOrder>,
        positions: List<ExchangePosition>,
        observedAt: Instant,
    ): ExchangeSafetyAttempt =
        enforceOnce(
            mode = mode,
            snapshot = ExchangeSafetySnapshot(openOrders.activeOrders(), positions.activePositions()),
            observedAt = observedAt,
        )

    private suspend fun enforceOnce(
        mode: BotMode,
        snapshot: ExchangeSafetySnapshot,
        observedAt: Instant,
    ): ExchangeSafetyAttempt {
        val action = mode.toSafetyAction()
        val issues = mutableListOf<String>()
        var cancelledEntryOrderCount = 0
        var submittedCloseOrderCount = 0
        val cancelCandidates =
            snapshot.activeOrders.filter { order ->
                !order.reduceOnly || (action == ExchangeSafetyAction.FLATTEN && snapshot.activePositions.isEmpty())
            }
        cancelCandidates.forEach { order ->
            try {
                gateway.cancelOrder(
                    ExchangeCancelRequest(
                        symbol = order.symbol,
                        exchangeOrderId = order.exchangeOrderId,
                        clientOrderId = order.clientOrderId,
                    ),
                )
                cancelledEntryOrderCount += 1
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                issues += "SAFETY_ORDER_CANCEL_FAILED"
            }
        }

        val positionsToClose =
            when (action) {
                ExchangeSafetyAction.SAFE_STOP -> snapshot.activePositions.filterNot(ExchangePosition::isProtected)
                ExchangeSafetyAction.FLATTEN -> snapshot.activePositions
            }
        if (positionsToClose.size > 1) {
            issues += "SAFETY_MULTIPLE_ACTIVE_POSITIONS_UNSUPPORTED"
        } else {
            positionsToClose.singleOrNull()?.let { position ->
                if (!hasPendingExit(snapshot, position.symbol, observedAt)) {
                    try {
                        submitClose(
                            position,
                            when (action) {
                                ExchangeSafetyAction.SAFE_STOP -> "SAFE_STOP_UNPROTECTED_POSITION"
                                ExchangeSafetyAction.FLATTEN -> "EMERGENCY_FLATTEN"
                            },
                        )
                        submittedCloseOrderCount += 1
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        issues += "SAFETY_POSITION_CLOSE_FAILED"
                    }
                }
            }
        }
        return ExchangeSafetyAttempt(
            cancelledEntryOrderCount = cancelledEntryOrderCount,
            submittedCloseOrderCount = submittedCloseOrderCount,
            protectedPositionCount = snapshot.protectedPositionCount,
            issueCodes = issues.distinct(),
        )
    }

    private suspend fun hasPendingExit(
        snapshot: ExchangeSafetySnapshot,
        symbol: Symbol,
        observedAt: Instant,
    ): Boolean {
        if (snapshot.activeOrders.any(ExchangeOpenOrder::isActiveReduceOnlyExit)) return true
        val latest = lifecycleStore?.latestLifecycleEvent(runtimeMode, symbol) ?: return false
        if (latest.state != ExecutionLifecycleState.EXIT_SUBMITTED) return false
        return snapshot.activeOrders.any { order -> order.matches(latest) } ||
            observedAt.isBefore(latest.occurredAt.plus(config.protectionGracePeriod))
    }

    private suspend fun snapshot(symbol: Symbol): ExchangeSafetySnapshot =
        ExchangeSafetySnapshot(
            activeOrders = gateway.openOrders(symbol).activeOrders(),
            activePositions = gateway.positions(symbol).activePositions(),
        )

    private fun failedSnapshotResult(
        action: ExchangeSafetyAction,
        mode: BotMode,
        symbol: Symbol,
        requestedAt: Instant,
    ): ExchangeSafetyResult =
        ExchangeSafetyResult(
            action = action,
            status = ExchangeSafetyStatus.FAILED,
            mode = mode.name,
            symbol = symbol,
            requestedAt = requestedAt,
            verifiedAt = Instant.now(clock),
            cancelledEntryOrderCount = 0,
            submittedCloseOrderCount = 0,
            protectedPositionCount = 0,
            remainingOpenOrderCount = null,
            remainingPositionCount = null,
            issueCodes = listOf("SAFETY_SNAPSHOT_UNAVAILABLE"),
        )
}

internal data class ExchangeSafetyAttempt(
    val cancelledEntryOrderCount: Int,
    val submittedCloseOrderCount: Int,
    val protectedPositionCount: Int,
    val issueCodes: List<String>,
)

private data class ExchangeSafetySnapshot(
    val activeOrders: List<ExchangeOpenOrder>,
    val activePositions: List<ExchangePosition>,
) {
    val protectedPositionCount: Int = activePositions.count(ExchangePosition::isProtected)

    fun isConfirmed(action: ExchangeSafetyAction): Boolean =
        when (action) {
            ExchangeSafetyAction.SAFE_STOP ->
                activeOrders.none { order -> !order.reduceOnly } && activePositions.all(ExchangePosition::isProtected)

            ExchangeSafetyAction.FLATTEN -> activeOrders.isEmpty() && activePositions.isEmpty()
        }
}

private fun BotMode.toSafetyAction(): ExchangeSafetyAction =
    when (this) {
        BotMode.PAUSE_ALL -> ExchangeSafetyAction.SAFE_STOP
        BotMode.EMERGENCY_STOP -> ExchangeSafetyAction.FLATTEN
        else -> throw IllegalArgumentException("Bot mode $name does not require an exchange safety action.")
    }

private fun List<ExchangeOpenOrder>.activeOrders(): List<ExchangeOpenOrder> = filter { order -> order.status.isActive() }

private fun List<ExchangePosition>.activePositions(): List<ExchangePosition> = filter { position -> position.size > BigDecimal.ZERO }

private fun OrderStatus.isActive(): Boolean = this == OrderStatus.SUBMITTED || this == OrderStatus.PARTIALLY_FILLED

private fun ExchangePosition.isProtected(): Boolean = stopLoss != null && stopLoss > BigDecimal.ZERO

private fun ExchangeOpenOrder.isActiveReduceOnlyExit(): Boolean =
    reduceOnly && (stopOrderType.isNullOrBlank() || stopOrderType == "UNKNOWN")

private fun ExchangeOpenOrder.matches(event: ExecutionLifecycleEvent): Boolean =
    (!exchangeOrderId.isNullOrBlank() && exchangeOrderId == event.exchangeOrderId) ||
        (!clientOrderId.isNullOrBlank() && clientOrderId == event.clientOrderId)
