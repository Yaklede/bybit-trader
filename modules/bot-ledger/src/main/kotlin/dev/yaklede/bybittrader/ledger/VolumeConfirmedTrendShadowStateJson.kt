package dev.yaklede.bybittrader.ledger

import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendEmaState
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendIndicatorState
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowPosition
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowState
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowStatus
import dev.yaklede.bybittrader.ledger.db.VolumeConfirmedTrendShadowStates
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.time.Instant

internal fun VolumeConfirmedTrendShadowState.toTrendShadowStatePayload(): String =
    buildJsonObject {
        put("schemaVersion", TREND_SHADOW_STATE_SCHEMA_VERSION)
        putNullableString("sessionStartedAt", sessionStartedAt?.toString())
        put("indicatorState", indicatorState.toJsonObject())
        put("lastAppliedFundingAt", lastAppliedFundingAt.toString())
        putNullableString("lastObservedAt", lastObservedAt?.toString())
        put("position", position?.toJsonObject() ?: JsonNull)
        put("sessionStartingEquity", sessionStartingEquity)
        put("cash", cash)
        put("equity", equity)
        put("peakEquity", peakEquity)
        put("maximumDrawdownPct", maximumDrawdownPct)
        put("totalFees", totalFees)
        put("totalSlippage", totalSlippage)
        put("totalFundingPnl", totalFundingPnl)
        put("closedTrades", closedTrades)
        put("executedTransitions", executedTransitions)
        put("invalidatedSessionCount", invalidatedSessionCount)
    }.toString()

internal fun VolumeConfirmedTrendShadowStates.toTrendShadowState(): VolumeConfirmedTrendShadowState {
    val payload = Json.parseToJsonElement(state_payload).jsonObject
    require(payload.requiredLong("schemaVersion") == TREND_SHADOW_STATE_SCHEMA_VERSION.toLong()) {
        "Unsupported volume-confirmed trend shadow state schema."
    }
    return VolumeConfirmedTrendShadowState(
        protocolId = protocol_id,
        candidateId = candidate_id,
        protocolSha256 = protocol_sha256,
        symbol = Symbol(symbol),
        sessionId = session_id,
        status = VolumeConfirmedTrendShadowStatus.valueOf(status),
        sessionStartedAt = payload.nullableString("sessionStartedAt")?.let(Instant::parse),
        indicatorState = payload.requiredObject("indicatorState").toIndicatorState(),
        lastAppliedFundingAt = Instant.parse(payload.requiredString("lastAppliedFundingAt")),
        lastObservedAt = payload.nullableString("lastObservedAt")?.let(Instant::parse),
        position = payload.nullableObject("position")?.toShadowPosition(),
        sessionStartingEquity = payload.requiredDouble("sessionStartingEquity"),
        cash = payload.requiredDouble("cash"),
        equity = payload.requiredDouble("equity"),
        peakEquity = payload.requiredDouble("peakEquity"),
        maximumDrawdownPct = payload.requiredDouble("maximumDrawdownPct"),
        totalFees = payload.requiredDouble("totalFees"),
        totalSlippage = payload.requiredDouble("totalSlippage"),
        totalFundingPnl = payload.requiredDouble("totalFundingPnl"),
        closedTrades = payload.requiredLong("closedTrades").toInt(),
        executedTransitions = payload.requiredLong("executedTransitions").toInt(),
        invalidatedSessionCount = payload.requiredLong("invalidatedSessionCount").toInt(),
        updatedAt = Instant.parse(updated_at),
    )
}

private fun VolumeConfirmedTrendIndicatorState.toJsonObject(): JsonObject =
    buildJsonObject {
        put("processedBars", processedBars)
        putNullableString("lastBarOpenedAt", lastBarOpenedAt?.toString())
        put(
            "emaStates",
            buildJsonArray {
                emaStates.forEach { state ->
                    add(
                        buildJsonObject {
                            putNullableDouble("fast", state.fast)
                            putNullableDouble("slow", state.slow)
                        },
                    )
                }
            },
        )
        putNullableString("targetSide", targetSide?.name)
        put("recentVolumes", buildJsonArray { recentVolumes.forEach { add(JsonPrimitive(it)) } })
    }

private fun JsonObject.toIndicatorState(): VolumeConfirmedTrendIndicatorState =
    VolumeConfirmedTrendIndicatorState(
        processedBars = requiredLong("processedBars"),
        lastBarOpenedAt = nullableString("lastBarOpenedAt")?.let(Instant::parse),
        emaStates =
            requiredArray("emaStates").map { value ->
                val state = value.jsonObject
                VolumeConfirmedTrendEmaState(
                    fast = state.nullableDouble("fast"),
                    slow = state.nullableDouble("slow"),
                )
            },
        targetSide = nullableString("targetSide")?.let(Side::valueOf),
        recentVolumes = requiredArray("recentVolumes").map { it.jsonPrimitive.double },
    )

private fun VolumeConfirmedTrendShadowPosition.toJsonObject(): JsonObject =
    buildJsonObject {
        put("side", side.name)
        put("quantity", quantity)
        put("entryAt", entryAt.toString())
        put("entryPrice", entryPrice)
        put("entryFee", entryFee)
        put("fundingPnl", fundingPnl)
    }

private fun JsonObject.toShadowPosition(): VolumeConfirmedTrendShadowPosition =
    VolumeConfirmedTrendShadowPosition(
        side = Side.valueOf(requiredString("side")),
        quantity = requiredDouble("quantity"),
        entryAt = Instant.parse(requiredString("entryAt")),
        entryPrice = requiredDouble("entryPrice"),
        entryFee = requiredDouble("entryFee"),
        fundingPnl = requiredDouble("fundingPnl"),
    )

private fun JsonObject.requiredString(key: String): String = requireNotNull(this[key]).jsonPrimitive.content

private fun JsonObject.requiredDouble(key: String): Double = requireNotNull(this[key]).jsonPrimitive.double

private fun JsonObject.requiredLong(key: String): Long = requireNotNull(this[key]).jsonPrimitive.long

private fun JsonObject.requiredObject(key: String): JsonObject = requireNotNull(this[key]).jsonObject

private fun JsonObject.requiredArray(key: String): JsonArray = requireNotNull(this[key]).jsonArray

private fun JsonObject.nullableObject(key: String): JsonObject? = this[key]?.takeUnless { it is JsonNull }?.jsonObject

private fun JsonObject.nullableString(key: String): String? =
    this[key]
        ?.takeUnless { it is JsonNull }
        ?.jsonPrimitive
        ?.content

private fun JsonObject.nullableDouble(key: String): Double? =
    this[key]
        ?.takeUnless { it is JsonNull }
        ?.jsonPrimitive
        ?.double

private fun JsonObjectBuilder.putNullableString(
    key: String,
    value: String?,
) {
    if (value == null) put(key, JsonNull) else put(key, value)
}

private fun JsonObjectBuilder.putNullableDouble(
    key: String,
    value: Double?,
) {
    if (value == null) put(key, JsonNull) else put(key, value)
}

private const val TREND_SHADOW_STATE_SCHEMA_VERSION = 1
