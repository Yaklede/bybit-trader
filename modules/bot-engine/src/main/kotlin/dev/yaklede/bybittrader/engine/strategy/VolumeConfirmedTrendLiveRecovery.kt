package dev.yaklede.bybittrader.engine.strategy

import dev.yaklede.bybittrader.domain.OrderStatus
import dev.yaklede.bybittrader.domain.OrderType
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.engine.execution.ExchangeCancelRequest
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionFill
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionGateway
import dev.yaklede.bybittrader.engine.execution.ExchangeOpenOrder
import dev.yaklede.bybittrader.engine.execution.ExchangePosition
import dev.yaklede.bybittrader.engine.execution.ExchangeTimeInForce
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

const val TREND_ACTIVE_ORDER_CANCEL_REQUESTED_REASON_CODE = "TREND_ACTIVE_ORDER_CANCEL_REQUESTED"

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
                .executions(
                    symbol = config.symbol,
                    startAt = state.updatedAt.minus(config.recoveryHistoryOverlap),
                    endAt = now,
                ).filter { it.clientOrderId == clientOrderId }
                .sortedWith(
                    compareBy<ExchangeExecutionFill>(ExchangeExecutionFill::executedAt)
                        .thenBy { it.executionId.orEmpty() },
                )
        val lifecycleEvents = store.trendLiveEvents(config.protocolId, config.symbol, config.recoveryEventLimit)
        val contractLookup = recoveryOrderContract(state, lifecycleEvents)
        val contract =
            contractLookup.contract
                ?: return contractFailureOrHalt(
                    state = state,
                    order = order,
                    contract = null,
                    now = now,
                    reasonCode = requireNotNull(contractLookup.failureReasonCode),
                    position = position,
                )
        order?.contractFailure(state, contract)?.let { reasonCode ->
            return contractFailureOrHalt(state, order, contract, now, reasonCode, position)
        }
        executions.contractFailure(state, order, contract, now)?.let { reasonCode ->
            return contractFailureOrHalt(state, order, contract, now, reasonCode, position)
        }
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
            if (order?.status?.isActive() == true) {
                return activeOrderOrHalt(state, order, now, "TREND_ENTRY_IOC_REMAINS_ACTIVE", position)
            }
            if (order?.hasUnknownProviderStatus() == true) {
                return halt(state, now, "TREND_ENTRY_ORDER_STATUS_UNKNOWN", position)
            }
            if (position.side != state.pendingTargetSide) {
                return halt(state, now, "TREND_ENTRY_FILLED_WRONG_SIDE", position)
            }
            val fillEvidence = fillQuantityEvidence(order, executions)
            if (!fillEvidence.consistent) {
                return pendingOrHalt(state, now, "TREND_ENTRY_FILL_QUANTITY_EVIDENCE_MISMATCH", position)
            }
            val filledQuantity =
                fillEvidence.quantity
                    ?: return pendingOrHalt(state, now, "TREND_ENTRY_FILL_QUANTITY_EVIDENCE_MISSING", position)
            if (position.size.compareTo(filledQuantity) != 0) {
                return pendingOrHalt(state, now, "TREND_ENTRY_POSITION_QUANTITY_MISMATCH", position)
            }
            val opened =
                state.copy(
                    status = VolumeConfirmedTrendLiveStatus.OPEN,
                    observedPositionSide = position.side,
                    observedPositionQuantity = position.size,
                    exchangeOrderId = order?.exchangeOrderId ?: executions.firstOrNull()?.exchangeOrderId ?: state.exchangeOrderId,
                    lastExecutionId = executions.lastExecutionId() ?: state.lastExecutionId,
                    haltedReasonCode = null,
                    updatedAt = now,
                )
            val event = lifecycleEvent(opened, VolumeConfirmedTrendLiveEventType.ENTRY_FILL_OBSERVED, "TREND_ENTRY_FILL_RECOVERED", now)
            store.commitTrendLive(opened, listOf(event))
            return VolumeConfirmedTrendLiveEvaluationResult(VolumeConfirmedTrendLiveEvaluationStatus.RECOVERED, opened, null)
        }
        if (order?.status?.isActive() == true) {
            return activeOrderOrHalt(state, order, now, "TREND_ENTRY_IOC_REMAINS_ACTIVE")
        }
        if (order?.hasUnknownProviderStatus() == true) {
            return halt(state, now, "TREND_ENTRY_ORDER_STATUS_UNKNOWN")
        }
        return when (order?.status) {
            OrderStatus.CREATED,
            OrderStatus.SUBMITTED,
            OrderStatus.PARTIALLY_FILLED,
            -> error("Active entry orders must be handled before terminal recovery.")
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
            if (order?.status?.isActive() == true) {
                return activeOrderOrHalt(state, order, now, "TREND_EXIT_IOC_REMAINS_ACTIVE")
            }
            if (order?.hasUnknownProviderStatus() == true) {
                return halt(state, now, "TREND_EXIT_ORDER_STATUS_UNKNOWN")
            }
            val expectedExitQuantity = state.observedPositionQuantity
            if (expectedExitQuantity == null || expectedExitQuantity <= BigDecimal.ZERO) {
                return halt(state, now, "TREND_EXIT_OWNERSHIP_QUANTITY_EVIDENCE_MISSING")
            }
            val fillEvidence = fillQuantityEvidence(order, executions)
            if (!fillEvidence.consistent) {
                return pendingOrHalt(state, now, "TREND_EXIT_FILL_QUANTITY_EVIDENCE_MISMATCH")
            }
            val filledQuantity =
                fillEvidence.quantity
                    ?: return pendingOrHalt(state, now, "TREND_EXIT_FILL_QUANTITY_EVIDENCE_MISSING")
            if (filledQuantity.compareTo(expectedExitQuantity) != 0) {
                return pendingOrHalt(state, now, "TREND_EXIT_FLAT_QUANTITY_MISMATCH")
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
        if (order?.status?.isActive() == true) {
            return activeOrderOrHalt(state, order, now, "TREND_EXIT_IOC_REMAINS_ACTIVE", position)
        }
        if (order?.hasUnknownProviderStatus() == true) {
            return halt(state, now, "TREND_EXIT_ORDER_STATUS_UNKNOWN", position)
        }
        val originalSide = state.observedPositionSide
        val originalQuantity = state.observedPositionQuantity
        if (originalSide == null || originalQuantity == null || originalQuantity <= BigDecimal.ZERO) {
            return halt(state, now, "TREND_EXIT_OWNERSHIP_QUANTITY_EVIDENCE_MISSING", position)
        }
        if (position.side != originalSide || position.size > originalQuantity) {
            return halt(state, now, "TREND_EXIT_POSITION_QUANTITY_MISMATCH", position)
        }
        return when (order?.status) {
            OrderStatus.CREATED,
            OrderStatus.SUBMITTED,
            OrderStatus.PARTIALLY_FILLED,
            -> error("Active exit orders must be handled before terminal recovery.")
            OrderStatus.CANCELLED,
            OrderStatus.REJECTED,
            -> {
                val fillEvidence = fillQuantityEvidence(order, executions)
                val reducedQuantity = originalQuantity - position.size
                when {
                    !fillEvidence.consistent ->
                        pendingOrHalt(state, now, "TREND_EXIT_FILL_QUANTITY_EVIDENCE_MISMATCH", position)
                    reducedQuantity.compareTo(BigDecimal.ZERO) == 0 && fillEvidence.quantity == null ->
                        recordNotFilled(state, order, entry = false, position = position, now = now)
                    fillEvidence.quantity == null ->
                        pendingOrHalt(state, now, "TREND_EXIT_FILL_QUANTITY_EVIDENCE_MISSING", position)
                    fillEvidence.quantity.compareTo(reducedQuantity) != 0 ->
                        pendingOrHalt(state, now, "TREND_EXIT_POSITION_QUANTITY_MISMATCH", position)
                    else -> recordNotFilled(state, order, entry = false, position = position, now = now)
                }
            }
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

    private fun recoveryOrderContract(
        state: VolumeConfirmedTrendLiveState,
        lifecycleEvents: List<VolumeConfirmedTrendLiveEvent>,
    ): RecoveryOrderContractLookup {
        val entry = state.isEntryRecovery()
        val phase = state.recoveryPhase()
        val expectedEventTypes =
            if (entry) {
                setOf(
                    VolumeConfirmedTrendLiveEventType.ENTRY_INTENT_RECORDED,
                    VolumeConfirmedTrendLiveEventType.ENTRY_SUBMITTED,
                )
            } else {
                setOf(
                    VolumeConfirmedTrendLiveEventType.EXIT_INTENT_RECORDED,
                    VolumeConfirmedTrendLiveEventType.EXIT_SUBMITTED,
                )
            }
        val event =
            lifecycleEvents.lastOrNull { candidate ->
                candidate.clientOrderId == state.clientOrderId &&
                    candidate.decisionKey == state.activeDecisionKey &&
                    candidate.type in expectedEventTypes
            } ?: return RecoveryOrderContractLookup(null, "${phase}_RECOVERY_INTENT_EVIDENCE_MISSING")
        val expectedOrderSide =
            if (entry) {
                state.pendingTargetSide
            } else {
                state.observedPositionSide?.opposite()
            }
        val quantity = event.orderQuantity
        val limitPrice = event.limitPrice
        val observedPositionQuantity = state.observedPositionQuantity
        val exchangeOrderIds = listOfNotNull(state.exchangeOrderId, event.exchangeOrderId).distinct()
        val invalid =
            event.protocolId != state.protocolId ||
                event.protocolSha256 != state.protocolSha256 ||
                event.symbol != config.symbol ||
                event.targetSide != state.pendingTargetSide ||
                event.orderSide == null ||
                event.orderSide != expectedOrderSide ||
                quantity == null ||
                quantity <= BigDecimal.ZERO ||
                limitPrice == null ||
                limitPrice <= BigDecimal.ZERO ||
                (!entry && (observedPositionQuantity == null || quantity.compareTo(observedPositionQuantity) != 0)) ||
                exchangeOrderIds.size > 1
        if (invalid) {
            return RecoveryOrderContractLookup(null, "${phase}_RECOVERY_INTENT_EVIDENCE_INVALID")
        }
        return RecoveryOrderContractLookup(
            contract =
                RecoveryOrderContract(
                    side = requireNotNull(event.orderSide),
                    quantity = requireNotNull(quantity),
                    limitPrice = requireNotNull(limitPrice),
                    reduceOnly = !entry,
                    exchangeOrderId = exchangeOrderIds.singleOrNull(),
                ),
            failureReasonCode = null,
        )
    }

    private fun ExchangeOpenOrder.contractFailure(
        state: VolumeConfirmedTrendLiveState,
        contract: RecoveryOrderContract,
    ): String? {
        val phase = state.recoveryPhase()
        val providerQuantity = quantity
        val providerPrice = price
        return when {
            clientOrderId != state.clientOrderId -> "${phase}_ORDER_CLIENT_ID_MISMATCH"
            symbol != config.symbol -> "${phase}_ORDER_SYMBOL_MISMATCH"
            exchangeOrderId.isNullOrBlank() -> "${phase}_ORDER_EXCHANGE_ID_MISSING"
            contract.exchangeOrderId != null && exchangeOrderId != contract.exchangeOrderId ->
                "${phase}_ORDER_EXCHANGE_ID_MISMATCH"
            side != contract.side -> "${phase}_ORDER_SIDE_MISMATCH"
            orderType != OrderType.LIMIT -> "${phase}_ORDER_TYPE_MISMATCH"
            providerQuantity == null || providerQuantity.compareTo(contract.quantity) != 0 ->
                "${phase}_ORDER_QUANTITY_MISMATCH"
            providerPrice == null || providerPrice.compareTo(contract.limitPrice) != 0 ->
                "${phase}_ORDER_PRICE_MISMATCH"
            timeInForce != ExchangeTimeInForce.IOC -> "${phase}_ORDER_TIME_IN_FORCE_MISMATCH"
            reduceOnly != contract.reduceOnly -> "${phase}_ORDER_REDUCE_ONLY_MISMATCH"
            filledQuantity != null && (filledQuantity < BigDecimal.ZERO || filledQuantity > providerQuantity) ->
                "${phase}_ORDER_FILL_QUANTITY_INVALID"
            else -> null
        }
    }

    private fun List<ExchangeExecutionFill>.contractFailure(
        state: VolumeConfirmedTrendLiveState,
        order: ExchangeOpenOrder?,
        contract: RecoveryOrderContract,
        now: Instant,
    ): String? {
        if (isEmpty()) return null
        val phase = state.recoveryPhase()
        val executionIds = map(ExchangeExecutionFill::executionId)
        val exchangeOrderIds = map(ExchangeExecutionFill::exchangeOrderId)
        val expectedExchangeOrderId = contract.exchangeOrderId ?: order?.exchangeOrderId
        return when {
            any { it.clientOrderId != state.clientOrderId } -> "${phase}_EXECUTION_CLIENT_ID_MISMATCH"
            any { it.symbol != config.symbol } -> "${phase}_EXECUTION_SYMBOL_MISMATCH"
            any { it.side != contract.side } -> "${phase}_EXECUTION_SIDE_MISMATCH"
            any { it.executionId.isNullOrBlank() } -> "${phase}_EXECUTION_ID_MISSING"
            executionIds.distinct().size != executionIds.size -> "${phase}_EXECUTION_ID_DUPLICATED"
            any { it.exchangeOrderId.isNullOrBlank() } -> "${phase}_EXECUTION_EXCHANGE_ORDER_ID_MISSING"
            exchangeOrderIds.distinct().size != 1 -> "${phase}_EXECUTION_EXCHANGE_ORDER_ID_MISMATCH"
            expectedExchangeOrderId != null && exchangeOrderIds.single() != expectedExchangeOrderId ->
                "${phase}_EXECUTION_EXCHANGE_ORDER_ID_MISMATCH"
            any { it.price <= BigDecimal.ZERO } -> "${phase}_EXECUTION_PRICE_INVALID"
            any { it.quantity <= BigDecimal.ZERO } -> "${phase}_EXECUTION_QUANTITY_INVALID"
            fold(BigDecimal.ZERO) { total, execution -> total + execution.quantity } > contract.quantity ->
                "${phase}_EXECUTION_QUANTITY_EXCEEDS_ORDER"
            any { it.executionType != "Trade" } -> "${phase}_EXECUTION_TYPE_MISMATCH"
            any { it.executedAt.isAfter(now) } -> "${phase}_EXECUTION_TIME_INVALID"
            else -> null
        }
    }

    private suspend fun contractFailureOrHalt(
        state: VolumeConfirmedTrendLiveState,
        order: ExchangeOpenOrder?,
        contract: RecoveryOrderContract?,
        now: Instant,
        reasonCode: String,
        position: ExchangePosition?,
    ): VolumeConfirmedTrendLiveEvaluationResult {
        if (order?.status?.isActive() == true && order.hasStableIdentity(state, contract)) {
            return activeOrderOrHalt(
                state = state,
                order = order,
                now = now,
                timeoutReasonCode = reasonCode,
                position = position,
                cancelImmediately = true,
            )
        }
        return pendingOrHalt(state, now, reasonCode, position)
    }

    private fun ExchangeOpenOrder.hasStableIdentity(
        state: VolumeConfirmedTrendLiveState,
        contract: RecoveryOrderContract?,
    ): Boolean =
        clientOrderId == state.clientOrderId &&
            symbol == config.symbol &&
            !exchangeOrderId.isNullOrBlank() &&
            (state.exchangeOrderId == null || exchangeOrderId == state.exchangeOrderId) &&
            (contract?.exchangeOrderId == null || exchangeOrderId == contract.exchangeOrderId)

    private suspend fun recordSubmittedRecovery(
        state: VolumeConfirmedTrendLiveState,
        order: ExchangeOpenOrder,
        now: Instant,
    ): VolumeConfirmedTrendLiveEvaluationResult {
        val entry =
            state.status in setOf(VolumeConfirmedTrendLiveStatus.ENTRY_INTENT_RECORDED, VolumeConfirmedTrendLiveStatus.ENTRY_SUBMITTED)
        val intentType =
            if (entry) {
                VolumeConfirmedTrendLiveEventType.ENTRY_INTENT_RECORDED
            } else {
                VolumeConfirmedTrendLiveEventType.EXIT_INTENT_RECORDED
            }
        val intentEvent =
            store
                .trendLiveEvents(config.protocolId, config.symbol, config.recoveryEventLimit)
                .lastOrNull { event ->
                    event.type == intentType &&
                        event.clientOrderId == state.clientOrderId &&
                        event.decisionKey == state.activeDecisionKey
                } ?: return halt(state, now, "${state.recoveryPhase()}_RECOVERY_INTENT_EVIDENCE_MISSING")
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
        val lifecycle = lifecycleEvent(submitted, type, "TREND_ORDER_ACK_RECOVERED", now)
        val event =
            intentEvent.copy(
                eventId = lifecycle.eventId,
                type = type,
                exchangeOrderId = order.exchangeOrderId,
                reasonCode = "TREND_ORDER_ACK_RECOVERED",
                occurredAt = now,
            )
        store.commitTrendLive(submitted, listOf(event))
        return VolumeConfirmedTrendLiveEvaluationResult(VolumeConfirmedTrendLiveEvaluationStatus.RECOVERED, submitted, null)
    }

    private suspend fun activeOrderOrHalt(
        state: VolumeConfirmedTrendLiveState,
        order: ExchangeOpenOrder,
        now: Instant,
        timeoutReasonCode: String,
        position: ExchangePosition? = null,
        cancelImmediately: Boolean = false,
    ): VolumeConfirmedTrendLiveEvaluationResult {
        val intentOnly =
            state.status == VolumeConfirmedTrendLiveStatus.ENTRY_INTENT_RECORDED ||
                state.status == VolumeConfirmedTrendLiveStatus.EXIT_INTENT_RECORDED
        val retryDelayPending = Duration.between(state.updatedAt, now) < config.recoveryRetryDelay
        val cancellationAlreadyRequested =
            store
                .trendLiveEvents(config.protocolId, config.symbol, config.recoveryEventLimit)
                .any { event ->
                    event.clientOrderId == state.clientOrderId &&
                        event.reasonCode == TREND_ACTIVE_ORDER_CANCEL_REQUESTED_REASON_CODE
                }
        if (cancellationAlreadyRequested) {
            if (retryDelayPending) {
                return VolumeConfirmedTrendLiveEvaluationResult(
                    status = VolumeConfirmedTrendLiveEvaluationStatus.RECOVERY_PENDING,
                    state = state,
                    plan = null,
                    recoveryReasonCode = TREND_ACTIVE_ORDER_CANCEL_REQUESTED_REASON_CODE,
                )
            }
            return halt(
                state = state,
                now = now,
                reasonCode = "$timeoutReasonCode|TREND_ACTIVE_ORDER_CANCEL_UNCONFIRMED",
                position = position,
            )
        }
        if (intentOnly && !cancelImmediately) return recordSubmittedRecovery(state, order, now)
        if (retryDelayPending && !cancelImmediately) {
            return VolumeConfirmedTrendLiveEvaluationResult(
                status = VolumeConfirmedTrendLiveEvaluationStatus.RECOVERY_PENDING,
                state = state,
                plan = null,
            )
        }

        val cancelled =
            gateway.cancelOrder(
                ExchangeCancelRequest(
                    symbol = config.symbol,
                    exchangeOrderId = order.exchangeOrderId ?: state.exchangeOrderId,
                    clientOrderId = order.clientOrderId ?: state.clientOrderId,
                ),
            )
        val cancellationPending =
            state.copy(
                exchangeOrderId = cancelled.exchangeOrderId ?: order.exchangeOrderId ?: state.exchangeOrderId,
                updatedAt = now,
            )
        val event =
            lifecycleEvent(
                cancellationPending,
                VolumeConfirmedTrendLiveEventType.RECONCILED,
                TREND_ACTIVE_ORDER_CANCEL_REQUESTED_REASON_CODE,
                now,
            )
        store.commitTrendLive(cancellationPending, listOf(event))
        return VolumeConfirmedTrendLiveEvaluationResult(
            status = VolumeConfirmedTrendLiveEvaluationStatus.RECOVERY_PENDING,
            state = cancellationPending,
            plan = null,
            recoveryReasonCode = TREND_ACTIVE_ORDER_CANCEL_REQUESTED_REASON_CODE,
        )
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
        val verifiedPosition = position?.takeIf { observed -> state.matchesObservedPosition(observed) }
        val halted =
            state.copy(
                status = VolumeConfirmedTrendLiveStatus.HALTED,
                observedPositionSide = verifiedPosition?.side ?: state.observedPositionSide,
                observedPositionQuantity = verifiedPosition?.size ?: state.observedPositionQuantity,
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

    private fun VolumeConfirmedTrendLiveState.isEntryRecovery(): Boolean =
        status == VolumeConfirmedTrendLiveStatus.ENTRY_INTENT_RECORDED ||
            status == VolumeConfirmedTrendLiveStatus.ENTRY_SUBMITTED

    private fun VolumeConfirmedTrendLiveState.recoveryPhase(): String = if (isEntryRecovery()) "TREND_ENTRY" else "TREND_EXIT"

    private fun Side.opposite(): Side = if (this == Side.BUY) Side.SELL else Side.BUY

    private fun fillQuantityEvidence(
        order: ExchangeOpenOrder?,
        executions: List<ExchangeExecutionFill>,
    ): FillQuantityEvidence {
        val executionIds = executions.map(ExchangeExecutionFill::executionId)
        val malformedExecutions =
            executions.any { it.executionId.isNullOrBlank() || it.quantity <= BigDecimal.ZERO } ||
                executionIds.distinct().size != executionIds.size
        val executionQuantity =
            executions
                .takeIf { it.isNotEmpty() && !malformedExecutions }
                ?.fold(BigDecimal.ZERO) { total, execution -> total + execution.quantity }
        val rawOrderQuantity = order?.filledQuantity
        val malformedOrderQuantity = rawOrderQuantity != null && rawOrderQuantity < BigDecimal.ZERO
        val orderQuantity = rawOrderQuantity?.takeIf { it > BigDecimal.ZERO }
        val orderExecutionQuantityMismatch =
            rawOrderQuantity != null &&
                executionQuantity != null &&
                rawOrderQuantity.compareTo(executionQuantity) != 0
        val consistent =
            !malformedExecutions &&
                !malformedOrderQuantity &&
                !orderExecutionQuantityMismatch
        return FillQuantityEvidence(
            quantity = if (consistent) executionQuantity ?: orderQuantity else null,
            consistent = consistent,
        )
    }

    private fun VolumeConfirmedTrendLiveState.matchesObservedPosition(position: ExchangePosition): Boolean =
        observedPositionSide == position.side && observedPositionQuantity?.compareTo(position.size) == 0

    private fun OrderStatus.isActive(): Boolean =
        this == OrderStatus.CREATED || this == OrderStatus.SUBMITTED || this == OrderStatus.PARTIALLY_FILLED

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

    private data class FillQuantityEvidence(
        val quantity: BigDecimal?,
        val consistent: Boolean,
    )

    private data class RecoveryOrderContract(
        val side: Side,
        val quantity: BigDecimal,
        val limitPrice: BigDecimal,
        val reduceOnly: Boolean,
        val exchangeOrderId: String?,
    )

    private data class RecoveryOrderContractLookup(
        val contract: RecoveryOrderContract?,
        val failureReasonCode: String?,
    )
}
