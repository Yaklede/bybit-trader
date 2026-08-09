package dev.yaklede.bybittrader.app

import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendForwardPolicy
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendHistoricalEvidence
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveRiskPolicy
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
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

data class VolumeConfirmedTrendApprovalDefinition(
    val historicalEvidence: VolumeConfirmedTrendHistoricalEvidence,
    val forwardPolicy: VolumeConfirmedTrendForwardPolicy,
    val liveRiskPolicy: VolumeConfirmedTrendLiveRiskPolicy,
)

private data class LiveRiskPolicyParityEvidence(
    val passed: Boolean,
    val policy: VolumeConfirmedTrendLiveRiskPolicy,
)

fun loadVolumeConfirmedTrendApprovalDefinition(
    protocolPath: Path,
    externalResultPath: Path = protocolPath.resolveSibling("volume-confirmed-trend-ensemble-v1-external-result.json"),
    kotlinParityResultPath: Path = protocolPath.resolveSibling("volume-confirmed-trend-ensemble-v1-kotlin-parity-result.json"),
    runtimeParityResultPath: Path = protocolPath.resolveSibling("volume-confirmed-trend-ensemble-v1-runtime-parity-result.json"),
    liveRiskParityResultPath: Path =
        protocolPath.resolveSibling("volume-confirmed-trend-ensemble-v1-live-risk-parity-result.json"),
    forwardPolicyPath: Path = protocolPath.resolveSibling("volume-confirmed-trend-ensemble-v1-forward-policy.json"),
): VolumeConfirmedTrendApprovalDefinition {
    val protocol = loadVolumeConfirmedTrendProtocolDefinition(protocolPath)
    val protocolRoot = Json.parseToJsonElement(Files.readString(protocolPath)).jsonObject
    val approvalGates = protocolRoot.requiredApprovalObject("approvalGates")
    val external = externalResultPath.readFrozenJson(FROZEN_TREND_EXTERNAL_RESULT_SHA256, "external result")
    validateExternalEvidence(external, protocol, approvalGates)
    val kotlinParity = kotlinParityResultPath.readFrozenJson(FROZEN_TREND_KOTLIN_PARITY_RESULT_SHA256, "Kotlin parity result")
    validateKotlinParity(kotlinParity, protocol, external)
    val runtimeParity = runtimeParityResultPath.readFrozenJson(FROZEN_TREND_RUNTIME_PARITY_RESULT_SHA256, "runtime parity result")
    validateRuntimeParity(runtimeParity, protocol, externalResultPath)
    val liveRiskParity =
        liveRiskParityResultPath.readFrozenJson(
            FROZEN_TREND_LIVE_RISK_PARITY_RESULT_SHA256,
            "live risk parity result",
        )
    val liveRiskPolicyParity =
        validateLiveRiskPolicyParity(
            root = liveRiskParity,
            protocol = protocol,
            external = external,
            externalResultPath = externalResultPath,
        )
    val forwardPolicy = forwardPolicyPath.readFrozenJson(FROZEN_TREND_FORWARD_POLICY_SHA256, "forward policy")
    val policy = validateAndMapForwardPolicy(forwardPolicy, protocol, forwardPolicyPath)
    require(
        liveRiskPolicyParity.policy.maximumAccountDrawdownFraction.compareTo(
            BigDecimal.valueOf(policy.maximumDrawdownPct).movePointLeft(2),
        ) == 0,
    ) {
        "Frozen trend Live risk policy drawdown does not match the forward approval policy."
    }
    return VolumeConfirmedTrendApprovalDefinition(
        historicalEvidence =
            VolumeConfirmedTrendHistoricalEvidence(
                protocolId = protocol.protocolId,
                candidateId = protocol.candidateId,
                protocolSha256 = protocol.protocolSha256,
                externalResultSha256 = externalResultPath.sha256(),
                kotlinCoreParityResultSha256 = kotlinParityResultPath.sha256(),
                runtimeReplayParityResultSha256 = runtimeParityResultPath.sha256(),
                liveRiskPolicyParityResultSha256 = liveRiskParityResultPath.sha256(),
                externalVenuePassed = true,
                kotlinCoreParityPassed = true,
                runtimeReplayParityPassed = true,
                liveRiskPolicyParityPassed = liveRiskPolicyParity.passed,
            ),
        forwardPolicy = policy,
        liveRiskPolicy = liveRiskPolicyParity.policy,
    )
}

private fun validateLiveRiskPolicyParity(
    root: JsonObject,
    protocol: VolumeConfirmedTrendProtocolDefinition,
    external: JsonObject,
    externalResultPath: Path,
): LiveRiskPolicyParityEvidence {
    require(root.requiredApprovalInt("schemaVersion") == 2) { "Unsupported frozen trend live risk parity schema." }
    val artifactProtocol = root.requiredApprovalObject("protocol")
    require(artifactProtocol.requiredApprovalString("id") == protocol.protocolId)
    require(artifactProtocol.requiredApprovalString("candidateId") == protocol.candidateId)
    require(artifactProtocol.requiredApprovalString("sha256") == protocol.protocolSha256)
    val source = root.requiredApprovalObject("sourceEvidence")
    require(source.requiredApprovalString("externalResultSha256") == externalResultPath.sha256())
    require(
        source.requiredApprovalString("databaseSha256") ==
            external.requiredApprovalObject("acquisitionEvidence").requiredApprovalString("databaseSha256"),
    )
    val baseline = root.requiredApprovalObject("canonicalBaseline")
    val externalMetrics = external.requiredApprovalObject("canonicalMetrics")
    val externalBaseline = externalMetrics.requiredApprovalObject("baseline")
    require(baseline.requiredApprovalDouble("startingEquityUsdt") == externalMetrics.requiredApprovalDouble("startingEquityUsdt"))
    require(baseline.requiredApprovalDouble("endingEquityUsdt") == externalBaseline.requiredApprovalDouble("endingEquityUsdt"))
    require(baseline.requiredApprovalDouble("netReturnPct") == externalBaseline.requiredApprovalDouble("netReturnPct"))
    require(baseline.requiredApprovalInt("closedTradeCount") == externalBaseline.requiredApprovalInt("closedTradeCount"))
    val risk = root.requiredApprovalObject("runtimeRiskPolicy")
    val policy =
        VolumeConfirmedTrendLiveRiskPolicy(
            maximumDailyLossFraction = risk.requiredNullableApprovalDecimal("maximumDailyLossFraction"),
            maximumAccountDrawdownFraction = risk.requiredApprovalDecimal("maximumAccountDrawdownFraction"),
            maximumConsecutiveLosses = risk.requiredNullableApprovalInt("maximumConsecutiveLosses"),
            riskStateMaximumAge = Duration.ofSeconds(risk.requiredApprovalInt("riskStateMaximumAgeSeconds").toLong()),
            walletReconciliationMaximumAge =
                Duration.ofSeconds(risk.requiredApprovalInt("walletReconciliationMaximumAgeSeconds").toLong()),
            walletReconciliationConfirmedMismatchCount =
                risk.requiredApprovalInt("walletReconciliationConfirmedMismatchCount"),
        )
    require(policy.maximumDailyLossFraction == null) { "Frozen trend Live daily loss limit must be disabled." }
    require(policy.maximumConsecutiveLosses == null) {
        "Frozen trend Live consecutive loss limit must be disabled."
    }
    val audit = root.requiredApprovalObject("audit")
    require(audit.requiredApprovalString("projectionKind") == "FROZEN_CLOSURE_PATH_COUNTERFACTUAL")
    require(!audit.requiredApprovalBoolean("livePathSimulation"))
    require(audit.requiredApprovalString("dailyLossMeasurementKind") == "DISABLED")
    require(audit.requiredApprovalInt("baselineTradeCount") == baseline.requiredApprovalInt("closedTradeCount"))
    require(
        audit.requiredApprovalDouble("maximumObservedAccountDrawdownPct") <=
            policy.maximumAccountDrawdownFraction.movePointRight(2).toDouble(),
    )
    audit.requireApprovalNull("firstDailyLossBreach")
    audit.requireApprovalNull("firstAccountDrawdownBreach")
    audit.requireApprovalNull("firstConsecutiveLossBreach")
    val frozenPathReproducible = audit.requiredApprovalBoolean("frozenPathReproducible")
    val decision = root.requiredApprovalObject("decision")
    val parityPassed = decision.requiredApprovalBoolean("riskPolicyParityPassed")
    require(parityPassed == frozenPathReproducible)
    require(root.requiredApprovalString("status") == if (parityPassed) "PASS" else "FAIL")
    require(!decision.requiredApprovalBoolean("automaticExecutionAllowed"))
    require(!decision.requiredApprovalBoolean("liveExecutionAllowed"))
    val auditReasons = audit.requiredApprovalArray("reasonCodes").map { value -> value.jsonPrimitive.content }
    val decisionReasons = decision.requiredApprovalArray("reasonCodes").map { value -> value.jsonPrimitive.content }
    require(auditReasons == decisionReasons)
    require(parityPassed || auditReasons.isNotEmpty())
    validateLiveRiskPolicyReplay(
        replay = root.requiredApprovalObject("policyReplay"),
        baseline = baseline,
        audit = audit,
        policy = policy,
    )
    return LiveRiskPolicyParityEvidence(parityPassed, policy)
}

private fun validateLiveRiskPolicyReplay(
    replay: JsonObject,
    baseline: JsonObject,
    audit: JsonObject,
    policy: VolumeConfirmedTrendLiveRiskPolicy,
) {
    require(replay.requiredApprovalString("simulationKind") == "H4_DECISION_BOUNDARY_RISK_POLICY_REPLAY")
    require(!replay.requiredApprovalBoolean("livePathSimulation"))
    val crossRuntime = replay.requiredApprovalObject("crossRuntimeParity")
    require(crossRuntime.requiredApprovalString("status") == "PASS")
    require(crossRuntime.requiredApprovalString("nodeResultSha256").isSha256())
    require(crossRuntime.requiredApprovalString("kotlinResultSha256").isSha256())
    require(crossRuntime.requiredApprovalDouble("numericTolerance") == 1e-8)
    val commandCount = crossRuntime.requiredApprovalInt("commandCount")
    val runCount = crossRuntime.requiredApprovalInt("runCount")
    val tradeCount = crossRuntime.requiredApprovalInt("tradeCount")
    require(commandCount > 0 && runCount > 0 && tradeCount > 0)

    val canonical = replay.requiredApprovalObject("canonical")
    require(canonical.requiredApprovalDouble("startingEquityUsdt") == baseline.requiredApprovalDouble("startingEquityUsdt"))
    require(canonical.requiredApprovalDouble("costMultiplier") == 1.0)
    require(canonical.requiredApprovalDouble("endingEquityUsdt") == baseline.requiredApprovalDouble("endingEquityUsdt"))
    require(canonical.requiredApprovalDouble("netReturnPct") == baseline.requiredApprovalDouble("netReturnPct"))
    require(
        canonical.requiredApprovalDouble("maximumConservativeIntrabarDrawdownPct") <=
            policy.maximumAccountDrawdownFraction.movePointRight(2).toDouble(),
    )
    val canonicalClosedTrades = canonical.requiredApprovalInt("closedTradeCount")
    val canonicalBlockedEntries = canonical.requiredApprovalInt("blockedEntryCount")
    require(canonicalClosedTrades + canonicalBlockedEntries == commandCount)
    require(canonicalClosedTrades == baseline.requiredApprovalInt("closedTradeCount"))
    require(canonicalBlockedEntries == 0)
    val blockedReasons = canonical.requiredApprovalObject("blockedEntryReasonCounts")
    require(blockedReasons.isEmpty())
    canonical.requireApprovalNull("firstBlockedEntry")
    require(canonical.requiredApprovalInt("maximumObservedConsecutiveLosses") >= 0)
    require(
        canonical.requiredApprovalInt("finalConsecutiveLosses") <=
            canonical.requiredApprovalInt("maximumObservedConsecutiveLosses"),
    )
    require(
        audit.requiredApprovalDouble("maximumObservedAccountDrawdownPct") ==
            canonical.requiredApprovalDouble("maximumConservativeIntrabarDrawdownPct"),
    )
    require(
        audit.requiredApprovalInt("maximumObservedConsecutiveLosses") ==
            canonical.requiredApprovalInt("maximumObservedConsecutiveLosses"),
    )

    val stressMatrix = replay.requiredApprovalArray("stressMatrix").map { value -> value.jsonObject }
    require(stressMatrix.size == runCount)
    require(stressMatrix.sumOf { run -> run.requiredApprovalInt("closedTradeCount") } == tradeCount)
    require(tradeCount == commandCount * runCount)
    stressMatrix.forEach { run ->
        require(
            run.requiredApprovalInt("closedTradeCount") == commandCount &&
                run.requiredApprovalInt("blockedEntryCount") == 0 &&
                run.requiredApprovalObject("blockedEntryReasonCounts").isEmpty() &&
                run.requiredApprovalDouble("endingEquityUsdt") > run.requiredApprovalDouble("startingEquityUsdt") &&
                run.requiredApprovalDouble("maximumConservativeIntrabarDrawdownPct") <=
                policy.maximumAccountDrawdownFraction.movePointRight(2).toDouble(),
        )
        run.requireApprovalNull("firstBlockedEntry")
    }
}

private fun validateExternalEvidence(
    root: JsonObject,
    protocol: VolumeConfirmedTrendProtocolDefinition,
    gates: JsonObject,
) {
    require(root.requiredApprovalInt("schemaVersion") == 1) { "Unsupported frozen trend external result schema." }
    require(root.requiredApprovalString("programStatus") == "HISTORICALLY_VALIDATED_SHADOW_REQUIRED") {
        "Frozen trend external evidence has not reached its required status."
    }
    require(root.requiredApprovalObject("protocol").requiredApprovalString("sha256") == protocol.protocolSha256) {
        "Frozen trend external evidence protocol fingerprint mismatch."
    }
    val baseline = root.requiredApprovalObject("canonicalMetrics").requiredApprovalObject("baseline")
    val doubleCost = root.requiredApprovalObject("canonicalMetrics").requiredApprovalObject("doubleCost")
    require(baseline.requiredApprovalDouble("cagrPct") >= gates.requiredApprovalDouble("minimumBaselineCagrPct"))
    require(doubleCost.requiredApprovalDouble("cagrPct") >= gates.requiredApprovalDouble("minimumDoubleCostCagrPct"))
    require(
        baseline.requiredApprovalDouble("maximumConservativeIntrabarDrawdownPct") <=
            gates.requiredApprovalDouble("maximumBaselineDrawdownPct"),
    )
    require(
        doubleCost.requiredApprovalDouble("maximumConservativeIntrabarDrawdownPct") <=
            gates.requiredApprovalDouble("maximumDoubleCostDrawdownPct"),
    )
    require(
        baseline.requiredApprovalDouble("positiveCompleteYearFraction") >=
            gates.requiredApprovalDouble("minimumPositiveCompleteYearFraction"),
    )
    require(
        baseline.requiredApprovalDouble("annualizedSideChangeCount") <=
            gates.requiredApprovalDouble("maximumDirectionChangesPerYear"),
    )
    require(
        baseline.requiredApprovalDouble("positiveRandomWindowFraction") >=
            gates.requiredApprovalDouble("minimumPositiveRandomWindowFraction"),
    )
    require(
        baseline.requiredApprovalDouble("positiveRollingTwelveMonthFraction") >=
            gates.requiredApprovalDouble("minimumPositiveRollingTwelveMonthFraction"),
    )
    require(
        doubleCost.requiredApprovalDouble("positiveRollingTwelveMonthFraction") >=
            gates.requiredApprovalDouble("minimumPositiveRollingTwelveMonthFraction"),
    )
    require(
        baseline.requiredApprovalDouble("worstRollingTwelveMonthReturnPct") >=
            gates.requiredApprovalDouble("minimumWorstRollingTwelveMonthReturnPct"),
    )
    require(
        doubleCost.requiredApprovalDouble("worstRollingTwelveMonthReturnPct") >=
            gates.requiredApprovalDouble("minimumWorstRollingTwelveMonthReturnPct"),
    )
    require(baseline.requiredApprovalInt("liquidationCount") == 0 && doubleCost.requiredApprovalInt("liquidationCount") == 0)
    root.requiredApprovalArray("startingCapitalStress").forEach { value ->
        val stress = value.jsonObject
        val starting = stress.requiredApprovalDouble("startingEquityUsdt")
        require(stress.requiredApprovalDouble("baselineEndingEquityUsdt") > starting)
        require(stress.requiredApprovalDouble("doubleCostEndingEquityUsdt") > starting)
    }
    val externalGate = root.requiredApprovalObject("externalGate")
    require(externalGate.requiredApprovalBoolean("passed"))
    require(externalGate.requiredApprovalArray("failedChecks").isEmpty())
    require(!externalGate.requiredApprovalBoolean("parametersChangedAfterExternalRead"))
    require(!root.requiredApprovalBoolean("automaticExecutionAllowed") && !root.requiredApprovalBoolean("liveExecutionAllowed"))
}

private fun validateKotlinParity(
    root: JsonObject,
    protocol: VolumeConfirmedTrendProtocolDefinition,
    external: JsonObject,
) {
    require(root.requiredApprovalInt("schemaVersion") == 1)
    require(root.requiredApprovalString("status") == "KOTLIN_CORE_PARITY_PASS_PAPER_RUNTIME_REQUIRED")
    require(root.requiredApprovalString("protocolSha256") == protocol.protocolSha256)
    val comparison = root.requiredApprovalObject("comparison")
    val acquisition = external.requiredApprovalObject("acquisitionEvidence")
    require(comparison.requiredApprovalBoolean("passed"))
    require(comparison.requiredApprovalInt("h4BarCount") == acquisition.requiredApprovalInt("h4BarCount"))
    require(comparison.requiredApprovalInt("fundingRateCount") == acquisition.requiredApprovalInt("fundingRateCount"))
    require(comparison.requiredApprovalDouble("numericTolerance") <= 0.00000001)
    require(!root.requiredApprovalBoolean("automaticExecutionAllowed") && !root.requiredApprovalBoolean("liveExecutionAllowed"))
}

private fun validateRuntimeParity(
    root: JsonObject,
    protocol: VolumeConfirmedTrendProtocolDefinition,
    externalResultPath: Path,
) {
    require(root.requiredApprovalInt("schemaVersion") == 1)
    require(root.requiredApprovalString("status") == "PASS")
    require(root.requiredApprovalObject("protocol").requiredApprovalString("sha256") == protocol.protocolSha256)
    require(root.requiredApprovalObject("externalResult").requiredApprovalString("sha256") == externalResultPath.sha256())
    val sourceResult = root.requiredApprovalObject("sourceResult")
    require(sourceResult.requiredApprovalString("sha256") == sourceResult.requiredApprovalString("deterministicRepeatSha256"))
    val comparison = root.requiredApprovalObject("comparison")
    require(comparison.requiredApprovalBoolean("passed"))
    require(comparison.requiredApprovalInt("mismatchCount") == 0)
    require(comparison.requiredApprovalDouble("maximumNumericDifference") <= comparison.requiredApprovalDouble("numericTolerance"))
    require(comparison.requiredApprovalInt("expectedTransitionCount") == comparison.requiredApprovalInt("actualTransitionCount"))
    require(comparison.requiredApprovalInt("expectedClosedTradeCount") == comparison.requiredApprovalInt("actualClosedTradeCount"))
    require(root.requiredApprovalObject("decision").requiredApprovalBoolean("paperRuntimeReplayParity"))
    require(!root.requiredApprovalBoolean("automaticExecutionAllowed") && !root.requiredApprovalBoolean("liveExecutionAllowed"))
}

private fun validateAndMapForwardPolicy(
    root: JsonObject,
    protocol: VolumeConfirmedTrendProtocolDefinition,
    policyPath: Path,
): VolumeConfirmedTrendForwardPolicy {
    require(root.requiredApprovalInt("schemaVersion") == 1)
    require(root.requiredApprovalString("status") == "FROZEN_BEFORE_FRESH_BYBIT_OBSERVATION")
    require(root.requiredApprovalObject("protocol").requiredApprovalString("sha256") == protocol.protocolSha256)
    val trials = root.requiredApprovalObject("trialAccounting")
    require(!trials.requiredApprovalBoolean("freshBybitForwardRead"))
    require(!trials.requiredApprovalBoolean("criteriaMayChangeAfterObservationStarts"))
    val decision = root.requiredApprovalObject("decision")
    require(decision.requiredApprovalString("passingStatus") == "READY_FOR_HUMAN_REVIEW")
    require(!decision.requiredApprovalBoolean("automaticLivePromotionAllowed"))
    require(decision.requiredApprovalBoolean("humanApprovalRequired"))
    require(!decision.requiredApprovalBoolean("liveExecutionAllowed"))
    val requirements = root.requiredApprovalObject("requirements")
    require(requirements.requiredApprovalBoolean("requireRuntimeReplayParity"))
    require(requirements.requiredApprovalBoolean("requireContinuousCurrentSession"))
    return VolumeConfirmedTrendForwardPolicy(
        policyId = root.requiredApprovalString("policyId"),
        policySha256 = policyPath.sha256(),
        minimumCalendarDays = requirements.requiredApprovalInt("minimumCalendarDays"),
        minimumClosedTrades = requirements.requiredApprovalInt("minimumClosedTrades"),
        minimumExecutedTransitions = requirements.requiredApprovalInt("minimumExecutedTransitions"),
        minimumSessionReturnPct = requirements.requiredApprovalDouble("minimumSessionReturnPct"),
        minimumClosedTradeProfitFactor = requirements.requiredApprovalDouble("minimumClosedTradeProfitFactor"),
        maximumDrawdownPct = requirements.requiredApprovalDouble("maximumDrawdownPct"),
        maximumEntryExposureFraction = requirements.requiredApprovalDouble("maximumEntryExposureFraction"),
        maximumAdverseExposureFraction = requirements.requiredApprovalDouble("maximumAdverseExposureFraction"),
        maximumLiquidationCount = requirements.requiredApprovalInt("maximumLiquidationCount"),
        maximumObservationStaleness =
            Duration.ofMinutes(requirements.requiredApprovalInt("maximumObservationStalenessMinutes").toLong()),
    )
}

private fun Path.readFrozenJson(
    expectedSha256: String,
    label: String,
): JsonObject {
    val bytes = Files.readAllBytes(this)
    require(bytes.sha256() == expectedSha256) { "Frozen trend $label fingerprint mismatch." }
    return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
}

private fun JsonObject.requiredApprovalObject(name: String): JsonObject = getValue(name).jsonObject

private fun JsonObject.requiredApprovalArray(name: String): JsonArray = getValue(name).jsonArray

private fun JsonObject.requiredApprovalString(name: String): String = getValue(name).jsonPrimitive.content

private fun JsonObject.requiredApprovalInt(name: String): Int = getValue(name).jsonPrimitive.int

private fun JsonObject.requiredApprovalDouble(name: String): Double = getValue(name).jsonPrimitive.double

private fun JsonObject.requiredApprovalDecimal(name: String): BigDecimal = getValue(name).jsonPrimitive.content.toBigDecimal()

private fun JsonObject.requiredNullableApprovalDecimal(name: String): BigDecimal? =
    getValue(name).let { value -> if (value is JsonNull) null else value.jsonPrimitive.content.toBigDecimal() }

private fun JsonObject.requiredNullableApprovalInt(name: String): Int? =
    getValue(name).let { value -> if (value is JsonNull) null else value.jsonPrimitive.int }

private fun JsonObject.requireApprovalNull(name: String) {
    require(getValue(name) is JsonNull) { "Frozen trend $name must be null." }
}

private fun JsonObject.requiredApprovalBoolean(name: String): Boolean = getValue(name).jsonPrimitive.boolean

private fun Path.sha256(): String = Files.readAllBytes(this).sha256()

private fun ByteArray.sha256(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

private fun String.isSha256(): Boolean = matches(Regex("[0-9a-f]{64}"))

private const val FROZEN_TREND_EXTERNAL_RESULT_SHA256 = "1a4a49029e7a24020e21fb90f23490dddeb7c27a98f02a20438fd28bf9cc2cd1"
private const val FROZEN_TREND_KOTLIN_PARITY_RESULT_SHA256 = "5174139b607139cc664fad861bcea313cc9408dd454e7e44b739d7057c1bdf8f"
private const val FROZEN_TREND_RUNTIME_PARITY_RESULT_SHA256 = "8421a3df1bd06f19eebcc0d0dd183faf6f74fbf54a2b28b66bf55f5f0c637a70"
private const val FROZEN_TREND_LIVE_RISK_PARITY_RESULT_SHA256 =
    "f5ce32043a0017199fa156b6ab6bc13e8a1a3a115c087b867677bc2fad592887"
private const val FROZEN_TREND_FORWARD_POLICY_SHA256 = "5ea2185fe9f4299f656ca89848a5ffd77acb578954517cd22432e5b4d64dc62b"
