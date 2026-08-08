package dev.yaklede.bybittrader.app

import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalGateContract
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalGateStatus
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalStatus
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendExecutionContract
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendForwardPolicy
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendIndicatorState
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveApprovalStatus
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveRuntimeMode
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveState
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveStatus
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendParameters
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowState
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
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

        "frozen evidence with duplicate event IDs cannot be approved" {
            withRuntimeApprovalFixture(duplicateEventId = true) { fixture ->
                shouldThrow<IllegalArgumentException> { fixture.load() }
            }
        }

        "frozen evidence containing another session cannot be approved" {
            withRuntimeApprovalFixture(eventSessionId = "another-session") { fixture ->
                shouldThrow<IllegalArgumentException> { fixture.load() }
            }
        }

        "frozen evidence whose closed trade counter exceeds its events cannot be approved" {
            withRuntimeApprovalFixture(closedTrades = EVIDENCE_CLOSED_TRADES + 1) { fixture ->
                shouldThrow<IllegalArgumentException> { fixture.load() }.message shouldBe
                    "Trend Shadow evidence counters do not match append-only events."
            }
        }

        "frozen evidence whose transition counter exceeds its events cannot be approved" {
            withRuntimeApprovalFixture(executedTransitions = EVIDENCE_EXECUTED_TRANSITIONS + 1) { fixture ->
                shouldThrow<IllegalArgumentException> { fixture.load() }.message shouldBe
                    "Trend Shadow evidence counters do not match append-only events."
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

        "an approved receipt cannot bypass a current Live risk parity failure" {
            withRuntimeApprovalFixture { fixture ->
                val approval = fixture.load()
                val currentReport =
                    approval.report.copy(
                        status = VolumeConfirmedTrendApprovalStatus.RUNTIME_PARITY_REQUIRED,
                        gates =
                            approval.report.gates.map { gate ->
                                if (gate.id == "LIVE_RISK_POLICY_PARITY") {
                                    gate.copy(status = VolumeConfirmedTrendApprovalGateStatus.FAIL, actual = "false")
                                } else {
                                    gate
                                }
                            },
                        readyForHumanReview = false,
                    )

                shouldThrow<IllegalArgumentException> {
                    validateVolumeConfirmedTrendLiveCurrentShadow(
                        approval = approval,
                        currentState = currentShadowState(),
                        currentReport = currentReport,
                        protocol = protocol(),
                        forwardPolicy = policy(),
                        now = Instant.parse(APPROVED_AT),
                    )
                }.message shouldBe "Current trend Shadow report no longer passes every forward gate."
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

        "management-only fallback receipt can never authorize live execution" {
            val receipt = managementOnlyTrendLiveReceipt(protocol(), policy())

            receipt.status shouldBe VolumeConfirmedTrendLiveApprovalStatus.NOT_APPROVED
            receipt.liveExecutionAllowed shouldBe false
            receipt.approvalId shouldBe null
            receipt.shadowSessionId shouldBe null
            receipt.protocolSha256 shouldBe PROTOCOL_SHA
            receipt.policySha256 shouldBe POLICY_SHA
        }

        "only unresolved persisted orders or observed exposure require management when live is disabled" {
            val flat = liveState(VolumeConfirmedTrendLiveStatus.FLAT)
            val pending =
                liveState(VolumeConfirmedTrendLiveStatus.FLAT).copy(
                    status = VolumeConfirmedTrendLiveStatus.ENTRY_SUBMITTED,
                    activeDecisionKey = "decision-001",
                    pendingTargetSide = Side.BUY,
                    clientOrderId = "vct-entry-001",
                )
            val open =
                liveState(VolumeConfirmedTrendLiveStatus.FLAT).copy(
                    status = VolumeConfirmedTrendLiveStatus.OPEN,
                    pendingTargetSide = Side.BUY,
                    observedPositionSide = Side.BUY,
                    observedPositionQuantity = BigDecimal("0.001"),
                )
            val haltedWithOrderEvidence =
                pending.copy(
                    status = VolumeConfirmedTrendLiveStatus.HALTED,
                    haltedReasonCode = "TREND_ENTRY_ORDER_STATUS_UNKNOWN",
                )
            val haltedWithoutOrderEvidence =
                flat.copy(
                    status = VolumeConfirmedTrendLiveStatus.HALTED,
                    haltedReasonCode = "TREND_EXCHANGE_CONTRACT_MISMATCH",
                )
            val settledNotFilled = pending.copy(status = VolumeConfirmedTrendLiveStatus.ENTRY_NOT_FILLED)

            (null as VolumeConfirmedTrendLiveState?).requiresTrendLiveManagement() shouldBe false
            flat.requiresTrendLiveManagement() shouldBe false
            pending.requiresTrendLiveManagement() shouldBe true
            open.requiresTrendLiveManagement() shouldBe true
            haltedWithOrderEvidence.requiresTrendLiveManagement() shouldBe true
            haltedWithoutOrderEvidence.requiresTrendLiveManagement() shouldBe false
            settledNotFilled.requiresTrendLiveManagement() shouldBe false
        }

        "live startup mode requires both configuration and approval for signals" {
            resolveVolumeConfirmedTrendLiveRuntimeMode(
                configured = true,
                approvalAvailable = true,
                persistedState = null,
            ) shouldBe VolumeConfirmedTrendLiveRuntimeMode.SIGNAL_ENABLED
            resolveVolumeConfirmedTrendLiveRuntimeMode(
                configured = true,
                approvalAvailable = false,
                persistedState = null,
            ) shouldBe VolumeConfirmedTrendLiveRuntimeMode.MANAGEMENT_ONLY
        }

        "disabled signal runtime still manages persisted orders and exposure" {
            val pending =
                liveState(VolumeConfirmedTrendLiveStatus.FLAT).copy(
                    status = VolumeConfirmedTrendLiveStatus.ENTRY_SUBMITTED,
                    activeDecisionKey = "decision-001",
                    pendingTargetSide = Side.BUY,
                    clientOrderId = "vct-entry-001",
                )
            val open =
                liveState(VolumeConfirmedTrendLiveStatus.FLAT).copy(
                    status = VolumeConfirmedTrendLiveStatus.OPEN,
                    pendingTargetSide = Side.BUY,
                    observedPositionSide = Side.BUY,
                    observedPositionQuantity = BigDecimal("0.001"),
                )
            val haltedWithOrderEvidence =
                pending.copy(
                    status = VolumeConfirmedTrendLiveStatus.HALTED,
                    haltedReasonCode = "TREND_ENTRY_ORDER_STATUS_UNKNOWN",
                )

            resolveVolumeConfirmedTrendLiveRuntimeMode(false, false, pending) shouldBe
                VolumeConfirmedTrendLiveRuntimeMode.MANAGEMENT_ONLY
            resolveVolumeConfirmedTrendLiveRuntimeMode(false, false, open) shouldBe
                VolumeConfirmedTrendLiveRuntimeMode.MANAGEMENT_ONLY
            resolveVolumeConfirmedTrendLiveRuntimeMode(false, false, haltedWithOrderEvidence) shouldBe
                VolumeConfirmedTrendLiveRuntimeMode.MANAGEMENT_ONLY
            resolveVolumeConfirmedTrendLiveRuntimeMode(
                configured = false,
                approvalAvailable = false,
                persistedState = liveState(VolumeConfirmedTrendLiveStatus.FLAT),
            ) shouldBe VolumeConfirmedTrendLiveRuntimeMode.DISABLED
            resolveVolumeConfirmedTrendLiveRuntimeMode(false, false, null) shouldBe
                VolumeConfirmedTrendLiveRuntimeMode.DISABLED
        }

        "approval cannot remain attached after signal execution is disabled" {
            shouldThrow<IllegalArgumentException> {
                resolveVolumeConfirmedTrendLiveRuntimeMode(
                    configured = false,
                    approvalAvailable = true,
                    persistedState = null,
                )
            }
        }
    })

private fun liveState(status: VolumeConfirmedTrendLiveStatus): VolumeConfirmedTrendLiveState =
    VolumeConfirmedTrendLiveState(
        protocolId = PROTOCOL_ID,
        candidateId = CANDIDATE_ID,
        protocolSha256 = PROTOCOL_SHA,
        symbol = Symbol("BTCUSDT"),
        status = status,
        approvalId = "approval-001",
        activeDecisionKey = null,
        pendingTargetSide = null,
        clientOrderId = null,
        exchangeOrderId = null,
        observedPositionSide = null,
        observedPositionQuantity = null,
        lastExecutionId = null,
        haltedReasonCode = null,
        updatedAt = Instant.parse(APPROVED_AT),
    )

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
    duplicateEventId: Boolean = false,
    eventSessionId: String = SESSION_ID,
    closedTrades: Int = EVIDENCE_CLOSED_TRADES,
    executedTransitions: Int = EVIDENCE_EXECUTED_TRANSITIONS,
    block: (RuntimeApprovalFixture) -> Unit,
) {
    val directory = Files.createTempDirectory("trend-live-runtime-approval-")
    try {
        val shadowPath = directory.resolve("shadow-evidence.json")
        val reportPath = directory.resolve("approval-report.json")
        val receiptPath = directory.resolve("receipt.json")
        Files.writeString(
            shadowPath,
            shadowEvidenceJson(
                duplicateSessionStart = duplicateSessionStart,
                duplicateEventId = duplicateEventId,
                eventSessionId = eventSessionId,
                closedTrades = closedTrades,
                executedTransitions = executedTransitions,
            ),
        )
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

private fun shadowEvidenceJson(
    duplicateSessionStart: Boolean,
    duplicateEventId: Boolean,
    eventSessionId: String,
    closedTrades: Int,
    executedTransitions: Int,
): String {
    val events =
        buildList {
            add(
                shadowEventJson(
                    eventId = "event-start",
                    sessionId = eventSessionId,
                    type = "SESSION_STARTED",
                    eventAt = SESSION_STARTED_AT,
                    observedAt = SESSION_STARTED_AT,
                ),
            )
            if (duplicateSessionStart) {
                add(
                    shadowEventJson(
                        eventId = "event-start-duplicate",
                        sessionId = eventSessionId,
                        type = "SESSION_STARTED",
                        eventAt = SESSION_STARTED_AT,
                        observedAt = SESSION_STARTED_AT,
                    ),
                )
            }
            add(
                shadowEventJson(
                    eventId = if (duplicateEventId) "event-start" else "event-h4",
                    sessionId = eventSessionId,
                    type = "H4_EVALUATED",
                    eventAt = EVIDENCE_OBSERVED_AT,
                    observedAt = EVIDENCE_OBSERVED_AT,
                ),
            )
            repeat(EVIDENCE_EXECUTED_TRANSITIONS) { index ->
                add(
                    shadowEventJson(
                        eventId = "event-open-$index",
                        sessionId = eventSessionId,
                        type = "POSITION_OPENED",
                        eventAt = EVIDENCE_OBSERVED_AT,
                        observedAt = EVIDENCE_OBSERVED_AT,
                    ),
                )
            }
            repeat(EVIDENCE_CLOSED_TRADES) { index ->
                add(
                    shadowEventJson(
                        eventId = "event-close-$index",
                        sessionId = eventSessionId,
                        type = "POSITION_CLOSED",
                        eventAt = EVIDENCE_OBSERVED_AT,
                        observedAt = EVIDENCE_OBSERVED_AT,
                    ),
                )
            }
        }.joinToString(prefix = "[", postfix = "]", separator = ",")
    return """
        {
          "schemaVersion": 2,
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
            "updatedAt": "$EVIDENCE_OBSERVED_AT",
            "closedTrades": $closedTrades,
            "executedTransitions": $executedTransitions
          },
          "events": $events
        }
        """.trimIndent() + "\n"
}

private fun shadowEventJson(
    eventId: String,
    sessionId: String,
    type: String,
    eventAt: String,
    observedAt: String,
): String =
    """
    {
      "eventId": "$eventId",
      "sessionId": "$sessionId",
      "protocolId": "$PROTOCOL_ID",
      "protocolSha256": "$PROTOCOL_SHA",
      "symbol": "BTCUSDT",
      "type": "$type",
      "eventAt": "$eventAt",
      "observedAt": "$observedAt"
    }
    """.trimIndent()

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
private const val EVIDENCE_CLOSED_TRADES = 5
private const val EVIDENCE_EXECUTED_TRANSITIONS = 6
private val PROTOCOL_SHA = "a".repeat(64)
private val POLICY_SHA = "b".repeat(64)
