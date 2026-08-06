package dev.yaklede.bybittrader.ledger

import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.paper.PaperOpenPosition
import dev.yaklede.bybittrader.engine.paper.PaperPendingEntry
import dev.yaklede.bybittrader.engine.paper.PaperRuntimePhase
import dev.yaklede.bybittrader.engine.paper.PaperRuntimeState
import dev.yaklede.bybittrader.engine.position.CausalPositionState
import dev.yaklede.bybittrader.ledger.db.PaperRuntimeStates
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate

internal fun PaperRuntimeState.toStatePayload(): String =
    buildJsonObject {
        put("schemaVersion", PAPER_RUNTIME_STATE_SCHEMA_VERSION)
        putNullableString("lastProcessedCandleAt", lastProcessedCandleAt?.toString())
        put("equity", equity)
        put("peakEquity", peakEquity)
        put("maxDrawdownPct", maxDrawdownPct)
        put("grossProfit", grossProfit)
        put("grossLoss", grossLoss)
        put("sumReturnR", sumReturnR)
        put("closedTrades", closedTrades)
        putNullableString("entryCountDate", entryCountDate?.toString())
        put("entryCount", entryCount)
        put("pendingEntry", pendingEntry?.toJsonObject() ?: JsonNull)
        put("openPosition", openPosition?.toJsonObject() ?: JsonNull)
    }.toString()

internal fun PaperRuntimeStates.toPaperRuntimeState(): PaperRuntimeState {
    val payload = Json.parseToJsonElement(state_payload).jsonObject
    require(payload.requiredInt("schemaVersion") == PAPER_RUNTIME_STATE_SCHEMA_VERSION) {
        "Unsupported paper runtime state schema."
    }
    return PaperRuntimeState(
        strategy = strategy,
        symbol = Symbol(symbol),
        timeframe = Timeframe.valueOf(timeframe),
        phase = PaperRuntimePhase.valueOf(phase),
        lastProcessedCandleAt = payload.nullableString("lastProcessedCandleAt")?.let(Instant::parse),
        equity = payload.requiredDouble("equity"),
        peakEquity = payload.requiredDouble("peakEquity"),
        maxDrawdownPct = payload.requiredDouble("maxDrawdownPct"),
        grossProfit = payload.requiredDouble("grossProfit"),
        grossLoss = payload.requiredDouble("grossLoss"),
        sumReturnR = payload.requiredDouble("sumReturnR"),
        closedTrades = payload.requiredInt("closedTrades"),
        entryCountDate = payload.nullableString("entryCountDate")?.let(LocalDate::parse),
        entryCount = payload.requiredInt("entryCount"),
        pendingEntry = payload.nullableObject("pendingEntry")?.toPaperPendingEntry(),
        openPosition = payload.nullableObject("openPosition")?.toPaperOpenPosition(),
        updatedAt = Instant.parse(updated_at),
    )
}

private fun PaperPendingEntry.toJsonObject(): JsonObject =
    buildJsonObject {
        put("signalId", signalId)
        put("signalAt", signalAt.toString())
        put("side", side.name)
        put("structuralStopPrice", structuralStopPrice)
        putNullableDouble("entryAnchoredStopDistance", entryAnchoredStopDistance)
        put("expectedR", expectedR)
    }

private fun JsonObject.toPaperPendingEntry(): PaperPendingEntry =
    PaperPendingEntry(
        signalId = requiredLong("signalId"),
        signalAt = Instant.parse(requiredString("signalAt")),
        side = Side.valueOf(requiredString("side")),
        structuralStopPrice = requiredDouble("structuralStopPrice"),
        entryAnchoredStopDistance = nullableDouble("entryAnchoredStopDistance"),
        expectedR = requiredDouble("expectedR"),
    )

private fun PaperOpenPosition.toJsonObject(): JsonObject =
    buildJsonObject {
        put("signalId", signalId)
        put("signalAt", signalAt.toString())
        put("entryOrderId", entryOrderId)
        put("entryFee", entryFee)
        put("riskAmount", riskAmount)
        put("policyState", policyState.toJsonObject())
    }

private fun JsonObject.toPaperOpenPosition(): PaperOpenPosition =
    PaperOpenPosition(
        signalId = requiredLong("signalId"),
        signalAt = Instant.parse(requiredString("signalAt")),
        entryOrderId = requiredLong("entryOrderId"),
        entryFee = requiredDouble("entryFee"),
        riskAmount = requiredDouble("riskAmount"),
        policyState = requiredObject("policyState").toCausalPositionState(),
    )

internal fun CausalPositionState.toJsonObject(): JsonObject =
    buildJsonObject {
        put("side", side.name)
        put("entryAt", entryAt.toString())
        put("entryPrice", entryPrice)
        put("initialStopPrice", initialStopPrice)
        put("currentStopPrice", currentStopPrice)
        put("riskPerUnit", riskPerUnit)
        put("expectedR", expectedR)
        put("initialQuantity", initialQuantity)
        put("remainingQuantity", remainingQuantity)
        putNullableDouble("fullTargetPrice", fullTargetPrice)
        put("partialTargetPrice", partialTargetPrice)
        put("bestHigh", bestHigh)
        put("bestLow", bestLow)
        put("processedCandles", processedCandles)
        put("partialTaken", partialTaken)
        putNullableString("partialTakeProfitAt", partialTakeProfitAt?.toString())
        putNullableDouble("partialExitPrice", partialExitPrice)
        put("partialQuantity", partialQuantity)
        put("partialGrossPnl", partialGrossPnl)
        put("partialFees", partialFees)
    }

internal fun JsonObject.toCausalPositionState(): CausalPositionState =
    CausalPositionState(
        side = Side.valueOf(requiredString("side")),
        entryAt = Instant.parse(requiredString("entryAt")),
        entryPrice = requiredDouble("entryPrice"),
        initialStopPrice = requiredDouble("initialStopPrice"),
        currentStopPrice = requiredDouble("currentStopPrice"),
        riskPerUnit = requiredDouble("riskPerUnit"),
        expectedR = requiredDouble("expectedR"),
        initialQuantity = requiredDouble("initialQuantity"),
        remainingQuantity = requiredDouble("remainingQuantity"),
        fullTargetPrice = nullableDouble("fullTargetPrice"),
        partialTargetPrice = requiredDouble("partialTargetPrice"),
        bestHigh = requiredDouble("bestHigh"),
        bestLow = requiredDouble("bestLow"),
        processedCandles = requiredInt("processedCandles"),
        partialTaken = requiredBoolean("partialTaken"),
        partialTakeProfitAt = nullableString("partialTakeProfitAt")?.let(Instant::parse),
        partialExitPrice = nullableDouble("partialExitPrice"),
        partialQuantity = requiredDouble("partialQuantity"),
        partialGrossPnl = requiredDouble("partialGrossPnl"),
        partialFees = requiredDouble("partialFees"),
    )

private fun JsonObject.requiredString(key: String): String = requireNotNull(this[key]).jsonPrimitive.content

private fun JsonObject.requiredDouble(key: String): Double = requireNotNull(this[key]).jsonPrimitive.double

private fun JsonObject.requiredInt(key: String): Int = requireNotNull(this[key]).jsonPrimitive.int

private fun JsonObject.requiredLong(key: String): Long = requireNotNull(this[key]).jsonPrimitive.long

private fun JsonObject.requiredBoolean(key: String): Boolean = requireNotNull(this[key]).jsonPrimitive.boolean

private fun JsonObject.requiredObject(key: String): JsonObject = requireNotNull(this[key]).jsonObject

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

private const val PAPER_RUNTIME_STATE_SCHEMA_VERSION = 1
