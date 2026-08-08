package dev.yaklede.bybittrader.engine.strategy

import dev.yaklede.bybittrader.domain.OrderStatus
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionFill
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionGateway
import dev.yaklede.bybittrader.engine.execution.ExchangeOpenOrder
import dev.yaklede.bybittrader.engine.execution.ExchangePosition
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

internal class VolumeConfirmedTrendLiveRecovery(
    private val gateway: ExchangeExecutionGateway,
    private val store: VolumeConfirmedTrendLiveStore,
    private val config: VolumeConfirmedTrendLiveConfig,
    private val projectionSink: VolumeConfirmedTrendLiveProjectionSink,
) {
    suspend fun recover(
        state: VolumeConfirmedTrendLiveState,
        position: ExchangePosition?,
        now: Instant,
    ): VolumeConfirmedTrendLiveEvaluationResult {
        val clientOrderId = requireNotNull(state.clientOrderId)
        val order = gateway.order(config.symbol, clientOrderId)
        val executions =
            gateway
                .executions(config.symbol)
                .filter { it.clientOrderId == clientOrderId }
                .sortedWith(
                    compareBy<ExchangeExecutionFill>(ExchangeExecutionFill::executedAt)
                        .thenBy { it.executionId.orEmpty() },
                )
        projectionSink.recordExecutionFills(executions, now)
        return when (state.status) {
            VolumeConfirmedTrendLiveStatus.ENTRY_INTENT_RECORDED,
            VolumeConfirmedTrendLiveStatus.ENTRY_SUBMITTED,
            -> recoverEntry(state, position, order, executions, now)
            VolumeConfirmedTrendLiveStatus.EXIT_INTENT_RECORDED,
            VolumeConfirmedTrendLiveStatus.EXIT_SUBMITTED,
            -> recoverExit(state, position, order, executions, now)
            else -> error("Unexpected pending trend live state ${state.status}.")
        }
    }

    private suspend fun recoverEntry(
        state: VolumeConfirmedTrendLiveState,
        position: ExchangePosition?,
        order: ExchangeOpenOrder?,
        executions: List<ExchangeExecutionFill>,
        now: Instant,
    ): VolumeConfirmedTrendLiveEvaluationResult {
        if (position != null) {
            if (order == null && executions.isEmpty()) {
                return pendingOrHalt(state, now, "TREND_ENTRY_POSITION_WITHOUT_ORDER_EVIDENCE", position)
            }
            if (position.side != state.pendingTargetSide) {
                return halt(state, now, "TREND_ENTRY_FILLED_WRONG_SIDE", position)
            }
            val opened =
                state.copy(
                    status = VolumeConfirmedTrendLiveStatus.OPEN,
                    observedPositionSide = position.side,
                    observedPositionQuantity = position.size,
                    exchangeOrderId = order?.exchangeOrderId ?: state.exchangeOrderId,
                    lastExecutionId = executions.lastExecutionId() ?: state.lastExecutionId,
                    haltedReasonCode = null,
                    updatedAt = now,
                )
            val event = lifecycleEvent(opened, VolumeConfirmedTrendLiveEventType.ENTRY_FILL_OBSERVED, "TREND_ENTRY_FILL_RECOVERED", now)
            store.commitTrendLive(opened, listOf(event))
            return VolumeConfirmedTrendLiveEvaluationResult(VolumeConfirmedTrendLiveEvaluationStatus.RECOVERED, opened, null)
        }
        if (order?.hasUnknownProviderStatus() == true) {
            return halt(state, now, "TREND_ENTRY_ORDER_STATUS_UNKNOWN")
        }
        return when (order?.status) {
            OrderStatus.CREATED,
            OrderStatus.SUBMITTED,
            OrderStatus.PARTIALLY_FILLED,
            -> activeOrderOrHalt(state, order, now, "TREND_ENTRY_IOC_REMAINS_ACTIVE")
            OrderStatus.CANCELLED,
            OrderStatus.REJECTED,
            -> {
                if (executions.isNotEmpty() || order.hasFilledQuantity()) {
                    pendingOrHalt(state, now, "TREND_ENTRY_PARTIAL_FILL_WITHOUT_POSITION")
                } else {
                    recordNotFilled(state, order, entry = true, now = now)
                }
            }
            OrderStatus.FILLED -> pendingOrHalt(state, now, "TREND_ENTRY_FILL_WITHOUT_POSITION")
            null -> {
                val reason =
                    if (executions.isNotEmpty()) {
                        "TREND_ENTRY_EXECUTION_WITHOUT_POSITION"
                    } else {
                        "TREND_ENTRY_ORDER_STATE_UNKNOWN"
                    }
                pendingOrHalt(state, now, reason)
            }
        }
    }

    private suspend fun recoverExit(
        state: VolumeConfirmedTrendLiveState,
        position: ExchangePosition?,
        order: ExchangeOpenOrder?,
        executions: List<ExchangeExecutionFill>,
        now: Instant,
    ): VolumeConfirmedTrendLiveEvaluationResult {
        if (position == null) {
            if (order == null && executions.isEmpty()) {
                return pendingOrHalt(state, now, "TREND_EXIT_FLAT_WITHOUT_ORDER_EVIDENCE")
            }
            val flat =
                state.copy(
                    status = VolumeConfirmedTrendLiveStatus.FLAT,
                    clientOrderId = null,
                    exchangeOrderId = null,
                    observedPositionSide = null,
                    observedPositionQuantity = null,
                    lastExecutionId = executions.lastExecutionId() ?: state.lastExecutionId,
                    haltedReasonCode = null,
                    updatedAt = now,
                )
            val event = lifecycleEvent(flat, VolumeConfirmedTrendLiveEventType.EXIT_FILL_OBSERVED, "TREND_EXIT_FILL_RECOVERED", now)
            store.commitTrendLive(flat, listOf(event))
            return VolumeConfirmedTrendLiveEvaluationResult(VolumeConfirmedTrendLiveEvaluationStatus.RECOVERED, flat, null)
        }
        if (order?.hasUnknownProviderStatus() == true) {
            return halt(state, now, "TREND_EXIT_ORDER_STATUS_UNKNOWN", position)
        }
        return when (order?.status) {
            OrderStatus.CREATED,
            OrderStatus.SUBMITTED,
            OrderStatus.PARTIALLY_FILLED,
            -> activeOrderOrHalt(state, order, now, "TREND_EXIT_IOC_REMAINS_ACTIVE", position)
            OrderStatus.CANCELLED,
            OrderStatus.REJECTED,
            -> recordNotFilled(state, order, entry = false, position = position, now = now)
            OrderStatus.FILLED -> pendingOrHalt(state, now, "TREND_EXIT_FILL_POSITION_REMAINS", position)
            null -> {
                val reason =
                    if (executions.isNotEmpty()) {
                        "TREND_EXIT_EXECUTION_POSITION_REMAINS"
                    } else {
                        "TREND_EXIT_ORDER_STATE_UNKNOWN"
                    }
                pendingOrHalt(state, now, reason, position)
            }
        }
    }

    private suspend fun recordSubmittedRecovery(
        state: VolumeConfirmedTrendLiveState,
        order: ExchangeOpenOrder,
        now: Instant,
    ): VolumeConfirmedTrendLiveEvaluationResult {
        val entry =
            state.status in setOf(VolumeConfirmedTrendLiveStatus.ENTRY_INTENT_RECORDED, VolumeConfirmedTrendLiveStatus.ENTRY_SUBMITTED)
        val submitted =
            state.copy(
                status = if (entry) VolumeConfirmedTrendLiveStatus.ENTRY_SUBMITTED else VolumeConfirmedTrendLiveStatus.EXIT_SUBMITTED,
                exchangeOrderId = order.exchangeOrderId,
                updatedAt =
                    if (state.status == VolumeConfirmedTrendLiveStatus.ENTRY_INTENT_RECORDED ||
                        state.status == VolumeConfirmedTrendLiveStatus.EXIT_INTENT_RECORDED
                    ) {
                        now
                    } else {
                        state.updatedAt
                    },
            )
        val type = if (entry) VolumeConfirmedTrendLiveEventType.ENTRY_SUBMITTED else VolumeConfirmedTrendLiveEventType.EXIT_SUBMITTED
        val event = lifecycleEvent(submitted, type, "TREND_ORDER_ACK_RECOVERED", now)
        store.commitTrendLive(submitted, listOf(event))
        return VolumeConfirmedTrendLiveEvaluationResult(VolumeConfirmedTrendLiveEvaluationStatus.RECOVERED, submitted, null)
    }

    private suspend fun activeOrderOrHalt(
        state: VolumeConfirmedTrendLiveState,
        order: ExchangeOpenOrder,
        now: Instant,
        timeoutReasonCode: String,
        position: ExchangePosition? = null,
    ): VolumeConfirmedTrendLiveEvaluationResult {
        val intentOnly =
            state.status == VolumeConfirmedTrendLiveStatus.ENTRY_INTENT_RECORDED ||
                state.status == VolumeConfirmedTrendLiveStatus.EXIT_INTENT_RECORDED
        if (!intentOnly && Duration.between(state.updatedAt, now) >= config.recoveryRetryDelay) {
            return halt(state, now, timeoutReasonCode, position)
        }
        return recordSubmittedRecovery(state, order, now)
    }

    private suspend fun recordNotFilled(
        state: VolumeConfirmedTrendLiveState,
        order: ExchangeOpenOrder,
        entry: Boolean,
        position: ExchangePosition? = null,
        now: Instant,
    ): VolumeConfirmedTrendLiveEvaluationResult {
        val notFilled =
            state.copy(
                status = if (entry) VolumeConfirmedTrendLiveStatus.ENTRY_NOT_FILLED else VolumeConfirmedTrendLiveStatus.EXIT_NOT_FILLED,
                exchangeOrderId = order.exchangeOrderId ?: state.exchangeOrderId,
                observedPositionSide = position?.side,
                observedPositionQuantity = position?.size,
                haltedReasonCode = null,
                updatedAt = now,
            )
        val type = if (entry) VolumeConfirmedTrendLiveEventType.ENTRY_NOT_FILLED else VolumeConfirmedTrendLiveEventType.EXIT_NOT_FILLED
        val reasonCode =
            when (order.status) {
                OrderStatus.REJECTED -> "TREND_ORDER_REJECTED_${order.rejectReason.orEmpty().ifBlank { "UNSPECIFIED" }}"
                else -> "TREND_IOC_NOT_FILLED_${order.cancelType.orEmpty().ifBlank { "UNSPECIFIED" }}"
            }
        val event = lifecycleEvent(notFilled, type, reasonCode, now)
        store.commitTrendLive(notFilled, listOf(event))
        return VolumeConfirmedTrendLiveEvaluationResult(
            status = VolumeConfirmedTrendLiveEvaluationStatus.ORDER_NOT_FILLED,
            state = notFilled,
            plan = null,
        )
    }

    private suspend fun pendingOrHalt(
        state: VolumeConfirmedTrendLiveState,
        now: Instant,
        reasonCode: String,
        position: ExchangePosition? = null,
    ): VolumeConfirmedTrendLiveEvaluationResult {
        if (Duration.between(state.updatedAt, now) < config.recoveryRetryDelay) {
            return VolumeConfirmedTrendLiveEvaluationResult(
                status = VolumeConfirmedTrendLiveEvaluationStatus.RECOVERY_PENDING,
                state = state,
                plan = null,
            )
        }
        return halt(state, now, reasonCode, position)
    }

    private suspend fun halt(
        state: VolumeConfirmedTrendLiveState,
        now: Instant,
        reasonCode: String,
        position: ExchangePosition? = null,
    ): VolumeConfirmedTrendLiveEvaluationResult {
        val halted =
            state.copy(
                status = VolumeConfirmedTrendLiveStatus.HALTED,
                observedPositionSide = position?.side ?: state.observedPositionSide,
                observedPositionQuantity = position?.size ?: state.observedPositionQuantity,
                haltedReasonCode = reasonCode,
                updatedAt = now,
            )
        val event = lifecycleEvent(halted, VolumeConfirmedTrendLiveEventType.HALTED, reasonCode, now)
        store.commitTrendLive(halted, listOf(event))
        return VolumeConfirmedTrendLiveEvaluationResult(
            status = VolumeConfirmedTrendLiveEvaluationStatus.HALTED,
            state = halted,
            plan = null,
        )
    }

    private fun ExchangeOpenOrder.hasFilledQuantity(): Boolean = filledQuantity?.let { it > BigDecimal.ZERO } == true

    private fun List<ExchangeExecutionFill>.lastExecutionId(): String? = lastOrNull { !it.executionId.isNullOrBlank() }?.executionId

    private fun ExchangeOpenOrder.hasUnknownProviderStatus(): Boolean =
        providerStatus != null && providerStatus !in KNOWN_PROVIDER_ORDER_STATUSES

    private companion object {
        val KNOWN_PROVIDER_ORDER_STATUSES =
            setOf(
                "New",
                "Created",
                "Untriggered",
                "PendingCancel",
                "Triggered",
                "Active",
                "PartiallyFilled",
                "Filled",
                "Cancelled",
                "Deactivated",
                "PartiallyFilledCanceled",
                "PartiallyFilledCancelled",
                "Rejected",
            )
    }
}
