package dev.yaklede.bybittrader.app

import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalGate
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalGateContract
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalGateStatus
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalReport
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalStatus
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendForwardPolicy
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveApprovalReceipt
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveApprovalValidator
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowState
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

data class VolumeConfirmedTrendLiveRuntimeApproval(
    val receipt: VolumeConfirmedTrendLiveApprovalReceipt,
    val report: VolumeConfirmedTrendApprovalReport,
    val shadowEvidenceSha256: String,
    val approvalReportSha256: String,
    val shadowSessionStartedAt: Instant,
    val shadowLastObservedAt: Instant,
    val shadowStateUpdatedAt: Instant,
)

fun loadVolumeConfirmedTrendLiveRuntimeApproval(
    receiptPath: Path,
    shadowEvidencePath: Path,
    approvalReportPath: Path,
    protocol: VolumeConfirmedTrendProtocolDefinition,
    forwardPolicy: VolumeConfirmedTrendForwardPolicy,
): VolumeConfirmedTrendLiveRuntimeApproval {
    val receipt = loadVolumeConfirmedTrendLiveApprovalReceipt(receiptPath)
    val shadowEvidenceBytes = Files.readAllBytes(shadowEvidencePath)
    val approvalReportBytes = Files.readAllBytes(approvalReportPath)
    val shadowEvidenceSha256 = shadowEvidenceBytes.sha256()
    val approvalReportSha256 = approvalReportBytes.sha256()
    val shadowEvidence = Json.parseToJsonElement(shadowEvidenceBytes.toString(Charsets.UTF_8)).jsonObject
    val report = parseApprovalReport(approvalReportBytes)

    validateExpectedIdentity(receipt, protocol, forwardPolicy)
    val frozenShadowState = validateShadowEvidence(shadowEvidence, receipt, report, protocol, forwardPolicy)
    require(VolumeConfirmedTrendApprovalGateContract.isSatisfiedBy(report)) {
        "Trend live approval report must contain the exact passing frozen gate set without execution permission."
    }
    requireNotNull(receipt.approvedAt).let { approvedAt ->
        require(!approvedAt.isBefore(report.evaluatedAt)) {
            "Trend live human approval cannot precede its frozen report."
        }
    }
    val validation =
        VolumeConfirmedTrendLiveApprovalValidator.validate(
            receipt = receipt,
            report = report,
            actualShadowEvidenceSha256 = shadowEvidenceSha256,
            actualApprovalReportSha256 = approvalReportSha256,
        )
    require(validation.liveExecutionAllowed) {
        "Trend live runtime approval validation failed: ${validation.failures.joinToString(",") { it.name }}"
    }
    return VolumeConfirmedTrendLiveRuntimeApproval(
        receipt = receipt,
        report = report,
        shadowEvidenceSha256 = shadowEvidenceSha256,
        approvalReportSha256 = approvalReportSha256,
        shadowSessionStartedAt = frozenShadowState.sessionStartedAt,
        shadowLastObservedAt = frozenShadowState.lastObservedAt,
        shadowStateUpdatedAt = frozenShadowState.updatedAt,
    )
}

fun validateVolumeConfirmedTrendLiveCurrentShadow(
    approval: VolumeConfirmedTrendLiveRuntimeApproval,
    currentState: VolumeConfirmedTrendShadowState?,
    currentReport: VolumeConfirmedTrendApprovalReport,
    protocol: VolumeConfirmedTrendProtocolDefinition,
    forwardPolicy: VolumeConfirmedTrendForwardPolicy,
    now: Instant = Instant.now(),
) {
    val state = requireNotNull(currentState) { "Trend live requires a persisted Shadow state." }
    require(state.protocolId == protocol.protocolId && state.candidateId == protocol.candidateId) {
        "Current trend Shadow identity does not match the runtime strategy."
    }
    require(state.protocolSha256 == protocol.protocolSha256 && state.symbol == protocol.symbol) {
        "Current trend Shadow fingerprint or symbol does not match the runtime strategy."
    }
    require(state.sessionId == approval.receipt.shadowSessionId) {
        "Current trend Shadow session does not match the human-approved session."
    }
    require(state.status == VolumeConfirmedTrendShadowStatus.OBSERVING) {
        "Current trend Shadow session is not observing."
    }
    require(state.sessionStartedAt == approval.shadowSessionStartedAt) {
        "Current trend Shadow session start does not match the approved evidence."
    }
    require(!state.updatedAt.isBefore(approval.shadowStateUpdatedAt)) {
        "Current trend Shadow state predates the approved evidence."
    }
    val lastObservedAt =
        requireNotNull(state.lastObservedAt) {
            "Current trend Shadow session has no completed observation."
        }
    require(!lastObservedAt.isBefore(approval.shadowLastObservedAt)) {
        "Current trend Shadow observation predates the approved evidence."
    }
    val observationAge = java.time.Duration.between(lastObservedAt, now)
    require(!observationAge.isNegative && observationAge <= forwardPolicy.maximumObservationStaleness) {
        "Current trend Shadow observation is stale or timestamped in the future."
    }
    require(state.maximumDrawdownPct <= forwardPolicy.maximumDrawdownPct) {
        "Current trend Shadow drawdown exceeds the approved forward policy."
    }
    require(state.maximumEntryExposureFraction <= forwardPolicy.maximumEntryExposureFraction) {
        "Current trend Shadow entry exposure exceeds the approved forward policy."
    }
    require(state.maximumAdverseExposureFraction <= forwardPolicy.maximumAdverseExposureFraction) {
        "Current trend Shadow adverse exposure exceeds the approved forward policy."
    }
    require(state.liquidationCount <= forwardPolicy.maximumLiquidationCount) {
        "Current trend Shadow liquidation count exceeds the approved forward policy."
    }
    require(
        currentReport.status == VolumeConfirmedTrendApprovalStatus.READY_FOR_HUMAN_REVIEW &&
            currentReport.readyForHumanReview &&
            currentReport.gates.isNotEmpty() &&
            currentReport.gates.all { it.status == VolumeConfirmedTrendApprovalGateStatus.PASS },
    ) {
        "Current trend Shadow report no longer passes every forward gate."
    }
    require(currentReport.sessionId == state.sessionId) {
        "Current trend Shadow report does not match the approved session."
    }
    val validation =
        VolumeConfirmedTrendLiveApprovalValidator.validate(
            receipt = approval.receipt,
            report = currentReport,
            actualShadowEvidenceSha256 = approval.shadowEvidenceSha256,
            actualApprovalReportSha256 = approval.approvalReportSha256,
        )
    require(validation.liveExecutionAllowed) {
        "Current trend live approval validation failed: ${validation.failures.joinToString(",") { it.name }}"
    }
}

private data class FrozenShadowState(
    val sessionStartedAt: Instant,
    val lastObservedAt: Instant,
    val updatedAt: Instant,
)

private fun validateExpectedIdentity(
    receipt: VolumeConfirmedTrendLiveApprovalReceipt,
    protocol: VolumeConfirmedTrendProtocolDefinition,
    forwardPolicy: VolumeConfirmedTrendForwardPolicy,
) {
    require(receipt.protocolId == protocol.protocolId && receipt.candidateId == protocol.candidateId) {
        "Trend live approval receipt does not match the runtime strategy identity."
    }
    require(receipt.protocolSha256 == protocol.protocolSha256) {
        "Trend live approval receipt does not match the runtime protocol fingerprint."
    }
    require(receipt.policyId == forwardPolicy.policyId && receipt.policySha256 == forwardPolicy.policySha256) {
        "Trend live approval receipt does not match the frozen forward policy."
    }
}

private fun validateShadowEvidence(
    root: JsonObject,
    receipt: VolumeConfirmedTrendLiveApprovalReceipt,
    report: VolumeConfirmedTrendApprovalReport,
    protocol: VolumeConfirmedTrendProtocolDefinition,
    forwardPolicy: VolumeConfirmedTrendForwardPolicy,
): FrozenShadowState {
    require(root.requiredRuntimeApprovalInt("schemaVersion") == 1) {
        "Unsupported trend Shadow evidence schema."
    }
    require(root.requiredRuntimeApprovalString("protocolId") == protocol.protocolId)
    require(root.requiredRuntimeApprovalString("candidateId") == protocol.candidateId)
    require(root.requiredRuntimeApprovalString("protocolSha256") == protocol.protocolSha256)
    require(root.requiredRuntimeApprovalString("policyId") == forwardPolicy.policyId)
    require(root.requiredRuntimeApprovalString("policySha256") == forwardPolicy.policySha256)
    require(root.requiredRuntimeApprovalString("symbol") == protocol.symbol.value)
    require(Instant.parse(root.requiredRuntimeApprovalString("generatedAt")) == report.evaluatedAt) {
        "Trend Shadow evidence and approval report timestamps do not match."
    }
    val state = root.requiredRuntimeApprovalObject("state")
    require(state.requiredRuntimeApprovalString("sessionId") == receipt.shadowSessionId) {
        "Trend Shadow evidence does not match the approved session."
    }
    require(state.requiredRuntimeApprovalString("status") == "OBSERVING") {
        "Trend Shadow evidence was not observing at export time."
    }
    val frozenState =
        FrozenShadowState(
            sessionStartedAt = Instant.parse(state.requiredRuntimeApprovalString("sessionStartedAt")),
            lastObservedAt = Instant.parse(state.requiredRuntimeApprovalString("lastObservedAt")),
            updatedAt = Instant.parse(state.requiredRuntimeApprovalString("updatedAt")),
        )
    require(!frozenState.lastObservedAt.isBefore(frozenState.sessionStartedAt)) {
        "Trend Shadow evidence observation predates its session."
    }
    require(!frozenState.updatedAt.isBefore(frozenState.lastObservedAt)) {
        "Trend Shadow evidence state timestamp predates its latest observation."
    }
    val events = root.requiredRuntimeApprovalArray("events").map { event -> event.jsonObject }
    val sessionStartEvents = events.filter { event -> event.requiredRuntimeApprovalString("type") == "SESSION_STARTED" }
    require(
        sessionStartEvents.size == 1 &&
            Instant.parse(sessionStartEvents.single().requiredRuntimeApprovalString("eventAt")) == frozenState.sessionStartedAt &&
            Instant.parse(sessionStartEvents.single().requiredRuntimeApprovalString("observedAt")) == frozenState.sessionStartedAt,
    ) {
        "Trend Shadow evidence requires exactly one session start matching the frozen state."
    }
    require(events.none { event -> event.requiredRuntimeApprovalString("type") == "SESSION_INVALIDATED" }) {
        "Trend Shadow evidence does not prove one continuous session."
    }
    return frozenState
}

private fun parseApprovalReport(bytes: ByteArray): VolumeConfirmedTrendApprovalReport {
    val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    require(root.requiredRuntimeApprovalInt("schemaVersion") == 1) {
        "Unsupported trend approval report schema."
    }
    return VolumeConfirmedTrendApprovalReport(
        status = VolumeConfirmedTrendApprovalStatus.valueOf(root.requiredRuntimeApprovalString("status")),
        protocolId = root.requiredRuntimeApprovalString("protocolId"),
        candidateId = root.requiredRuntimeApprovalString("candidateId"),
        protocolSha256 = root.requiredRuntimeApprovalString("protocolSha256"),
        policyId = root.requiredRuntimeApprovalString("policyId"),
        policySha256 = root.requiredRuntimeApprovalString("policySha256"),
        evaluatedAt = Instant.parse(root.requiredRuntimeApprovalString("evaluatedAt")),
        sessionId = root.optionalRuntimeApprovalString("sessionId"),
        observedCalendarDays = root.requiredRuntimeApprovalDouble("observedCalendarDays"),
        sessionReturnPct = root.optionalRuntimeApprovalDouble("sessionReturnPct"),
        closedTradeProfitFactor = root.optionalRuntimeApprovalDouble("closedTradeProfitFactor"),
        gates =
            root.requiredRuntimeApprovalArray("gates").map { value ->
                val gate = value.jsonObject
                VolumeConfirmedTrendApprovalGate(
                    id = gate.requiredRuntimeApprovalString("id"),
                    status = VolumeConfirmedTrendApprovalGateStatus.valueOf(gate.requiredRuntimeApprovalString("status")),
                    actual = gate.requiredRuntimeApprovalString("actual"),
                    required = gate.requiredRuntimeApprovalString("required"),
                    reason = gate.requiredRuntimeApprovalString("reason"),
                )
            },
        readyForHumanReview = root.requiredRuntimeApprovalBoolean("readyForHumanReview"),
        automaticExecutionAllowed = root.requiredRuntimeApprovalBoolean("automaticExecutionAllowed"),
        liveExecutionAllowed = root.requiredRuntimeApprovalBoolean("liveExecutionAllowed"),
    )
}

private fun JsonObject.requiredRuntimeApprovalObject(name: String): JsonObject = getValue(name).jsonObject

private fun JsonObject.requiredRuntimeApprovalArray(name: String): JsonArray = getValue(name).jsonArray

private fun JsonObject.requiredRuntimeApprovalString(name: String): String = getValue(name).jsonPrimitive.content

private fun JsonObject.requiredRuntimeApprovalInt(name: String): Int = getValue(name).jsonPrimitive.int

private fun JsonObject.requiredRuntimeApprovalDouble(name: String): Double = getValue(name).jsonPrimitive.double

private fun JsonObject.requiredRuntimeApprovalBoolean(name: String): Boolean = getValue(name).jsonPrimitive.boolean

private fun JsonObject.optionalRuntimeApprovalString(name: String): String? =
    this[name]
        ?.takeUnless { it is JsonNull }
        ?.jsonPrimitive
        ?.content

private fun JsonObject.optionalRuntimeApprovalDouble(name: String): Double? =
    this[name]
        ?.takeUnless { it is JsonNull }
        ?.jsonPrimitive
        ?.double

private fun ByteArray.sha256(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }
