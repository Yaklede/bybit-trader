package dev.yaklede.bybittrader.ledger

import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.execution.ExecutionRiskNavStatus
import dev.yaklede.bybittrader.engine.execution.ExecutionRiskState
import dev.yaklede.bybittrader.engine.execution.ExecutionRuntimeMode
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveEvent
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveEventType
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveState
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveStatus
import dev.yaklede.bybittrader.ledger.db.VolumeConfirmedTrendLiveEvents
import dev.yaklede.bybittrader.ledger.db.VolumeConfirmedTrendLiveStates
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.time.Instant

internal fun VolumeConfirmedTrendLiveState.toTrendLiveStatePayload(): String =
    buildJsonObject {
        put("schemaVersion", TREND_LIVE_STATE_SCHEMA_VERSION)
        putNullableString("approvalId", approvalId)
        putNullableString("activeDecisionKey", activeDecisionKey)
        putNullableString("pendingTargetSide", pendingTargetSide?.name)
        putNullableString("clientOrderId", clientOrderId)
        putNullableString("exchangeOrderId", exchangeOrderId)
        putNullableString("observedPositionSide", observedPositionSide?.name)
        putNullableString("observedPositionQuantity", observedPositionQuantity?.toPlainString())
        putNullableString("lastExecutionId", lastExecutionId)
        putNullableString("haltedReasonCode", haltedReasonCode)
        put("riskState", riskState?.toTrendLiveRiskPayload() ?: JsonNull)
        put("riskReasonCodes", kotlinx.serialization.json.JsonArray(riskReasonCodes.map(::JsonPrimitive)))
    }.toString()

internal fun VolumeConfirmedTrendLiveStates.toTrendLiveState(): VolumeConfirmedTrendLiveState {
    val payload = Json.parseToJsonElement(state_payload).jsonObject
    val schemaVersion = payload.requiredLong("schemaVersion")
    require(schemaVersion in 1..TREND_LIVE_STATE_SCHEMA_VERSION.toLong()) {
        "Unsupported volume-confirmed trend live state schema."
    }
    return VolumeConfirmedTrendLiveState(
        protocolId = protocol_id,
        candidateId = candidate_id,
        protocolSha256 = protocol_sha256,
        symbol = Symbol(symbol),
        status = VolumeConfirmedTrendLiveStatus.valueOf(status),
        approvalId = payload.nullableString("approvalId"),
        activeDecisionKey = payload.nullableString("activeDecisionKey"),
        pendingTargetSide = payload.nullableString("pendingTargetSide")?.let(Side::valueOf),
        clientOrderId = payload.nullableString("clientOrderId"),
        exchangeOrderId = payload.nullableString("exchangeOrderId"),
        observedPositionSide = payload.nullableString("observedPositionSide")?.let(Side::valueOf),
        observedPositionQuantity = payload.nullableString("observedPositionQuantity")?.let(::BigDecimal),
        lastExecutionId = payload.nullableString("lastExecutionId"),
        haltedReasonCode = payload.nullableString("haltedReasonCode"),
        updatedAt = Instant.parse(updated_at),
        riskState = if (schemaVersion >= 2) payload.nullableObject("riskState")?.toTrendLiveRiskState() else null,
        riskReasonCodes =
            if (schemaVersion >= 3) {
                payload["riskReasonCodes"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            } else {
                emptyList()
            },
    )
}

private fun ExecutionRiskState.toTrendLiveRiskPayload(): JsonObject =
    buildJsonObject {
        put("mode", mode.name)
        put("peakEquity", peakEquity.toPlainString())
        put("utcDayStartedAt", utcDayStartedAt.toString())
        put("dayStartEquity", dayStartEquity.toPlainString())
        put("latestEquity", latestEquity.toPlainString())
        put("consecutiveLosses", consecutiveLosses)
        putNullableLong("lastClosureId", lastClosureId)
        put("updatedAt", updatedAt.toString())
        put("navStatus", navStatus.name)
        put("strategyUnits", strategyUnits.toPlainString())
        put("latestUnitizedNav", latestUnitizedNav.toPlainString())
        put("peakUnitizedNav", peakUnitizedNav.toPlainString())
        put("dayStartUnitizedNav", dayStartUnitizedNav.toPlainString())
        put("cumulativeExternalCashFlow", cumulativeExternalCashFlow.toPlainString())
        putNullableLong("lastAccountTransactionId", lastAccountTransactionId)
    }

private fun JsonObject.toTrendLiveRiskState(): ExecutionRiskState =
    ExecutionRiskState(
        mode = ExecutionRuntimeMode.valueOf(requiredString("mode")),
        peakEquity = BigDecimal(requiredString("peakEquity")),
        utcDayStartedAt = Instant.parse(requiredString("utcDayStartedAt")),
        dayStartEquity = BigDecimal(requiredString("dayStartEquity")),
        latestEquity = BigDecimal(requiredString("latestEquity")),
        consecutiveLosses = requiredLong("consecutiveLosses").toInt(),
        lastClosureId = nullableLong("lastClosureId"),
        updatedAt = Instant.parse(requiredString("updatedAt")),
        navStatus = ExecutionRiskNavStatus.valueOf(requiredString("navStatus")),
        strategyUnits = BigDecimal(requiredString("strategyUnits")),
        latestUnitizedNav = BigDecimal(requiredString("latestUnitizedNav")),
        peakUnitizedNav = BigDecimal(requiredString("peakUnitizedNav")),
        dayStartUnitizedNav = BigDecimal(requiredString("dayStartUnitizedNav")),
        cumulativeExternalCashFlow = BigDecimal(requiredString("cumulativeExternalCashFlow")),
        lastAccountTransactionId = nullableLong("lastAccountTransactionId"),
    )

internal fun VolumeConfirmedTrendLiveEvent.toTrendLiveEventPayload(): String =
    buildJsonObject {
        put("schemaVersion", TREND_LIVE_EVENT_SCHEMA_VERSION)
        putNullableString("targetSide", targetSide?.name)
        putNullableString("orderSide", orderSide?.name)
        putNullableString("orderQuantity", orderQuantity?.toPlainString())
        putNullableString("referencePrice", referencePrice?.toPlainString())
        putNullableString("limitPrice", limitPrice?.toPlainString())
        putNullableString("clientOrderId", clientOrderId)
        putNullableString("exchangeOrderId", exchangeOrderId)
        putNullableString("executionId", executionId)
        put("reasonCode", reasonCode)
    }.toString()

internal fun VolumeConfirmedTrendLiveEvents.toTrendLiveEvent(): VolumeConfirmedTrendLiveEvent {
    val payload = Json.parseToJsonElement(event_payload).jsonObject
    require(payload.requiredLong("schemaVersion") == TREND_LIVE_EVENT_SCHEMA_VERSION.toLong()) {
        "Unsupported volume-confirmed trend live event schema."
    }
    return VolumeConfirmedTrendLiveEvent(
        eventId = event_id,
        protocolId = protocol_id,
        protocolSha256 = protocol_sha256,
        symbol = Symbol(symbol),
        decisionKey = decision_key,
        type = VolumeConfirmedTrendLiveEventType.valueOf(event_type),
        targetSide = payload.nullableString("targetSide")?.let(Side::valueOf),
        orderSide = payload.nullableString("orderSide")?.let(Side::valueOf),
        orderQuantity = payload.nullableString("orderQuantity")?.let(::BigDecimal),
        referencePrice = payload.nullableString("referencePrice")?.let(::BigDecimal),
        limitPrice = payload.nullableString("limitPrice")?.let(::BigDecimal),
        clientOrderId = payload.nullableString("clientOrderId"),
        exchangeOrderId = payload.nullableString("exchangeOrderId"),
        executionId = payload.nullableString("executionId"),
        reasonCode = payload.requiredString("reasonCode"),
        occurredAt = Instant.parse(occurred_at),
    )
}

private fun JsonObject.requiredString(key: String): String = requireNotNull(this[key]).jsonPrimitive.content

private fun JsonObject.requiredLong(key: String): Long = requireNotNull(this[key]).jsonPrimitive.long

private fun JsonObject.nullableString(key: String): String? =
    this[key]
        ?.takeUnless { it is JsonNull }
        ?.jsonPrimitive
        ?.content

private fun JsonObject.nullableLong(key: String): Long? =
    this[key]
        ?.takeUnless { it is JsonNull }
        ?.jsonPrimitive
        ?.long

private fun JsonObject.nullableObject(key: String): JsonObject? =
    this[key]
        ?.takeUnless { it is JsonNull }
        ?.jsonObject

private fun JsonObjectBuilder.putNullableString(
    key: String,
    value: String?,
) {
    if (value == null) put(key, JsonNull) else put(key, value)
}

private fun JsonObjectBuilder.putNullableLong(
    key: String,
    value: Long?,
) {
    if (value == null) put(key, JsonNull) else put(key, value)
}

private const val TREND_LIVE_STATE_SCHEMA_VERSION = 3
private const val TREND_LIVE_EVENT_SCHEMA_VERSION = 1
