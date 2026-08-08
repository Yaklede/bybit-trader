package dev.yaklede.bybittrader.app

import dev.yaklede.bybittrader.alerts.AlertSeverity
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalGate
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalGateStatus
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalReport
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalStatus
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Instant

class VolumeConfirmedTrendApprovalAlertPolicyTest :
    StringSpec({
        "unchanged gate states do not repeat progress alerts" {
            val policy = VolumeConfirmedTrendApprovalAlertPolicy()
            val collecting = report()

            policy.shouldAlert(collecting) shouldBe true
            policy.shouldAlert(collecting.copy(observedCalendarDays = 12.5, sessionReturnPct = 3.4)) shouldBe false
        }

        "gate transitions and a new session emit a new alert" {
            val policy = VolumeConfirmedTrendApprovalAlertPolicy()
            val collecting = report()

            policy.shouldAlert(collecting) shouldBe true
            policy.shouldAlert(
                collecting.copy(
                    gates = collecting.gates.map { gate -> gate.copy(status = VolumeConfirmedTrendApprovalGateStatus.PASS) },
                ),
            ) shouldBe true
            policy.shouldAlert(collecting.copy(sessionId = "shadow-session-002")) shouldBe true
        }

        "ready report explains that human approval is still required" {
            val alert =
                report(
                    status = VolumeConfirmedTrendApprovalStatus.READY_FOR_HUMAN_REVIEW,
                    gateStatus = VolumeConfirmedTrendApprovalGateStatus.PASS,
                ).toOperatorAlert()

            alert.severity shouldBe AlertSeverity.INFO
            alert.title shouldBe "4시간 전략 검토 준비 완료"
            alert.body shouldContain "사람이 별도로 검토"
            alert.body shouldContain "실거래 주문은 계속 차단"
            report().toOperatorAlert().body shouldContain "연속 관측 기간"
        }

        "stale and failed reports use operator-visible warning severity" {
            val stale = report(status = VolumeConfirmedTrendApprovalStatus.SHADOW_STALE).toOperatorAlert()
            val failed = report(status = VolumeConfirmedTrendApprovalStatus.SHADOW_SESSION_FAILED).toOperatorAlert()

            stale.severity shouldBe AlertSeverity.WARNING
            stale.body shouldContain "최근 관측 시각"
            failed.severity shouldBe AlertSeverity.CRITICAL
            failed.body shouldContain "현재 세션을 승인에 사용하지 마세요"
        }
    })

private fun report(
    status: VolumeConfirmedTrendApprovalStatus = VolumeConfirmedTrendApprovalStatus.SHADOW_COLLECTING,
    gateStatus: VolumeConfirmedTrendApprovalGateStatus = VolumeConfirmedTrendApprovalGateStatus.PENDING,
): VolumeConfirmedTrendApprovalReport =
    VolumeConfirmedTrendApprovalReport(
        status = status,
        protocolId = "volume-confirmed-trend-ensemble-v1",
        candidateId = "vcte_4h_majority_001",
        protocolSha256 = "a".repeat(64),
        policyId = "volume-confirmed-trend-forward-v1",
        policySha256 = "b".repeat(64),
        evaluatedAt = Instant.parse("2026-08-08T00:00:00Z"),
        sessionId = "shadow-session-001",
        observedCalendarDays = 10.25,
        sessionReturnPct = 2.5,
        closedTradeProfitFactor = 1.8,
        gates =
            listOf(
                VolumeConfirmedTrendApprovalGate(
                    id = "FRESH_SHADOW_DAYS",
                    status = gateStatus,
                    actual = "10.25",
                    required = ">=90.0",
                    reason = "Fresh observation is required.",
                ),
            ),
        readyForHumanReview = status == VolumeConfirmedTrendApprovalStatus.READY_FOR_HUMAN_REVIEW,
    )
