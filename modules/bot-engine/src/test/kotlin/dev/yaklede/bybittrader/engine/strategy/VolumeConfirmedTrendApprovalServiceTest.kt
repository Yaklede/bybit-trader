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
            val report = approvalService(shadowReport(state, listOf(closureEvent(state, 1.0)))).evaluate()

            report.status shouldBe VolumeConfirmedTrendApprovalStatus.SHADOW_COLLECTING
            report.gates.single { it.id == "FRESH_SHADOW_DAYS" }.status shouldBe
                VolumeConfirmedTrendApprovalGateStatus.PENDING
            report.readyForHumanReview shouldBe false
        }

        "marks a complete profitable continuous shadow session ready only for human review" {
            val state = approvalState(sessionDays = 91, closedTrades = 5, executedTransitions = 6)
            val pnls = listOf(3.0, -1.0, 2.0, -1.0, 1.0)
            val report = approvalService(shadowReport(state, pnls.mapIndexed { index, pnl -> closureEvent(state, pnl, index) })).evaluate()

            report.status shouldBe VolumeConfirmedTrendApprovalStatus.READY_FOR_HUMAN_REVIEW
            report.closedTradeProfitFactor shouldBe 3.0
            report.gates.all { it.status == VolumeConfirmedTrendApprovalGateStatus.PASS } shouldBe true
            report.readyForHumanReview shouldBe true
            report.automaticExecutionAllowed shouldBe false
            report.liveExecutionAllowed shouldBe false
        }

        "fails the current shadow session after a hard risk breach" {
            val state = approvalState(sessionDays = 91, closedTrades = 5, executedTransitions = 6).copy(liquidationCount = 1)
            val report = approvalService(shadowReport(state, listOf(closureEvent(state, 1.0)))).evaluate()

            report.status shouldBe VolumeConfirmedTrendApprovalStatus.SHADOW_SESSION_FAILED
            report.gates.single { it.id == "LIQUIDATION_COUNT" }.status shouldBe VolumeConfirmedTrendApprovalGateStatus.FAIL
        }

        "rejects invalid historical evidence before considering shadow performance" {
            val evidence = historicalEvidence().copy(externalVenuePassed = false)
            val state = approvalState(sessionDays = 91, closedTrades = 5, executedTransitions = 6)
            val report = approvalService(shadowReport(state, listOf(closureEvent(state, 1.0))), evidence).evaluate()

            report.status shouldBe VolumeConfirmedTrendApprovalStatus.HISTORICAL_EVIDENCE_REJECTED
            report.readyForHumanReview shouldBe false
        }
    })

private fun approvalService(
    shadowReport: VolumeConfirmedTrendShadowReport?,
    evidence: VolumeConfirmedTrendHistoricalEvidence = historicalEvidence(),
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
        shadowReportProvider = { shadowReport },
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
        externalVenuePassed = true,
        kotlinCoreParityPassed = true,
        runtimeReplayParityPassed = true,
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
        recentEvents = events,
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

private val APPROVAL_NOW = Instant.parse("2026-11-10T00:05:00Z")
