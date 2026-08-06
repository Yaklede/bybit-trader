package dev.yaklede.bybittrader.api.strategy

import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalGate
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalReport
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

typealias VolumeConfirmedTrendShadowReportProvider = suspend (Int) -> VolumeConfirmedTrendShadowReport
typealias VolumeConfirmedTrendApprovalReportProvider = suspend () -> VolumeConfirmedTrendApprovalReport
typealias VolumeConfirmedTrendApprovalArtifactExportProvider = suspend () -> VolumeConfirmedTrendApprovalArtifactExportResponse

fun Route.configureVolumeConfirmedTrendShadowRoutes(
    reportProvider: VolumeConfirmedTrendShadowReportProvider?,
    approvalReportProvider: VolumeConfirmedTrendApprovalReportProvider? = null,
    approvalArtifactExportProvider: VolumeConfirmedTrendApprovalArtifactExportProvider? = null,
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
    }
}

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
