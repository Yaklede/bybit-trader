package dev.yaklede.bybittrader.app

import dev.yaklede.bybittrader.engine.backtest.VolumeFlowAggressiveExecutionContract
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowAggressiveProfiles
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

class AggressiveRuntimeReplayContractParityTest :
    StringSpec({
        "frozen replay contract matches the Kotlin runtime profile" {
            val root =
                Json
                    .parseToJsonElement(Files.readString(findRepositoryFile("config/aggressive-runtime-replay-contract-v2.json")))
                    .jsonObject
            val runtime = root.getValue("runtimeProfile").jsonObject
            val frozenContract =
                VolumeFlowAggressiveExecutionContract(
                    riskFraction = runtime.requiredDouble("riskFraction"),
                    feeRate = runtime.requiredDouble("feeRate"),
                    entrySlippageRate = runtime.requiredDouble("slippageRate"),
                    exitSlippageRate = runtime.requiredDouble("exitSlippageRate"),
                    fundingRatePer8h = runtime.optionalDouble("fundingRatePer8h") ?: 0.0,
                    quantityStep = runtime.optionalDouble("quantityStep"),
                    minQuantity = runtime.optionalDouble("minQuantity"),
                    maxQuantity = runtime.optionalDouble("maxQuantity"),
                    maxNotional = runtime.optionalDouble("maxNotional"),
                    leverage = runtime.optionalDouble("leverage"),
                    liquidationBufferPct = runtime.requiredDouble("liquidationBufferPct"),
                    minimumNetRiskReward = runtime.requiredDouble("minimumNetRiskReward"),
                )
            val currentProfile = VolumeFlowAggressiveProfiles.current()

            root.getValue("contractVersion").jsonPrimitive.content shouldBe currentProfile.contractVersion
            runtime.getValue("profileId").jsonPrimitive.content shouldBe currentProfile.profileId
            frozenContract shouldBe currentProfile.executionContract
            frozenContract.fingerprint shouldBe "cb6391046012c35c0cf605b2c19a8b629c31266d46360fe4a259549de08eea5c"
        }
    })

private fun JsonObject.requiredDouble(name: String): Double = getValue(name).jsonPrimitive.double

private fun JsonObject.optionalDouble(name: String): Double? =
    this[name]?.let { value -> if (value is JsonNull) null else value.jsonPrimitive.double }

private fun findRepositoryFile(relativePath: String): Path =
    generateSequence(Path.of("").toAbsolutePath().normalize()) { current -> current.parent }
        .map { root -> root.resolve(relativePath) }
        .firstOrNull(Files::isRegularFile)
        ?: error("Could not locate repository file: $relativePath")
