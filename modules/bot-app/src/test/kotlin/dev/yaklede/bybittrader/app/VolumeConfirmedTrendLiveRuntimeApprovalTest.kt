package dev.yaklede.bybittrader.app

import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalGateContract
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendExecutionContract
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendForwardPolicy
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendIndicatorState
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveApprovalStatus
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendParameters
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowState
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

class VolumeConfirmedTrendLiveRuntimeApprovalTest :
    StringSpec({
        "matching immutable evidence and human receipt enable runtime construction" {
            withRuntimeApprovalFixture { fixture ->
                val approval = fixture.load()

                approval.receipt.status shouldBe VolumeConfirmedTrendLiveApprovalStatus.APPROVED
                approval.receipt.liveExecutionAllowed shouldBe true
                approval.report.readyForHumanReview shouldBe true
                approval.shadowEvidenceSha256 shouldBe fixture.shadowEvidenceSha256
                approval.approvalReportSha256 shouldBe fixture.approvalReportSha256
            }
        }

        "tampered Shadow evidence is rejected before private runtime construction" {
            withRuntimeApprovalFixture { fixture ->
                Files.writeString(fixture.shadowEvidencePath, Files.readString(fixture.shadowEvidencePath) + " ")

                shouldThrow<IllegalArgumentException> { fixture.load() }
            }
        }

        "a report with a non-passing gate cannot be approved" {
            withRuntimeApprovalFixture(reportGateStatus = "PENDING") { fixture ->
                shouldThrow<IllegalArgumentException> { fixture.load() }
            }
        }

        "a report that omits frozen gates cannot be approved" {
            withRuntimeApprovalFixture(omitFrozenGates = true) { fixture ->
                shouldThrow<IllegalArgumentException> { fixture.load() }
            }
        }

        "frozen evidence with duplicate session starts cannot be approved" {
            withRuntimeApprovalFixture(duplicateSessionStart = true) { fixture ->
                shouldThrow<IllegalArgumentException> { fixture.load() }
            }
        }

        "current Shadow continuity and policy gates are checked before private access" {
            withRuntimeApprovalFixture { fixture ->
                val approval = fixture.load()

                validateVolumeConfirmedTrendLiveCurrentShadow(
                    approval = approval,
                    currentState = currentShadowState(),
                    currentReport = approval.report,
                    protocol = protocol(),
                    forwardPolicy = policy(),
                    now = Instant.parse(APPROVED_AT),
                )
            }
        }

        "a Shadow state rolled back behind approved evidence is rejected" {
            withRuntimeApprovalFixture { fixture ->
                val approval = fixture.load()
                val rolledBack =
                    currentShadowState().copy(
                        lastObservedAt = Instant.parse("2026-11-06T19:59:59Z"),
                        updatedAt = Instant.parse("2026-11-06T19:59:59Z"),
                    )

                shouldThrow<IllegalArgumentException> {
                    validateVolumeConfirmedTrendLiveCurrentShadow(
                        approval = approval,
                        currentState = rolledBack,
                        currentReport = approval.report,
                        protocol = protocol(),
                        forwardPolicy = policy(),
                        now = Instant.parse(APPROVED_AT),
                    )
                }
            }
        }
    })

private data class RuntimeApprovalFixture(
    val directory: Path,
    val receiptPath: Path,
    val shadowEvidencePath: Path,
    val approvalReportPath: Path,
    val shadowEvidenceSha256: String,
    val approvalReportSha256: String,
) {
    fun load(): VolumeConfirmedTrendLiveRuntimeApproval =
        loadVolumeConfirmedTrendLiveRuntimeApproval(
            receiptPath = receiptPath,
            shadowEvidencePath = shadowEvidencePath,
            approvalReportPath = approvalReportPath,
            protocol = protocol(),
            forwardPolicy = policy(),
        )
}

private inline fun withRuntimeApprovalFixture(
    reportGateStatus: String = "PASS",
    omitFrozenGates: Boolean = false,
    duplicateSessionStart: Boolean = false,
    block: (RuntimeApprovalFixture) -> Unit,
) {
    val directory = Files.createTempDirectory("trend-live-runtime-approval-")
    try {
        val shadowPath = directory.resolve("shadow-evidence.json")
        val reportPath = directory.resolve("approval-report.json")
        val receiptPath = directory.resolve("receipt.json")
        Files.writeString(shadowPath, shadowEvidenceJson(duplicateSessionStart))
        Files.writeString(reportPath, approvalReportJson(reportGateStatus, omitFrozenGates))
        val shadowSha = Files.readAllBytes(shadowPath).sha256()
        val reportSha = Files.readAllBytes(reportPath).sha256()
        Files.writeString(receiptPath, approvalReceiptJson(shadowSha, reportSha))
        block(
            RuntimeApprovalFixture(
                directory = directory,
                receiptPath = receiptPath,
                shadowEvidencePath = shadowPath,
                approvalReportPath = reportPath,
                shadowEvidenceSha256 = shadowSha,
                approvalReportSha256 = reportSha,
            ),
        )
    } finally {
        directory.toFile().deleteRecursively()
    }
}

private fun shadowEvidenceJson(duplicateSessionStart: Boolean): String {
    val events =
        if (duplicateSessionStart) {
            """[{"type":"SESSION_STARTED","eventAt":"$SESSION_STARTED_AT","observedAt":"$SESSION_STARTED_AT"},{"type":"SESSION_STARTED","eventAt":"$SESSION_STARTED_AT","observedAt":"$SESSION_STARTED_AT"},{"type":"H4_EVALUATED","eventAt":"$EVIDENCE_OBSERVED_AT","observedAt":"$EVIDENCE_OBSERVED_AT"}]"""
        } else {
            """[{"type":"SESSION_STARTED","eventAt":"$SESSION_STARTED_AT","observedAt":"$SESSION_STARTED_AT"},{"type":"H4_EVALUATED","eventAt":"$EVIDENCE_OBSERVED_AT","observedAt":"$EVIDENCE_OBSERVED_AT"}]"""
        }
    return """
        {
          "schemaVersion": 1,
          "generatedAt": "$REPORT_AT",
          "policyId": "$POLICY_ID",
          "policySha256": "$POLICY_SHA",
          "protocolId": "$PROTOCOL_ID",
          "candidateId": "$CANDIDATE_ID",
          "protocolSha256": "$PROTOCOL_SHA",
          "symbol": "BTCUSDT",
          "state": {
            "sessionId": "$SESSION_ID",
            "status": "OBSERVING",
            "sessionStartedAt": "$SESSION_STARTED_AT",
            "lastObservedAt": "$EVIDENCE_OBSERVED_AT",
            "updatedAt": "$EVIDENCE_OBSERVED_AT"
          },
          "events": $events
        }
        """.trimIndent() + "\n"
}

private fun approvalReportJson(
    gateStatus: String,
    omitFrozenGates: Boolean,
): String {
    val gateIds =
        if (omitFrozenGates) {
            VolumeConfirmedTrendApprovalGateContract.requiredIds.take(1)
        } else {
            VolumeConfirmedTrendApprovalGateContract.requiredIds
        }
    val gates =
        gateIds.joinToString(",") { id ->
            val status = if (id == "FRESH_SHADOW_DAYS") gateStatus else "PASS"
            """{"id":"$id","status":"$status","actual":"90","required":"frozen","reason":"frozen"}"""
        }
    return """
        {
          "schemaVersion": 1,
          "status": "READY_FOR_HUMAN_REVIEW",
          "protocolId": "$PROTOCOL_ID",
          "candidateId": "$CANDIDATE_ID",
          "protocolSha256": "$PROTOCOL_SHA",
          "policyId": "$POLICY_ID",
          "policySha256": "$POLICY_SHA",
          "evaluatedAt": "$REPORT_AT",
          "sessionId": "$SESSION_ID",
          "observedCalendarDays": 90.0,
          "sessionReturnPct": 5.0,
          "closedTradeProfitFactor": 1.5,
          "gates": [$gates],
          "readyForHumanReview": true,
          "automaticExecutionAllowed": false,
          "liveExecutionAllowed": false
        }
        """.trimIndent() + "\n"
}

private fun approvalReceiptJson(
    shadowSha: String,
    reportSha: String,
): String =
    """
    {
      "schemaVersion": 1,
      "status": "APPROVED",
      "approvalId": "approval-001",
      "protocol": {"id": "$PROTOCOL_ID", "candidateId": "$CANDIDATE_ID", "sha256": "$PROTOCOL_SHA"},
      "forwardPolicy": {"id": "$POLICY_ID", "sha256": "$POLICY_SHA"},
      "shadowSessionId": "$SESSION_ID",
      "shadowEvidenceSha256": "$shadowSha",
      "approvalReportSha256": "$reportSha",
      "approvedAt": "$APPROVED_AT",
      "approvedBy": "human-owner",
      "liveExecutionAllowed": true,
      "reasonCode": "HUMAN_REVIEW_APPROVED"
    }
    """.trimIndent() + "\n"

private fun protocol(): VolumeConfirmedTrendProtocolDefinition =
    VolumeConfirmedTrendProtocolDefinition(
        protocolId = PROTOCOL_ID,
        candidateId = CANDIDATE_ID,
        protocolSha256 = PROTOCOL_SHA,
        symbol = Symbol("BTCUSDT"),
        parameters = VolumeConfirmedTrendParameters(),
        executionContract = VolumeConfirmedTrendExecutionContract(),
        developmentStartInclusive = Instant.parse("2020-01-01T00:00:00Z"),
        developmentEndExclusive = Instant.parse("2026-01-01T00:00:00Z"),
    )

private fun policy(): VolumeConfirmedTrendForwardPolicy =
    VolumeConfirmedTrendForwardPolicy(
        policyId = POLICY_ID,
        policySha256 = POLICY_SHA,
        minimumCalendarDays = 90,
        minimumClosedTrades = 5,
        minimumExecutedTransitions = 6,
        minimumSessionReturnPct = 0.0,
        minimumClosedTradeProfitFactor = 1.0,
        maximumDrawdownPct = 35.0,
        maximumEntryExposureFraction = 0.85,
        maximumAdverseExposureFraction = 1.2,
        maximumLiquidationCount = 0,
        maximumObservationStaleness = Duration.ofHours(5),
    )

private fun currentShadowState(): VolumeConfirmedTrendShadowState =
    VolumeConfirmedTrendShadowState(
        protocolId = PROTOCOL_ID,
        candidateId = CANDIDATE_ID,
        protocolSha256 = PROTOCOL_SHA,
        symbol = Symbol("BTCUSDT"),
        sessionId = SESSION_ID,
        status = VolumeConfirmedTrendShadowStatus.OBSERVING,
        sessionStartedAt = Instant.parse(SESSION_STARTED_AT),
        indicatorState =
            VolumeConfirmedTrendIndicatorState(
                processedBars = 1,
                lastBarOpenedAt = Instant.parse(EVIDENCE_OBSERVED_AT),
                emaStates = emptyList(),
                targetSide = null,
                recentVolumes = emptyList(),
            ),
        lastAppliedFundingAt = Instant.parse(EVIDENCE_OBSERVED_AT),
        lastObservedAt = Instant.parse(EVIDENCE_OBSERVED_AT),
        position = null,
        sessionStartingEquity = 660.0,
        cash = 700.0,
        equity = 700.0,
        peakEquity = 710.0,
        maximumDrawdownPct = 2.0,
        totalFees = 1.0,
        totalSlippage = 1.0,
        totalFundingPnl = 0.0,
        closedTrades = 10,
        executedTransitions = 11,
        invalidatedSessionCount = 0,
        updatedAt = Instant.parse(EVIDENCE_OBSERVED_AT),
        maximumEntryExposureFraction = 0.8,
        maximumAdverseExposureFraction = 0.9,
        liquidationCount = 0,
    )

private fun ByteArray.sha256(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

private const val PROTOCOL_ID = "volume-confirmed-trend-ensemble-v1"
private const val CANDIDATE_ID = "vcte_4h_majority_001"
private const val POLICY_ID = "volume-confirmed-trend-ensemble-v1-forward-policy"
private const val SESSION_ID = "trend-shadow-forward-session"
private const val SESSION_STARTED_AT = "2026-08-01T00:00:00Z"
private const val EVIDENCE_OBSERVED_AT = "2026-11-06T20:00:00Z"
private const val REPORT_AT = "2026-11-07T00:00:00Z"
private const val APPROVED_AT = "2026-11-07T01:00:00Z"
private val PROTOCOL_SHA = "a".repeat(64)
private val POLICY_SHA = "b".repeat(64)
