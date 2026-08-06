package dev.yaklede.bybittrader.app.research

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.MathContext
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant

private val MATRIX_JSON = Json { ignoreUnknownKeys = false }
private val MATRIX_PRETTY_JSON = Json { prettyPrint = true }
private val MATRIX_DECIMAL_CONTEXT = MathContext.DECIMAL64

fun main(args: Array<String>) =
    runBlocking {
        val options = parseMakerShadowReplayArgs(args)
        val result = replayMakerShadowMatrix(options)
        val payload = MATRIX_PRETTY_JSON.encodeToString(result)
        writeAtomically(options.outputPath, payload)
        println(payload)
    }

internal suspend fun replayMakerShadowMatrix(options: MakerShadowReplayOptions): MakerShadowReplayMatrixResult {
    val sourceBytes = Files.readAllBytes(options.definitionPath)
    val sourceDefinition =
        MATRIX_JSON.decodeFromString<MakerShadowReplayDefinition>(sourceBytes.toString(StandardCharsets.UTF_8))
    val queueMultipliers = sourceDefinition.evidencePolicy.requiredQueueStressMultipliers.map(String::toDecimal)
    val costMultipliers = sourceDefinition.evidencePolicy.requiredCostStressMultipliers.map(String::toDecimal)
    require(queueMultipliers.isNotEmpty()) { "Maker shadow queue stress multipliers must not be empty." }
    require(costMultipliers.isNotEmpty()) { "Maker shadow cost stress multipliers must not be empty." }
    require(queueMultipliers.all { it >= BigDecimal.ONE }) {
        "Maker shadow queue stress multipliers must be at least one."
    }
    require(costMultipliers.all { it >= BigDecimal.ONE }) {
        "Maker shadow cost stress multipliers must be at least one."
    }
    require(queueMultipliers.distinct().size == queueMultipliers.size) {
        "Maker shadow queue stress multipliers must be unique."
    }
    require(costMultipliers.distinct().size == costMultipliers.size) {
        "Maker shadow cost stress multipliers must be unique."
    }

    val scenarios =
        queueMultipliers.flatMap { queueStressMultiplier ->
            costMultipliers.map { costStressMultiplier ->
                val definition =
                    sourceDefinition.withStress(
                        queueMultiplier = queueStressMultiplier,
                        costMultiplier = costStressMultiplier,
                    )
                val definitionBytes =
                    if (queueStressMultiplier.compareTo(BigDecimal.ONE) == 0 &&
                        costStressMultiplier.compareTo(BigDecimal.ONE) == 0
                    ) {
                        sourceBytes
                    } else {
                        MATRIX_JSON.encodeToString(definition).toByteArray(StandardCharsets.UTF_8)
                    }
                val result =
                    replayMakerShadow(
                        options = options,
                        definitionOverride = definition,
                        definitionBytesOverride = definitionBytes,
                    )
                MakerShadowReplayStressScenario(
                    scenarioId =
                        "queue-${queueStressMultiplier.renderMatrix()}_cost-${costStressMultiplier.renderMatrix()}",
                    queueStressMultiplier = queueStressMultiplier.renderMatrix(),
                    effectiveQueueMultiplier = definition.parameters.queueMultiplier,
                    costStressMultiplier = costStressMultiplier.renderMatrix(),
                    effectiveMakerFeeRate = definition.parameters.makerFeeRate,
                    effectiveTakerFeeRate = definition.parameters.takerFeeRate,
                    effectiveTakerExitSlippageBps = definition.parameters.takerExitSlippageBps,
                    definitionSha256 = result.definitionSha256,
                    replayFingerprint = result.replayFingerprint,
                    sourceSnapshotSha256 = result.sourceSnapshotSha256,
                    metrics = result.metrics,
                    gates = result.gates,
                )
            }
        }
    val baseline =
        requireNotNull(
            scenarios.singleOrNull { scenario ->
                scenario.queueStressMultiplier.toDecimal().compareTo(BigDecimal.ONE) == 0 &&
                    scenario.costStressMultiplier.toDecimal().compareTo(BigDecimal.ONE) == 0
            },
        ) { "Maker shadow stress matrix must include queue=1 and cost=1 baseline." }
    val minimumClosedPositions = sourceDefinition.evidencePolicy.minimumClosedPositions
    val gates =
        MakerShadowReplayMatrixGates(
            sourceEvidenceSufficient =
                baseline.gates.minimumObservedHours &&
                    baseline.gates.zeroGapEvents &&
                    baseline.gates.sealedFilesOnly,
            baselineMinimumClosedPositions = baseline.metrics.closedPositionCount >= minimumClosedPositions,
            baselineNetPositive = baseline.metrics.netPnl.toDecimal() > BigDecimal.ZERO,
            everyScenarioMinimumClosedPositions =
                scenarios.all { scenario -> scenario.metrics.closedPositionCount >= minimumClosedPositions },
            everyScenarioClosedInventory = scenarios.all { scenario -> scenario.metrics.inventoryQuantity.toDecimal() == BigDecimal.ZERO },
            everyScenarioNetPositive = scenarios.all { scenario -> scenario.metrics.netPnl.toDecimal() > BigDecimal.ZERO },
            queueStressComplete =
                scenarios.map(MakerShadowReplayStressScenario::queueStressMultiplier).toSet().size == queueMultipliers.size,
            costStressComplete =
                scenarios.map(MakerShadowReplayStressScenario::costStressMultiplier).toSet().size == costMultipliers.size,
        )
    val status =
        when {
            !gates.baselineNetPositive -> "REJECTED_DEVELOPMENT_STRESS"
            !gates.sourceEvidenceSufficient || !gates.baselineMinimumClosedPositions ->
                "DEVELOPMENT_INSUFFICIENT_EVIDENCE"
            gates.allPassed -> "DEVELOPMENT_STRESS_COMPLETE_FORWARD_VALIDATION_REQUIRED"
            else -> "REJECTED_DEVELOPMENT_STRESS"
        }
    val sourceDefinitionSha256 = sourceBytes.sha256Matrix()
    val sourceSnapshots = scenarios.map(MakerShadowReplayStressScenario::sourceSnapshotSha256).distinct()
    require(sourceSnapshots.size == 1) { "Maker shadow stress scenarios did not use one source snapshot." }
    val sourceSnapshotSha256 = sourceSnapshots.single()
    return MakerShadowReplayMatrixResult(
        schemaVersion = 1,
        generatedAt = Instant.now().toString(),
        experimentId = sourceDefinition.experimentId,
        candidateId = sourceDefinition.candidateId,
        researchStage = sourceDefinition.researchStage,
        status = status,
        automaticExecutionAllowed = false,
        sourceDefinitionSha256 = sourceDefinitionSha256,
        sourceSnapshotSha256 = sourceSnapshotSha256,
        matrixFingerprint =
            buildString {
                append(sourceDefinitionSha256)
                append('|')
                append(sourceSnapshotSha256)
                scenarios.forEach { scenario ->
                    append('|')
                    append(scenario.scenarioId)
                    append('|')
                    append(scenario.replayFingerprint)
                }
            }.sha256Matrix(),
        scenarioCount = scenarios.size,
        scenarios = scenarios,
        gates = gates,
    )
}

private fun MakerShadowReplayDefinition.withStress(
    queueMultiplier: BigDecimal,
    costMultiplier: BigDecimal,
): MakerShadowReplayDefinition =
    copy(
        parameters =
            parameters.copy(
                queueMultiplier =
                    parameters.queueMultiplier
                        .toDecimal()
                        .multiply(queueMultiplier)
                        .renderMatrix(),
                makerFeeRate =
                    parameters.makerFeeRate
                        .toDecimal()
                        .stressFee(costMultiplier)
                        .renderMatrix(),
                takerFeeRate =
                    parameters.takerFeeRate
                        .toDecimal()
                        .multiply(costMultiplier)
                        .renderMatrix(),
                takerExitSlippageBps =
                    parameters.takerExitSlippageBps
                        .toDecimal()
                        .multiply(costMultiplier)
                        .renderMatrix(),
            ),
    )

private fun BigDecimal.stressFee(multiplier: BigDecimal): BigDecimal =
    if (this >= BigDecimal.ZERO) multiply(multiplier) else divide(multiplier, MATRIX_DECIMAL_CONTEXT)

@Serializable
internal data class MakerShadowReplayMatrixResult(
    val schemaVersion: Int,
    val generatedAt: String,
    val experimentId: String,
    val candidateId: String,
    val researchStage: String,
    val status: String,
    val automaticExecutionAllowed: Boolean,
    val sourceDefinitionSha256: String,
    val sourceSnapshotSha256: String,
    val matrixFingerprint: String,
    val scenarioCount: Int,
    val scenarios: List<MakerShadowReplayStressScenario>,
    val gates: MakerShadowReplayMatrixGates,
)

@Serializable
internal data class MakerShadowReplayStressScenario(
    val scenarioId: String,
    val queueStressMultiplier: String,
    val effectiveQueueMultiplier: String,
    val costStressMultiplier: String,
    val effectiveMakerFeeRate: String,
    val effectiveTakerFeeRate: String,
    val effectiveTakerExitSlippageBps: String,
    val definitionSha256: String,
    val replayFingerprint: String,
    val sourceSnapshotSha256: String,
    val metrics: MakerShadowReplayMetrics,
    val gates: MakerShadowReplayGates,
)

@Serializable
internal data class MakerShadowReplayMatrixGates(
    val sourceEvidenceSufficient: Boolean,
    val baselineMinimumClosedPositions: Boolean,
    val baselineNetPositive: Boolean,
    val everyScenarioMinimumClosedPositions: Boolean,
    val everyScenarioClosedInventory: Boolean,
    val everyScenarioNetPositive: Boolean,
    val queueStressComplete: Boolean,
    val costStressComplete: Boolean,
) {
    val allPassed: Boolean
        get() =
            sourceEvidenceSufficient &&
                baselineMinimumClosedPositions &&
                baselineNetPositive &&
                everyScenarioMinimumClosedPositions &&
                everyScenarioClosedInventory &&
                everyScenarioNetPositive &&
                queueStressComplete &&
                costStressComplete
}

private fun String.toDecimal(): BigDecimal = BigDecimal(this)

private fun BigDecimal.renderMatrix(): String = stripTrailingZeros().toPlainString()

private fun ByteArray.sha256Matrix(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

private fun String.sha256Matrix(): String = toByteArray(StandardCharsets.UTF_8).sha256Matrix()
