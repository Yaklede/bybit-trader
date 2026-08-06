package dev.yaklede.bybittrader.app

import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendBootstrap
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendEmaPair
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendEmaState
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendEvaluator
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendExecutionContract
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendIndicatorState
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendParameters
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
import kotlinx.serialization.json.long
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

data class VolumeConfirmedTrendProtocolDefinition(
    val protocolId: String,
    val candidateId: String,
    val protocolSha256: String,
    val symbol: Symbol,
    val parameters: VolumeConfirmedTrendParameters,
    val executionContract: VolumeConfirmedTrendExecutionContract,
    val developmentStartInclusive: Instant,
    val developmentEndExclusive: Instant,
)

data class VolumeConfirmedTrendRuntimeDefinition(
    val protocol: VolumeConfirmedTrendProtocolDefinition,
    val bootstrap: VolumeConfirmedTrendBootstrap,
)

fun loadVolumeConfirmedTrendRuntimeDefinition(
    protocolPath: Path,
    bootstrapPath: Path,
): VolumeConfirmedTrendRuntimeDefinition {
    val protocol = loadVolumeConfirmedTrendProtocolDefinition(protocolPath)
    val bootstrapBytes = Files.readAllBytes(bootstrapPath)
    require(bootstrapBytes.sha256() == FROZEN_TREND_BOOTSTRAP_SHA256) {
        "Trend bootstrap fingerprint is not approved by this runtime."
    }
    val bootstrapRoot = Json.parseToJsonElement(bootstrapBytes.toString(Charsets.UTF_8)).jsonObject
    require(bootstrapRoot.requiredInt("schemaVersion") == TREND_BOOTSTRAP_SCHEMA_VERSION) {
        "Unsupported volume-confirmed trend bootstrap schema."
    }
    require(bootstrapRoot.requiredString("protocolId") == protocol.protocolId) { "Trend bootstrap protocol ID mismatch." }
    require(bootstrapRoot.requiredString("candidateId") == protocol.candidateId) { "Trend bootstrap candidate ID mismatch." }
    require(bootstrapRoot.requiredString("protocolSha256") == protocol.protocolSha256) {
        "Trend bootstrap protocol hash mismatch."
    }
    val source = bootstrapRoot.requiredObject("sourceEvidence")
    val indicator = bootstrapRoot.requiredObject("indicatorState").toIndicatorState()
    val bootstrap =
        VolumeConfirmedTrendBootstrap(
            protocolId = protocol.protocolId,
            candidateId = protocol.candidateId,
            protocolSha256 = protocol.protocolSha256,
            sourceFeatureSha256 = source.requiredString("sourceFeatureSha256"),
            sourceH4BarCount = source.requiredInt("h4BarCount"),
            indicatorState = indicator,
        )
    require(indicator.lastBarOpenedAt == Instant.parse(source.requiredString("lastH4OpenedAt"))) {
        "Trend bootstrap final H4 timestamp mismatch."
    }
    require(bootstrap.sourceH4BarCount.toLong() == indicator.processedBars) {
        "Trend bootstrap processed bar count mismatch."
    }
    require(indicator.recentVolumes.size == protocol.parameters.volumeMedianLookbackBars) {
        "Trend bootstrap volume state does not match the configured lookback."
    }
    val lastBarOpenedAt = requireNotNull(indicator.lastBarOpenedAt)
    require(
        !lastBarOpenedAt.isBefore(protocol.developmentStartInclusive) &&
            !lastBarOpenedAt.plus(Duration.ofHours(4)).isAfter(protocol.developmentEndExclusive),
    ) {
        "Trend bootstrap timestamp is outside the frozen development evidence."
    }
    VolumeConfirmedTrendEvaluator.restore(indicator, protocol.parameters)
    return VolumeConfirmedTrendRuntimeDefinition(protocol = protocol, bootstrap = bootstrap)
}

fun loadVolumeConfirmedTrendProtocolDefinition(path: Path): VolumeConfirmedTrendProtocolDefinition {
    val bytes = Files.readAllBytes(path)
    require(bytes.sha256() == FROZEN_TREND_PROTOCOL_SHA256) {
        "Trend protocol fingerprint is not approved by this runtime."
    }
    val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    require(root.requiredInt("schemaVersion") == 1) { "Unsupported volume-confirmed trend protocol schema." }
    require(!root.requiredBoolean("automaticExecutionAllowed") && !root.requiredBoolean("liveExecutionAllowed")) {
        "The frozen trend research protocol must not allow automatic or live execution."
    }
    val market = root.requiredObject("market")
    require(market.requiredString("decisionTimeframe") == "H4") { "Trend decision timeframe must be H4." }
    require(market.requiredString("sourceTimeframe") == "M15") { "Trend source timeframe must be M15." }
    require(market.requiredInt("requiredSourceBarsPerDecisionBar") == 16) {
        "Trend H4 aggregation must require sixteen M15 bars."
    }
    val strategy = root.requiredObject("strategy")
    require(strategy.requiredBoolean("volumeMedianExcludesDecisionBar")) {
        "Trend volume median must exclude the decision bar."
    }
    require(strategy.requiredBoolean("changeSideOnlyWhenVolumeAtOrAboveMedian")) {
        "Trend side changes must require median volume confirmation."
    }
    require(strategy.requiredBoolean("holdUntilOppositeConfirmed")) {
        "Trend positions must be held until an opposite confirmation."
    }
    val capital = root.requiredObject("capital")
    val costs = root.requiredObject("costs")
    require(costs.requiredBoolean("applyActualFunding")) { "Trend runtime must apply actual funding." }
    val development = root.requiredObject("developmentEvidence")
    return VolumeConfirmedTrendProtocolDefinition(
        protocolId = root.requiredString("protocolId"),
        candidateId = root.requiredString("candidateId"),
        protocolSha256 = bytes.sha256(),
        symbol = Symbol(market.requiredString("symbol")),
        parameters =
            VolumeConfirmedTrendParameters(
                emaVotePairs =
                    strategy.requiredArray("emaVotePairs").map { value ->
                        val pair = value.jsonObject
                        VolumeConfirmedTrendEmaPair(
                            fast = pair.requiredInt("fast"),
                            slow = pair.requiredInt("slow"),
                        )
                    },
                minimumMajorityVotes = strategy.requiredInt("minimumMajorityVotes"),
                volumeMedianLookbackBars = strategy.requiredInt("volumeMedianLookbackBars"),
                executionDelayBars = strategy.requiredInt("executionDelayBars"),
                warmupDecisionBars = market.requiredInt("warmupDecisionBars"),
            ),
        executionContract =
            VolumeConfirmedTrendExecutionContract(
                targetExposureFraction = capital.requiredDouble("targetExposureFraction"),
                maximumRoundedExposureFraction = capital.requiredDouble("maximumRoundedExposureFraction"),
                quantityStepBtc = capital.requiredDouble("quantityStepBtc"),
                minimumQuantityBtc = capital.requiredDouble("minimumQuantityBtc"),
                absoluteMaximumNotionalUsdt = capital.optionalDouble("absoluteMaximumNotionalUsdt"),
                oneWayFeeRate = costs.requiredDouble("oneWayFeeRate"),
                oneWaySlippageRate = costs.requiredDouble("oneWaySlippageRate"),
            ),
        developmentStartInclusive = Instant.parse(development.requiredString("startInclusive")),
        developmentEndExclusive = Instant.parse(development.requiredString("endExclusive")),
    )
}

private fun JsonObject.toIndicatorState(): VolumeConfirmedTrendIndicatorState =
    VolumeConfirmedTrendIndicatorState(
        processedBars = requiredLong("processedBars"),
        lastBarOpenedAt = optionalString("lastBarOpenedAt")?.let(Instant::parse),
        emaStates =
            requiredArray("emaStates").map { value ->
                val state = value.jsonObject
                VolumeConfirmedTrendEmaState(
                    fast = state.optionalDouble("fast"),
                    slow = state.optionalDouble("slow"),
                )
            },
        targetSide = optionalString("targetSide")?.let(Side::valueOf),
        recentVolumes = requiredArray("recentVolumes").map { it.jsonPrimitive.double },
    )

private fun JsonObject.requiredObject(name: String): JsonObject = getValue(name).jsonObject

private fun JsonObject.requiredArray(name: String): JsonArray = getValue(name).jsonArray

private fun JsonObject.requiredString(name: String): String = getValue(name).jsonPrimitive.content

private fun JsonObject.requiredInt(name: String): Int = getValue(name).jsonPrimitive.int

private fun JsonObject.requiredLong(name: String): Long = getValue(name).jsonPrimitive.long

private fun JsonObject.requiredDouble(name: String): Double = getValue(name).jsonPrimitive.double

private fun JsonObject.requiredBoolean(name: String): Boolean = getValue(name).jsonPrimitive.boolean

private fun JsonObject.optionalString(name: String): String? =
    this[name]
        ?.takeUnless { it is JsonNull }
        ?.jsonPrimitive
        ?.content

private fun JsonObject.optionalDouble(name: String): Double? =
    this[name]
        ?.takeUnless { it is JsonNull }
        ?.jsonPrimitive
        ?.double

private fun ByteArray.sha256(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

private const val TREND_BOOTSTRAP_SCHEMA_VERSION = 1
private const val FROZEN_TREND_PROTOCOL_SHA256 = "6cb43d081a9f36e2a89aa723438dacf6da2906fe82e6aeb19efa067aba13fd74"
private const val FROZEN_TREND_BOOTSTRAP_SHA256 = "36d017e2c84ee2119d015f7084acd84e5c6e8f80cd88b1c40c76f8773f806b2d"
