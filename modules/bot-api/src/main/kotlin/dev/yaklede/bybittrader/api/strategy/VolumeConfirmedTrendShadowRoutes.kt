package dev.yaklede.bybittrader.api.strategy

import dev.yaklede.bybittrader.engine.execution.ExecutionAccountSnapshot
import dev.yaklede.bybittrader.engine.execution.ExecutionAccountTransactionEvent
import dev.yaklede.bybittrader.engine.execution.ExecutionFillEvent
import dev.yaklede.bybittrader.engine.execution.ExecutionTradeClosure
import dev.yaklede.bybittrader.engine.execution.ExecutionWalletReconciliationState
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalGate
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalReport
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendExchangeContractSnapshot
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveEvent
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLivePerformanceEvidence
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveRuntimeMode
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveState
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowEvent
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowReport
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowState
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode

typealias VolumeConfirmedTrendShadowReportProvider = suspend (Int) -> VolumeConfirmedTrendShadowReport
typealias VolumeConfirmedTrendApprovalReportProvider = suspend () -> VolumeConfirmedTrendApprovalReport
typealias VolumeConfirmedTrendApprovalArtifactExportProvider = suspend () -> VolumeConfirmedTrendApprovalArtifactExportResponse
typealias VolumeConfirmedTrendLiveSnapshotProvider = suspend (Int) -> VolumeConfirmedTrendLiveSnapshot
typealias VolumeConfirmedTrendExchangeContractProvider = suspend () -> VolumeConfirmedTrendExchangeContractSnapshot

data class VolumeConfirmedTrendLiveSnapshot(
    val enabled: Boolean,
    val runtimeMode: VolumeConfirmedTrendLiveRuntimeMode,
    val runtimeActive: Boolean,
    val state: VolumeConfirmedTrendLiveState?,
    val recentEvents: List<VolumeConfirmedTrendLiveEvent>,
    val accountSnapshot: ExecutionAccountSnapshot? = null,
    val recentExecutionFills: List<ExecutionFillEvent> = emptyList(),
    val maximumAccountDrawdownFraction: BigDecimal? = null,
    val walletReconciliation: ExecutionWalletReconciliationState? = null,
    val performance: List<VolumeConfirmedTrendLivePerformanceEvidence> = emptyList(),
    val recentClosures: List<ExecutionTradeClosure> = emptyList(),
    val recentAccountTransactions: List<ExecutionAccountTransactionEvent> = emptyList(),
)

fun Route.configureVolumeConfirmedTrendShadowRoutes(
    reportProvider: VolumeConfirmedTrendShadowReportProvider?,
    approvalReportProvider: VolumeConfirmedTrendApprovalReportProvider? = null,
    approvalArtifactExportProvider: VolumeConfirmedTrendApprovalArtifactExportProvider? = null,
    liveSnapshotProvider: VolumeConfirmedTrendLiveSnapshotProvider? = null,
    exchangeContractProvider: VolumeConfirmedTrendExchangeContractProvider? = null,
) {
    authenticate("control") {
        get("/strategy/volume-confirmed-trend/shadow") {
            val rawLimit = call.request.queryParameters["limit"]
            val limit = rawLimit?.toIntOrNull() ?: if (rawLimit == null) 50 else 0
            require(limit in 1..100) { "Shadow event limit must be between 1 and 100." }
            val report = reportProvider?.invoke(limit)
            call.respond(report?.toResponse() ?: VolumeConfirmedTrendShadowResponse.disabled())
        }
        get("/strategy/volume-confirmed-trend/approval") {
            val report = approvalReportProvider?.invoke()
            call.respond(report?.toResponse() ?: VolumeConfirmedTrendApprovalResponse.unavailable())
        }
        post("/strategy/volume-confirmed-trend/approval/export") {
            val provider = approvalArtifactExportProvider
            if (provider == null) {
                call.respond(
                    HttpStatusCode.Conflict,
                    VolumeConfirmedTrendApprovalArtifactExportResponse.unavailable(),
                )
            } else {
                call.respond(provider())
            }
        }
        get("/strategy/volume-confirmed-trend/live") {
            val rawLimit = call.request.queryParameters["limit"]
            val limit = rawLimit?.toIntOrNull() ?: if (rawLimit == null) 50 else 0
            require(limit in 1..100) { "Live event limit must be between 1 and 100." }
            val snapshot = liveSnapshotProvider?.invoke(limit)
            call.respond(snapshot?.toResponse() ?: VolumeConfirmedTrendLiveResponse.disabled())
        }
        get("/strategy/volume-confirmed-trend/exchange-contract") {
            val snapshot = exchangeContractProvider?.invoke()
            call.respond(snapshot?.toResponse() ?: VolumeConfirmedTrendExchangeContractResponse.unavailable())
        }
    }
}

@Serializable
data class VolumeConfirmedTrendExchangeContractResponse(
    val available: Boolean,
    val valid: Boolean,
    val checkedAt: String?,
    val failures: List<String>,
    val account: VolumeConfirmedTrendExchangeContractAccountResponse?,
    val position: VolumeConfirmedTrendExchangeContractPositionResponse?,
    val instrument: VolumeConfirmedTrendExchangeContractInstrumentResponse?,
) {
    companion object {
        fun unavailable(): VolumeConfirmedTrendExchangeContractResponse =
            VolumeConfirmedTrendExchangeContractResponse(
                available = false,
                valid = false,
                checkedAt = null,
                failures = listOf("PRIVATE_EXCHANGE_UNAVAILABLE"),
                account = null,
                position = null,
                instrument = null,
            )
    }
}

@Serializable
data class VolumeConfirmedTrendExchangeContractAccountResponse(
    val accountType: String,
    val accountMode: String,
    val unifiedMarginStatus: Int,
    val marginMode: String,
    val updatedAt: String?,
)

@Serializable
data class VolumeConfirmedTrendExchangeContractPositionResponse(
    val symbol: String,
    val positionMode: String,
    val buyLeverage: String?,
    val sellLeverage: String?,
    val observedPositionIndices: List<Int>,
    val reduceOnlyRestricted: Boolean,
)

@Serializable
data class VolumeConfirmedTrendExchangeContractInstrumentResponse(
    val symbol: String,
    val status: String,
    val contractType: String,
    val baseCoin: String,
    val quoteCoin: String,
    val settleCoin: String,
    val unifiedMarginTrade: Boolean,
    val minimumOrderQuantity: String,
    val quantityStep: String,
    val minimumNotional: String,
    val priceTick: String,
    val minimumLeverage: String,
    val maximumLeverage: String,
    val leverageStep: String,
)

@Serializable
data class VolumeConfirmedTrendLiveResponse(
    val enabled: Boolean,
    val runtimeMode: String,
    val runtimeActive: Boolean,
    val state: VolumeConfirmedTrendLiveStateResponse?,
    val recentEvents: List<VolumeConfirmedTrendLiveEventResponse>,
    val account: VolumeConfirmedTrendLiveAccountResponse?,
    val risk: VolumeConfirmedTrendLiveRiskResponse?,
    val walletReconciliation: VolumeConfirmedTrendLiveWalletReconciliationResponse?,
    val performance: List<VolumeConfirmedTrendLivePerformanceResponse>,
    val recentClosedTrades: List<VolumeConfirmedTrendLiveClosedTradeResponse>,
    val recentExecutionFills: List<VolumeConfirmedTrendLiveFillResponse>,
    val recentAccountTransactions: List<VolumeConfirmedTrendLiveAccountTransactionResponse>,
) {
    companion object {
        fun disabled(): VolumeConfirmedTrendLiveResponse =
            VolumeConfirmedTrendLiveResponse(
                enabled = false,
                runtimeMode = VolumeConfirmedTrendLiveRuntimeMode.DISABLED.name,
                runtimeActive = false,
                state = null,
                recentEvents = emptyList(),
                account = null,
                risk = null,
                walletReconciliation = null,
                performance = emptyList(),
                recentClosedTrades = emptyList(),
                recentExecutionFills = emptyList(),
                recentAccountTransactions = emptyList(),
            )
    }
}

@Serializable
data class VolumeConfirmedTrendLiveRiskResponse(
    val allowsNewEntry: Boolean,
    val reasonCodes: List<String>,
    val mode: String,
    val navStatus: String,
    val latestEquity: String,
    val peakEquity: String,
    val latestUnitizedNav: String,
    val peakUnitizedNav: String,
    val currentAccountDrawdownFraction: String,
    val maximumAccountDrawdownFraction: String?,
    val cumulativeExternalCashFlow: String,
    val updatedAt: String,
)

@Serializable
data class VolumeConfirmedTrendLiveWalletReconciliationResponse(
    val status: String,
    val currency: String,
    val baselineWalletBalance: String?,
    val currentWalletBalance: String?,
    val observedWalletChange: String?,
    val ledgerChange: String?,
    val difference: String?,
    val tolerance: String,
    val consecutiveMismatches: Int,
    val lastMatchedAt: String?,
    val reconciledAt: String,
)

@Serializable
data class VolumeConfirmedTrendLivePerformanceResponse(
    val window: String,
    val tradeCount: Int,
    val winRatePct: String,
    val grossProfit: String,
    val grossLoss: String,
    val fees: String,
    val netPnl: String,
    val profitFactor: String?,
    val expectancy: String?,
    val maxClosedTradeDrawdownPct: String,
    val accountEquity: String?,
    val accountPeakEquity: String?,
    val maxAccountDrawdownPct: String?,
    val btcFundingPnl: String,
    val strategyTransactionFees: String,
    val lastClosedAt: String?,
    val capturedAt: String,
)

@Serializable
data class VolumeConfirmedTrendLiveClosedTradeResponse(
    val id: Long,
    val symbol: String,
    val side: String,
    val openedAt: String,
    val closedAt: String,
    val entryPrice: String,
    val exitPrice: String,
    val quantity: String,
    val grossPnl: String,
    val fees: String,
    val netPnl: String,
    val exitReason: String,
    val exchangeOrderId: String?,
    val clientOrderId: String?,
)

@Serializable
data class VolumeConfirmedTrendLiveAccountTransactionResponse(
    val id: Long,
    val transactionId: String,
    val symbol: String?,
    val side: String?,
    val transactionAt: String,
    val type: String,
    val subtype: String?,
    val currency: String,
    val funding: String,
    val fee: String,
    val cashFlow: String,
    val change: String,
    val cashBalance: String?,
    val clientOrderId: String?,
)

@Serializable
data class VolumeConfirmedTrendLiveAccountResponse(
    val mode: String,
    val accountType: String,
    val totalEquity: String?,
    val totalWalletBalance: String?,
    val totalAvailableBalance: String?,
    val totalPerpUnrealizedPnl: String?,
    val totalInitialMargin: String?,
    val totalMaintenanceMargin: String?,
    val trackedCoin: String?,
    val trackedCoinEquity: String?,
    val trackedCoinWalletBalance: String?,
    val trackedCoinUnrealizedPnl: String?,
    val trackedCoinCumulativeRealizedPnl: String?,
    val capturedAt: String,
)

@Serializable
data class VolumeConfirmedTrendLiveFillResponse(
    val executionId: String?,
    val exchangeOrderId: String?,
    val clientOrderId: String?,
    val symbol: String,
    val side: String,
    val price: String,
    val quantity: String,
    val fee: String,
    val executionPnl: String?,
    val executionType: String?,
    val executedAt: String,
    val receivedAt: String,
)

@Serializable
data class VolumeConfirmedTrendLiveStateResponse(
    val protocolId: String,
    val candidateId: String,
    val protocolSha256: String,
    val symbol: String,
    val status: String,
    val approvalId: String?,
    val activeDecisionKey: String?,
    val pendingTargetSide: String?,
    val clientOrderId: String?,
    val exchangeOrderId: String?,
    val observedPositionSide: String?,
    val observedPositionQuantity: String?,
    val lastExecutionId: String?,
    val haltedReasonCode: String?,
    val updatedAt: String,
)

@Serializable
data class VolumeConfirmedTrendLiveEventResponse(
    val eventId: String,
    val type: String,
    val decisionKey: String?,
    val targetSide: String?,
    val orderSide: String?,
    val orderQuantity: String?,
    val referencePrice: String?,
    val limitPrice: String?,
    val clientOrderId: String?,
    val exchangeOrderId: String?,
    val executionId: String?,
    val reasonCode: String,
    val occurredAt: String,
)

@Serializable
data class VolumeConfirmedTrendApprovalArtifactExportResponse(
    val available: Boolean,
    val exportDirectory: String?,
    val shadowEvidencePath: String?,
    val shadowEvidenceSha256: String?,
    val approvalReportPath: String?,
    val approvalReportSha256: String?,
    val manifestPath: String?,
    val sessionId: String?,
    val evaluatedAt: String?,
    val liveExecutionAllowed: Boolean,
) {
    companion object {
        fun unavailable(): VolumeConfirmedTrendApprovalArtifactExportResponse =
            VolumeConfirmedTrendApprovalArtifactExportResponse(
                available = false,
                exportDirectory = null,
                shadowEvidencePath = null,
                shadowEvidenceSha256 = null,
                approvalReportPath = null,
                approvalReportSha256 = null,
                manifestPath = null,
                sessionId = null,
                evaluatedAt = null,
                liveExecutionAllowed = false,
            )
    }
}

@Serializable
data class VolumeConfirmedTrendApprovalResponse(
    val available: Boolean,
    val status: String,
    val protocolId: String?,
    val candidateId: String?,
    val protocolSha256: String?,
    val policyId: String?,
    val policySha256: String?,
    val evaluatedAt: String?,
    val sessionId: String?,
    val observedCalendarDays: String?,
    val sessionReturnPct: String?,
    val closedTradeProfitFactor: String?,
    val gates: List<VolumeConfirmedTrendApprovalGateResponse>,
    val readyForHumanReview: Boolean,
    val automaticExecutionAllowed: Boolean,
    val liveExecutionAllowed: Boolean,
) {
    companion object {
        fun unavailable(): VolumeConfirmedTrendApprovalResponse =
            VolumeConfirmedTrendApprovalResponse(
                available = false,
                status = "UNAVAILABLE",
                protocolId = null,
                candidateId = null,
                protocolSha256 = null,
                policyId = null,
                policySha256 = null,
                evaluatedAt = null,
                sessionId = null,
                observedCalendarDays = null,
                sessionReturnPct = null,
                closedTradeProfitFactor = null,
                gates = emptyList(),
                readyForHumanReview = false,
                automaticExecutionAllowed = false,
                liveExecutionAllowed = false,
            )
    }
}

@Serializable
data class VolumeConfirmedTrendApprovalGateResponse(
    val id: String,
    val status: String,
    val actual: String,
    val required: String,
    val reason: String,
)

@Serializable
data class VolumeConfirmedTrendShadowResponse(
    val enabled: Boolean,
    val protocolId: String?,
    val candidateId: String?,
    val protocolSha256: String?,
    val symbol: String?,
    val state: VolumeConfirmedTrendShadowStateResponse?,
    val recentEvents: List<VolumeConfirmedTrendShadowEventResponse>,
) {
    companion object {
        fun disabled(): VolumeConfirmedTrendShadowResponse =
            VolumeConfirmedTrendShadowResponse(
                enabled = false,
                protocolId = null,
                candidateId = null,
                protocolSha256 = null,
                symbol = null,
                state = null,
                recentEvents = emptyList(),
            )
    }
}

@Serializable
data class VolumeConfirmedTrendShadowStateResponse(
    val sessionId: String,
    val status: String,
    val sessionStartedAt: String?,
    val lastProcessedH4OpenedAt: String,
    val lastAppliedFundingAt: String,
    val lastObservedAt: String?,
    val updatedAt: String,
    val position: VolumeConfirmedTrendShadowPositionResponse?,
    val sessionStartingEquity: String,
    val cash: String,
    val equity: String,
    val sessionReturnPct: String,
    val peakEquity: String,
    val maximumDrawdownPct: String,
    val totalFees: String,
    val totalSlippage: String,
    val totalFundingPnl: String,
    val closedTrades: Int,
    val executedTransitions: Int,
    val invalidatedSessionCount: Int,
    val maximumEntryExposurePct: String,
    val maximumAdverseExposurePct: String,
    val liquidationCount: Int,
)

@Serializable
data class VolumeConfirmedTrendShadowPositionResponse(
    val side: String,
    val quantity: String,
    val entryAt: String,
    val entryPrice: String,
    val entryFee: String,
    val fundingPnl: String,
)

@Serializable
data class VolumeConfirmedTrendShadowEventResponse(
    val eventId: String,
    val sessionId: String,
    val type: String,
    val eventAt: String,
    val observedAt: String,
    val h4OpenedAt: String?,
    val side: String?,
    val referencePrice: String?,
    val fillPrice: String?,
    val quantity: String?,
    val fee: String,
    val slippage: String,
    val fundingPnl: String,
    val grossPnl: String,
    val netPnl: String,
    val cash: String,
    val equity: String,
    val reason: String,
)

private fun VolumeConfirmedTrendExchangeContractSnapshot.toResponse(): VolumeConfirmedTrendExchangeContractResponse =
    VolumeConfirmedTrendExchangeContractResponse(
        available = true,
        valid = validation.valid,
        checkedAt = checkedAt.toString(),
        failures = validation.failures.map { failure -> failure.name },
        account =
            VolumeConfirmedTrendExchangeContractAccountResponse(
                accountType = account.accountType,
                accountMode = account.accountMode.name,
                unifiedMarginStatus = account.unifiedMarginStatus,
                marginMode = account.marginMode.name,
                updatedAt = account.updatedAt?.toString(),
            ),
        position =
            VolumeConfirmedTrendExchangeContractPositionResponse(
                symbol = position.symbol.value,
                positionMode = position.positionMode.name,
                buyLeverage = position.buyLeverage?.toPlainString(),
                sellLeverage = position.sellLeverage?.toPlainString(),
                observedPositionIndices = position.observedPositionIndices.sorted(),
                reduceOnlyRestricted = position.reduceOnlyRestricted,
            ),
        instrument =
            VolumeConfirmedTrendExchangeContractInstrumentResponse(
                symbol = instrument.symbol.value,
                status = instrument.status,
                contractType = instrument.contractType,
                baseCoin = instrument.baseCoin,
                quoteCoin = instrument.quoteCoin,
                settleCoin = instrument.settleCoin,
                unifiedMarginTrade = instrument.unifiedMarginTrade,
                minimumOrderQuantity = instrument.minimumOrderQuantity.toPlainString(),
                quantityStep = instrument.quantityStep.toPlainString(),
                minimumNotional = instrument.minimumNotional.toPlainString(),
                priceTick = instrument.priceTick.toPlainString(),
                minimumLeverage = instrument.minimumLeverage.toPlainString(),
                maximumLeverage = instrument.maximumLeverage.toPlainString(),
                leverageStep = instrument.leverageStep.toPlainString(),
            ),
    )

private fun VolumeConfirmedTrendLiveSnapshot.toResponse(): VolumeConfirmedTrendLiveResponse =
    VolumeConfirmedTrendLiveResponse(
        enabled = enabled,
        runtimeMode = runtimeMode.name,
        runtimeActive = runtimeActive,
        state = state?.toResponse(),
        recentEvents = recentEvents.map(VolumeConfirmedTrendLiveEvent::toResponse),
        account = accountSnapshot?.toTrendLiveResponse(),
        risk = state?.toRiskResponse(maximumAccountDrawdownFraction),
        walletReconciliation = walletReconciliation?.toTrendLiveResponse(),
        performance = performance.map(VolumeConfirmedTrendLivePerformanceEvidence::toResponse),
        recentClosedTrades = recentClosures.map(ExecutionTradeClosure::toTrendLiveResponse),
        recentExecutionFills = recentExecutionFills.map(ExecutionFillEvent::toTrendLiveResponse),
        recentAccountTransactions =
            recentAccountTransactions.map(ExecutionAccountTransactionEvent::toTrendLiveResponse),
    )

private fun VolumeConfirmedTrendLiveState.toRiskResponse(
    maximumAccountDrawdownFraction: BigDecimal?,
): VolumeConfirmedTrendLiveRiskResponse? {
    val risk = riskState ?: return null
    val currentDrawdown =
        if (risk.peakUnitizedNav > BigDecimal.ZERO) {
            risk.peakUnitizedNav
                .subtract(risk.latestUnitizedNav)
                .max(BigDecimal.ZERO)
                .divide(risk.peakUnitizedNav, 8, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO
        }
    return VolumeConfirmedTrendLiveRiskResponse(
        allowsNewEntry =
            status != dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveStatus.HALTED &&
                riskReasonCodes.isEmpty(),
        reasonCodes = riskReasonCodes,
        mode = risk.mode.name,
        navStatus = risk.navStatus.name,
        latestEquity = risk.latestEquity.toPlainString(),
        peakEquity = risk.peakEquity.toPlainString(),
        latestUnitizedNav = risk.latestUnitizedNav.toPlainString(),
        peakUnitizedNav = risk.peakUnitizedNav.toPlainString(),
        currentAccountDrawdownFraction = currentDrawdown.toPlainString(),
        maximumAccountDrawdownFraction = maximumAccountDrawdownFraction?.toPlainString(),
        cumulativeExternalCashFlow = risk.cumulativeExternalCashFlow.toPlainString(),
        updatedAt = risk.updatedAt.toString(),
    )
}

private fun ExecutionWalletReconciliationState.toTrendLiveResponse(): VolumeConfirmedTrendLiveWalletReconciliationResponse =
    VolumeConfirmedTrendLiveWalletReconciliationResponse(
        status = status.name,
        currency = currency,
        baselineWalletBalance = baselineWalletBalance?.toPlainString(),
        currentWalletBalance = currentWalletBalance?.toPlainString(),
        observedWalletChange = observedWalletChange?.toPlainString(),
        ledgerChange = ledgerChange?.toPlainString(),
        difference = difference?.toPlainString(),
        tolerance = tolerance.toPlainString(),
        consecutiveMismatches = consecutiveMismatches,
        lastMatchedAt = lastMatchedAt?.toString(),
        reconciledAt = reconciledAt.toString(),
    )

private fun VolumeConfirmedTrendLivePerformanceEvidence.toResponse(): VolumeConfirmedTrendLivePerformanceResponse =
    VolumeConfirmedTrendLivePerformanceResponse(
        window = snapshot.window.name,
        tradeCount = snapshot.tradeCount,
        winRatePct = snapshot.winRatePct.toPlainString(),
        grossProfit = snapshot.grossProfit.toPlainString(),
        grossLoss = snapshot.grossLoss.toPlainString(),
        fees = snapshot.fees.toPlainString(),
        netPnl = snapshot.netPnl.toPlainString(),
        profitFactor = snapshot.profitFactor?.toPlainString(),
        expectancy = snapshot.expectancy?.toPlainString(),
        maxClosedTradeDrawdownPct = snapshot.maxClosedTradeDrawdownPct.toPlainString(),
        accountEquity = snapshot.accountEquity?.toPlainString(),
        accountPeakEquity = snapshot.accountPeakEquity?.toPlainString(),
        maxAccountDrawdownPct = snapshot.maxAccountDrawdownPct?.toPlainString(),
        btcFundingPnl = btcFundingPnl.toPlainString(),
        strategyTransactionFees = strategyTransactionFees.toPlainString(),
        lastClosedAt = snapshot.lastClosedAt?.toString(),
        capturedAt = snapshot.capturedAt.toString(),
    )

private fun ExecutionTradeClosure.toTrendLiveResponse(): VolumeConfirmedTrendLiveClosedTradeResponse =
    VolumeConfirmedTrendLiveClosedTradeResponse(
        id = id,
        symbol = symbol.value,
        side = side.name,
        openedAt = openedAt.toString(),
        closedAt = closedAt.toString(),
        entryPrice = entryPrice.toPlainString(),
        exitPrice = exitPrice.toPlainString(),
        quantity = quantity.toPlainString(),
        grossPnl = grossPnl.toPlainString(),
        fees = fees.toPlainString(),
        netPnl = netPnl.toPlainString(),
        exitReason = exitReason,
        exchangeOrderId = exchangeOrderId,
        clientOrderId = clientOrderId,
    )

private fun ExecutionAccountTransactionEvent.toTrendLiveResponse(): VolumeConfirmedTrendLiveAccountTransactionResponse =
    VolumeConfirmedTrendLiveAccountTransactionResponse(
        id = id,
        transactionId = transaction.transactionId,
        symbol = transaction.symbol?.value,
        side = transaction.side?.name,
        transactionAt = transaction.transactionAt.toString(),
        type = transaction.type,
        subtype = transaction.subtype,
        currency = transaction.currency,
        funding = transaction.funding.toPlainString(),
        fee = transaction.fee.toPlainString(),
        cashFlow = transaction.cashFlow.toPlainString(),
        change = transaction.change.toPlainString(),
        cashBalance = transaction.cashBalance?.toPlainString(),
        clientOrderId = transaction.clientOrderId,
    )

private fun ExecutionAccountSnapshot.toTrendLiveResponse(): VolumeConfirmedTrendLiveAccountResponse =
    VolumeConfirmedTrendLiveAccountResponse(
        mode = mode.name,
        accountType = accountType,
        totalEquity = totalEquity?.toPlainString(),
        totalWalletBalance = totalWalletBalance?.toPlainString(),
        totalAvailableBalance = totalAvailableBalance?.toPlainString(),
        totalPerpUnrealizedPnl = totalPerpUnrealizedPnl?.toPlainString(),
        totalInitialMargin = totalInitialMargin?.toPlainString(),
        totalMaintenanceMargin = totalMaintenanceMargin?.toPlainString(),
        trackedCoin = trackedCoin,
        trackedCoinEquity = trackedCoinEquity?.toPlainString(),
        trackedCoinWalletBalance = trackedCoinWalletBalance?.toPlainString(),
        trackedCoinUnrealizedPnl = trackedCoinUnrealizedPnl?.toPlainString(),
        trackedCoinCumulativeRealizedPnl = trackedCoinCumulativeRealizedPnl?.toPlainString(),
        capturedAt = capturedAt.toString(),
    )

private fun ExecutionFillEvent.toTrendLiveResponse(): VolumeConfirmedTrendLiveFillResponse =
    VolumeConfirmedTrendLiveFillResponse(
        executionId = fill.executionId,
        exchangeOrderId = fill.exchangeOrderId,
        clientOrderId = fill.clientOrderId,
        symbol = fill.symbol.value,
        side = fill.side.name,
        price = fill.price.toPlainString(),
        quantity = fill.quantity.toPlainString(),
        fee = fill.fee.toPlainString(),
        executionPnl = fill.executionPnl?.toPlainString(),
        executionType = fill.executionType,
        executedAt = fill.executedAt.toString(),
        receivedAt = receivedAt.toString(),
    )

private fun VolumeConfirmedTrendLiveState.toResponse(): VolumeConfirmedTrendLiveStateResponse =
    VolumeConfirmedTrendLiveStateResponse(
        protocolId = protocolId,
        candidateId = candidateId,
        protocolSha256 = protocolSha256,
        symbol = symbol.value,
        status = status.name,
        approvalId = approvalId,
        activeDecisionKey = activeDecisionKey,
        pendingTargetSide = pendingTargetSide?.name,
        clientOrderId = clientOrderId,
        exchangeOrderId = exchangeOrderId,
        observedPositionSide = observedPositionSide?.name,
        observedPositionQuantity = observedPositionQuantity?.toPlainString(),
        lastExecutionId = lastExecutionId,
        haltedReasonCode = haltedReasonCode,
        updatedAt = updatedAt.toString(),
    )

private fun VolumeConfirmedTrendLiveEvent.toResponse(): VolumeConfirmedTrendLiveEventResponse =
    VolumeConfirmedTrendLiveEventResponse(
        eventId = eventId,
        type = type.name,
        decisionKey = decisionKey,
        targetSide = targetSide?.name,
        orderSide = orderSide?.name,
        orderQuantity = orderQuantity?.toPlainString(),
        referencePrice = referencePrice?.toPlainString(),
        limitPrice = limitPrice?.toPlainString(),
        clientOrderId = clientOrderId,
        exchangeOrderId = exchangeOrderId,
        executionId = executionId,
        reasonCode = reasonCode,
        occurredAt = occurredAt.toString(),
    )

private fun VolumeConfirmedTrendShadowReport.toResponse(): VolumeConfirmedTrendShadowResponse =
    VolumeConfirmedTrendShadowResponse(
        enabled = true,
        protocolId = protocolId,
        candidateId = candidateId,
        protocolSha256 = protocolSha256,
        symbol = symbol.value,
        state = state?.toResponse(),
        recentEvents = recentEvents.map(VolumeConfirmedTrendShadowEvent::toResponse),
    )

private fun VolumeConfirmedTrendApprovalReport.toResponse(): VolumeConfirmedTrendApprovalResponse =
    VolumeConfirmedTrendApprovalResponse(
        available = true,
        status = status.name,
        protocolId = protocolId,
        candidateId = candidateId,
        protocolSha256 = protocolSha256,
        policyId = policyId,
        policySha256 = policySha256,
        evaluatedAt = evaluatedAt.toString(),
        sessionId = sessionId,
        observedCalendarDays = observedCalendarDays.toString(),
        sessionReturnPct = sessionReturnPct?.toString(),
        closedTradeProfitFactor = closedTradeProfitFactor?.toString(),
        gates = gates.map(VolumeConfirmedTrendApprovalGate::toResponse),
        readyForHumanReview = readyForHumanReview,
        automaticExecutionAllowed = automaticExecutionAllowed,
        liveExecutionAllowed = liveExecutionAllowed,
    )

private fun VolumeConfirmedTrendApprovalGate.toResponse(): VolumeConfirmedTrendApprovalGateResponse =
    VolumeConfirmedTrendApprovalGateResponse(
        id = id,
        status = status.name,
        actual = actual,
        required = required,
        reason = reason,
    )

private fun VolumeConfirmedTrendShadowState.toResponse(): VolumeConfirmedTrendShadowStateResponse =
    VolumeConfirmedTrendShadowStateResponse(
        sessionId = sessionId,
        status = status.name,
        sessionStartedAt = sessionStartedAt?.toString(),
        lastProcessedH4OpenedAt = requireNotNull(indicatorState.lastBarOpenedAt).toString(),
        lastAppliedFundingAt = lastAppliedFundingAt.toString(),
        lastObservedAt = lastObservedAt?.toString(),
        updatedAt = updatedAt.toString(),
        position =
            position?.let {
                VolumeConfirmedTrendShadowPositionResponse(
                    side = it.side.name,
                    quantity = it.quantity.toString(),
                    entryAt = it.entryAt.toString(),
                    entryPrice = it.entryPrice.toString(),
                    entryFee = it.entryFee.toString(),
                    fundingPnl = it.fundingPnl.toString(),
                )
            },
        sessionStartingEquity = sessionStartingEquity.toString(),
        cash = cash.toString(),
        equity = equity.toString(),
        sessionReturnPct = ((equity / sessionStartingEquity - 1.0) * 100.0).toString(),
        peakEquity = peakEquity.toString(),
        maximumDrawdownPct = maximumDrawdownPct.toString(),
        totalFees = totalFees.toString(),
        totalSlippage = totalSlippage.toString(),
        totalFundingPnl = totalFundingPnl.toString(),
        closedTrades = closedTrades,
        executedTransitions = executedTransitions,
        invalidatedSessionCount = invalidatedSessionCount,
        maximumEntryExposurePct = (maximumEntryExposureFraction * 100.0).toString(),
        maximumAdverseExposurePct = (maximumAdverseExposureFraction * 100.0).toString(),
        liquidationCount = liquidationCount,
    )

private fun VolumeConfirmedTrendShadowEvent.toResponse(): VolumeConfirmedTrendShadowEventResponse =
    VolumeConfirmedTrendShadowEventResponse(
        eventId = eventId,
        sessionId = sessionId,
        type = type.name,
        eventAt = eventAt.toString(),
        observedAt = observedAt.toString(),
        h4OpenedAt = h4OpenedAt?.toString(),
        side = side?.name,
        referencePrice = referencePrice?.toString(),
        fillPrice = fillPrice?.toString(),
        quantity = quantity?.toString(),
        fee = fee.toString(),
        slippage = slippage.toString(),
        fundingPnl = fundingPnl.toString(),
        grossPnl = grossPnl.toString(),
        netPnl = netPnl.toString(),
        cash = cash.toString(),
        equity = equity.toString(),
        reason = reason,
    )
