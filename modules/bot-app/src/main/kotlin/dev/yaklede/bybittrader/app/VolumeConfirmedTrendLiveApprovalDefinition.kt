package dev.yaklede.bybittrader.app

import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveApprovalReceipt
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveApprovalStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

fun loadVolumeConfirmedTrendLiveApprovalReceipt(path: Path): VolumeConfirmedTrendLiveApprovalReceipt {
    val root = Json.parseToJsonElement(Files.readString(path)).jsonObject
    val protocol = root.requiredLiveApprovalObject("protocol")
    val policy = root.requiredLiveApprovalObject("forwardPolicy")
    return VolumeConfirmedTrendLiveApprovalReceipt(
        schemaVersion = root.getValue("schemaVersion").jsonPrimitive.int,
        status = VolumeConfirmedTrendLiveApprovalStatus.valueOf(root.requiredLiveApprovalString("status")),
        approvalId = root.nullableLiveApprovalString("approvalId"),
        protocolId = protocol.requiredLiveApprovalString("id"),
        candidateId = protocol.requiredLiveApprovalString("candidateId"),
        protocolSha256 = protocol.requiredLiveApprovalString("sha256"),
        policyId = policy.requiredLiveApprovalString("id"),
        policySha256 = policy.requiredLiveApprovalString("sha256"),
        shadowSessionId = root.nullableLiveApprovalString("shadowSessionId"),
        shadowEvidenceSha256 = root.nullableLiveApprovalString("shadowEvidenceSha256"),
        approvalReportSha256 = root.nullableLiveApprovalString("approvalReportSha256"),
        approvedAt = root.nullableLiveApprovalString("approvedAt")?.let(Instant::parse),
        approvedBy = root.nullableLiveApprovalString("approvedBy"),
        liveExecutionAllowed = root.getValue("liveExecutionAllowed").jsonPrimitive.boolean,
        reasonCode = root.requiredLiveApprovalString("reasonCode"),
    )
}

private fun JsonObject.requiredLiveApprovalObject(name: String): JsonObject = getValue(name).jsonObject

private fun JsonObject.requiredLiveApprovalString(name: String): String = getValue(name).jsonPrimitive.content

private fun JsonObject.nullableLiveApprovalString(name: String): String? =
    this[name]
        ?.takeUnless { it is JsonNull }
        ?.jsonPrimitive
        ?.content
