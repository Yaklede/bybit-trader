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
    private val projectionSink: VolumeConfirmedTrendLiveProjectionSink = NoopVolumeConfirmedTrendLiveProjectionSink,
    private val clock: () -> Instant = Instant::now,
) : VolumeConfirmedTrendLiveExecutor {
    private val evaluationMutex = Mutex()
    private val recovery = VolumeConfirmedTrendLiveRecovery(gateway, store, config, projectionSink)

    suspend fun evaluate(
        command: VolumeConfirmedTrendCommand,
        referencePrice: BigDecimal,
    ): VolumeConfirmedTrendLiveEvaluationResult = evaluate(command.toExecutionSignal(), referencePrice)

    override suspend fun evaluate(
        signal: VolumeConfirmedTrendExecutionSignal,
        referencePrice: BigDecimal,
    ): VolumeConfirmedTrendLiveEvaluationResult =
        evaluationMutex.withLock {
            evaluateLocked(signal, referencePrice)
        }

    override suspend fun reconcile(): VolumeConfirmedTrendLiveEvaluationResult =
        evaluationMutex.withLock {
            reconcileLocked()
        }

    override suspend fun haltForSafety(reasonCode: String): VolumeConfirmedTrendLiveEvaluationResult =
        evaluationMutex.withLock {
            require(reasonCode.isNotBlank()) { "Trend live safety halt reason must not be blank." }
            val now = clock()
            val stored = store.trendLiveState(config.protocolId, config.symbol)
            if (stored?.status == VolumeConfirmedTrendLiveStatus.HALTED && stored.haltedReasonCode == reasonCode) {
                reconcileHalted(stored, now)
            } else {
                halt(
                    previous = stored,
                    signal = null,
                    now = now,
                    reasonCode = reasonCode,
                )
            }
        }

    private suspend fun evaluateLocked(
        signal: VolumeConfirmedTrendExecutionSignal,
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
                signal = signal,
                now = now,
                reasonCode = "TREND_EXCHANGE_CONTRACT_MISMATCH",
                contractFailures = contractValidation.failures,
            )
        }
        val openPositions = gateway.positions(config.symbol).filter { it.size > BigDecimal.ZERO }
        if (openPositions.size > 1) {
            return halt(stored, signal, now, "TREND_MULTIPLE_POSITIONS_OBSERVED")
        }
        val position = openPositions.singleOrNull()
        if (stored != null && stored.status in PENDING_ORDER_STATES) {
            return recovery.recover(stored, position, now)
        }
        if (stored == null && position != null) {
            return halt(null, signal, now, "TREND_UNOWNED_POSITION_OBSERVED", position = position)
        }
        if (stored?.status in setOf(VolumeConfirmedTrendLiveStatus.FLAT, VolumeConfirmedTrendLiveStatus.ENTRY_NOT_FILLED) &&
            position != null
        ) {
            return halt(stored, signal, now, "TREND_FLAT_STATE_POSITION_MISMATCH", position = position)
        }
        if (stored != null &&
            stored.status in setOf(VolumeConfirmedTrendLiveStatus.OPEN, VolumeConfirmedTrendLiveStatus.EXIT_NOT_FILLED) &&
            !stored.matches(position)
        ) {
            return halt(stored, signal, now, "TREND_OPEN_STATE_POSITION_MISMATCH", position = position)
        }
        if (stored != null && stored.status in NOT_FILLED_STATES && stored.activeDecisionKey == signalDecisionKey(signal)) {
            return VolumeConfirmedTrendLiveEvaluationResult(
                status = VolumeConfirmedTrendLiveEvaluationStatus.ORDER_NOT_FILLED,
                state = stored,
                plan = null,
            )
        }

        val balance = gateway.accountBalance("USDT")
        projectionSink.recordAccountBalance(balance)
        val equity =
            balance.totalEquity?.takeIf { it > BigDecimal.ZERO }
                ?: return halt(stored, signal, now, "TREND_ACCOUNT_EQUITY_UNAVAILABLE", position = position)
        val observed = position?.toObservedPosition()
        val plan =
            VolumeConfirmedTrendTargetPlanner.plan(
                protocolSha256 = config.protocolSha256,
                signal = signal,
                accountEquity = equity,
                referencePrice = referencePrice,
                priceTick = instrument.priceTick,
                currentPosition = observed,
                contract = executionContract,
            )
        return when (plan.action) {
            VolumeConfirmedTrendTargetAction.NO_ACTION -> noAction(stored, signal, position, plan, now)
            VolumeConfirmedTrendTargetAction.NO_TRADE -> noTrade(stored, signal, plan, now)
            VolumeConfirmedTrendTargetAction.OPEN,
            VolumeConfirmedTrendTargetAction.CLOSE,
            -> submitPlan(stored, signal, plan, instrument, position, now)
        }
    }

    private suspend fun reconcileLocked(): VolumeConfirmedTrendLiveEvaluationResult {
        val now = clock()
        val stored = store.trendLiveState(config.protocolId, config.symbol)
        val approval = approvalValidation()
        if (!approval.liveExecutionAllowed) {
            val state = blockByApproval(stored, now)
            return VolumeConfirmedTrendLiveEvaluationResult(
                status = VolumeConfirmedTrendLiveEvaluationStatus.APPROVAL_BLOCKED,
                state = state,
                plan = null,
                approvalFailures = approval.failures,
            )
        }
        if (stored?.status == VolumeConfirmedTrendLiveStatus.HALTED) return reconcileHalted(stored, now)

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
                signal = null,
                now = now,
                reasonCode = "TREND_EXCHANGE_CONTRACT_MISMATCH",
                contractFailures = contractValidation.failures,
            )
        }
        captureAccountSnapshotIfDue(now)
        val openPositions = gateway.positions(config.symbol).filter { it.size > BigDecimal.ZERO }
        if (openPositions.size > 1) {
            return halt(stored, null, now, "TREND_MULTIPLE_POSITIONS_OBSERVED")
        }
        val position = openPositions.singleOrNull()
        if (stored != null && stored.status in PENDING_ORDER_STATES) {
            return recovery.recover(stored, position, now)
        }
        if (stored == null || stored.status == VolumeConfirmedTrendLiveStatus.DISABLED) {
            if (position != null) {
                return halt(stored, null, now, "TREND_UNOWNED_POSITION_OBSERVED", position = position)
            }
            val initialized =
                baseState(stored, now).copy(
                    status = VolumeConfirmedTrendLiveStatus.FLAT,
                    approvalId = approvalReceipt.approvalId,
                    updatedAt = now,
                )
            store.commitTrendLive(
                initialized,
                listOf(lifecycleEvent(initialized, VolumeConfirmedTrendLiveEventType.INITIALIZED, "TREND_LIVE_INITIALIZED", now)),
            )
            return VolumeConfirmedTrendLiveEvaluationResult(
                status = VolumeConfirmedTrendLiveEvaluationStatus.RECONCILED,
                state = initialized,
                plan = null,
            )
        }
        if (stored.status in setOf(VolumeConfirmedTrendLiveStatus.FLAT, VolumeConfirmedTrendLiveStatus.ENTRY_NOT_FILLED) &&
            position != null
        ) {
            return halt(stored, null, now, "TREND_FLAT_STATE_POSITION_MISMATCH", position = position)
        }
        if (stored.status in setOf(VolumeConfirmedTrendLiveStatus.OPEN, VolumeConfirmedTrendLiveStatus.EXIT_NOT_FILLED) &&
            !stored.matches(position)
        ) {
            return halt(stored, null, now, "TREND_OPEN_STATE_POSITION_MISMATCH", position = position)
        }
        return VolumeConfirmedTrendLiveEvaluationResult(
            status =
                if (stored.status in NOT_FILLED_STATES) {
                    VolumeConfirmedTrendLiveEvaluationStatus.ORDER_NOT_FILLED
                } else {
                    VolumeConfirmedTrendLiveEvaluationStatus.RECONCILED
                },
            state = stored,
            plan = null,
        )
    }

    private suspend fun reconcileHalted(
        stored: VolumeConfirmedTrendLiveState,
        now: Instant,
    ): VolumeConfirmedTrendLiveEvaluationResult {
        captureAccountSnapshotIfDue(now)
        val positions = gateway.positions(config.symbol).filter { it.size > BigDecimal.ZERO }
        if (positions.size > 1) {
            return halt(stored, null, now, "TREND_MULTIPLE_POSITIONS_OBSERVED")
        }
        val position = positions.singleOrNull()
        val reconciled =
            stored.copy(
                observedPositionSide = position?.side,
                observedPositionQuantity = position?.size,
                updatedAt = now,
            )
        if (!reconciled.samePersistedStateAs(stored)) {
            store.commitTrendLive(
                reconciled,
                listOf(
                    lifecycleEvent(
                        reconciled,
                        VolumeConfirmedTrendLiveEventType.RECONCILED,
                        "TREND_HALTED_STATE_RECONCILED",
                        now,
                    ),
                ),
            )
        }
        return VolumeConfirmedTrendLiveEvaluationResult(
            status = VolumeConfirmedTrendLiveEvaluationStatus.HALTED,
            state = if (reconciled.samePersistedStateAs(stored)) stored else reconciled,
            plan = null,
        )
    }

    private suspend fun captureAccountSnapshotIfDue(now: Instant) {
        if (!projectionSink.accountSnapshotDue(now)) return
        projectionSink.recordAccountBalance(gateway.accountBalance("USDT"))
    }

    private suspend fun approvalValidation(): VolumeConfirmedTrendLiveApprovalValidation =
        VolumeConfirmedTrendLiveApprovalValidator.validate(
            receipt = approvalReceipt,
            report = approvalReportProvider(),
            actualShadowEvidenceSha256 = shadowEvidenceSha256,
            actualApprovalReportSha256 = approvalReportSha256,
        )

    private suspend fun submitPlan(
        previous: VolumeConfirmedTrendLiveState?,
        signal: VolumeConfirmedTrendExecutionSignal,
        plan: VolumeConfirmedTrendTargetPlan,
        instrument: ExchangeInstrumentRules,
        position: ExchangePosition?,
        now: Instant,
    ): VolumeConfirmedTrendLiveEvaluationResult {
        val quantity = requireNotNull(plan.orderQuantity)
        val limitPrice = requireNotNull(plan.limitPrice)
        if (quantity * limitPrice < instrument.minimumNotional) {
            return noTrade(previous, signal, plan, now, reasonCode = "MINIMUM_NOTIONAL_NOT_MET")
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
        signal: VolumeConfirmedTrendExecutionSignal,
        position: ExchangePosition?,
        plan: VolumeConfirmedTrendTargetPlan,
        now: Instant,
    ): VolumeConfirmedTrendLiveEvaluationResult {
        val state =
            baseState(previous, now).copy(
                status = if (position == null) VolumeConfirmedTrendLiveStatus.FLAT else VolumeConfirmedTrendLiveStatus.OPEN,
                approvalId = approvalReceipt.approvalId,
                activeDecisionKey = plan.decisionKey,
                pendingTargetSide = signal.side,
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
        signal: VolumeConfirmedTrendExecutionSignal,
        plan: VolumeConfirmedTrendTargetPlan,
        now: Instant,
        reasonCode: String = plan.reasonCode,
    ): VolumeConfirmedTrendLiveEvaluationResult {
        val state =
            baseState(previous, now).copy(
                status = VolumeConfirmedTrendLiveStatus.FLAT,
                approvalId = approvalReceipt.approvalId,
                activeDecisionKey = plan.decisionKey,
                pendingTargetSide = signal.side,
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
        if (state.samePersistedStateAs(previous)) return requireNotNull(previous)
        val event = lifecycleEvent(state, VolumeConfirmedTrendLiveEventType.HALTED, "TREND_LIVE_NOT_APPROVED", now)
        store.commitTrendLive(state, listOf(event))
        return state
    }

    private suspend fun halt(
        previous: VolumeConfirmedTrendLiveState?,
        signal: VolumeConfirmedTrendExecutionSignal?,
        now: Instant,
        reasonCode: String,
        position: ExchangePosition? = null,
        contractFailures: List<VolumeConfirmedTrendExchangeContractFailure> = emptyList(),
    ): VolumeConfirmedTrendLiveEvaluationResult {
        val state =
            baseState(previous, now).copy(
                status = VolumeConfirmedTrendLiveStatus.HALTED,
                approvalId = approvalReceipt.approvalId,
                activeDecisionKey = signal?.let { signalDecisionKey(it) } ?: previous?.activeDecisionKey,
                pendingTargetSide = signal?.side ?: previous?.pendingTargetSide,
                observedPositionSide = position?.side ?: previous?.observedPositionSide,
                observedPositionQuantity = position?.size ?: previous?.observedPositionQuantity,
                haltedReasonCode = reasonCode,
                updatedAt = now,
            )
        if (state.samePersistedStateAs(previous)) {
            return VolumeConfirmedTrendLiveEvaluationResult(
                status = VolumeConfirmedTrendLiveEvaluationStatus.HALTED,
                state = requireNotNull(previous),
                plan = null,
                contractFailures = contractFailures,
            )
        }
        val event = lifecycleEvent(state, VolumeConfirmedTrendLiveEventType.HALTED, reasonCode, now)
        store.commitTrendLive(state, listOf(event))
        return VolumeConfirmedTrendLiveEvaluationResult(
            status = VolumeConfirmedTrendLiveEvaluationStatus.HALTED,
            state = state,
            plan = null,
            contractFailures = contractFailures,
        )
    }

    private fun VolumeConfirmedTrendLiveState.samePersistedStateAs(previous: VolumeConfirmedTrendLiveState?): Boolean =
        previous != null && copy(updatedAt = previous.updatedAt) == previous

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

    private fun signalDecisionKey(signal: VolumeConfirmedTrendExecutionSignal): String =
        "${config.protocolSha256}|${signal.executionAt}|${signal.side.name}"

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
