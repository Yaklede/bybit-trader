package dev.yaklede.bybittrader.app.research

import dev.yaklede.bybittrader.app.loadVolumeConfirmedTrendProtocolDefinition
import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendBootstrap
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendEngine
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendEvaluator
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.sqlite.SQLiteConfig
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

private val BOOTSTRAP_JSON = Json { prettyPrint = true }

fun main(args: Array<String>) {
    val options = parseBootstrapArgs(args)
    val protocolPath = Path.of(options.requiredBootstrapArg("protocol")).toAbsolutePath().normalize()
    val developmentResultPath = Path.of(options.requiredBootstrapArg("development-result")).toAbsolutePath().normalize()
    val databasePath = Path.of(options.requiredBootstrapArg("db")).toAbsolutePath().normalize()
    val outputPath = Path.of(options.requiredBootstrapArg("out")).toAbsolutePath().normalize()
    val protocol = loadVolumeConfirmedTrendProtocolDefinition(protocolPath)
    val development = Json.parseToJsonElement(Files.readString(developmentResultPath)).jsonObject
    val developmentProtocol = development.requiredBootstrapObject("protocol")
    require(developmentProtocol.requiredBootstrapString("sha256") == protocol.protocolSha256) {
        "Development result does not reference the current trend protocol."
    }
    val expectedEvidence = development.requiredBootstrapObject("sourceEvidence")

    Class.forName("org.sqlite.JDBC")
    val sqliteConfig = SQLiteConfig().apply { setReadOnly(true) }
    val candles =
        DriverManager.getConnection("jdbc:sqlite:$databasePath", sqliteConfig.toProperties()).use { connection ->
            loadDevelopmentM15(connection, protocol)
        }
    val bars = VolumeConfirmedTrendEngine.aggregateM15(candles)
    require(bars.size == expectedEvidence.requiredBootstrapInt("h4BarCount")) {
        "Trend bootstrap H4 count differs from the frozen development result."
    }
    require(bars.first().openedAt == Instant.parse(expectedEvidence.requiredBootstrapString("firstH4OpenedAt"))) {
        "Trend bootstrap first H4 differs from the frozen development result."
    }
    require(bars.last().openedAt == Instant.parse(expectedEvidence.requiredBootstrapString("lastH4OpenedAt"))) {
        "Trend bootstrap final H4 differs from the frozen development result."
    }
    val evaluator = VolumeConfirmedTrendEvaluator(protocol.parameters)
    bars.forEach(evaluator::evaluate)
    val state = evaluator.snapshot()
    val bootstrap =
        VolumeConfirmedTrendBootstrap(
            protocolId = protocol.protocolId,
            candidateId = protocol.candidateId,
            protocolSha256 = protocol.protocolSha256,
            sourceFeatureSha256 = expectedEvidence.requiredBootstrapString("sourceFeatureSha256"),
            sourceH4BarCount = bars.size,
            indicatorState = state,
        )
    val root =
        buildJsonObject {
            put("schemaVersion", 1)
            put("protocolId", bootstrap.protocolId)
            put("candidateId", bootstrap.candidateId)
            put("protocolSha256", bootstrap.protocolSha256)
            put(
                "sourceEvidence",
                buildJsonObject {
                    put("venue", expectedEvidence.requiredBootstrapString("venue"))
                    put("disposition", expectedEvidence.requiredBootstrapString("disposition"))
                    put("sourceFeatureSha256", bootstrap.sourceFeatureSha256)
                    put("h4BarCount", bootstrap.sourceH4BarCount)
                    put("firstH4OpenedAt", bars.first().openedAt.toString())
                    put("lastH4OpenedAt", bars.last().openedAt.toString())
                },
            )
            put(
                "indicatorState",
                buildJsonObject {
                    put("processedBars", state.processedBars)
                    putNullableBootstrapString("lastBarOpenedAt", state.lastBarOpenedAt?.toString())
                    put(
                        "emaStates",
                        buildJsonArray {
                            state.emaStates.forEach { ema ->
                                add(
                                    buildJsonObject {
                                        putNullableBootstrapDouble("fast", ema.fast)
                                        putNullableBootstrapDouble("slow", ema.slow)
                                    },
                                )
                            }
                        },
                    )
                    putNullableBootstrapString("targetSide", state.targetSide?.name)
                    put(
                        "recentVolumes",
                        buildJsonArray {
                            state.recentVolumes.forEach { volume -> add(kotlinx.serialization.json.JsonPrimitive(volume)) }
                        },
                    )
                },
            )
        }
    val payload = BOOTSTRAP_JSON.encodeToString(root) + "\n"
    outputPath.parent?.let { parent ->
        if (!Files.isDirectory(parent)) Files.createDirectories(parent)
    }
    val temporary = outputPath.resolveSibling("${outputPath.fileName}.tmp")
    Files.writeString(temporary, payload)
    Files.move(temporary, outputPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    print(payload)
}

private fun loadDevelopmentM15(
    connection: Connection,
    protocol: dev.yaklede.bybittrader.app.VolumeConfirmedTrendProtocolDefinition,
): List<Candle> =
    connection
        .prepareStatement(
            """
            SELECT opened_at,open,high,low,close,volume FROM marketCandles
            WHERE symbol=? AND timeframe='M15' ORDER BY opened_at
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, protocol.symbol.value)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val openedAt = Instant.parse(rows.getString("opened_at"))
                        if (!openedAt.isBefore(protocol.developmentStartInclusive) &&
                            openedAt.isBefore(protocol.developmentEndExclusive)
                        ) {
                            add(
                                Candle(
                                    symbol = protocol.symbol,
                                    timeframe = Timeframe.M15,
                                    openedAt = openedAt,
                                    open = BigDecimal(rows.getString("open")),
                                    high = BigDecimal(rows.getString("high")),
                                    low = BigDecimal(rows.getString("low")),
                                    close = BigDecimal(rows.getString("close")),
                                    volume = BigDecimal(rows.getString("volume")),
                                ),
                            )
                        }
                    }
                }
            }
        }

private fun parseBootstrapArgs(args: Array<String>): Map<String, String> {
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

private fun Map<String, String>.requiredBootstrapArg(name: String): String = requireNotNull(this[name]) { "Missing --$name." }

private fun kotlinx.serialization.json.JsonObject.requiredBootstrapObject(name: String) = getValue(name).jsonObject

private fun kotlinx.serialization.json.JsonObject.requiredBootstrapString(name: String): String = getValue(name).jsonPrimitive.content

private fun kotlinx.serialization.json.JsonObject.requiredBootstrapInt(name: String): Int = getValue(name).jsonPrimitive.int

private fun JsonObjectBuilder.putNullableBootstrapString(
    name: String,
    value: String?,
) {
    if (value == null) put(name, JsonNull) else put(name, value)
}

private fun JsonObjectBuilder.putNullableBootstrapDouble(
    name: String,
    value: Double?,
) {
    if (value == null) put(name, JsonNull) else put(name, value)
}
