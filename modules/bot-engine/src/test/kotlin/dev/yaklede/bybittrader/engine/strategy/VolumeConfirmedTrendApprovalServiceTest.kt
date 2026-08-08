package dev.yaklede.bybittrader.engine.strategy

import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant

class VolumeConfirmedTrendApprovalServiceTest :
    StringSpec({
        "keeps execution disabled while shadow is not running" {
            val report = approvalService(shadowReport = null).evaluate()

            report.status shouldBe VolumeConfirmedTrendApprovalStatus.SHADOW_DISABLED
            report.readyForHumanReview shouldBe false
            report.automaticExecutionAllowed shouldBe false
            report.liveExecutionAllowed shouldBe false
        }

        "collects evidence until every frozen forward gate passes" {
            val state = approvalState(sessionDays = 30, closedTrades = 2, executedTransitions = 3)
            val report = approvalService(shadowReport(state, consistentEvents(state, listOf(1.0, -0.5)))).evaluate()

            report.status shouldBe VolumeConfirmedTrendApprovalStatus.SHADOW_COLLECTING
            report.gates.single { it.id == "FRESH_SHADOW_DAYS" }.status shouldBe
                VolumeConfirmedTrendApprovalGateStatus.PENDING
            report.readyForHumanReview shouldBe false
        }

        "marks a complete profitable continuous shadow session ready only for human review" {
            val state = approvalState(sessionDays = 91, closedTrades = 5, executedTransitions = 6)
            val pnls = listOf(3.0, -1.0, 2.0, -1.0, 1.0)
            val report = approvalService(shadowReport(state, consistentEvents(state, pnls))).evaluate()

            report.status shouldBe VolumeConfirmedTrendApprovalStatus.READY_FOR_HUMAN_REVIEW
            report.closedTradeProfitFactor shouldBe 3.0
            report.gates.map { gate -> gate.id }.toSet() shouldBe VolumeConfirmedTrendApprovalGateContract.requiredIds
            report.gates.all { it.status == VolumeConfirmedTrendApprovalGateStatus.PASS } shouldBe true
            report.readyForHumanReview shouldBe true
            report.automaticExecutionAllowed shouldBe false
            report.liveExecutionAllowed shouldBe false
        }

        "freezes one shadow read and evaluates its report from the same snapshot" {
            val state = approvalState(sessionDays = 91, closedTrades = 5, executedTransitions = 6)
            val shadow = shadowReport(state, consistentEvents(state, listOf(3.0, -1.0, 2.0, -1.0, 1.0)))
            var reads = 0
            val service =
                approvalService(
                    shadowReport = null,
                    shadowReportProvider = {
                        reads += 1
                        shadow
                    },
                )

            val snapshot = service.snapshot()

            reads shouldBe 1
            snapshot.shadowReport shouldBe shadow
            snapshot.approvalReport.status shouldBe VolumeConfirmedTrendApprovalStatus.READY_FOR_HUMAN_REVIEW
        }

        "fails the current shadow session after a hard risk breach" {
            val state = approvalState(sessionDays = 91, closedTrades = 5, executedTransitions = 6).copy(liquidationCount = 1)
            val report = approvalService(shadowReport(state, consistentEvents(state, listOf(3.0, -1.0, 2.0, -1.0, 1.0)))).evaluate()

            report.status shouldBe VolumeConfirmedTrendApprovalStatus.SHADOW_SESSION_FAILED
            report.gates.single { it.id == "LIQUIDATION_COUNT" }.status shouldBe VolumeConfirmedTrendApprovalGateStatus.FAIL
        }

        "fails a session that contains more than one start event" {
            val state = approvalState(sessionDays = 91, closedTrades = 5, executedTransitions = 6)
            val duplicateStart = sessionStartEvent(state).copy(eventId = "duplicate-session-start")
            val report =
                approvalService(
                    shadowReport(
                        state,
                        listOf(duplicateStart) + consistentEvents(state, listOf(3.0, -1.0, 2.0, -1.0, 1.0)),
                    ),
                ).evaluate()

            report.status shouldBe VolumeConfirmedTrendApprovalStatus.SHADOW_SESSION_FAILED
            report.gates.single { it.id == "CURRENT_SESSION_START" }.status shouldBe
                VolumeConfirmedTrendApprovalGateStatus.FAIL
        }

        "fails a session whose counters exceed its append-only event evidence" {
            val state = approvalState(sessionDays = 91, closedTrades = 5, executedTransitions = 6)
            val incompleteEvents =
                consistentEvents(state, listOf(3.0, -1.0, 2.0, -1.0, 1.0))
                    .filterNot { event -> event.eventId == "approval-close-4" }

            val report = approvalService(shadowReport(state, incompleteEvents)).evaluate()

            report.status shouldBe VolumeConfirmedTrendApprovalStatus.SHADOW_SESSION_FAILED
            report.gates.single { it.id == "CURRENT_SESSION_CONTINUITY" }.status shouldBe
                VolumeConfirmedTrendApprovalGateStatus.FAIL
            report.readyForHumanReview shouldBe false
        }

        "rejects invalid historical evidence before considering shadow performance" {
            val evidence = historicalEvidence().copy(externalVenuePassed = false)
            val state = approvalState(sessionDays = 91, closedTrades = 5, executedTransitions = 6)
            val report =
                approvalService(
                    shadowReport(state, consistentEvents(state, listOf(3.0, -1.0, 2.0, -1.0, 1.0))),
                    evidence,
                ).evaluate()

            report.status shouldBe VolumeConfirmedTrendApprovalStatus.HISTORICAL_EVIDENCE_REJECTED
            report.readyForHumanReview shouldBe false
        }

        "blocks human review while Live risk policy parity is unresolved" {
            val evidence = historicalEvidence().copy(liveRiskPolicyParityPassed = false)
            val state = approvalState(sessionDays = 91, closedTrades = 5, executedTransitions = 6)
            val report =
                approvalService(
                    shadowReport(state, consistentEvents(state, listOf(3.0, -1.0, 2.0, -1.0, 1.0))),
                    evidence,
                ).evaluate()

            report.status shouldBe VolumeConfirmedTrendApprovalStatus.RUNTIME_PARITY_REQUIRED
            report.gates.single { it.id == "LIVE_RISK_POLICY_PARITY" }.status shouldBe
                VolumeConfirmedTrendApprovalGateStatus.FAIL
            report.readyForHumanReview shouldBe false
        }
    })

private fun approvalService(
    shadowReport: VolumeConfirmedTrendShadowReport?,
    evidence: VolumeConfirmedTrendHistoricalEvidence = historicalEvidence(),
    shadowReportProvider: suspend () -> VolumeConfirmedTrendShadowReport? = { shadowReport },
): VolumeConfirmedTrendApprovalService =
    VolumeConfirmedTrendApprovalService(
        historicalEvidence = evidence,
        forwardPolicy =
            VolumeConfirmedTrendForwardPolicy(
                policyId = "forward-policy",
                policySha256 = "f".repeat(64),
                minimumCalendarDays = 90,
                minimumClosedTrades = 5,
                minimumExecutedTransitions = 6,
                minimumSessionReturnPct = 0.0,
                minimumClosedTradeProfitFactor = 1.0,
                maximumDrawdownPct = 35.0,
                maximumEntryExposureFraction = 0.85,
                maximumAdverseExposureFraction = 1.2,
                maximumLiquidationCount = 0,
                maximumObservationStaleness = Duration.ofMinutes(300),
            ),
        shadowReportProvider = shadowReportProvider,
        clock = { APPROVAL_NOW },
    )

private fun historicalEvidence(): VolumeConfirmedTrendHistoricalEvidence =
    VolumeConfirmedTrendHistoricalEvidence(
        protocolId = "volume-confirmed-trend-ensemble-v1",
        candidateId = "vcte_4h_majority_001",
        protocolSha256 = "a".repeat(64),
        externalResultSha256 = "b".repeat(64),
        kotlinCoreParityResultSha256 = "c".repeat(64),
        runtimeReplayParityResultSha256 = "d".repeat(64),
        liveRiskPolicyParityResultSha256 = "e".repeat(64),
        externalVenuePassed = true,
        kotlinCoreParityPassed = true,
        runtimeReplayParityPassed = true,
        liveRiskPolicyParityPassed = true,
    )

private fun approvalState(
    sessionDays: Long,
    closedTrades: Int,
    executedTransitions: Int,
): VolumeConfirmedTrendShadowState {
    val startedAt = APPROVAL_NOW.minus(Duration.ofDays(sessionDays))
    return VolumeConfirmedTrendShadowState(
        protocolId = "volume-confirmed-trend-ensemble-v1",
        candidateId = "vcte_4h_majority_001",
        protocolSha256 = "a".repeat(64),
        symbol = Symbol("BTCUSDT"),
        sessionId = "approval-shadow-session",
        status = VolumeConfirmedTrendShadowStatus.OBSERVING,
        sessionStartedAt = startedAt,
        indicatorState =
            VolumeConfirmedTrendIndicatorState(
                processedBars = 1_000,
                lastBarOpenedAt = APPROVAL_NOW.minus(Duration.ofHours(4)),
                emaStates = listOf(VolumeConfirmedTrendEmaState(60_000.0, 59_000.0)),
                targetSide = Side.BUY,
                recentVolumes = listOf(1.0),
            ),
        lastAppliedFundingAt = APPROVAL_NOW.minus(Duration.ofHours(8)),
        lastObservedAt = APPROVAL_NOW.minus(Duration.ofMinutes(5)),
        position = null,
        sessionStartingEquity = 100.0,
        cash = 110.0,
        equity = 110.0,
        peakEquity = 112.0,
        maximumDrawdownPct = 10.0,
        totalFees = 1.0,
        totalSlippage = 0.2,
        totalFundingPnl = -0.1,
        closedTrades = closedTrades,
        executedTransitions = executedTransitions,
        invalidatedSessionCount = 0,
        updatedAt = APPROVAL_NOW.minus(Duration.ofMinutes(5)),
        maximumEntryExposureFraction = 0.65,
        maximumAdverseExposureFraction = 0.9,
        liquidationCount = 0,
    )
}

private fun shadowReport(
    state: VolumeConfirmedTrendShadowState,
    events: List<VolumeConfirmedTrendShadowEvent>,
): VolumeConfirmedTrendShadowReport =
    VolumeConfirmedTrendShadowReport(
        protocolId = state.protocolId,
        candidateId = state.candidateId,
        protocolSha256 = state.protocolSha256,
        symbol = state.symbol,
        state = state,
        recentEvents = listOf(sessionStartEvent(state)) + events,
    )

private fun sessionStartEvent(state: VolumeConfirmedTrendShadowState): VolumeConfirmedTrendShadowEvent =
    VolumeConfirmedTrendShadowEvent(
        eventId = "approval-session-start",
        sessionId = state.sessionId,
        protocolId = state.protocolId,
        protocolSha256 = state.protocolSha256,
        symbol = state.symbol,
        type = VolumeConfirmedTrendShadowEventType.SESSION_STARTED,
        eventAt = requireNotNull(state.sessionStartedAt),
        observedAt = requireNotNull(state.sessionStartedAt),
        h4OpenedAt = null,
        side = null,
        referencePrice = 60_000.0,
        fillPrice = null,
        quantity = null,
        fee = 0.0,
        slippage = 0.0,
        fundingPnl = 0.0,
        grossPnl = 0.0,
        netPnl = 0.0,
        cash = state.cash,
        equity = state.equity,
        reason = "WAIT_FOR_NEXT_CONFIRMED_TRANSITION",
    )

private fun closureEvent(
    state: VolumeConfirmedTrendShadowState,
    netPnl: Double,
    index: Int = 0,
): VolumeConfirmedTrendShadowEvent =
    VolumeConfirmedTrendShadowEvent(
        eventId = "approval-close-$index",
        sessionId = state.sessionId,
        protocolId = state.protocolId,
        protocolSha256 = state.protocolSha256,
        symbol = state.symbol,
        type = VolumeConfirmedTrendShadowEventType.POSITION_CLOSED,
        eventAt = APPROVAL_NOW.minus(Duration.ofDays((index + 1).toLong())),
        observedAt = APPROVAL_NOW.minus(Duration.ofDays((index + 1).toLong())),
        h4OpenedAt = null,
        side = Side.BUY,
        referencePrice = 60_000.0,
        fillPrice = 60_000.0,
        quantity = 0.001,
        fee = 0.03,
        slippage = 0.01,
        fundingPnl = 0.0,
        grossPnl = netPnl + 0.06,
        netPnl = netPnl,
        cash = state.cash,
        equity = state.equity,
        reason = "OPPOSITE_VOLUME_CONFIRMED_TREND",
    )

private fun consistentEvents(
    state: VolumeConfirmedTrendShadowState,
    netPnls: List<Double>,
): List<VolumeConfirmedTrendShadowEvent> {
    require(netPnls.size == state.closedTrades)
    return netPnls.mapIndexed { index, netPnl -> closureEvent(state, netPnl, index) } +
        List(state.executedTransitions) { index -> transitionEvent(state, index) }
}

private fun transitionEvent(
    state: VolumeConfirmedTrendShadowState,
    index: Int,
): VolumeConfirmedTrendShadowEvent {
    val eventAt = APPROVAL_NOW.minus(Duration.ofDays((index + 10).toLong()))
    return VolumeConfirmedTrendShadowEvent(
        eventId = "approval-open-$index",
        sessionId = state.sessionId,
        protocolId = state.protocolId,
        protocolSha256 = state.protocolSha256,
        symbol = state.symbol,
        type = VolumeConfirmedTrendShadowEventType.POSITION_OPENED,
        eventAt = eventAt,
        observedAt = eventAt,
        h4OpenedAt = eventAt.minus(Duration.ofHours(4)),
        side = if (index % 2 == 0) Side.BUY else Side.SELL,
        referencePrice = 60_000.0,
        fillPrice = 60_012.0,
        quantity = 0.001,
        fee = 0.036,
        slippage = 0.012,
        fundingPnl = 0.0,
        grossPnl = 0.0,
        netPnl = -0.036,
        cash = state.cash,
        equity = state.equity,
        reason = "VOLUME_CONFIRMED_TREND_TRANSITION",
    )
}

private val APPROVAL_NOW = Instant.parse("2026-11-10T00:05:00Z")
