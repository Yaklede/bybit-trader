package dev.yaklede.bybittrader.app.research

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.yaklede.bybittrader.app.loadVolumeConfirmedTrendProtocolDefinition
import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.market.MarketTicker
import dev.yaklede.bybittrader.engine.market.flow.FundingRateSnapshot
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendBootstrap
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendEngine
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendEvaluator
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowConfig
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowEvent
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowEventType
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowService
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendSimulator
import dev.yaklede.bybittrader.ledger.SqlDelightLedger
import dev.yaklede.bybittrader.ledger.createLedgerDatabase
import dev.yaklede.bybittrader.ledger.db.LedgerDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.sqlite.SQLiteConfig
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max

private const val RUNTIME_PARITY_TOLERANCE = 1e-8
private const val M15_SECONDS = 900L
private val RUNTIME_PARITY_JSON = Json { prettyPrint = true }

fun main(args: Array<String>) {
    val options = parseRuntimeParityArgs(args)
    val protocolPath = Path.of(options.requiredRuntimeParityArg("protocol")).toAbsolutePath().normalize()
    val externalResultPath = Path.of(options.requiredRuntimeParityArg("external-result")).toAbsolutePath().normalize()
    val databasePath = Path.of(options.requiredRuntimeParityArg("db")).toAbsolutePath().normalize()
    val outputPath = Path.of(options.requiredRuntimeParityArg("out")).toAbsolutePath().normalize()
    val protocol = loadVolumeConfirmedTrendProtocolDefinition(protocolPath)
    val protocolRoot = Json.parseToJsonElement(Files.readString(protocolPath)).jsonObject
    val externalDefinition = protocolRoot.getValue("externalEvidence").jsonObject
    val externalResultBytes = Files.readAllBytes(externalResultPath)
    val externalResult = Json.parseToJsonElement(externalResultBytes.toString(Charsets.UTF_8)).jsonObject
    val externalProtocol = externalResult.getValue("protocol").jsonObject
    require(externalProtocol.getValue("sha256").jsonPrimitive.content == protocol.protocolSha256) {
        "External trend result does not reference the frozen protocol."
    }
    require(
        externalResult
            .getValue("externalGate")
            .jsonObject
            .getValue("passed")
            .jsonPrimitive
            .boolean,
    ) {
        "External trend evidence did not pass its frozen gate."
    }
    require(!externalResult.getValue("automaticExecutionAllowed").jsonPrimitive.boolean) {
        "External trend evidence must not grant automatic execution."
    }
    require(!externalResult.getValue("liveExecutionAllowed").jsonPrimitive.boolean) {
        "External trend evidence must not grant live execution."
    }
    val acquisition = externalResult.getValue("acquisitionEvidence").jsonObject
    val databaseSha256 = databasePath.sha256()
    require(databaseSha256 == acquisition.getValue("databaseSha256").jsonPrimitive.content) {
        "External trend database fingerprint mismatch."
    }

    Class.forName("org.sqlite.JDBC")
    val sqliteConfig = SQLiteConfig().apply { setReadOnly(true) }
    val sourceStart = Instant.parse(externalDefinition.getValue("startInclusive").jsonPrimitive.content)
    val sourceEnd = Instant.parse(externalDefinition.getValue("endExclusive").jsonPrimitive.content)
    val (bars, funding) =
        DriverManager.getConnection("jdbc:sqlite:$databasePath", sqliteConfig.toProperties()).use { connection ->
            loadVolumeConfirmedTrendH4(connection, protocol.symbol.value, sourceStart, sourceEnd) to
                loadVolumeConfirmedTrendFunding(connection, protocol.symbol.value, sourceStart, sourceEnd)
        }
    require(bars.size == acquisition.getValue("h4BarCount").jsonPrimitive.int) {
        "External trend H4 count differs from the frozen evidence."
    }
    require(funding.size == acquisition.getValue("fundingRateCount").jsonPrimitive.int) {
        "External trend funding count differs from the frozen evidence."
    }

    val bootstrapBarCount = protocol.parameters.warmupDecisionBars
    require(bars.size > bootstrapBarCount + 1) { "External trend evidence is too short for runtime replay." }
    val bootstrapEvaluator = VolumeConfirmedTrendEvaluator(protocol.parameters)
    bars.take(bootstrapBarCount).forEach(bootstrapEvaluator::evaluate)
    val bootstrapState = bootstrapEvaluator.snapshot()
    val bootstrap =
        VolumeConfirmedTrendBootstrap(
            protocolId = protocol.protocolId,
            candidateId = protocol.candidateId,
            protocolSha256 = protocol.protocolSha256,
            sourceFeatureSha256 = acquisition.getValue("sourceFeatureSha256").jsonPrimitive.content,
            sourceH4BarCount = bootstrapBarCount,
            indicatorState = bootstrapState,
        )

    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    LedgerDatabase.Schema.create(driver)
    val ledger = SqlDelightLedger(createLedgerDatabase(driver))
    val replayBars = bars.drop(bootstrapBarCount)
    val replayM15 = bars.subList(bootstrapBarCount, bars.lastIndex).flatMap(::syntheticM15)
    val fundingSnapshots =
        funding.map { rate ->
            FundingRateSnapshot(
                symbol = protocol.symbol,
                timestamp = rate.timestamp,
                fundingRate = BigDecimal.valueOf(rate.rate),
            )
        }
    runBlocking {
        replayM15.chunked(10_000).forEach { candles -> ledger.upsert(candles) }
        fundingSnapshots.chunked(10_000).forEach { snapshots -> ledger.upsertFundingRateSnapshots(snapshots) }
    }

    val shadowService =
        VolumeConfirmedTrendShadowService(
            candleStore = ledger,
            flowStore = ledger,
            shadowStore = ledger,
            config =
                VolumeConfirmedTrendShadowConfig(
                    symbol = protocol.symbol,
                    bootstrap = bootstrap,
                    initialEquity = 660.0,
                    parameters = protocol.parameters,
                    executionContract = protocol.executionContract,
                ),
            sessionIdFactory = { "external-runtime-parity" },
        )
    runBlocking {
        shadowService.evaluate(replayBars.first().ticker(protocol.symbol))
        replayBars.drop(1).forEach { bar -> shadowService.evaluate(bar.ticker(protocol.symbol)) }
    }
    val shadowState = requireNotNull(runBlocking { shadowService.state() })
    val shadowEvents =
        runBlocking {
            ledger.trendShadowEvents(
                protocolId = protocol.protocolId,
                symbol = protocol.symbol,
                limit = 100_000,
            )
        }

    val referenceCommands = VolumeConfirmedTrendEngine.commands(bars, protocol.parameters).drop(bootstrapBarCount).toMutableList()
    referenceCommands[0] = null
    val reference =
        VolumeConfirmedTrendSimulator.run(
            bars = replayBars,
            fundingRates = funding.filter { rate -> !rate.timestamp.isBefore(replayBars.first().openedAt) },
            commands = referenceCommands,
            startingEquity = 660.0,
            costMultiplier = 1.0,
            contract = protocol.executionContract,
            closeAtEvidenceEnd = false,
        )

    val comparison = compareRuntimeReplay(referenceCommands.filterNotNull().size, reference, shadowState, shadowEvents)
    val output =
        VolumeConfirmedTrendRuntimeParityOutput(
            schemaVersion = 1,
            status = if (comparison.passed) "PASS" else "FAIL",
            protocolSha256 = protocol.protocolSha256,
            externalResultSha256 = externalResultBytes.sha256(),
            externalDatabaseSha256 = databaseSha256,
            sourceFeatureSha256 = acquisition.getValue("sourceFeatureSha256").jsonPrimitive.content,
            h4BarCount = bars.size,
            fundingRateCount = funding.size,
            bootstrapH4BarCount = bootstrapBarCount,
            replayedH4BarCount = replayBars.size - 1,
            comparison = comparison,
            automaticExecutionAllowed = false,
            liveExecutionAllowed = false,
        )
    val payload = RUNTIME_PARITY_JSON.encodeToString(output) + "\n"
    outputPath.parent?.let(Files::createDirectories)
    Files.writeString(outputPath, payload)
    driver.close()
    print(payload)
    check(comparison.passed) { "Volume-confirmed trend runtime parity failed: ${comparison.mismatchPreview}" }
}

private fun compareRuntimeReplay(
    expectedTransitionCount: Int,
    reference: dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendSimulation,
    shadow: dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowState,
    events: List<VolumeConfirmedTrendShadowEvent>,
): VolumeConfirmedTrendRuntimeParityComparison {
    val mismatches = mutableListOf<String>()
    var maximumNumericDifference = 0.0

    fun exact(
        field: String,
        expected: Any?,
        actual: Any?,
    ) {
        if (expected != actual) mismatches += "$field expected=$expected actual=$actual"
    }

    fun numeric(
        field: String,
        expected: Double,
        actual: Double,
    ) {
        val difference = abs(expected - actual)
        maximumNumericDifference = max(maximumNumericDifference, difference)
        if (difference > RUNTIME_PARITY_TOLERANCE) {
            mismatches += "$field expected=$expected actual=$actual difference=$difference"
        }
    }

    val shadowTrades = shadowTrades(events, mismatches)
    exact("transitionCount", expectedTransitionCount, shadow.executedTransitions)
    exact("closedTradeCount", reference.trades.size, shadowTrades.size)
    reference.trades.zip(shadowTrades).forEachIndexed { index, (expected, actual) ->
        exact("trades[$index].side", expected.side, actual.open.side)
        exact("trades[$index].entryAt", expected.entryAt, actual.open.eventAt)
        exact("trades[$index].exitAt", expected.exitAt, actual.close.eventAt)
        exact("trades[$index].reason", expected.reason, actual.close.reason)
        numeric("trades[$index].quantity", expected.quantity, requireNotNull(actual.open.quantity))
        numeric("trades[$index].entryPrice", expected.entryPrice, requireNotNull(actual.open.fillPrice))
        numeric("trades[$index].exitPrice", expected.exitPrice, requireNotNull(actual.close.fillPrice))
        numeric("trades[$index].grossPnl", expected.grossPnl, actual.close.grossPnl)
        numeric("trades[$index].fundingPnl", expected.fundingPnl, actual.close.fundingPnl)
        numeric("trades[$index].fees", expected.fees, actual.open.fee + actual.close.fee)
        numeric("trades[$index].netPnl", expected.netPnl, actual.close.netPnl)
    }
    numeric("endingCash", reference.endingCash, shadow.cash)
    numeric("totalFees", reference.totalFees, shadow.totalFees)
    numeric("totalSlippage", reference.totalSlippage, shadow.totalSlippage)
    numeric("totalFundingPnl", reference.totalFundingPnl, shadow.totalFundingPnl)
    exact("openPosition.side", reference.endingOpenPosition?.side, shadow.position?.side)
    exact("openPosition.entryAt", reference.endingOpenPosition?.entryAt, shadow.position?.entryAt)
    reference.endingOpenPosition?.let { expected ->
        val actual = requireNotNull(shadow.position)
        numeric("openPosition.quantity", expected.quantity, actual.quantity)
        numeric("openPosition.entryPrice", expected.entryPrice, actual.entryPrice)
        numeric("openPosition.entryFee", expected.entryFee, actual.entryFee)
        numeric("openPosition.fundingPnl", expected.fundingPnl, actual.fundingPnl)
    }
    exact("liquidationCount", 0, shadow.liquidationCount)
    return VolumeConfirmedTrendRuntimeParityComparison(
        passed = mismatches.isEmpty(),
        numericTolerance = RUNTIME_PARITY_TOLERANCE,
        maximumNumericDifference = maximumNumericDifference,
        expectedTransitionCount = expectedTransitionCount,
        actualTransitionCount = shadow.executedTransitions,
        comparedClosedTradeCount = minOf(reference.trades.size, shadowTrades.size),
        expectedClosedTradeCount = reference.trades.size,
        actualClosedTradeCount = shadowTrades.size,
        mismatchCount = mismatches.size,
        mismatchPreview = mismatches.take(20),
    )
}

private fun shadowTrades(
    events: List<VolumeConfirmedTrendShadowEvent>,
    mismatches: MutableList<String>,
): List<ShadowTradeEvents> {
    var open: VolumeConfirmedTrendShadowEvent? = null
    return buildList {
        events.forEach { event ->
            when (event.type) {
                VolumeConfirmedTrendShadowEventType.POSITION_OPENED -> {
                    if (open != null) mismatches += "A shadow position opened before the previous one closed."
                    open = event
                }
                VolumeConfirmedTrendShadowEventType.POSITION_CLOSED -> {
                    val entry = open
                    if (entry == null) {
                        mismatches += "A shadow position closed without a matching open event."
                    } else {
                        add(ShadowTradeEvents(open = entry, close = event))
                        open = null
                    }
                }
                else -> Unit
            }
        }
    }
}

private fun syntheticM15(bar: dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendBar): List<Candle> =
    (0 until 16).map { index ->
        val capturesRange = index == 0
        Candle(
            symbol = Symbol("BTCUSDT"),
            timeframe = Timeframe.M15,
            openedAt = bar.openedAt.plusSeconds(index * M15_SECONDS),
            open = BigDecimal.valueOf(if (capturesRange) bar.open else bar.close),
            high = BigDecimal.valueOf(if (capturesRange) bar.high else bar.close),
            low = BigDecimal.valueOf(if (capturesRange) bar.low else bar.close),
            close = BigDecimal.valueOf(bar.close),
            volume = BigDecimal.valueOf(if (index == 15) bar.volume else 0.0),
        )
    }

private fun dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendBar.ticker(symbol: Symbol): MarketTicker =
    MarketTicker(
        symbol = symbol,
        lastPrice = BigDecimal.valueOf(open),
        markPrice = BigDecimal.valueOf(open),
        indexPrice = BigDecimal.valueOf(open),
        price24hPcnt = null,
        fundingRate = null,
        nextFundingTime = null,
        capturedAt = openedAt,
    )

private fun parseRuntimeParityArgs(args: Array<String>): Map<String, String> {
    val values = mutableMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val argument = args[index]
        require(argument.startsWith("--") && index + 1 < args.size) { "Invalid argument: $argument" }
        values[argument.removePrefix("--")] = args[index + 1]
        index += 2
    }
    return values
}

private fun Map<String, String>.requiredRuntimeParityArg(name: String): String = requireNotNull(this[name]) { "Missing --$name." }

private fun Path.sha256(): String = Files.readAllBytes(this).sha256()

private fun ByteArray.sha256(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

private data class ShadowTradeEvents(
    val open: VolumeConfirmedTrendShadowEvent,
    val close: VolumeConfirmedTrendShadowEvent,
)

@Serializable
private data class VolumeConfirmedTrendRuntimeParityOutput(
    val schemaVersion: Int,
    val status: String,
    val protocolSha256: String,
    val externalResultSha256: String,
    val externalDatabaseSha256: String,
    val sourceFeatureSha256: String,
    val h4BarCount: Int,
    val fundingRateCount: Int,
    val bootstrapH4BarCount: Int,
    val replayedH4BarCount: Int,
    val comparison: VolumeConfirmedTrendRuntimeParityComparison,
    val automaticExecutionAllowed: Boolean,
    val liveExecutionAllowed: Boolean,
)

@Serializable
private data class VolumeConfirmedTrendRuntimeParityComparison(
    val passed: Boolean,
    val numericTolerance: Double,
    val maximumNumericDifference: Double,
    val expectedTransitionCount: Int,
    val actualTransitionCount: Int,
    val comparedClosedTradeCount: Int,
    val expectedClosedTradeCount: Int,
    val actualClosedTradeCount: Int,
    val mismatchCount: Int,
    val mismatchPreview: List<String>,
)
