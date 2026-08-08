package dev.yaklede.bybittrader.app

import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalGate
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalGateContract
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalGateStatus
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalReport
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalStatus
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendEmaState
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendIndicatorState
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowEvent
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowEventType
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowReport
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowState
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant

class VolumeConfirmedTrendApprovalArtifactWriterTest :
    StringSpec({
        "ready snapshot is atomically frozen with verifiable hashes" {
            val output = Files.createTempDirectory("trend-approval-writer-test")
            try {
                val shadow = readyShadowReport()
                val approval = readyApprovalReport()
                val writer =
                    VolumeConfirmedTrendApprovalArtifactWriter(
                        outputDirectory = output,
                        shadowReportProvider = { shadow },
                        approvalReportProvider = { approval },
                    )

                val exported = writer.export()

                Files.isDirectory(exported.exportDirectory) shouldBe true
                Files.isRegularFile(exported.shadowEvidencePath) shouldBe true
                Files.isRegularFile(exported.approvalReportPath) shouldBe true
                Files.isRegularFile(exported.manifestPath) shouldBe true
                Files.readAllBytes(exported.shadowEvidencePath).sha256() shouldBe exported.shadowEvidenceSha256
                Files.readAllBytes(exported.approvalReportPath).sha256() shouldBe exported.approvalReportSha256
                val evidence = Files.readString(exported.shadowEvidencePath)
                evidence shouldContain "\"schemaVersion\": 2"
                evidence shouldContain "\"sessionId\": \"shadow-ready-1\""
                (evidence.indexOf("SESSION_STARTED") < evidence.indexOf("H4_EVALUATED")) shouldBe true
                Files.readString(exported.manifestPath).also { manifest ->
                    manifest shouldContain "\"readyForHumanReview\": true"
                    manifest shouldContain "\"automaticExecutionAllowed\": false"
                    manifest shouldContain "\"liveExecutionAllowed\": false"
                    manifest shouldContain exported.shadowEvidenceSha256
                    manifest shouldContain exported.approvalReportSha256
                }
                shouldThrow<IllegalArgumentException> { writer.export() }
            } finally {
                output.toFile().deleteRecursively()
            }
        }

        "collecting snapshot cannot be exported" {
            val output = Files.createTempDirectory("trend-approval-writer-blocked-test")
            try {
                val writer =
                    VolumeConfirmedTrendApprovalArtifactWriter(
                        outputDirectory = output,
                        shadowReportProvider = { readyShadowReport() },
                        approvalReportProvider = {
                            readyApprovalReport().copy(
                                status = VolumeConfirmedTrendApprovalStatus.SHADOW_COLLECTING,
                                readyForHumanReview = false,
                            )
                        },
                    )

                shouldThrow<IllegalArgumentException> { writer.export() }
                Files.list(output).use { entries -> entries.count() shouldBe 0L }
            } finally {
                output.toFile().deleteRecursively()
            }
        }

        "ready snapshot with an incomplete frozen gate set cannot be exported" {
            val output = Files.createTempDirectory("trend-approval-writer-incomplete-gates-test")
            try {
                val writer =
                    VolumeConfirmedTrendApprovalArtifactWriter(
                        outputDirectory = output,
                        shadowReportProvider = { readyShadowReport() },
                        approvalReportProvider = { readyApprovalReport().copy(gates = readyApprovalReport().gates.take(1)) },
                    )

                shouldThrow<IllegalArgumentException> { writer.export() }
                Files.list(output).use { entries -> entries.count() shouldBe 0L }
            } finally {
                output.toFile().deleteRecursively()
            }
        }

        "ready snapshot with duplicate session starts cannot be exported" {
            val output = Files.createTempDirectory("trend-approval-writer-duplicate-start-test")
            try {
                val shadow = readyShadowReport()
                val duplicate =
                    shadow.recentEvents
                        .single { event ->
                            event.type == VolumeConfirmedTrendShadowEventType.SESSION_STARTED
                        }.copy(
                            eventId = "event-started-duplicate",
                        )
                val writer =
                    VolumeConfirmedTrendApprovalArtifactWriter(
                        outputDirectory = output,
                        shadowReportProvider = { shadow.copy(recentEvents = shadow.recentEvents + duplicate) },
                        approvalReportProvider = { readyApprovalReport() },
                    )

                shouldThrow<IllegalArgumentException> { writer.export() }
                Files.list(output).use { entries -> entries.count() shouldBe 0L }
            } finally {
                output.toFile().deleteRecursively()
            }
        }

        "ready snapshot with a mismatched event strategy cannot be exported" {
            val output = Files.createTempDirectory("trend-approval-writer-event-identity-test")
            try {
                val shadow = readyShadowReport()
                val mismatched = shadow.recentEvents.last().copy(protocolSha256 = "f".repeat(64))
                val writer =
                    VolumeConfirmedTrendApprovalArtifactWriter(
                        outputDirectory = output,
                        shadowReportProvider = {
                            shadow.copy(recentEvents = shadow.recentEvents.dropLast(1) + mismatched)
                        },
                        approvalReportProvider = { readyApprovalReport() },
                    )

                shouldThrow<IllegalArgumentException> { writer.export() }
                Files.list(output).use { entries -> entries.count() shouldBe 0L }
            } finally {
                output.toFile().deleteRecursively()
            }
        }
    })

private fun readyShadowReport(): VolumeConfirmedTrendShadowReport {
    val state = readyShadowState()
    return VolumeConfirmedTrendShadowReport(
        protocolId = PROTOCOL_ID,
        candidateId = CANDIDATE_ID,
        protocolSha256 = PROTOCOL_SHA,
        symbol = state.symbol,
        state = state,
        recentEvents =
            listOf(
                readyShadowEvent(
                    state = state,
                    eventId = "event-evaluated",
                    type = VolumeConfirmedTrendShadowEventType.H4_EVALUATED,
                    eventAt = Instant.parse("2026-11-07T00:00:00Z"),
                    observedAt = Instant.parse("2026-11-07T00:00:10Z"),
                    side = Side.BUY,
                    reason = "CONFIRMED_SIDE_CHANGE",
                ),
                readyShadowEvent(
                    state = state,
                    eventId = "event-started",
                    type = VolumeConfirmedTrendShadowEventType.SESSION_STARTED,
                    eventAt = Instant.parse("2026-08-07T00:00:10Z"),
                    observedAt = Instant.parse("2026-08-07T00:00:10Z"),
                    side = null,
                    reason = "WAIT_FOR_NEXT_CONFIRMED_TRANSITION",
                ),
            ),
    )
}

private fun readyShadowState(): VolumeConfirmedTrendShadowState =
    VolumeConfirmedTrendShadowState(
        protocolId = PROTOCOL_ID,
        candidateId = CANDIDATE_ID,
        protocolSha256 = PROTOCOL_SHA,
        symbol = Symbol("BTCUSDT"),
        sessionId = SESSION_ID,
        status = VolumeConfirmedTrendShadowStatus.OBSERVING,
        sessionStartedAt = Instant.parse("2026-08-07T00:00:10Z"),
        indicatorState =
            VolumeConfirmedTrendIndicatorState(
                processedBars = 10_000,
                lastBarOpenedAt = Instant.parse("2026-11-06T20:00:00Z"),
                emaStates = listOf(VolumeConfirmedTrendEmaState(62_000.0, 61_000.0)),
                targetSide = Side.BUY,
                recentVolumes = listOf(10.0, 12.0),
            ),
        lastAppliedFundingAt = Instant.parse("2026-11-07T00:00:00Z"),
        lastObservedAt = Instant.parse("2026-11-07T00:00:10Z"),
        position = null,
        sessionStartingEquity = 660.0,
        cash = 700.0,
        equity = 700.0,
        peakEquity = 720.0,
        maximumDrawdownPct = 12.0,
        totalFees = 4.0,
        totalSlippage = 2.0,
        totalFundingPnl = -1.0,
        closedTrades = 6,
        executedTransitions = 7,
        invalidatedSessionCount = 0,
        updatedAt = Instant.parse("2026-11-07T00:00:10Z"),
        maximumEntryExposureFraction = 0.65,
        maximumAdverseExposureFraction = 0.7,
        liquidationCount = 0,
    )

private fun readyShadowEvent(
    state: VolumeConfirmedTrendShadowState,
    eventId: String,
    type: VolumeConfirmedTrendShadowEventType,
    eventAt: Instant,
    observedAt: Instant,
    side: Side?,
    reason: String,
): VolumeConfirmedTrendShadowEvent =
    VolumeConfirmedTrendShadowEvent(
        eventId = eventId,
        sessionId = state.sessionId,
        protocolId = state.protocolId,
        protocolSha256 = state.protocolSha256,
        symbol = state.symbol,
        type = type,
        eventAt = eventAt,
        observedAt = observedAt,
        h4OpenedAt = eventAt.minusSeconds(14_400),
        side = side,
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
        reason = reason,
    )

private fun readyApprovalReport(): VolumeConfirmedTrendApprovalReport =
    VolumeConfirmedTrendApprovalReport(
        status = VolumeConfirmedTrendApprovalStatus.READY_FOR_HUMAN_REVIEW,
        protocolId = PROTOCOL_ID,
        candidateId = CANDIDATE_ID,
        protocolSha256 = PROTOCOL_SHA,
        policyId = POLICY_ID,
        policySha256 = POLICY_SHA,
        evaluatedAt = Instant.parse("2026-11-07T00:01:00Z"),
        sessionId = SESSION_ID,
        observedCalendarDays = 92.0,
        sessionReturnPct = 6.0,
        closedTradeProfitFactor = 1.5,
        gates =
            VolumeConfirmedTrendApprovalGateContract.requiredIds.map { id ->
                VolumeConfirmedTrendApprovalGate(
                    id = id,
                    status = VolumeConfirmedTrendApprovalGateStatus.PASS,
                    actual = "92.0",
                    required = ">=90.0",
                    reason = "Frozen gate passed.",
                )
            },
        readyForHumanReview = true,
    )

private fun ByteArray.sha256(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

private const val PROTOCOL_ID = "volume-confirmed-trend-ensemble-v1"
private const val CANDIDATE_ID = "vcte_4h_majority_001"
private const val SESSION_ID = "shadow-ready-1"
private const val POLICY_ID = "volume-confirmed-trend-ensemble-v1-forward-policy"
private val PROTOCOL_SHA = "a".repeat(64)
private val POLICY_SHA = "b".repeat(64)
