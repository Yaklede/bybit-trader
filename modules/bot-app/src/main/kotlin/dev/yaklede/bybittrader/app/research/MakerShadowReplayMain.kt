package dev.yaklede.bybittrader.app.research

import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketDataQuality
import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketEventKind
import dev.yaklede.bybittrader.engine.market.maker.MAKER_SHADOW_ENGINE_VERSION
import dev.yaklede.bybittrader.engine.market.maker.MakerShadowConfig
import dev.yaklede.bybittrader.engine.market.maker.MakerShadowEngine
import dev.yaklede.bybittrader.engine.market.maker.MakerShadowLedger
import dev.yaklede.bybittrader.engine.market.maker.MakerShadowLedgerEvent
import dev.yaklede.bybittrader.engine.market.maker.MakerShadowLedgerEventType
import dev.yaklede.bybittrader.engine.market.maker.MakerShadowSnapshot
import dev.yaklede.bybittrader.exchange.bybit.BybitPublicMarketCaptureParser
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.math.MathContext
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.zip.GZIPInputStream

private val JSON = Json { ignoreUnknownKeys = false }
private val PRETTY_JSON = Json { prettyPrint = true }
private val DECIMAL_CONTEXT = MathContext.DECIMAL64

fun main(args: Array<String>) =
    runBlocking {
        val options = parseMakerShadowReplayArgs(args)
        val result = replayMakerShadow(options)
        val payload = PRETTY_JSON.encodeToString(result)
        writeAtomically(options.outputPath, payload)
        println(payload)
    }

internal data class MakerShadowReplayOptions(
    val definitionPath: Path,
    val inputRoot: Path,
    val outputPath: Path,
)

internal fun parseMakerShadowReplayArgs(args: Array<String>): MakerShadowReplayOptions {
    val values = mutableMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val argument = args[index]
        require(argument.startsWith("--")) { "Unexpected argument: $argument" }
        require(index + 1 < args.size) { "Missing value for argument: $argument" }
        values[argument.removePrefix("--")] = args[index + 1]
        index += 2
    }
    return MakerShadowReplayOptions(
        definitionPath = Path.of(values.required("definition")).toAbsolutePath().normalize(),
        inputRoot = Path.of(values.required("input")).toAbsolutePath().normalize(),
        outputPath = Path.of(values.required("out")).toAbsolutePath().normalize(),
    )
}

internal suspend fun replayMakerShadow(options: MakerShadowReplayOptions): MakerShadowReplayResult =
    replayMakerShadow(
        options = options,
        definitionOverride = null,
        definitionBytesOverride = null,
    )

internal suspend fun replayMakerShadow(
    options: MakerShadowReplayOptions,
    definitionOverride: MakerShadowReplayDefinition?,
    definitionBytesOverride: ByteArray?,
): MakerShadowReplayResult {
    require(Files.isRegularFile(options.definitionPath)) {
        "Maker shadow replay definition does not exist: ${options.definitionPath}"
    }
    require(Files.isDirectory(options.inputRoot)) {
        "Maker shadow replay input directory does not exist: ${options.inputRoot}"
    }
    require((definitionOverride == null) == (definitionBytesOverride == null)) {
        "Maker shadow replay definition and bytes must be overridden together."
    }
    val definitionBytes = definitionBytesOverride ?: Files.readAllBytes(options.definitionPath)
    val definition =
        definitionOverride
            ?: JSON.decodeFromString<MakerShadowReplayDefinition>(definitionBytes.toString(StandardCharsets.UTF_8))
    require(definition.schemaVersion == 1) { "Unsupported maker shadow replay schema: ${definition.schemaVersion}" }
    require(!definition.automaticExecutionAllowed) { "Maker shadow replay definition cannot enable automatic execution." }
    require(definition.evidencePolicy.source == "FORWARD_RAW_ARCHIVE") {
        "Maker shadow replay requires FORWARD_RAW_ARCHIVE evidence."
    }
    require(!definition.evidencePolicy.allowPartFiles) { "Maker shadow replay cannot accept active part files." }

    val sealedFiles =
        Files
            .walk(options.inputRoot)
            .use { paths ->
                paths
                    .filter(Files::isRegularFile)
                    .filter { path -> path.fileName.toString().endsWith(".ndjson.gz") }
                    .sorted()
                    .toList()
            }
    require(sealedFiles.isNotEmpty()) { "Maker shadow replay input contains no sealed .ndjson.gz files." }
    val excludedPartFiles =
        Files
            .walk(options.inputRoot)
            .use { paths ->
                paths
                    .filter(Files::isRegularFile)
                    .filter { path -> path.fileName.toString().endsWith(".part") }
                    .count()
            }

    val summaryLedger = ReplaySummaryLedger(definition.parameters.initialEquity.decimal())
    val engine =
        MakerShadowEngine(
            config = definition.toEngineConfig(),
            ledger = summaryLedger,
        )
    val parser = BybitPublicMarketCaptureParser(definition.orderBookDepth)
    var activeConnectionId: String? = null
    var previousReceivedAt: Instant? = null
    var firstReceivedAt: Instant? = null
    var lastReceivedAt: Instant? = null
    var rawEventCount = 0L
    var normalizedEventCount = 0L
    var gapEventCount = 0L
    val fileEvidence = mutableListOf<MakerShadowReplayFileEvidence>()

    sealedFiles.forEach { path ->
        val relativePath = options.inputRoot.relativize(path).toString()
        fileEvidence +=
            MakerShadowReplayFileEvidence(
                path = relativePath,
                size = Files.size(path),
                sha256 = path.sha256(),
            )
        GZIPInputStream(Files.newInputStream(path))
            .bufferedReader(StandardCharsets.UTF_8)
            .use { reader ->
                var lineNumber = 0L
                while (true) {
                    val line = reader.readLine() ?: break
                    lineNumber += 1
                    if (line.isBlank()) continue
                    val archived = JSON.parseToJsonElement(line).jsonObject
                    val schemaVersion = archived.required("schemaVersion").toInt()
                    require(schemaVersion == 1) {
                        "Unsupported raw archive schema path=$relativePath line=$lineNumber schema=$schemaVersion"
                    }
                    val connectionId = archived.required("localConnectionId")
                    if (connectionId != activeConnectionId) {
                        parser.beginConnection(connectionId)
                        activeConnectionId = connectionId
                    }
                    val receivedAt = Instant.parse(archived.required("receivedAt"))
                    if (definition.evidencePolicy.requireContiguousReceiveOrder) {
                        require(previousReceivedAt?.let { previous -> !receivedAt.isBefore(previous) } != false) {
                            "Raw archive receive order regressed path=$relativePath line=$lineNumber"
                        }
                    }
                    val payload = archived.required("rawPayload")
                    val batch =
                        requireNotNull(parser.parse(payload, receivedAt)) {
                            "Raw archive payload is not a supported market event path=$relativePath line=$lineNumber"
                        }
                    val expectedQuality = ForwardMarketDataQuality.valueOf(archived.required("quality"))
                    require(batch.rawEvent.quality == expectedQuality) {
                        "Raw archive quality mismatch path=$relativePath line=$lineNumber " +
                            "expected=$expectedQuality actual=${batch.rawEvent.quality}"
                    }
                    require(batch.rawEvent.localConnectionId == connectionId) {
                        "Raw archive connection mismatch path=$relativePath line=$lineNumber"
                    }
                    engine.onBatch(batch)
                    if (batch.rawEvent.eventKind == ForwardMarketEventKind.ORDER_BOOK) {
                        summaryLedger.observe(engine.snapshot())
                    }
                    rawEventCount += 1
                    normalizedEventCount += batch.normalizedEvents.size
                    if (batch.rawEvent.gapDetected) gapEventCount += 1
                    firstReceivedAt = firstReceivedAt ?: receivedAt
                    lastReceivedAt = receivedAt
                    previousReceivedAt = receivedAt
                }
            }
    }

    val finalSnapshot = engine.snapshot()
    summaryLedger.observe(finalSnapshot)
    val observedDuration = Duration.between(requireNotNull(firstReceivedAt), requireNotNull(lastReceivedAt))
    val observedHours = BigDecimal(observedDuration.toMillis()).divide(BigDecimal("3600000"), DECIMAL_CONTEXT)
    val sourceSnapshotHash =
        fileEvidence
            .joinToString("\n") { evidence -> "${evidence.path}|${evidence.size}|${evidence.sha256}" }
            .sha256()
    val definitionHash = definitionBytes.sha256()
    val gates =
        MakerShadowReplayGates(
            minimumObservedHours = observedHours >= definition.evidencePolicy.minimumObservedHours.decimal(),
            minimumClosedPositions =
                summaryLedger.closedPositionCount >= definition.evidencePolicy.minimumClosedPositions,
            closedInventory = finalSnapshot.inventoryQuantity == BigDecimal.ZERO,
            zeroGapEvents = gapEventCount == 0L,
            sealedFilesOnly = true,
            queueStressComplete = false,
            costStressComplete = false,
        )
    val status =
        if (gates.allPassed) {
            "DEVELOPMENT_COMPLETE_FORWARD_VALIDATION_REQUIRED"
        } else {
            "DEVELOPMENT_INSUFFICIENT_EVIDENCE"
        }
    return MakerShadowReplayResult(
        schemaVersion = 1,
        generatedAt = Instant.now().toString(),
        experimentId = definition.experimentId,
        candidateId = definition.candidateId,
        researchStage = definition.researchStage,
        status = status,
        automaticExecutionAllowed = false,
        engineVersion = MAKER_SHADOW_ENGINE_VERSION,
        definitionSha256 = definitionHash,
        sourceSnapshotSha256 = sourceSnapshotHash,
        replayFingerprint = "$definitionHash|$sourceSnapshotHash|$MAKER_SHADOW_ENGINE_VERSION".sha256(),
        input =
            MakerShadowReplayInput(
                root = options.inputRoot.toString(),
                sealedFileCount = fileEvidence.size,
                excludedPartFileCount = excludedPartFiles,
                rawEventCount = rawEventCount,
                normalizedEventCount = normalizedEventCount,
                gapEventCount = gapEventCount,
                firstReceivedAt = requireNotNull(firstReceivedAt).toString(),
                lastReceivedAt = requireNotNull(lastReceivedAt).toString(),
                observedHours = observedHours.render(),
                files = fileEvidence,
            ),
        metrics = summaryLedger.toMetrics(finalSnapshot),
        gates = gates,
    )
}

private class ReplaySummaryLedger(
    private val initialEquity: BigDecimal,
) : MakerShadowLedger {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val eventTypeCounts = sortedMapOf<String, Long>()
    private val closedPositionPnls = mutableListOf<BigDecimal>()
    private val markOuts = mutableMapOf<String, MutableList<BigDecimal>>()
    private var peakEquity = initialEquity
    private var maximumDrawdownPct = BigDecimal.ZERO
    private var lastFlatEquity = initialEquity
    var eventCount: Long = 0
        private set
    var closedPositionCount: Int = 0
        private set

    override suspend fun append(events: List<MakerShadowLedgerEvent>) {
        events.forEach { event ->
            eventCount += 1
            eventTypeCounts.compute(event.type.name) { _, count -> (count ?: 0L) + 1L }
            digest.update(event.canonicalTrace().toByteArray(StandardCharsets.UTF_8))
            digest.update('\n'.code.toByte())
            observeEquity(event.equity)
            if (event.type == MakerShadowLedgerEventType.POSITION_CLOSED) {
                closedPositionCount += 1
                closedPositionPnls += event.equity - lastFlatEquity
                lastFlatEquity = event.equity
            }
            event.markOutBps?.let { markOut ->
                markOuts.getOrPut(event.type.name, ::mutableListOf).add(markOut)
            }
        }
    }

    fun observe(snapshot: MakerShadowSnapshot) {
        observeEquity(snapshot.equity)
    }

    fun toMetrics(snapshot: MakerShadowSnapshot): MakerShadowReplayMetrics {
        val netPnl = snapshot.equity - initialEquity
        val grossProfit = closedPositionPnls.filter { it > BigDecimal.ZERO }.fold(BigDecimal.ZERO, BigDecimal::add)
        val grossLoss =
            closedPositionPnls
                .filter { it < BigDecimal.ZERO }
                .fold(BigDecimal.ZERO, BigDecimal::add)
                .abs()
        return MakerShadowReplayMetrics(
            initialEquity = initialEquity.render(),
            finalCash = snapshot.cash.render(),
            finalEquity = snapshot.equity.render(),
            netPnl = netPnl.render(),
            netReturnPct = netPnl.divide(initialEquity, DECIMAL_CONTEXT).multiply(BigDecimal("100")).render(),
            maximumDrawdownPct = maximumDrawdownPct.render(),
            inventoryQuantity = snapshot.inventoryQuantity.render(),
            totalMakerFees = snapshot.totalMakerFees.render(),
            totalTakerFees = snapshot.totalTakerFees.render(),
            grossPnlBeforeFees = (netPnl + snapshot.totalMakerFees + snapshot.totalTakerFees).render(),
            closedPositionCount = closedPositionCount,
            profitableClosedPositions = closedPositionPnls.count { it > BigDecimal.ZERO },
            losingClosedPositions = closedPositionPnls.count { it < BigDecimal.ZERO },
            profitFactor = if (grossLoss == BigDecimal.ZERO) null else grossProfit.divide(grossLoss, DECIMAL_CONTEXT).render(),
            ledgerEventCount = eventCount,
            ledgerEventSha256 = digest.copyDigestHex(),
            eventTypeCounts = eventTypeCounts,
            meanMarkOutBps =
                markOuts
                    .toSortedMap()
                    .mapValues { (_, values) ->
                        values.fold(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal(values.size), DECIMAL_CONTEXT).render()
                    },
        )
    }

    private fun observeEquity(equity: BigDecimal) {
        if (equity > peakEquity) peakEquity = equity
        if (peakEquity > BigDecimal.ZERO) {
            val drawdown =
                peakEquity
                    .subtract(equity)
                    .divide(peakEquity, DECIMAL_CONTEXT)
                    .multiply(BigDecimal("100"))
            if (drawdown > maximumDrawdownPct) maximumDrawdownPct = drawdown
        }
    }
}

@Serializable
internal data class MakerShadowReplayDefinition(
    val schemaVersion: Int,
    val experimentId: String,
    val candidateId: String,
    val researchStage: String,
    val automaticExecutionAllowed: Boolean,
    val symbol: String,
    val orderBookDepth: Int,
    val parameters: MakerShadowReplayParameters,
    val evidencePolicy: MakerShadowReplayEvidencePolicy,
)

@Serializable
internal data class MakerShadowReplayParameters(
    val initialEquity: String,
    val orderQuantity: String,
    val maxNotional: String,
    val queueMultiplier: String,
    val queueBufferQuantity: String,
    val minSpreadBps: String,
    val makerFeeRate: String,
    val takerFeeRate: String,
    val takerExitSlippageBps: String,
    val maxQuoteAgeMillis: Long,
    val maxHoldingSeconds: Long,
    val maxEventDelayMillis: Long,
    val tradeIdCacheSize: Int,
)

@Serializable
internal data class MakerShadowReplayEvidencePolicy(
    val source: String,
    val allowPartFiles: Boolean,
    val requireContiguousReceiveOrder: Boolean,
    val minimumObservedHours: String,
    val minimumClosedPositions: Int,
    val requiredQueueStressMultipliers: List<String>,
    val requiredCostStressMultipliers: List<String>,
)

@Serializable
internal data class MakerShadowReplayResult(
    val schemaVersion: Int,
    val generatedAt: String,
    val experimentId: String,
    val candidateId: String,
    val researchStage: String,
    val status: String,
    val automaticExecutionAllowed: Boolean,
    val engineVersion: String,
    val definitionSha256: String,
    val sourceSnapshotSha256: String,
    val replayFingerprint: String,
    val input: MakerShadowReplayInput,
    val metrics: MakerShadowReplayMetrics,
    val gates: MakerShadowReplayGates,
)

@Serializable
internal data class MakerShadowReplayInput(
    val root: String,
    val sealedFileCount: Int,
    val excludedPartFileCount: Long,
    val rawEventCount: Long,
    val normalizedEventCount: Long,
    val gapEventCount: Long,
    val firstReceivedAt: String,
    val lastReceivedAt: String,
    val observedHours: String,
    val files: List<MakerShadowReplayFileEvidence>,
)

@Serializable
internal data class MakerShadowReplayFileEvidence(
    val path: String,
    val size: Long,
    val sha256: String,
)

@Serializable
internal data class MakerShadowReplayMetrics(
    val initialEquity: String,
    val finalCash: String,
    val finalEquity: String,
    val netPnl: String,
    val netReturnPct: String,
    val maximumDrawdownPct: String,
    val inventoryQuantity: String,
    val totalMakerFees: String,
    val totalTakerFees: String,
    val grossPnlBeforeFees: String,
    val closedPositionCount: Int,
    val profitableClosedPositions: Int,
    val losingClosedPositions: Int,
    val profitFactor: String?,
    val ledgerEventCount: Long,
    val ledgerEventSha256: String,
    val eventTypeCounts: Map<String, Long>,
    val meanMarkOutBps: Map<String, String>,
)

@Serializable
internal data class MakerShadowReplayGates(
    val minimumObservedHours: Boolean,
    val minimumClosedPositions: Boolean,
    val closedInventory: Boolean,
    val zeroGapEvents: Boolean,
    val sealedFilesOnly: Boolean,
    val queueStressComplete: Boolean,
    val costStressComplete: Boolean,
) {
    val allPassed: Boolean
        get() =
            minimumObservedHours &&
                minimumClosedPositions &&
                closedInventory &&
                zeroGapEvents &&
                sealedFilesOnly &&
                queueStressComplete &&
                costStressComplete
}

private fun MakerShadowReplayDefinition.toEngineConfig(): MakerShadowConfig =
    MakerShadowConfig(
        sessionId = "replay-${candidateId.replace(Regex("[^A-Za-z0-9_-]"), "_")}",
        symbol = Symbol(symbol),
        initialEquity = parameters.initialEquity.decimal(),
        orderQuantity = parameters.orderQuantity.decimal(),
        maxNotional = parameters.maxNotional.decimal(),
        queueMultiplier = parameters.queueMultiplier.decimal(),
        queueBufferQuantity = parameters.queueBufferQuantity.decimal(),
        minSpreadBps = parameters.minSpreadBps.decimal(),
        makerFeeRate = parameters.makerFeeRate.decimal(),
        takerFeeRate = parameters.takerFeeRate.decimal(),
        takerExitSlippageBps = parameters.takerExitSlippageBps.decimal(),
        maxQuoteAge = Duration.ofMillis(parameters.maxQuoteAgeMillis),
        maxHoldingDuration = Duration.ofSeconds(parameters.maxHoldingSeconds),
        maxEventDelay = Duration.ofMillis(parameters.maxEventDelayMillis),
        tradeIdCacheSize = parameters.tradeIdCacheSize,
    )

private fun MakerShadowLedgerEvent.canonicalTrace(): String =
    listOf(
        eventId,
        sessionId,
        engineVersion,
        configFingerprint,
        type.name,
        symbol.value,
        eventAt,
        receivedAt,
        bookEpoch,
        crossSequence,
        quoteId,
        tradeId,
        side?.name,
        price?.render(),
        quantity?.render(),
        fee?.render(),
        queueAhead?.render(),
        inventoryQuantity.render(),
        cash.render(),
        equity.render(),
        markOutBps?.render(),
        reason,
    ).joinToString("\u001f") { value -> value?.toString().orEmpty() }

private fun Map<String, String>.required(name: String): String = requireNotNull(this[name]) { "Missing required argument: --$name" }

private fun kotlinx.serialization.json.JsonObject.required(name: String): String =
    requireNotNull(this[name]?.jsonPrimitive?.contentOrNull) { "Raw archive field is missing: $name" }

private fun String.decimal(): BigDecimal = BigDecimal(this)

private fun BigDecimal.render(): String = stripTrailingZeros().toPlainString()

private fun Path.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(this).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().hex()
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).hex()

private fun String.sha256(): String = toByteArray(StandardCharsets.UTF_8).sha256()

private fun ByteArray.hex(): String = joinToString("") { byte -> "%02x".format(byte) }

private fun MessageDigest.copyDigestHex(): String =
    try {
        (clone() as MessageDigest).digest().hex()
    } catch (_: CloneNotSupportedException) {
        error("SHA-256 digest cloning is required for maker shadow replay output.")
    }

internal fun writeAtomically(
    outputPath: Path,
    payload: String,
) {
    outputPath.parent?.let(Files::createDirectories)
    val temporary = outputPath.resolveSibling("${outputPath.fileName}.tmp")
    Files.writeString(temporary, "$payload\n", StandardCharsets.UTF_8)
    try {
        Files.move(temporary, outputPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary, outputPath, StandardCopyOption.REPLACE_EXISTING)
    }
}
