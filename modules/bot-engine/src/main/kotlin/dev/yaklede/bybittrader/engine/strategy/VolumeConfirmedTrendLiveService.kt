package dev.yaklede.bybittrader.engine.strategy

import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionGateway
import dev.yaklede.bybittrader.engine.execution.ExchangeInstrumentRules
import dev.yaklede.bybittrader.engine.execution.ExchangePosition
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.time.Instant

class VolumeConfirmedTrendLiveService(
    private val gateway: ExchangeExecutionGateway,
    private val store: VolumeConfirmedTrendLiveStore,
    private val config: VolumeConfirmedTrendLiveConfig,
    private val approvalReceipt: VolumeConfirmedTrendLiveApprovalReceipt,
    private val approvalReportProvider: suspend () -> VolumeConfirmedTrendApprovalReport,
    private val shadowEvidenceSha256: String,
    private val approvalReportSha256: String,
    private val executionContract: VolumeConfirmedTrendExecutionContract = VolumeConfirmedTrendExecutionContract(),
    private val clock: () -> Instant = Instant::now,
) {
    private val evaluationMutex = Mutex()
    private val recovery = VolumeConfirmedTrendLiveRecovery(gateway, store, config)

    suspend fun evaluate(
        command: VolumeConfirmedTrendCommand,
        referencePrice: BigDecimal,
    ): VolumeConfirmedTrendLiveEvaluationResult =
        evaluationMutex.withLock {
            evaluateLocked(command, referencePrice)
        }

    private suspend fun evaluateLocked(
        command: VolumeConfirmedTrendCommand,
        referencePrice: BigDecimal,
    ): VolumeConfirmedTrendLiveEvaluationResult {
        require(referencePrice > BigDecimal.ZERO) { "Trend live reference price must be positive." }
        val now = clock()
        val stored = store.trendLiveState(config.protocolId, config.symbol)
        val approval =
            VolumeConfirmedTrendLiveApprovalValidator.validate(
                receipt = approvalReceipt,
                report = approvalReportProvider(),
                actualShadowEvidenceSha256 = shadowEvidenceSha256,
                actualApprovalReportSha256 = approvalReportSha256,
            )
        if (!approval.liveExecutionAllowed) {
            val state = blockByApproval(stored, now)
            return VolumeConfirmedTrendLiveEvaluationResult(
                status = VolumeConfirmedTrendLiveEvaluationStatus.APPROVAL_BLOCKED,
                state = state,
                plan = null,
                approvalFailures = approval.failures,
            )
        }
        if (stored?.status == VolumeConfirmedTrendLiveStatus.HALTED) {
            return VolumeConfirmedTrendLiveEvaluationResult(
                status = VolumeConfirmedTrendLiveEvaluationStatus.HALTED,
                state = stored,
                plan = null,
            )
        }

        val instrument = gateway.instrumentRules(config.symbol)
        val contractValidation =
            VolumeConfirmedTrendExchangeContractValidator.validate(
                account = gateway.accountExecutionProfile(),
                position = gateway.positionExecutionProfile(config.symbol),
                instrument = instrument,
                contract = executionContract,
            )
        if (!contractValidation.valid) {
            return halt(
                previous = stored,
                command = command,
                now = now,
                reasonCode = "TREND_EXCHANGE_CONTRACT_MISMATCH",
                contractFailures = contractValidation.failures,
            )
        }
        val openPositions = gateway.positions(config.symbol).filter { it.size > BigDecimal.ZERO }
        if (openPositions.size > 1) {
            return halt(stored, command, now, "TREND_MULTIPLE_POSITIONS_OBSERVED")
        }
        val position = openPositions.singleOrNull()
        if (stored != null && stored.status in PENDING_ORDER_STATES) {
            return recovery.recover(stored, position, now)
        }
        if (stored == null && position != null) {
            return halt(null, command, now, "TREND_UNOWNED_POSITION_OBSERVED", position = position)
        }
        if (stored?.status in setOf(VolumeConfirmedTrendLiveStatus.FLAT, VolumeConfirmedTrendLiveStatus.ENTRY_NOT_FILLED) &&
            position != null
        ) {
            return halt(stored, command, now, "TREND_FLAT_STATE_POSITION_MISMATCH", position = position)
        }
        if (stored != null &&
            stored.status in setOf(VolumeConfirmedTrendLiveStatus.OPEN, VolumeConfirmedTrendLiveStatus.EXIT_NOT_FILLED) &&
            !stored.matches(position)
        ) {
            return halt(stored, command, now, "TREND_OPEN_STATE_POSITION_MISMATCH", position = position)
        }
        if (stored != null && stored.status in NOT_FILLED_STATES && stored.activeDecisionKey == commandDecisionKey(command)) {
            return VolumeConfirmedTrendLiveEvaluationResult(
                status = VolumeConfirmedTrendLiveEvaluationStatus.ORDER_NOT_FILLED,
                state = stored,
                plan = null,
            )
        }

        val balance = gateway.accountBalance("USDT")
        val equity =
            balance.totalEquity?.takeIf { it > BigDecimal.ZERO }
                ?: return halt(stored, command, now, "TREND_ACCOUNT_EQUITY_UNAVAILABLE", position = position)
        val observed = position?.toObservedPosition()
        val plan =
            VolumeConfirmedTrendTargetPlanner.plan(
                protocolSha256 = config.protocolSha256,
                command = command,
                accountEquity = equity,
                referencePrice = referencePrice,
                priceTick = instrument.priceTick,
                currentPosition = observed,
                contract = executionContract,
            )
        return when (plan.action) {
            VolumeConfirmedTrendTargetAction.NO_ACTION -> noAction(stored, command, position, plan, now)
            VolumeConfirmedTrendTargetAction.NO_TRADE -> noTrade(stored, command, plan, now)
            VolumeConfirmedTrendTargetAction.OPEN,
            VolumeConfirmedTrendTargetAction.CLOSE,
            -> submitPlan(stored, command, plan, instrument, position, now)
        }
    }

    private suspend fun submitPlan(
        previous: VolumeConfirmedTrendLiveState?,
        command: VolumeConfirmedTrendCommand,
        plan: VolumeConfirmedTrendTargetPlan,
        instrument: ExchangeInstrumentRules,
        position: ExchangePosition?,
        now: Instant,
    ): VolumeConfirmedTrendLiveEvaluationResult {
        val quantity = requireNotNull(plan.orderQuantity)
        val limitPrice = requireNotNull(plan.limitPrice)
        if (quantity * limitPrice < instrument.minimumNotional) {
            return noTrade(previous, command, plan, now, reasonCode = "MINIMUM_NOTIONAL_NOT_MET")
        }
        val intentType =
            if (plan.action == VolumeConfirmedTrendTargetAction.OPEN) {
                VolumeConfirmedTrendLiveEventType.ENTRY_INTENT_RECORDED
            } else {
                VolumeConfirmedTrendLiveEventType.EXIT_INTENT_RECORDED
            }
        val intentStatus =
            if (plan.action == VolumeConfirmedTrendTargetAction.OPEN) {
                VolumeConfirmedTrendLiveStatus.ENTRY_INTENT_RECORDED
            } else {
                VolumeConfirmedTrendLiveStatus.EXIT_INTENT_RECORDED
            }
        val intentState =
            baseState(previous, now).copy(
                status = intentStatus,
                approvalId = approvalReceipt.approvalId,
                activeDecisionKey = plan.decisionKey,
                pendingTargetSide = plan.targetSide,
                clientOrderId = plan.clientOrderId,
                exchangeOrderId = null,
                observedPositionSide = position?.side,
                observedPositionQuantity = position?.size,
                haltedReasonCode = null,
                updatedAt = now,
            )
        val event = plan.toIntentEvent(config.protocolId, config.protocolSha256, config.symbol, intentType, now)
        store.commitTrendLive(intentState, listOf(event))
        return submitRecordedIntent(intentState, plan, now)
    }

    private suspend fun submitRecordedIntent(
        intentState: VolumeConfirmedTrendLiveState,
        plan: VolumeConfirmedTrendTargetPlan,
        now: Instant,
    ): VolumeConfirmedTrendLiveEvaluationResult {
        val result = gateway.placeOrder(plan.toExchangeOrderRequest(config.symbol))
        val submittedStatus =
            if (plan.action == VolumeConfirmedTrendTargetAction.OPEN) {
                VolumeConfirmedTrendLiveStatus.ENTRY_SUBMITTED
            } else {
                VolumeConfirmedTrendLiveStatus.EXIT_SUBMITTED
            }
        val eventType =
            if (plan.action == VolumeConfirmedTrendTargetAction.OPEN) {
                VolumeConfirmedTrendLiveEventType.ENTRY_SUBMITTED
            } else {
                VolumeConfirmedTrendLiveEventType.EXIT_SUBMITTED
            }
        val submitted =
            intentState.copy(
                status = submittedStatus,
                exchangeOrderId = result.exchangeOrderId,
                updatedAt = now,
            )
        val event = plan.toSubmittedEvent(config.protocolId, config.protocolSha256, config.symbol, eventType, result, now)
        store.commitTrendLive(submitted, listOf(event))
        return VolumeConfirmedTrendLiveEvaluationResult(
            status = VolumeConfirmedTrendLiveEvaluationStatus.ORDER_SUBMITTED,
            state = submitted,
            plan = plan,
        )
    }

    private suspend fun noAction(
        previous: VolumeConfirmedTrendLiveState?,
        command: VolumeConfirmedTrendCommand,
        position: ExchangePosition?,
        plan: VolumeConfirmedTrendTargetPlan,
        now: Instant,
    ): VolumeConfirmedTrendLiveEvaluationResult {
        val state =
            baseState(previous, now).copy(
                status = if (position == null) VolumeConfirmedTrendLiveStatus.FLAT else VolumeConfirmedTrendLiveStatus.OPEN,
                approvalId = approvalReceipt.approvalId,
                activeDecisionKey = plan.decisionKey,
                pendingTargetSide = command.side,
                clientOrderId = null,
                exchangeOrderId = null,
                observedPositionSide = position?.side,
                observedPositionQuantity = position?.size,
                haltedReasonCode = null,
                updatedAt = now,
            )
        store.commitTrendLive(state, emptyList())
        return VolumeConfirmedTrendLiveEvaluationResult(VolumeConfirmedTrendLiveEvaluationStatus.NO_ACTION, state, plan)
    }

    private suspend fun noTrade(
        previous: VolumeConfirmedTrendLiveState?,
        command: VolumeConfirmedTrendCommand,
        plan: VolumeConfirmedTrendTargetPlan,
        now: Instant,
        reasonCode: String = plan.reasonCode,
    ): VolumeConfirmedTrendLiveEvaluationResult {
        val state =
            baseState(previous, now).copy(
                status = VolumeConfirmedTrendLiveStatus.FLAT,
                approvalId = approvalReceipt.approvalId,
                activeDecisionKey = plan.decisionKey,
                pendingTargetSide = command.side,
                clientOrderId = null,
                exchangeOrderId = null,
                observedPositionSide = null,
                observedPositionQuantity = null,
                haltedReasonCode = null,
                updatedAt = now,
            )
        val event =
            plan.toIntentEvent(
                config.protocolId,
                config.protocolSha256,
                config.symbol,
                VolumeConfirmedTrendLiveEventType.RECONCILED,
                now,
                reasonCode,
            )
        store.commitTrendLive(state, listOf(event))
        return VolumeConfirmedTrendLiveEvaluationResult(VolumeConfirmedTrendLiveEvaluationStatus.NO_TRADE, state, plan)
    }

    private suspend fun blockByApproval(
        previous: VolumeConfirmedTrendLiveState?,
        now: Instant,
    ): VolumeConfirmedTrendLiveState {
        val state =
            if (previous == null || previous.status == VolumeConfirmedTrendLiveStatus.DISABLED) {
                baseState(previous, now).copy(status = VolumeConfirmedTrendLiveStatus.DISABLED, updatedAt = now)
            } else {
                previous.copy(
                    status = VolumeConfirmedTrendLiveStatus.HALTED,
                    haltedReasonCode = "TREND_LIVE_NOT_APPROVED",
                    updatedAt = now,
                )
            }
        val event = lifecycleEvent(state, VolumeConfirmedTrendLiveEventType.HALTED, "TREND_LIVE_NOT_APPROVED", now)
        store.commitTrendLive(state, listOf(event))
        return state
    }

    private suspend fun halt(
        previous: VolumeConfirmedTrendLiveState?,
        command: VolumeConfirmedTrendCommand?,
        now: Instant,
        reasonCode: String,
        position: ExchangePosition? = null,
        contractFailures: List<VolumeConfirmedTrendExchangeContractFailure> = emptyList(),
    ): VolumeConfirmedTrendLiveEvaluationResult {
        val state =
            baseState(previous, now).copy(
                status = VolumeConfirmedTrendLiveStatus.HALTED,
                approvalId = approvalReceipt.approvalId,
                activeDecisionKey = command?.let { commandDecisionKey(it) } ?: previous?.activeDecisionKey,
                pendingTargetSide = command?.side ?: previous?.pendingTargetSide,
                observedPositionSide = position?.side ?: previous?.observedPositionSide,
                observedPositionQuantity = position?.size ?: previous?.observedPositionQuantity,
                haltedReasonCode = reasonCode,
                updatedAt = now,
            )
        val event = lifecycleEvent(state, VolumeConfirmedTrendLiveEventType.HALTED, reasonCode, now)
        store.commitTrendLive(state, listOf(event))
        return VolumeConfirmedTrendLiveEvaluationResult(
            status = VolumeConfirmedTrendLiveEvaluationStatus.HALTED,
            state = state,
            plan = null,
            contractFailures = contractFailures,
        )
    }

    private fun baseState(
        previous: VolumeConfirmedTrendLiveState?,
        now: Instant,
    ): VolumeConfirmedTrendLiveState =
        previous
            ?: VolumeConfirmedTrendLiveState(
                protocolId = config.protocolId,
                candidateId = config.candidateId,
                protocolSha256 = config.protocolSha256,
                symbol = config.symbol,
                status = VolumeConfirmedTrendLiveStatus.DISABLED,
                approvalId = null,
                activeDecisionKey = null,
                pendingTargetSide = null,
                clientOrderId = null,
                exchangeOrderId = null,
                observedPositionSide = null,
                observedPositionQuantity = null,
                lastExecutionId = null,
                haltedReasonCode = null,
                updatedAt = now,
            )

    private fun commandDecisionKey(command: VolumeConfirmedTrendCommand): String =
        "${config.protocolSha256}|${command.executionAt}|${command.side.name}"

    private fun VolumeConfirmedTrendLiveState.matches(position: ExchangePosition?): Boolean =
        position != null && observedPositionSide == position.side && observedPositionQuantity?.compareTo(position.size) == 0

    private companion object {
        val PENDING_ORDER_STATES =
            setOf(
                VolumeConfirmedTrendLiveStatus.ENTRY_INTENT_RECORDED,
                VolumeConfirmedTrendLiveStatus.ENTRY_SUBMITTED,
                VolumeConfirmedTrendLiveStatus.EXIT_INTENT_RECORDED,
                VolumeConfirmedTrendLiveStatus.EXIT_SUBMITTED,
            )
        val NOT_FILLED_STATES =
            setOf(
                VolumeConfirmedTrendLiveStatus.ENTRY_NOT_FILLED,
                VolumeConfirmedTrendLiveStatus.EXIT_NOT_FILLED,
            )
    }
}
