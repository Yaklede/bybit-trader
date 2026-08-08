package dev.yaklede.bybittrader.engine.strategy

import dev.yaklede.bybittrader.engine.execution.ExchangeAccountBalance
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionGateway
import dev.yaklede.bybittrader.engine.execution.ExchangeInstrumentRules
import dev.yaklede.bybittrader.engine.execution.ExchangeOpenOrder
import dev.yaklede.bybittrader.engine.execution.ExchangePosition
import dev.yaklede.bybittrader.engine.execution.ExchangePositionExecutionProfile
import dev.yaklede.bybittrader.engine.execution.ExchangePositionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class VolumeConfirmedTrendLiveService(
    private val gateway: ExchangeExecutionGateway,
    private val store: VolumeConfirmedTrendLiveStore,
    private val config: VolumeConfirmedTrendLiveConfig,
    private val approvalReceipt: VolumeConfirmedTrendLiveApprovalReceipt,
    private val approvalReportProvider: suspend () -> VolumeConfirmedTrendApprovalReport,
    private val shadowEvidenceSha256: String,
    private val approvalReportSha256: String,
    private val projectionSink: VolumeConfirmedTrendLiveProjectionSink,
    private val executionContract: VolumeConfirmedTrendExecutionContract = VolumeConfirmedTrendExecutionContract(),
    private val clock: () -> Instant = Instant::now,
) : VolumeConfirmedTrendLiveExecutor {
    private val logger = LoggerFactory.getLogger(javaClass)
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
            safetyHalt(stored, reasonCode, now)
        }

    private suspend fun safetyHalt(
        stored: VolumeConfirmedTrendLiveState?,
        reasonCode: String,
        now: Instant,
    ): VolumeConfirmedTrendLiveEvaluationResult {
        val positions =
            try {
                gateway.positions(config.symbol).filter { it.size > BigDecimal.ZERO }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logger.warn("trend safety halt position read failed reason={}", reasonCode, error)
                return halt(stored, null, now, "$reasonCode|TREND_SAFETY_POSITION_READ_UNAVAILABLE")
            }
        if (positions.size > 1) {
            return halt(stored, null, now, "TREND_MULTIPLE_POSITIONS_OBSERVED")
        }
        val position = positions.singleOrNull()
        if (stored != null && stored.status in PENDING_ORDER_STATES) {
            return recovery.recover(stored, position, now)
        }
        if (position == null) return halt(stored, null, now, reasonCode)
        if (stored == null || !stored.ownsManagedPosition(position)) {
            return halt(
                previous = stored,
                signal = null,
                now = now,
                reasonCode = "$reasonCode|TREND_SAFETY_POSITION_OWNERSHIP_UNCONFIRMED",
                position = position,
            )
        }
        if (stored.status == VolumeConfirmedTrendLiveStatus.EXIT_NOT_FILLED) {
            val retryAge = Duration.between(stored.updatedAt, now)
            if (retryAge.isNegative) {
                return halt(
                    previous = stored,
                    signal = null,
                    now = now,
                    reasonCode = "$reasonCode|TREND_SAFETY_EXIT_RETRY_CLOCK_SKEW",
                    position = position,
                )
            }
            if (retryAge < config.approvalRevocationExitRetryDelay) {
                return VolumeConfirmedTrendLiveEvaluationResult(
                    status = VolumeConfirmedTrendLiveEvaluationStatus.ORDER_NOT_FILLED,
                    state = stored,
                    plan = null,
                )
            }
        }
        val accountOpenOrders =
            try {
                gateway.openOrdersBySettleCoin(TREND_SETTLE_COIN)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logger.warn("trend safety halt order read failed reason={}", reasonCode, error)
                return halt(
                    stored,
                    null,
                    now,
                    "$reasonCode|TREND_SAFETY_ORDER_READ_UNAVAILABLE",
                    position = position,
                )
            }
        if (accountOpenOrders.any(::isUnresolvedOwnedOrder)) {
            return halt(stored, null, now, "TREND_UNRESOLVED_OWNED_OPEN_ORDER_OBSERVED", position = position)
        }
        val referencePrice = position.markPrice
        if (referencePrice == null || referencePrice <= BigDecimal.ZERO) {
            return halt(
                stored,
                null,
                now,
                "$reasonCode|TREND_SAFETY_EXIT_PRICE_UNAVAILABLE",
                position = position,
            )
        }
        val exitContract =
            try {
                SafetyExitContract(
                    position = gateway.positionExecutionProfile(config.symbol),
                    instrument = gateway.instrumentRules(config.symbol),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logger.warn("trend safety halt instrument read failed reason={}", reasonCode, error)
                return halt(
                    stored,
                    null,
                    now,
                    "$reasonCode|TREND_SAFETY_INSTRUMENT_READ_UNAVAILABLE",
                    position = position,
                )
            }
        if (!exitContract.allowsOrder(config.symbol)) {
            return halt(
                stored,
                null,
                now,
                "$reasonCode|TREND_SAFETY_EXIT_CONTRACT_UNAVAILABLE",
                position = position,
            )
        }
        val instrument = exitContract.instrument
        val plan =
            VolumeConfirmedTrendTargetPlanner.safetyExit(
                protocolSha256 = config.protocolSha256,
                observedAt = now,
                referencePrice = referencePrice,
                priceTick = instrument.priceTick,
                currentPosition = position.toObservedPosition(),
                contract = executionContract,
                reasonCode = "$TREND_SAFETY_HALT_EXIT_REASON_CODE_PREFIX|$reasonCode",
            )
        val signal =
            VolumeConfirmedTrendExecutionSignal(
                side = plan.targetSide,
                decisionAt = now,
                executionAt = now,
            )
        return submitPlan(stored, signal, plan, instrument, position, now)
    }

    private suspend fun evaluateLocked(
        signal: VolumeConfirmedTrendExecutionSignal,
        referencePrice: BigDecimal,
    ): VolumeConfirmedTrendLiveEvaluationResult {
        require(referencePrice > BigDecimal.ZERO) { "Trend live reference price must be positive." }
        val now = clock()
        val stored = store.trendLiveState(config.protocolId, config.symbol)
        val approval = approvalValidation()
        if (!approval.liveExecutionAllowed) {
            return manageApprovalRevocation(stored, approval, now, referencePrice)
        }
        recoverPendingOrder(stored, now)?.let { return it }
        if (stored?.status == VolumeConfirmedTrendLiveStatus.HALTED) {
            return VolumeConfirmedTrendLiveEvaluationResult(
                status = VolumeConfirmedTrendLiveEvaluationStatus.HALTED,
                state = stored,
                plan = null,
            )
        }

        val accountPositions = gateway.positionsBySettleCoin(TREND_SETTLE_COIN).filter { it.size > BigDecimal.ZERO }
        val openPositions = accountPositions.filter { it.symbol == config.symbol }
        if (openPositions.size > 1) {
            return halt(stored, signal, now, "TREND_MULTIPLE_POSITIONS_OBSERVED")
        }
        val position = openPositions.singleOrNull()
        val accountOpenOrders = gateway.openOrdersBySettleCoin(TREND_SETTLE_COIN)
        if (accountOpenOrders.any(::isUnresolvedOwnedOrder)) {
            return halt(stored, signal, now, "TREND_UNRESOLVED_OWNED_OPEN_ORDER_OBSERVED", position = position)
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
        if (position != null && (stored == null || !stored.ownsManagedPosition(position))) {
            return halt(stored, signal, now, "TREND_UNOWNED_POSITION_OBSERVED", position = position)
        }
        val inventoryReasonCodes = accountInventoryReasonCodes(accountPositions, accountOpenOrders)
        if (position == null && inventoryReasonCodes.isNotEmpty()) {
            return halt(stored, signal, now, inventoryReasonCodes.first())
        }
        if (stored != null && stored.status in NOT_FILLED_STATES && stored.activeDecisionKey == signalDecisionKey(signal)) {
            return VolumeConfirmedTrendLiveEvaluationResult(
                status = VolumeConfirmedTrendLiveEvaluationStatus.ORDER_NOT_FILLED,
                state = stored,
                plan = null,
            )
        }
        if (position != null && position.side != signal.side) {
            val exitContract =
                try {
                    SafetyExitContract(
                        position = gateway.positionExecutionProfile(config.symbol),
                        instrument = gateway.instrumentRules(config.symbol),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logger.warn("trend owned-position exit contract read failed", error)
                    return halt(
                        stored,
                        signal,
                        now,
                        "TREND_POSITION_EXIT_CONTRACT_READ_UNAVAILABLE",
                        position = position,
                    )
                }
            if (!exitContract.allowsOrder(config.symbol)) {
                return halt(
                    stored,
                    signal,
                    now,
                    "TREND_POSITION_EXIT_CONTRACT_UNAVAILABLE",
                    position = position,
                )
            }
            val plan =
                VolumeConfirmedTrendTargetPlanner.plan(
                    protocolSha256 = config.protocolSha256,
                    signal = signal,
                    accountEquity = BigDecimal.ZERO,
                    referencePrice = referencePrice,
                    priceTick = exitContract.instrument.priceTick,
                    currentPosition = position.toObservedPosition(),
                    contract = executionContract,
                )
            return submitPlan(stored, signal, plan, exitContract.instrument, position, now)
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
            return safetyHalt(stored, TREND_EXCHANGE_CONTRACT_MISMATCH_REASON_CODE, now).copy(
                contractFailures = contractValidation.failures,
            )
        }
        val capturedBalance = captureAccountSnapshotIfDue(now)
        captureAccountingIfDue(now, stored?.updatedAt ?: now)
        val riskAssessment = projectionSink.assessEntryRisk(stored?.riskState, now, config.riskPolicy)
        val effectiveStored = persistRiskState(stored, riskAssessment, now)
        val balance = capturedBalance ?: captureAccountBalance()
        val accountIsolation = VolumeConfirmedTrendAccountIsolationPolicy.assess(balance, TREND_SETTLE_COIN)
        if (position == null && !accountIsolation.allowsEntry) {
            return halt(
                effectiveStored,
                signal,
                now,
                requireNotNull(accountIsolation.reasonCode),
            )
        }
        val equity = accountIsolation.tradingEquity ?: BigDecimal.ZERO
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
            VolumeConfirmedTrendTargetAction.NO_ACTION ->
                if (position != null && inventoryReasonCodes.isNotEmpty()) {
                    managedPositionEntryBlocked(
                        previous = requireNotNull(effectiveStored),
                        signal = signal,
                        position = position,
                        plan = plan,
                        assessment = riskAssessment,
                        additionalReasonCodes = inventoryReasonCodes,
                        now = now,
                    )
                } else {
                    noAction(effectiveStored, signal, position, plan, now)
                }
            VolumeConfirmedTrendTargetAction.NO_TRADE -> noTrade(effectiveStored, signal, plan, now)
            VolumeConfirmedTrendTargetAction.OPEN ->
                if (riskAssessment.allowsEntry) {
                    submitPlan(effectiveStored, signal, plan, instrument, position, now)
                } else {
                    riskBlocked(effectiveStored, signal, plan, riskAssessment, now)
                }
            VolumeConfirmedTrendTargetAction.CLOSE -> submitPlan(effectiveStored, signal, plan, instrument, position, now)
        }
    }

    private suspend fun reconcileLocked(): VolumeConfirmedTrendLiveEvaluationResult {
        val now = clock()
        val stored = store.trendLiveState(config.protocolId, config.symbol)
        val approval = approvalValidation()
        if (!approval.liveExecutionAllowed) {
            return manageApprovalRevocation(stored, approval, now, null)
        }
        recoverPendingOrder(stored, now)?.let { return it }
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
            return safetyHalt(stored, TREND_EXCHANGE_CONTRACT_MISMATCH_REASON_CODE, now).copy(
                contractFailures = contractValidation.failures,
            )
        }
        val capturedBalance = captureAccountSnapshotIfDue(now)
        captureAccountingIfDue(now, stored?.updatedAt ?: now)
        val riskAssessment = projectionSink.assessEntryRisk(stored?.riskState, now, config.riskPolicy)
        val effectiveStored = persistRiskState(stored, riskAssessment, now)
        val accountPositions = gateway.positionsBySettleCoin(TREND_SETTLE_COIN).filter { it.size > BigDecimal.ZERO }
        val openPositions = accountPositions.filter { it.symbol == config.symbol }
        if (openPositions.size > 1) {
            return halt(effectiveStored, null, now, "TREND_MULTIPLE_POSITIONS_OBSERVED")
        }
        val position = openPositions.singleOrNull()
        val accountOpenOrders = gateway.openOrdersBySettleCoin(TREND_SETTLE_COIN)
        if (accountOpenOrders.any(::isUnresolvedOwnedOrder)) {
            return halt(effectiveStored, null, now, "TREND_UNRESOLVED_OWNED_OPEN_ORDER_OBSERVED", position = position)
        }
        if (effectiveStored == null && position != null) {
            return halt(effectiveStored, null, now, "TREND_UNOWNED_POSITION_OBSERVED", position = position)
        }
        if (effectiveStored != null &&
            effectiveStored.status in setOf(VolumeConfirmedTrendLiveStatus.FLAT, VolumeConfirmedTrendLiveStatus.ENTRY_NOT_FILLED) &&
            position != null
        ) {
            return halt(effectiveStored, null, now, "TREND_FLAT_STATE_POSITION_MISMATCH", position = position)
        }
        if (effectiveStored != null &&
            effectiveStored.status in setOf(VolumeConfirmedTrendLiveStatus.OPEN, VolumeConfirmedTrendLiveStatus.EXIT_NOT_FILLED) &&
            !effectiveStored.matches(position)
        ) {
            return halt(effectiveStored, null, now, "TREND_OPEN_STATE_POSITION_MISMATCH", position = position)
        }
        val inventoryReasonCodes = accountInventoryReasonCodes(accountPositions, accountOpenOrders)
        if (position == null && inventoryReasonCodes.isNotEmpty()) {
            return halt(effectiveStored, null, now, inventoryReasonCodes.first())
        }
        if (position != null && inventoryReasonCodes.isNotEmpty()) {
            return managedPositionEntryBlocked(
                previous = requireNotNull(effectiveStored),
                signal = null,
                position = position,
                plan = null,
                assessment = riskAssessment,
                additionalReasonCodes = inventoryReasonCodes,
                now = now,
            )
        }
        val entryCapableState =
            effectiveStored == null ||
                effectiveStored.status in
                setOf(
                    VolumeConfirmedTrendLiveStatus.DISABLED,
                    VolumeConfirmedTrendLiveStatus.FLAT,
                    VolumeConfirmedTrendLiveStatus.ENTRY_NOT_FILLED,
                )
        if (position == null && entryCapableState) {
            val balance = capturedBalance ?: captureAccountBalance()
            val accountIsolation = VolumeConfirmedTrendAccountIsolationPolicy.assess(balance, TREND_SETTLE_COIN)
            if (!accountIsolation.allowsEntry) {
                return halt(
                    effectiveStored,
                    null,
                    now,
                    requireNotNull(accountIsolation.reasonCode),
                )
            }
        }
        if (effectiveStored == null || effectiveStored.status == VolumeConfirmedTrendLiveStatus.DISABLED) {
            val initialized =
                baseState(effectiveStored, now).copy(
                    status = VolumeConfirmedTrendLiveStatus.FLAT,
                    approvalId = approvalReceipt.approvalId,
                    riskState = riskAssessment.state,
                    riskReasonCodes = riskAssessment.reasonCodes,
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
        return VolumeConfirmedTrendLiveEvaluationResult(
            status =
                if (effectiveStored.status in NOT_FILLED_STATES) {
                    VolumeConfirmedTrendLiveEvaluationStatus.ORDER_NOT_FILLED
                } else {
                    VolumeConfirmedTrendLiveEvaluationStatus.RECONCILED
                },
            state = effectiveStored,
            plan = null,
        )
    }

    private suspend fun reconcileHalted(
        stored: VolumeConfirmedTrendLiveState,
        now: Instant,
    ): VolumeConfirmedTrendLiveEvaluationResult {
        val accountPositions = gateway.positionsBySettleCoin(TREND_SETTLE_COIN).filter { it.size > BigDecimal.ZERO }
        val positions = accountPositions.filter { it.symbol == config.symbol }
        if (positions.size > 1) {
            return halt(stored, null, now, "TREND_MULTIPLE_POSITIONS_OBSERVED")
        }
        val position = positions.singleOrNull()
        if (position != null && !stored.ownsManagedPosition(position)) {
            return halt(stored, null, now, "TREND_HALTED_POSITION_OWNERSHIP_MISMATCH")
        }
        if (stored.haltedReasonCode?.startsWith(TREND_EXCHANGE_CONTRACT_MISMATCH_REASON_CODE) == true &&
            position != null
        ) {
            return safetyHalt(stored, TREND_EXCHANGE_CONTRACT_MISMATCH_REASON_CODE, now)
        }
        captureAccountSnapshotIfDue(now)
        captureAccountingIfDue(now, stored.updatedAt)
        val riskAssessment = projectionSink.assessEntryRisk(stored.riskState, now, config.riskPolicy)
        val riskStored = requireNotNull(persistRiskState(stored, riskAssessment, now))
        if (riskStored.haltedReasonCode in RECOVERABLE_ENTRY_INVENTORY_HALT_REASONS &&
            position != null &&
            riskStored.matches(position)
        ) {
            val accountOpenOrders = gateway.openOrdersBySettleCoin(TREND_SETTLE_COIN)
            if (accountOpenOrders.none(::isUnresolvedOwnedOrder)) {
                return resumeManagedPosition(
                    previous = riskStored,
                    position = position,
                    assessment = riskAssessment,
                    reasonCodes = accountInventoryReasonCodes(accountPositions, accountOpenOrders),
                    now = now,
                )
            }
        }
        val reconciled =
            riskStored.copy(
                observedPositionSide = position?.side,
                observedPositionQuantity = position?.size,
                updatedAt = now,
            )
        if (!reconciled.samePersistedStateAs(riskStored)) {
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
            state = if (reconciled.samePersistedStateAs(riskStored)) riskStored else reconciled,
            plan = null,
        )
    }

    private suspend fun recoverPendingOrder(
        stored: VolumeConfirmedTrendLiveState?,
        now: Instant,
    ): VolumeConfirmedTrendLiveEvaluationResult? {
        if (stored == null || stored.status !in PENDING_ORDER_STATES) return null
        val positions = gateway.positions(config.symbol).filter { it.size > BigDecimal.ZERO }
        if (positions.size > 1) {
            return halt(stored, null, now, "TREND_MULTIPLE_POSITIONS_OBSERVED")
        }
        return recovery.recover(stored, positions.singleOrNull(), now)
    }

    private suspend fun captureAccountSnapshotIfDue(now: Instant): ExchangeAccountBalance? {
        if (!projectionSink.accountSnapshotDue(now)) return null
        return captureAccountBalance()
    }

    private suspend fun captureAccountBalance(): ExchangeAccountBalance =
        gateway.accountBalance().also { balance ->
            projectionSink.recordAccountBalance(balance)
        }

    private suspend fun captureAccountingIfDue(
        now: Instant,
        recoveryStartAt: Instant,
    ) {
        val request = projectionSink.reserveAccountingRequest(now, recoveryStartAt) ?: return
        try {
            val executions =
                request.closureStartAt
                    ?.let { startAt -> gateway.executions(config.symbol, startAt, request.requestedAt) }
                    .orEmpty()
            val closedPnls =
                request.closureStartAt
                    ?.let { startAt -> gateway.closedPnls(config.symbol, startAt, request.requestedAt) }
                    .orEmpty()
            val accountTransactions =
                request.transactionStartAt
                    ?.let { startAt -> gateway.accountTransactions("USDT", startAt, request.requestedAt) }
                    .orEmpty()
            projectionSink.recordAccounting(
                VolumeConfirmedTrendLiveAccountingObservation(
                    request = request,
                    executions = executions,
                    closedPnls = closedPnls,
                    accountTransactions = accountTransactions,
                    receivedAt = now,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            try {
                projectionSink.recordAccountingFailure(request, now)
            } catch (projectionError: Throwable) {
                error.addSuppressed(projectionError)
            }
            throw error
        }
    }

    private suspend fun persistRiskState(
        previous: VolumeConfirmedTrendLiveState?,
        assessment: VolumeConfirmedTrendLiveRiskAssessment,
        now: Instant,
    ): VolumeConfirmedTrendLiveState? {
        if (
            previous == null ||
            previous.riskState == assessment.state &&
            previous.riskReasonCodes == assessment.reasonCodes
        ) {
            return previous
        }
        val updated =
            previous.copy(
                riskState = assessment.state,
                riskReasonCodes = assessment.reasonCodes,
                updatedAt =
                    if (previous.status in PENDING_ORDER_STATES) {
                        previous.updatedAt
                    } else {
                        now
                    },
            )
        store.commitTrendLive(updated, emptyList())
        return updated
    }

    private suspend fun approvalValidation(): VolumeConfirmedTrendLiveApprovalValidation =
        try {
            VolumeConfirmedTrendLiveApprovalValidator.validate(
                receipt = approvalReceipt,
                report = approvalReportProvider(),
                actualShadowEvidenceSha256 = shadowEvidenceSha256,
                actualApprovalReportSha256 = approvalReportSha256,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger.warn("volume-confirmed trend live approval report unavailable", error)
            VolumeConfirmedTrendLiveApprovalValidation(
                liveExecutionAllowed = false,
                failures = listOf(VolumeConfirmedTrendLiveApprovalFailure.APPROVAL_REPORT_UNAVAILABLE),
            )
        }

    private suspend fun manageApprovalRevocation(
        stored: VolumeConfirmedTrendLiveState?,
        approval: VolumeConfirmedTrendLiveApprovalValidation,
        now: Instant,
        referencePrice: BigDecimal?,
    ): VolumeConfirmedTrendLiveEvaluationResult {
        if (stored == null || stored.status == VolumeConfirmedTrendLiveStatus.DISABLED) {
            return approvalBlocked(blockByApproval(stored, now), approval)
        }

        val openPositions =
            try {
                gateway.positions(config.symbol).filter { it.size > BigDecimal.ZERO }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logger.warn("trend approval-revocation position read failed", error)
                return halt(
                    stored,
                    null,
                    now,
                    "TREND_APPROVAL_REVOKED_POSITION_READ_UNAVAILABLE",
                ).withApproval(approval)
            }
        if (openPositions.size > 1) {
            return halt(stored, null, now, "TREND_MULTIPLE_POSITIONS_OBSERVED").withApproval(approval)
        }
        val position = openPositions.singleOrNull()
        if (stored.status in PENDING_ORDER_STATES) {
            return recovery.recover(stored, position, now).withApproval(approval)
        }
        if (position == null) {
            captureAccountSnapshotIfDue(now)
            captureAccountingIfDue(now, stored.updatedAt)
            return approvalBlocked(
                blockByApproval(
                    previous = stored,
                    now = now,
                    positionConfirmedFlat = true,
                ),
                approval,
            )
        }
        if (!stored.ownsManagedPosition(position)) {
            return halt(
                previous = stored,
                signal = null,
                now = now,
                reasonCode = "TREND_APPROVAL_REVOKED_POSITION_OWNERSHIP_UNCONFIRMED",
                position = position,
            ).withApproval(approval)
        }
        if (stored.status == VolumeConfirmedTrendLiveStatus.EXIT_NOT_FILLED) {
            val retryAge = Duration.between(stored.updatedAt, now)
            if (retryAge.isNegative) {
                return halt(
                    previous = stored,
                    signal = null,
                    now = now,
                    reasonCode = "TREND_APPROVAL_REVOKED_EXIT_RETRY_CLOCK_SKEW",
                    position = position,
                ).withApproval(approval)
            }
            if (retryAge < config.approvalRevocationExitRetryDelay) {
                return approvalBlocked(stored, approval)
            }
        }

        val accountOpenOrders =
            try {
                gateway.openOrdersBySettleCoin(TREND_SETTLE_COIN)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logger.warn("trend approval-revocation order read failed", error)
                return halt(
                    stored,
                    null,
                    now,
                    "TREND_APPROVAL_REVOKED_ORDER_READ_UNAVAILABLE",
                    position = position,
                ).withApproval(approval)
            }
        if (accountOpenOrders.any(::isUnresolvedOwnedOrder)) {
            return halt(
                stored,
                null,
                now,
                "TREND_UNRESOLVED_OWNED_OPEN_ORDER_OBSERVED",
                position = position,
            ).withApproval(approval)
        }

        val exitContract =
            try {
                SafetyExitContract(
                    position = gateway.positionExecutionProfile(config.symbol),
                    instrument = gateway.instrumentRules(config.symbol),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logger.warn("trend approval-revocation instrument read failed", error)
                return halt(
                    stored,
                    null,
                    now,
                    "TREND_APPROVAL_REVOKED_INSTRUMENT_READ_UNAVAILABLE",
                    position = position,
                ).withApproval(approval)
            }
        if (!exitContract.allowsOrder(config.symbol)) {
            return halt(
                stored,
                null,
                now,
                "TREND_APPROVAL_REVOKED_EXIT_CONTRACT_UNAVAILABLE",
                position = position,
            ).withApproval(approval)
        }

        val exitReferencePrice = referencePrice?.takeIf { it > BigDecimal.ZERO } ?: position.markPrice
        if (exitReferencePrice == null || exitReferencePrice <= BigDecimal.ZERO) {
            return halt(
                previous = stored,
                signal = null,
                now = now,
                reasonCode = "TREND_APPROVAL_REVOKED_EXIT_PRICE_UNAVAILABLE",
                position = position,
            ).withApproval(approval)
        }
        val instrument = exitContract.instrument
        val plan =
            VolumeConfirmedTrendTargetPlanner.safetyExit(
                protocolSha256 = config.protocolSha256,
                observedAt = now,
                referencePrice = exitReferencePrice,
                priceTick = instrument.priceTick,
                currentPosition = position.toObservedPosition(),
                contract = executionContract,
            )
        val signal =
            VolumeConfirmedTrendExecutionSignal(
                side = plan.targetSide,
                decisionAt = now,
                executionAt = now,
            )
        return submitPlan(stored, signal, plan, instrument, position, now).withApproval(approval)
    }

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
        if (plan.action == VolumeConfirmedTrendTargetAction.OPEN && quantity * limitPrice < instrument.minimumNotional) {
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
                approvalId =
                    if (plan.reasonCode == TREND_APPROVAL_REVOKED_EXIT_REASON_CODE) {
                        previous?.approvalId
                    } else {
                        approvalReceipt.approvalId
                    },
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

    private suspend fun riskBlocked(
        previous: VolumeConfirmedTrendLiveState?,
        signal: VolumeConfirmedTrendExecutionSignal,
        plan: VolumeConfirmedTrendTargetPlan,
        assessment: VolumeConfirmedTrendLiveRiskAssessment,
        now: Instant,
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
                riskState = assessment.state,
                riskReasonCodes = assessment.reasonCodes,
                updatedAt = now,
            )
        val reasonCode = "TREND_ENTRY_RISK_BLOCKED|${assessment.reasonCodes.joinToString("|")}"
        store.commitTrendLive(
            state,
            listOf(lifecycleEvent(state, VolumeConfirmedTrendLiveEventType.RECONCILED, reasonCode, now)),
        )
        return VolumeConfirmedTrendLiveEvaluationResult(
            status = VolumeConfirmedTrendLiveEvaluationStatus.RISK_BLOCKED,
            state = state,
            plan = plan,
            riskReasonCodes = assessment.reasonCodes,
        )
    }

    private suspend fun managedPositionEntryBlocked(
        previous: VolumeConfirmedTrendLiveState,
        signal: VolumeConfirmedTrendExecutionSignal?,
        position: ExchangePosition,
        plan: VolumeConfirmedTrendTargetPlan?,
        assessment: VolumeConfirmedTrendLiveRiskAssessment,
        additionalReasonCodes: List<String>,
        now: Instant,
    ): VolumeConfirmedTrendLiveEvaluationResult {
        require(previous.matches(position)) { "A managed trend position must match persisted ownership evidence." }
        val reasonCodes = (assessment.reasonCodes + additionalReasonCodes).distinct()
        require(reasonCodes.isNotEmpty()) { "A managed trend position entry block requires a reason." }
        val state =
            previous.copy(
                status = VolumeConfirmedTrendLiveStatus.OPEN,
                approvalId = approvalReceipt.approvalId,
                activeDecisionKey = signal?.let(::signalDecisionKey) ?: previous.activeDecisionKey,
                pendingTargetSide = signal?.side ?: previous.pendingTargetSide,
                clientOrderId = null,
                exchangeOrderId = null,
                observedPositionSide = position.side,
                observedPositionQuantity = position.size,
                haltedReasonCode = null,
                riskState = assessment.state,
                riskReasonCodes = reasonCodes,
                updatedAt = now,
            )
        if (!state.samePersistedStateAs(previous)) {
            val eventType =
                if (previous.status == VolumeConfirmedTrendLiveStatus.HALTED) {
                    VolumeConfirmedTrendLiveEventType.RESUMED
                } else {
                    VolumeConfirmedTrendLiveEventType.RECONCILED
                }
            store.commitTrendLive(
                state,
                listOf(
                    lifecycleEvent(
                        state,
                        eventType,
                        "TREND_POSITION_ENTRY_BLOCKED|${reasonCodes.joinToString("|")}",
                        now,
                    ),
                ),
            )
        }
        return VolumeConfirmedTrendLiveEvaluationResult(
            status = VolumeConfirmedTrendLiveEvaluationStatus.RISK_BLOCKED,
            state = if (state.samePersistedStateAs(previous)) previous else state,
            plan = plan,
            riskReasonCodes = reasonCodes,
        )
    }

    private suspend fun resumeManagedPosition(
        previous: VolumeConfirmedTrendLiveState,
        position: ExchangePosition,
        assessment: VolumeConfirmedTrendLiveRiskAssessment,
        reasonCodes: List<String>,
        now: Instant,
    ): VolumeConfirmedTrendLiveEvaluationResult {
        if ((assessment.reasonCodes + reasonCodes).isNotEmpty()) {
            return managedPositionEntryBlocked(
                previous = previous,
                signal = null,
                position = position,
                plan = null,
                assessment = assessment,
                additionalReasonCodes = reasonCodes,
                now = now,
            )
        }
        val resumed =
            previous.copy(
                status = VolumeConfirmedTrendLiveStatus.OPEN,
                approvalId = approvalReceipt.approvalId,
                clientOrderId = null,
                exchangeOrderId = null,
                observedPositionSide = position.side,
                observedPositionQuantity = position.size,
                haltedReasonCode = null,
                riskState = assessment.state,
                riskReasonCodes = emptyList(),
                updatedAt = now,
            )
        store.commitTrendLive(
            resumed,
            listOf(
                lifecycleEvent(
                    resumed,
                    VolumeConfirmedTrendLiveEventType.RESUMED,
                    "TREND_POSITION_MANAGEMENT_RESUMED",
                    now,
                ),
            ),
        )
        return VolumeConfirmedTrendLiveEvaluationResult(
            status = VolumeConfirmedTrendLiveEvaluationStatus.RECOVERED,
            state = resumed,
            plan = null,
        )
    }

    private suspend fun blockByApproval(
        previous: VolumeConfirmedTrendLiveState?,
        now: Instant,
        positionConfirmedFlat: Boolean = false,
    ): VolumeConfirmedTrendLiveState {
        val state =
            when {
                previous == null || previous.status == VolumeConfirmedTrendLiveStatus.DISABLED ->
                    baseState(previous, now).copy(status = VolumeConfirmedTrendLiveStatus.DISABLED, updatedAt = now)
                previous.status == VolumeConfirmedTrendLiveStatus.HALTED ->
                    if (positionConfirmedFlat && previous.observedPositionSide != null) {
                        previous.copy(
                            observedPositionSide = null,
                            observedPositionQuantity = null,
                            updatedAt = now,
                        )
                    } else {
                        previous
                    }
                else -> {
                    val settledWithoutExposure =
                        previous.status in
                            setOf(
                                VolumeConfirmedTrendLiveStatus.FLAT,
                                VolumeConfirmedTrendLiveStatus.ENTRY_NOT_FILLED,
                            ) ||
                            positionConfirmedFlat
                    previous.copy(
                        status = VolumeConfirmedTrendLiveStatus.HALTED,
                        clientOrderId = previous.clientOrderId.takeUnless { settledWithoutExposure },
                        exchangeOrderId = previous.exchangeOrderId.takeUnless { settledWithoutExposure },
                        observedPositionSide = previous.observedPositionSide.takeUnless { positionConfirmedFlat },
                        observedPositionQuantity = previous.observedPositionQuantity.takeUnless { positionConfirmedFlat },
                        haltedReasonCode = "TREND_LIVE_NOT_APPROVED",
                        updatedAt = now,
                    )
                }
            }
        if (state.samePersistedStateAs(previous)) return requireNotNull(previous)
        val existingHaltReconciled =
            previous?.status == VolumeConfirmedTrendLiveStatus.HALTED &&
                state.haltedReasonCode == previous.haltedReasonCode
        val event =
            if (existingHaltReconciled) {
                lifecycleEvent(
                    state,
                    VolumeConfirmedTrendLiveEventType.RECONCILED,
                    "TREND_APPROVAL_REVOKED_FLAT_RECONCILED",
                    now,
                )
            } else {
                lifecycleEvent(state, VolumeConfirmedTrendLiveEventType.HALTED, "TREND_LIVE_NOT_APPROVED", now)
            }
        store.commitTrendLive(state, listOf(event))
        return state
    }

    private fun approvalBlocked(
        state: VolumeConfirmedTrendLiveState,
        approval: VolumeConfirmedTrendLiveApprovalValidation,
    ): VolumeConfirmedTrendLiveEvaluationResult =
        VolumeConfirmedTrendLiveEvaluationResult(
            status = VolumeConfirmedTrendLiveEvaluationStatus.APPROVAL_BLOCKED,
            state = state,
            plan = null,
            approvalFailures = approval.failures,
        )

    private fun VolumeConfirmedTrendLiveEvaluationResult.withApproval(
        approval: VolumeConfirmedTrendLiveApprovalValidation,
    ): VolumeConfirmedTrendLiveEvaluationResult = copy(approvalFailures = approval.failures)

    private suspend fun halt(
        previous: VolumeConfirmedTrendLiveState?,
        signal: VolumeConfirmedTrendExecutionSignal?,
        now: Instant,
        reasonCode: String,
        position: ExchangePosition? = null,
        contractFailures: List<VolumeConfirmedTrendExchangeContractFailure> = emptyList(),
    ): VolumeConfirmedTrendLiveEvaluationResult {
        val ownedPosition = position?.takeIf { observed -> previous?.ownsManagedPosition(observed) == true }
        val state =
            baseState(previous, now).copy(
                status = VolumeConfirmedTrendLiveStatus.HALTED,
                approvalId = approvalReceipt.approvalId ?: previous?.approvalId,
                activeDecisionKey = signal?.let { signalDecisionKey(it) } ?: previous?.activeDecisionKey,
                pendingTargetSide = signal?.side ?: previous?.pendingTargetSide,
                observedPositionSide = ownedPosition?.side ?: previous?.observedPositionSide,
                observedPositionQuantity = ownedPosition?.size ?: previous?.observedPositionQuantity,
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

    private fun VolumeConfirmedTrendLiveState.ownsManagedPosition(position: ExchangePosition): Boolean =
        status in APPROVAL_REVOCATION_OWNED_POSITION_STATES && matches(position)

    private fun accountInventoryReasonCodes(
        positions: List<ExchangePosition>,
        openOrders: List<ExchangeOpenOrder>,
    ): List<String> =
        buildList {
            if (positions.any { it.symbol != config.symbol }) add("TREND_FOREIGN_POSITION_OBSERVED")
            if (openOrders.any { !isUnresolvedOwnedOrder(it) }) add("TREND_UNOWNED_OPEN_ORDER_OBSERVED")
        }

    private fun isUnresolvedOwnedOrder(order: ExchangeOpenOrder): Boolean = order.clientOrderId?.startsWith(TREND_ORDER_ID_PREFIX) == true

    private data class SafetyExitContract(
        val position: ExchangePositionExecutionProfile,
        val instrument: ExchangeInstrumentRules,
    ) {
        fun allowsOrder(symbol: dev.yaklede.bybittrader.domain.Symbol): Boolean =
            position.symbol == symbol &&
                position.positionMode == ExchangePositionMode.ONE_WAY &&
                !position.reduceOnlyRestricted &&
                instrument.symbol == symbol &&
                instrument.status == "Trading" &&
                instrument.contractType == "LinearPerpetual" &&
                instrument.baseCoin == "BTC" &&
                instrument.quoteCoin == "USDT" &&
                instrument.settleCoin == "USDT"
    }

    private companion object {
        const val TREND_SETTLE_COIN = "USDT"
        const val TREND_ORDER_ID_PREFIX = "vct-"
        const val TREND_EXCHANGE_CONTRACT_MISMATCH_REASON_CODE = "TREND_EXCHANGE_CONTRACT_MISMATCH"
        val RECOVERABLE_ENTRY_INVENTORY_HALT_REASONS =
            setOf(
                "TREND_FOREIGN_POSITION_OBSERVED",
                "TREND_UNOWNED_OPEN_ORDER_OBSERVED",
            )
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
        val APPROVAL_REVOCATION_OWNED_POSITION_STATES =
            setOf(
                VolumeConfirmedTrendLiveStatus.OPEN,
                VolumeConfirmedTrendLiveStatus.EXIT_NOT_FILLED,
                VolumeConfirmedTrendLiveStatus.HALTED,
            )
    }
}
