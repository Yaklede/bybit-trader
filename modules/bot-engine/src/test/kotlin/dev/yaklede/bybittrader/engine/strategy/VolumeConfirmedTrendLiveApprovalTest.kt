package dev.yaklede.bybittrader.engine.strategy

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class VolumeConfirmedTrendLiveApprovalTest :
    StringSpec({
        "default non-approved receipt always blocks live execution" {
            val result =
                VolumeConfirmedTrendLiveApprovalValidator.validate(
                    receipt = receipt(approved = false),
                    report = report(ready = true),
                    actualShadowEvidenceSha256 = EVIDENCE_SHA,
                    actualApprovalReportSha256 = REPORT_SHA,
                )

            result.liveExecutionAllowed shouldBe false
            result.failures shouldBe
                listOf(
                    VolumeConfirmedTrendLiveApprovalFailure.RECEIPT_NOT_APPROVED,
                    VolumeConfirmedTrendLiveApprovalFailure.RECEIPT_LIVE_EXECUTION_DISABLED,
                    VolumeConfirmedTrendLiveApprovalFailure.SHADOW_SESSION_MISMATCH,
                    VolumeConfirmedTrendLiveApprovalFailure.SHADOW_EVIDENCE_SHA256_MISMATCH,
                    VolumeConfirmedTrendLiveApprovalFailure.APPROVAL_REPORT_SHA256_MISMATCH,
                )
        }

        "matching human receipt and ready forward report allow activation" {
            val result =
                VolumeConfirmedTrendLiveApprovalValidator.validate(
                    receipt = receipt(approved = true),
                    report = report(ready = true),
                    actualShadowEvidenceSha256 = EVIDENCE_SHA,
                    actualApprovalReportSha256 = REPORT_SHA,
                )

            result.liveExecutionAllowed shouldBe true
            result.failures shouldBe emptyList()
        }

        "human receipt cannot override an incomplete forward report" {
            val result =
                VolumeConfirmedTrendLiveApprovalValidator.validate(
                    receipt = receipt(approved = true),
                    report = report(ready = false),
                    actualShadowEvidenceSha256 = EVIDENCE_SHA,
                    actualApprovalReportSha256 = REPORT_SHA,
                )

            result.liveExecutionAllowed shouldBe false
            result.failures shouldBe listOf(VolumeConfirmedTrendLiveApprovalFailure.FORWARD_REPORT_NOT_READY)
        }

        "human receipt cannot approve a report that omits frozen gates" {
            val result =
                VolumeConfirmedTrendLiveApprovalValidator.validate(
                    receipt = receipt(approved = true),
                    report =
                        report(ready = true).copy(
                            gates = report(ready = true).gates.take(1),
                        ),
                    actualShadowEvidenceSha256 = EVIDENCE_SHA,
                    actualApprovalReportSha256 = REPORT_SHA,
                )

            result.liveExecutionAllowed shouldBe false
            result.failures shouldBe listOf(VolumeConfirmedTrendLiveApprovalFailure.FORWARD_REPORT_GATES_INVALID)
        }

        "receipt is bound to its exact session and evidence files" {
            val result =
                VolumeConfirmedTrendLiveApprovalValidator.validate(
                    receipt = receipt(approved = true),
                    report = report(ready = true).copy(sessionId = "different-session"),
                    actualShadowEvidenceSha256 = "d".repeat(64),
                    actualApprovalReportSha256 = REPORT_SHA,
                )

            result.liveExecutionAllowed shouldBe false
            result.failures shouldBe
                listOf(
                    VolumeConfirmedTrendLiveApprovalFailure.SHADOW_SESSION_MISMATCH,
                    VolumeConfirmedTrendLiveApprovalFailure.SHADOW_EVIDENCE_SHA256_MISMATCH,
                )
        }
    })

private fun receipt(approved: Boolean): VolumeConfirmedTrendLiveApprovalReceipt =
    VolumeConfirmedTrendLiveApprovalReceipt(
        schemaVersion = 1,
        status =
            if (approved) {
                VolumeConfirmedTrendLiveApprovalStatus.APPROVED
            } else {
                VolumeConfirmedTrendLiveApprovalStatus.NOT_APPROVED
            },
        approvalId = if (approved) "approval-2026-11-07" else null,
        protocolId = PROTOCOL_ID,
        candidateId = CANDIDATE_ID,
        protocolSha256 = PROTOCOL_SHA,
        policyId = POLICY_ID,
        policySha256 = POLICY_SHA,
        shadowSessionId = if (approved) SESSION_ID else null,
        shadowEvidenceSha256 = if (approved) EVIDENCE_SHA else null,
        approvalReportSha256 = if (approved) REPORT_SHA else null,
        approvedAt = if (approved) Instant.parse("2026-11-07T00:00:00Z") else null,
        approvedBy = if (approved) "human-owner" else null,
        liveExecutionAllowed = approved,
        reasonCode = if (approved) "HUMAN_REVIEW_APPROVED" else "FRESH_SHADOW_AND_HUMAN_REVIEW_REQUIRED",
    )

private fun report(ready: Boolean): VolumeConfirmedTrendApprovalReport =
    VolumeConfirmedTrendApprovalReport(
        status =
            if (ready) {
                VolumeConfirmedTrendApprovalStatus.READY_FOR_HUMAN_REVIEW
            } else {
                VolumeConfirmedTrendApprovalStatus.SHADOW_COLLECTING
            },
        protocolId = PROTOCOL_ID,
        candidateId = CANDIDATE_ID,
        protocolSha256 = PROTOCOL_SHA,
        policyId = POLICY_ID,
        policySha256 = POLICY_SHA,
        evaluatedAt = Instant.parse("2026-11-07T00:00:00Z"),
        sessionId = SESSION_ID,
        observedCalendarDays = if (ready) 90.0 else 30.0,
        sessionReturnPct = 5.0,
        closedTradeProfitFactor = 1.5,
        gates =
            VolumeConfirmedTrendApprovalGateContract.requiredIds.map { id ->
                VolumeConfirmedTrendApprovalGate(
                    id = id,
                    status = VolumeConfirmedTrendApprovalGateStatus.PASS,
                    actual = "PASS",
                    required = "FROZEN",
                    reason = "Frozen gate passed.",
                )
            },
        readyForHumanReview = ready,
    )

private const val PROTOCOL_ID = "volume-confirmed-trend-ensemble-v1"
private const val CANDIDATE_ID = "vcte_4h_majority_001"
private const val POLICY_ID = "volume-confirmed-trend-ensemble-v1-forward-policy"
private const val SESSION_ID = "trend-shadow-forward-session"
private val PROTOCOL_SHA = "a".repeat(64)
private val POLICY_SHA = "b".repeat(64)
private val EVIDENCE_SHA = "c".repeat(64)
private val REPORT_SHA = "e".repeat(64)
