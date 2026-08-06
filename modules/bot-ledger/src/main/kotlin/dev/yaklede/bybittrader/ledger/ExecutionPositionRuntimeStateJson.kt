package dev.yaklede.bybittrader.ledger

import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.execution.ExecutionPositionRuntimeState
import dev.yaklede.bybittrader.engine.execution.ExecutionRuntimeMode
import dev.yaklede.bybittrader.ledger.db.ExecutionPositionRuntimeStates
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

internal fun ExecutionPositionRuntimeState.toStatePayload(): String =
    buildJsonObject {
        put("schemaVersion", EXECUTION_POSITION_RUNTIME_STATE_SCHEMA_VERSION)
        put("lastProcessedCandleAt", lastProcessedCandleAt?.toString())
        put("policyState", policyState.toJsonObject())
    }.toString()

internal fun ExecutionPositionRuntimeStates.toExecutionPositionRuntimeState(): ExecutionPositionRuntimeState {
    val payload = Json.parseToJsonElement(state_payload).jsonObject
    require(payload.requiredInt("schemaVersion") == EXECUTION_POSITION_RUNTIME_STATE_SCHEMA_VERSION) {
        "Unsupported execution position runtime state schema."
    }
    return ExecutionPositionRuntimeState(
        mode = ExecutionRuntimeMode.valueOf(mode),
        lifecycleId = lifecycle_id,
        symbol = Symbol(symbol),
        timeframe = Timeframe.valueOf(timeframe),
        lastProcessedCandleAt = payload.nullableString("lastProcessedCandleAt")?.let(Instant::parse),
        policyState = requireNotNull(payload["policyState"]).jsonObject.toCausalPositionState(),
        updatedAt = Instant.parse(updated_at),
    )
}

private fun JsonObject.requiredInt(key: String): Int = requireNotNull(this[key]).jsonPrimitive.int

private fun JsonObject.nullableString(key: String): String? =
    this[key]
        ?.takeUnless { it is JsonNull }
        ?.jsonPrimitive
        ?.content

private const val EXECUTION_POSITION_RUNTIME_STATE_SCHEMA_VERSION = 1
