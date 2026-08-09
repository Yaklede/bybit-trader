package dev.yaklede.bybittrader.app.research

import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendBar
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendBlockedEntry
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendCommand
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendEmaPair
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendEngine
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendExecutionContract
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendFundingRate
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendParameters
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendSimulation
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendSimulationRiskPolicy
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendSimulator
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendTrade
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.sqlite.SQLiteConfig
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import kotlin.math.round

private val PRETTY_JSON = Json { prettyPrint = true }
private val SQL_INSTANT = DateTimeFormatterBuilder().appendInstant(3).toFormatter()

fun main(args: Array<String>) {
    val options = parseArgs(args)
    val protocolPath = Path.of(options.required("protocol")).toAbsolutePath().normalize()
    val databasePath = Path.of(options.required("db")).toAbsolutePath().normalize()
    val outputPath = Path.of(options.required("out")).toAbsolutePath().normalize()
    val riskPolicy = options.simulationRiskPolicy()
    val protocolBytes = Files.readAllBytes(protocolPath)
    val root = Json.parseToJsonElement(protocolBytes.toString(StandardCharsets.UTF_8)).jsonObject
    require(root.requiredBoolean("automaticExecutionAllowed") == false)
    require(root.requiredBoolean("liveExecutionAllowed") == false)
    val protocolSha256 = protocolBytes.sha256()
    val market = root.requiredObject("market")
    val strategy = root.requiredObject("strategy")
    val capital = root.requiredObject("capital")
    val costs = root.requiredObject("costs")
    val external = root.requiredObject("externalEvidence")
    val parameters =
        VolumeConfirmedTrendParameters(
            emaVotePairs =
                strategy.requiredArray("emaVotePairs").map { value ->
                    val pair = value.jsonObject
                    VolumeConfirmedTrendEmaPair(pair.requiredInt("fast"), pair.requiredInt("slow"))
                },
            minimumMajorityVotes = strategy.requiredInt("minimumMajorityVotes"),
            volumeMedianLookbackBars = strategy.requiredInt("volumeMedianLookbackBars"),
            executionDelayBars = strategy.requiredInt("executionDelayBars"),
            warmupDecisionBars = market.requiredInt("warmupDecisionBars"),
        )
    val contract =
        VolumeConfirmedTrendExecutionContract(
            targetExposureFraction = capital.requiredDouble("targetExposureFraction"),
            maximumRoundedExposureFraction = capital.requiredDouble("maximumRoundedExposureFraction"),
            quantityStepBtc = capital.requiredDouble("quantityStepBtc"),
            minimumQuantityBtc = capital.requiredDouble("minimumQuantityBtc"),
            absoluteMaximumNotionalUsdt = capital.optionalDouble("absoluteMaximumNotionalUsdt"),
            oneWayFeeRate = costs.requiredDouble("oneWayFeeRate"),
            oneWaySlippageRate = costs.requiredDouble("oneWaySlippageRate"),
        )
    Class.forName("org.sqlite.JDBC")
    val sqliteConfig = SQLiteConfig().apply { setReadOnly(true) }
    DriverManager.getConnection("jdbc:sqlite:$databasePath", sqliteConfig.toProperties()).use { connection ->
        val symbol = market.requiredString("symbol")
        val start = Instant.parse(external.requiredString("startInclusive"))
        val end = Instant.parse(external.requiredString("endExclusive"))
        val bars = loadVolumeConfirmedTrendH4(connection, symbol, start, end)
        val funding = loadVolumeConfirmedTrendFunding(connection, symbol, start, end)
        val commands = VolumeConfirmedTrendEngine.commands(bars, parameters)
        val runs =
            capital.requiredArray("startingEquitiesUsdt").flatMap { equityValue ->
                val equityText = equityValue.jsonPrimitive.content
                costs.requiredArray("stressMultipliers").map { multiplierValue ->
                    val multiplierText = multiplierValue.jsonPrimitive.content
                    VolumeConfirmedTrendParityRun.from(
                        startingEquityUsdt = equityText,
                        costMultiplier = multiplierText,
                        riskPolicy = riskPolicy,
                        simulation =
                            VolumeConfirmedTrendSimulator.run(
                                bars = bars,
                                fundingRates = funding,
                                commands = commands,
                                startingEquity = equityText.toDouble(),
                                costMultiplier = multiplierText.toDouble(),
                                contract = contract,
                                riskPolicy = riskPolicy,
                            ),
                    )
                }
            }
        val output =
            VolumeConfirmedTrendParityOutput(
                schemaVersion = 1,
                protocolSha256 = protocolSha256,
                venue = external.requiredString("venue"),
                h4BarCount = bars.size,
                fundingRateCount = funding.size,
                commands = commands.filterNotNull().map(VolumeConfirmedTrendParityCommand::from),
                runs = runs,
            )
        val payload = PRETTY_JSON.encodeToString(output) + "\n"
        outputPath.parent?.let(Files::createDirectories)
        Files.writeString(outputPath, payload)
        print(payload)
    }
}

internal fun loadVolumeConfirmedTrendH4(
    connection: Connection,
    symbol: String,
    start: Instant,
    end: Instant,
): List<VolumeConfirmedTrendBar> =
    connection
        .prepareStatement(
            """
            SELECT opened_at,open,high,low,close,volume FROM marketCandles
            WHERE symbol=? AND timeframe='H4' AND opened_at>=? AND opened_at<? ORDER BY opened_at
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, symbol)
            statement.setString(2, SQL_INSTANT.format(start))
            statement.setString(3, SQL_INSTANT.format(end))
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            VolumeConfirmedTrendBar(
                                openedAt = Instant.parse(rows.getString("opened_at")),
                                open = rows.getString("open").toDouble(),
                                high = rows.getString("high").toDouble(),
                                low = rows.getString("low").toDouble(),
                                close = rows.getString("close").toDouble(),
                                volume = rows.getString("volume").toDouble(),
                            ),
                        )
                    }
                }
            }
        }

internal fun loadVolumeConfirmedTrendFunding(
    connection: Connection,
    symbol: String,
    start: Instant,
    end: Instant,
): List<VolumeConfirmedTrendFundingRate> =
    connection
        .prepareStatement(
            """
            SELECT timestamp,funding_rate FROM fundingRates
            WHERE symbol=? AND timestamp>=? AND timestamp<? ORDER BY timestamp
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, symbol)
            statement.setString(2, SQL_INSTANT.format(start))
            statement.setString(3, SQL_INSTANT.format(end))
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            VolumeConfirmedTrendFundingRate(
                                timestamp = Instant.parse(rows.getString("timestamp")),
                                rate = rows.getString("funding_rate").toDouble(),
                            ),
                        )
                    }
                }
            }
        }

private fun parseArgs(args: Array<String>): Map<String, String> {
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

private fun Map<String, String>.required(name: String): String = requireNotNull(this[name]) { "Missing --$name." }

private fun Map<String, String>.simulationRiskPolicy(): VolumeConfirmedTrendSimulationRiskPolicy? {
    val daily = this["maximum-daily-loss-fraction"]
    val drawdown = this["maximum-account-drawdown-fraction"]
    val consecutive = this["maximum-consecutive-losses"]
    val values = listOf(daily, drawdown, consecutive)
    require(values.all { it == null } || values.all { it != null }) {
        "Trend parity risk policy requires all three risk limits."
    }
    if (daily == null) return null
    return VolumeConfirmedTrendSimulationRiskPolicy(
        maximumDailyLossFraction = daily.optionalRiskLimitDouble(),
        maximumAccountDrawdownFraction = requireNotNull(drawdown).toDouble(),
        maximumConsecutiveLosses = requireNotNull(consecutive).optionalRiskLimitInt(),
    )
}

private fun String.optionalRiskLimitDouble(): Double? = takeUnless { it == "disabled" }?.toDouble()

private fun String.optionalRiskLimitInt(): Int? = takeUnless { it == "disabled" }?.toInt()

private fun JsonObject.requiredObject(name: String): JsonObject = getValue(name).jsonObject

private fun JsonObject.requiredArray(name: String): JsonArray = getValue(name).jsonArray

private fun JsonObject.requiredString(name: String): String = getValue(name).jsonPrimitive.content

private fun JsonObject.requiredInt(name: String): Int = getValue(name).jsonPrimitive.int

private fun JsonObject.requiredDouble(name: String): Double = getValue(name).jsonPrimitive.double

private fun JsonObject.optionalDouble(name: String): Double? =
    this[name]
        ?.jsonPrimitive
        ?.content
        ?.takeUnless { it == "null" }
        ?.toDouble()

private fun JsonObject.requiredBoolean(name: String): Boolean = getValue(name).jsonPrimitive.content.toBooleanStrict()

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { byte -> "%02x".format(byte) }

private fun Double.round8(): Double = round((this + Math.ulp(1.0)) * 1e8) / 1e8

@Serializable
private data class VolumeConfirmedTrendParityOutput(
    val schemaVersion: Int,
    val protocolSha256: String,
    val venue: String,
    val h4BarCount: Int,
    val fundingRateCount: Int,
    val commands: List<VolumeConfirmedTrendParityCommand>,
    val runs: List<VolumeConfirmedTrendParityRun>,
)

@Serializable
private data class VolumeConfirmedTrendParityCommand(
    val side: String,
    val decisionAt: String,
    val executionAt: String,
    val decisionIndex: Int,
    val executionIndex: Int,
    val netVotes: Int,
    val decisionVolume: Double,
    val priorVolumeMedian: Double,
) {
    companion object {
        fun from(command: VolumeConfirmedTrendCommand): VolumeConfirmedTrendParityCommand =
            VolumeConfirmedTrendParityCommand(
                side = command.side.name,
                decisionAt = SQL_INSTANT.format(command.decisionAt),
                executionAt = SQL_INSTANT.format(command.executionAt),
                decisionIndex = command.decisionIndex,
                executionIndex = command.executionIndex,
                netVotes = command.netVotes,
                decisionVolume = command.decisionVolume.round8(),
                priorVolumeMedian = command.priorVolumeMedian.round8(),
            )
    }
}

@Serializable
private data class VolumeConfirmedTrendParityRun(
    val startingEquityUsdt: String,
    val costMultiplier: String,
    val endingEquityUsdt: Double,
    val netReturnPct: Double,
    val compoundDailyReturnPct: Double,
    val maximumConservativeIntrabarDrawdownPct: Double,
    val maximumEntryExposureFraction: Double,
    val maximumAdverseExposureFraction: Double,
    val totalFeesUsdt: Double,
    val totalSlippageUsdt: Double,
    val totalFundingPnlUsdt: Double,
    val liquidationCount: Int,
    val trades: List<VolumeConfirmedTrendParityTrade>,
    val riskPolicyReplay: VolumeConfirmedTrendParityRiskReplay? = null,
) {
    companion object {
        fun from(
            startingEquityUsdt: String,
            costMultiplier: String,
            riskPolicy: VolumeConfirmedTrendSimulationRiskPolicy?,
            simulation: VolumeConfirmedTrendSimulation,
        ): VolumeConfirmedTrendParityRun =
            VolumeConfirmedTrendParityRun(
                startingEquityUsdt = startingEquityUsdt,
                costMultiplier = costMultiplier,
                endingEquityUsdt = simulation.endingEquity.round8(),
                netReturnPct = simulation.netReturnPct.round8(),
                compoundDailyReturnPct = simulation.compoundDailyReturnPct.round8(),
                maximumConservativeIntrabarDrawdownPct = simulation.maximumConservativeIntrabarDrawdownPct.round8(),
                maximumEntryExposureFraction = simulation.maximumEntryExposureFraction.round8(),
                maximumAdverseExposureFraction = simulation.maximumAdverseExposureFraction.round8(),
                totalFeesUsdt = simulation.totalFees.round8(),
                totalSlippageUsdt = simulation.totalSlippage.round8(),
                totalFundingPnlUsdt = simulation.totalFundingPnl.round8(),
                liquidationCount = simulation.liquidationCount,
                trades = simulation.trades.map(VolumeConfirmedTrendParityTrade::from),
                riskPolicyReplay =
                    riskPolicy?.let { policy ->
                        VolumeConfirmedTrendParityRiskReplay.from(policy, simulation)
                    },
            )
    }
}

@Serializable
private data class VolumeConfirmedTrendParityRiskPolicy(
    val maximumDailyLossFraction: Double?,
    val maximumAccountDrawdownFraction: Double,
    val maximumConsecutiveLosses: Int?,
)

@Serializable
private data class VolumeConfirmedTrendParityBlockedEntry(
    val executionAt: String,
    val side: Int,
    val equityUsdt: Double,
    val dayStartEquityUsdt: Double,
    val peakEquityUsdt: Double,
    val consecutiveLosses: Int,
    val reasonCodes: List<String>,
) {
    companion object {
        fun from(entry: VolumeConfirmedTrendBlockedEntry): VolumeConfirmedTrendParityBlockedEntry =
            VolumeConfirmedTrendParityBlockedEntry(
                executionAt = SQL_INSTANT.format(entry.executionAt),
                side = if (entry.side == dev.yaklede.bybittrader.domain.Side.BUY) 1 else -1,
                equityUsdt = entry.equity.round8(),
                dayStartEquityUsdt = entry.dayStartEquity.round8(),
                peakEquityUsdt = entry.peakEquity.round8(),
                consecutiveLosses = entry.consecutiveLosses,
                reasonCodes = entry.reasonCodes,
            )
    }
}

@Serializable
private data class VolumeConfirmedTrendParityRiskReplay(
    val policy: VolumeConfirmedTrendParityRiskPolicy,
    val blockedEntryCount: Int,
    val blockedEntryReasonCounts: Map<String, Int>,
    val firstBlockedEntry: VolumeConfirmedTrendParityBlockedEntry?,
    val maximumObservedConsecutiveLosses: Int,
    val finalConsecutiveLosses: Int,
) {
    companion object {
        fun from(
            policy: VolumeConfirmedTrendSimulationRiskPolicy,
            simulation: VolumeConfirmedTrendSimulation,
        ): VolumeConfirmedTrendParityRiskReplay =
            VolumeConfirmedTrendParityRiskReplay(
                policy =
                    VolumeConfirmedTrendParityRiskPolicy(
                        maximumDailyLossFraction = policy.maximumDailyLossFraction,
                        maximumAccountDrawdownFraction = policy.maximumAccountDrawdownFraction,
                        maximumConsecutiveLosses = policy.maximumConsecutiveLosses,
                    ),
                blockedEntryCount = simulation.blockedEntries.size,
                blockedEntryReasonCounts =
                    simulation.blockedEntries
                        .flatMap(VolumeConfirmedTrendBlockedEntry::reasonCodes)
                        .groupingBy { reason -> reason }
                        .eachCount()
                        .toSortedMap(),
                firstBlockedEntry = simulation.blockedEntries.firstOrNull()?.let(VolumeConfirmedTrendParityBlockedEntry::from),
                maximumObservedConsecutiveLosses = simulation.maximumObservedConsecutiveLosses,
                finalConsecutiveLosses = simulation.finalConsecutiveLosses,
            )
    }
}

@Serializable
private data class VolumeConfirmedTrendParityTrade(
    val side: String,
    val quantity: Double,
    val entryAt: String,
    val exitAt: String,
    val entryPrice: Double,
    val exitPrice: Double,
    val grossPnl: Double,
    val fundingPnl: Double,
    val fees: Double,
    val netPnl: Double,
    val reason: String,
) {
    companion object {
        fun from(trade: VolumeConfirmedTrendTrade): VolumeConfirmedTrendParityTrade =
            VolumeConfirmedTrendParityTrade(
                side = trade.side.name,
                quantity = trade.quantity,
                entryAt = SQL_INSTANT.format(trade.entryAt),
                exitAt = SQL_INSTANT.format(trade.exitAt),
                entryPrice = trade.entryPrice.round8(),
                exitPrice = trade.exitPrice.round8(),
                grossPnl = trade.grossPnl.round8(),
                fundingPnl = trade.fundingPnl.round8(),
                fees = trade.fees.round8(),
                netPnl = trade.netPnl.round8(),
                reason = trade.reason,
            )
    }
}
