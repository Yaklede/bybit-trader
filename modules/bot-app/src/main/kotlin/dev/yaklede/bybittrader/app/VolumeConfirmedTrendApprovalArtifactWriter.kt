package dev.yaklede.bybittrader.app

import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalGate
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalGateContract
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalReport
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalSnapshot
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalStatus
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendEmaState
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendIndicatorState
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowEvent
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowEventType
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowPosition
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowReport
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowState
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant

data class VolumeConfirmedTrendApprovalArtifactExport(
    val exportDirectory: Path,
    val shadowEvidencePath: Path,
    val shadowEvidenceSha256: String,
    val approvalReportPath: Path,
    val approvalReportSha256: String,
    val manifestPath: Path,
    val sessionId: String,
    val evaluatedAt: Instant,
)

class VolumeConfirmedTrendApprovalArtifactWriter(
    private val outputDirectory: Path,
    private val approvalSnapshotProvider: suspend () -> VolumeConfirmedTrendApprovalSnapshot,
) {
    suspend fun export(): VolumeConfirmedTrendApprovalArtifactExport {
        val snapshot = approvalSnapshotProvider()
        val shadowReport = requireNotNull(snapshot.shadowReport) { "Trend approval Shadow snapshot is unavailable." }
        val approvalReport = snapshot.approvalReport
        validateSnapshot(shadowReport, approvalReport)
        val state = requireNotNull(shadowReport.state)
        val orderedEvents =
            shadowReport.recentEvents.sortedWith(
                compareBy<VolumeConfirmedTrendShadowEvent>(VolumeConfirmedTrendShadowEvent::observedAt)
                    .thenBy(VolumeConfirmedTrendShadowEvent::eventAt)
                    .thenBy(VolumeConfirmedTrendShadowEvent::eventId),
            )
        val shadowBytes =
            approvalArtifactJson
                .encodeToString(
                    ShadowEvidenceArtifact(
                        schemaVersion = SHADOW_EVIDENCE_SCHEMA_VERSION,
                        generatedAt = approvalReport.evaluatedAt.toString(),
                        policyId = approvalReport.policyId,
                        policySha256 = approvalReport.policySha256,
                        protocolId = shadowReport.protocolId,
                        candidateId = shadowReport.candidateId,
                        protocolSha256 = shadowReport.protocolSha256,
                        symbol = shadowReport.symbol.value,
                        state = state.toArtifact(),
                        events = orderedEvents.map(VolumeConfirmedTrendShadowEvent::toArtifact),
                    ),
                ).withFinalNewline()
                .toByteArray()
        val reportBytes =
            approvalArtifactJson
                .encodeToString(approvalReport.toArtifact())
                .withFinalNewline()
                .toByteArray()
        val shadowSha = shadowBytes.sha256()
        val reportSha = reportBytes.sha256()
        val directoryName = "${state.sessionId}-${approvalReport.evaluatedAt.toFileTimestamp()}"
        Files.createDirectories(outputDirectory)
        val finalDirectory = outputDirectory.resolve(directoryName)
        require(!Files.exists(finalDirectory)) { "Trend approval export already exists: $finalDirectory" }
        val temporaryDirectory = Files.createTempDirectory(outputDirectory, ".trend-approval-")
        return try {
            val shadowPath = temporaryDirectory.resolve(SHADOW_EVIDENCE_FILE)
            val reportPath = temporaryDirectory.resolve(APPROVAL_REPORT_FILE)
            val manifestPath = temporaryDirectory.resolve(MANIFEST_FILE)
            Files.write(shadowPath, shadowBytes)
            Files.write(reportPath, reportBytes)
            val manifestBytes =
                approvalArtifactJson
                    .encodeToString(
                        ApprovalArtifactManifest(
                            schemaVersion = ARTIFACT_SCHEMA_VERSION,
                            protocolId = approvalReport.protocolId,
                            candidateId = approvalReport.candidateId,
                            protocolSha256 = approvalReport.protocolSha256,
                            policyId = approvalReport.policyId,
                            policySha256 = approvalReport.policySha256,
                            sessionId = requireNotNull(approvalReport.sessionId),
                            evaluatedAt = approvalReport.evaluatedAt.toString(),
                            shadowEvidenceFile = SHADOW_EVIDENCE_FILE,
                            shadowEvidenceSha256 = shadowSha,
                            approvalReportFile = APPROVAL_REPORT_FILE,
                            approvalReportSha256 = reportSha,
                            readyForHumanReview = true,
                            automaticExecutionAllowed = false,
                            liveExecutionAllowed = false,
                        ),
                    ).withFinalNewline()
                    .toByteArray()
            Files.write(manifestPath, manifestBytes)
            moveAtomically(temporaryDirectory, finalDirectory)
            VolumeConfirmedTrendApprovalArtifactExport(
                exportDirectory = finalDirectory,
                shadowEvidencePath = finalDirectory.resolve(SHADOW_EVIDENCE_FILE),
                shadowEvidenceSha256 = shadowSha,
                approvalReportPath = finalDirectory.resolve(APPROVAL_REPORT_FILE),
                approvalReportSha256 = reportSha,
                manifestPath = finalDirectory.resolve(MANIFEST_FILE),
                sessionId = state.sessionId,
                evaluatedAt = approvalReport.evaluatedAt,
            )
        } catch (error: Throwable) {
            temporaryDirectory.toFile().deleteRecursively()
            throw error
        }
    }

    private fun validateSnapshot(
        shadow: VolumeConfirmedTrendShadowReport,
        approval: VolumeConfirmedTrendApprovalReport,
    ) {
        require(approval.status == VolumeConfirmedTrendApprovalStatus.READY_FOR_HUMAN_REVIEW && approval.readyForHumanReview) {
            "Trend approval artifacts can be exported only when every forward gate is ready for human review."
        }
        require(VolumeConfirmedTrendApprovalGateContract.isSatisfiedBy(approval)) {
            "Trend approval artifacts require the exact frozen gate set to pass without execution permission."
        }
        require(shadow.protocolId == approval.protocolId && shadow.candidateId == approval.candidateId) {
            "Trend approval shadow and report identities do not match."
        }
        require(shadow.protocolSha256 == approval.protocolSha256) {
            "Trend approval shadow and report protocol fingerprints do not match."
        }
        val state = requireNotNull(shadow.state) { "Trend approval shadow state is unavailable." }
        require(state.sessionId == approval.sessionId) { "Trend approval session IDs do not match." }
        require(shadow.recentEvents.size < MAX_ARTIFACT_EVENTS) {
            "Trend approval event export reached its limit and may be truncated."
        }
        require(shadow.recentEvents.isNotEmpty()) { "Trend approval session has no persisted events." }
        require(shadow.recentEvents.all { it.sessionId == state.sessionId }) {
            "Trend approval evidence contains an event from another session."
        }
        require(
            shadow.recentEvents.all { event ->
                event.protocolId == shadow.protocolId &&
                    event.protocolSha256 == shadow.protocolSha256 &&
                    event.symbol == shadow.symbol
            },
        ) {
            "Trend approval evidence contains an event from another strategy."
        }
        require(
            shadow.recentEvents
                .map(VolumeConfirmedTrendShadowEvent::eventId)
                .toSet()
                .size == shadow.recentEvents.size,
        ) {
            "Trend approval evidence contains duplicate event IDs."
        }
        val sessionStartedAt = requireNotNull(state.sessionStartedAt)
        require(
            shadow.recentEvents.all { event ->
                !event.eventAt.isBefore(sessionStartedAt) &&
                    !event.eventAt.isAfter(event.observedAt) &&
                    !event.observedAt.isAfter(state.updatedAt)
            },
        ) {
            "Trend approval evidence contains an event outside its causal session order."
        }
        val sessionStartEvents =
            shadow.recentEvents.filter { event -> event.type == VolumeConfirmedTrendShadowEventType.SESSION_STARTED }
        require(
            sessionStartEvents.size == 1 &&
                sessionStartEvents.single().eventAt == sessionStartedAt &&
                sessionStartEvents.single().observedAt == sessionStartedAt,
        ) {
            "Trend approval evidence requires exactly one session start matching the persisted state."
        }
        require(shadow.recentEvents.none { it.type == VolumeConfirmedTrendShadowEventType.SESSION_INVALIDATED }) {
            "Trend approval evidence contains a continuity invalidation."
        }
    }
}

private fun moveAtomically(
    source: Path,
    target: Path,
) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target)
    }
}

@Serializable
private data class ShadowEvidenceArtifact(
    val schemaVersion: Int,
    val generatedAt: String,
    val policyId: String,
    val policySha256: String,
    val protocolId: String,
    val candidateId: String,
    val protocolSha256: String,
    val symbol: String,
    val state: ShadowStateArtifact,
    val events: List<ShadowEventArtifact>,
)

@Serializable
private data class ShadowStateArtifact(
    val sessionId: String,
    val status: String,
    val sessionStartedAt: String?,
    val indicatorState: IndicatorStateArtifact,
    val lastAppliedFundingAt: String,
    val lastObservedAt: String?,
    val position: ShadowPositionArtifact?,
    val sessionStartingEquity: Double,
    val cash: Double,
    val equity: Double,
    val peakEquity: Double,
    val maximumDrawdownPct: Double,
    val totalFees: Double,
    val totalSlippage: Double,
    val totalFundingPnl: Double,
    val closedTrades: Int,
    val executedTransitions: Int,
    val invalidatedSessionCount: Int,
    val maximumEntryExposureFraction: Double,
    val maximumAdverseExposureFraction: Double,
    val liquidationCount: Int,
    val updatedAt: String,
)

@Serializable
private data class IndicatorStateArtifact(
    val processedBars: Long,
    val lastBarOpenedAt: String?,
    val emaStates: List<EmaStateArtifact>,
    val targetSide: String?,
    val recentVolumes: List<Double>,
)

@Serializable
private data class EmaStateArtifact(
    val fast: Double?,
    val slow: Double?,
)

@Serializable
private data class ShadowPositionArtifact(
    val side: String,
    val quantity: Double,
    val entryAt: String,
    val entryPrice: Double,
    val entryFee: Double,
    val fundingPnl: Double,
)

@Serializable
private data class ShadowEventArtifact(
    val eventId: String,
    val sessionId: String,
    val protocolId: String,
    val protocolSha256: String,
    val symbol: String,
    val type: String,
    val eventAt: String,
    val observedAt: String,
    val h4OpenedAt: String?,
    val side: String?,
    val referencePrice: Double?,
    val fillPrice: Double?,
    val quantity: Double?,
    val fee: Double,
    val slippage: Double,
    val fundingPnl: Double,
    val grossPnl: Double,
    val netPnl: Double,
    val cash: Double,
    val equity: Double,
    val reason: String,
)

@Serializable
private data class ApprovalReportArtifact(
    val schemaVersion: Int,
    val status: String,
    val protocolId: String,
    val candidateId: String,
    val protocolSha256: String,
    val policyId: String,
    val policySha256: String,
    val evaluatedAt: String,
    val sessionId: String?,
    val observedCalendarDays: Double,
    val sessionReturnPct: Double?,
    val closedTradeProfitFactor: Double?,
    val gates: List<ApprovalGateArtifact>,
    val readyForHumanReview: Boolean,
    val automaticExecutionAllowed: Boolean,
    val liveExecutionAllowed: Boolean,
)

@Serializable
private data class ApprovalGateArtifact(
    val id: String,
    val status: String,
    val actual: String,
    val required: String,
    val reason: String,
)

@Serializable
private data class ApprovalArtifactManifest(
    val schemaVersion: Int,
    val protocolId: String,
    val candidateId: String,
    val protocolSha256: String,
    val policyId: String,
    val policySha256: String,
    val sessionId: String,
    val evaluatedAt: String,
    val shadowEvidenceFile: String,
    val shadowEvidenceSha256: String,
    val approvalReportFile: String,
    val approvalReportSha256: String,
    val readyForHumanReview: Boolean,
    val automaticExecutionAllowed: Boolean,
    val liveExecutionAllowed: Boolean,
)

private fun VolumeConfirmedTrendShadowState.toArtifact(): ShadowStateArtifact =
    ShadowStateArtifact(
        sessionId = sessionId,
        status = status.name,
        sessionStartedAt = sessionStartedAt?.toString(),
        indicatorState = indicatorState.toArtifact(),
        lastAppliedFundingAt = lastAppliedFundingAt.toString(),
        lastObservedAt = lastObservedAt?.toString(),
        position = position?.toArtifact(),
        sessionStartingEquity = sessionStartingEquity,
        cash = cash,
        equity = equity,
        peakEquity = peakEquity,
        maximumDrawdownPct = maximumDrawdownPct,
        totalFees = totalFees,
        totalSlippage = totalSlippage,
        totalFundingPnl = totalFundingPnl,
        closedTrades = closedTrades,
        executedTransitions = executedTransitions,
        invalidatedSessionCount = invalidatedSessionCount,
        maximumEntryExposureFraction = maximumEntryExposureFraction,
        maximumAdverseExposureFraction = maximumAdverseExposureFraction,
        liquidationCount = liquidationCount,
        updatedAt = updatedAt.toString(),
    )

private fun VolumeConfirmedTrendIndicatorState.toArtifact(): IndicatorStateArtifact =
    IndicatorStateArtifact(
        processedBars = processedBars,
        lastBarOpenedAt = lastBarOpenedAt?.toString(),
        emaStates = emaStates.map(VolumeConfirmedTrendEmaState::toArtifact),
        targetSide = targetSide?.name,
        recentVolumes = recentVolumes,
    )

private fun VolumeConfirmedTrendEmaState.toArtifact(): EmaStateArtifact = EmaStateArtifact(fast = fast, slow = slow)

private fun VolumeConfirmedTrendShadowPosition.toArtifact(): ShadowPositionArtifact =
    ShadowPositionArtifact(
        side = side.name,
        quantity = quantity,
        entryAt = entryAt.toString(),
        entryPrice = entryPrice,
        entryFee = entryFee,
        fundingPnl = fundingPnl,
    )

private fun VolumeConfirmedTrendShadowEvent.toArtifact(): ShadowEventArtifact =
    ShadowEventArtifact(
        eventId = eventId,
        sessionId = sessionId,
        protocolId = protocolId,
        protocolSha256 = protocolSha256,
        symbol = symbol.value,
        type = type.name,
        eventAt = eventAt.toString(),
        observedAt = observedAt.toString(),
        h4OpenedAt = h4OpenedAt?.toString(),
        side = side?.name,
        referencePrice = referencePrice,
        fillPrice = fillPrice,
        quantity = quantity,
        fee = fee,
        slippage = slippage,
        fundingPnl = fundingPnl,
        grossPnl = grossPnl,
        netPnl = netPnl,
        cash = cash,
        equity = equity,
        reason = reason,
    )

private fun VolumeConfirmedTrendApprovalReport.toArtifact(): ApprovalReportArtifact =
    ApprovalReportArtifact(
        schemaVersion = ARTIFACT_SCHEMA_VERSION,
        status = status.name,
        protocolId = protocolId,
        candidateId = candidateId,
        protocolSha256 = protocolSha256,
        policyId = policyId,
        policySha256 = policySha256,
        evaluatedAt = evaluatedAt.toString(),
        sessionId = sessionId,
        observedCalendarDays = observedCalendarDays,
        sessionReturnPct = sessionReturnPct,
        closedTradeProfitFactor = closedTradeProfitFactor,
        gates = gates.map(VolumeConfirmedTrendApprovalGate::toArtifact),
        readyForHumanReview = readyForHumanReview,
        automaticExecutionAllowed = automaticExecutionAllowed,
        liveExecutionAllowed = liveExecutionAllowed,
    )

private fun VolumeConfirmedTrendApprovalGate.toArtifact(): ApprovalGateArtifact =
    ApprovalGateArtifact(
        id = id,
        status = status.name,
        actual = actual,
        required = required,
        reason = reason,
    )

private fun String.withFinalNewline(): String = if (endsWith('\n')) this else "$this\n"

private fun Instant.toFileTimestamp(): String = toString().replace(":", "").replace("-", "")

private fun ByteArray.sha256(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

private val approvalArtifactJson =
    Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

private const val ARTIFACT_SCHEMA_VERSION = 1
private const val SHADOW_EVIDENCE_SCHEMA_VERSION = 2
private const val MAX_ARTIFACT_EVENTS = 100_000
private const val SHADOW_EVIDENCE_FILE = "shadow-evidence.json"
private const val APPROVAL_REPORT_FILE = "approval-report.json"
private const val MANIFEST_FILE = "manifest.json"
