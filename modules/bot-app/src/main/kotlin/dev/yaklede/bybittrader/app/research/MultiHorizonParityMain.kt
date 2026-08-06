package dev.yaklede.bybittrader.app.research

import dev.yaklede.bybittrader.app.openLedgerDatabase
import dev.yaklede.bybittrader.domain.ResearchCandleLimits
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.backtest.BacktestRunner
import dev.yaklede.bybittrader.engine.backtest.MultiHorizonMomentumResearchProfiles
import dev.yaklede.bybittrader.ledger.SqlDelightLedger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

fun main(args: Array<String>) =
    runBlocking {
        val options = parseArgs(args)
        val databasePath = Path.of(options.required("db")).toAbsolutePath()
        require(Files.isRegularFile(databasePath)) { "Research database does not exist: $databasePath" }
        val replayStartAt = Instant.parse(options.required("start"))
        val replayEndAtExclusive = Instant.parse(options.required("end"))
        require(replayStartAt.isBefore(replayEndAtExclusive)) { "Replay start must be before replay end." }

        val profile = MultiHorizonMomentumResearchProfiles.current()
        val historyStartAt =
            options["historyStart"]?.let(Instant::parse)
                ?: replayStartAt.minusSeconds(profile.parameters.minimumCandles * Timeframe.M5.secondsPerCandle())
        require(historyStartAt.isBefore(replayStartAt)) { "History start must be before replay start." }

        val ledger = SqlDelightLedger(openLedgerDatabase(databasePath))
        val candles =
            ledger
                .candlesBetween(
                    symbol = Symbol("BTCUSDT"),
                    timeframe = Timeframe.M5,
                    startAt = historyStartAt,
                    endAt = replayEndAtExclusive,
                    limit = ResearchCandleLimits.MAX_M5_REPLAY_CANDLES,
                ).asSequence()
                .filter { it.openedAt.isBefore(replayEndAtExclusive) }
                .sortedBy { it.openedAt }
                .toList()
        val historyCount = candles.count { it.openedAt.isBefore(replayStartAt) }
        require(historyCount >= profile.parameters.minimumCandles) {
            "Insufficient warmup candles: required=${profile.parameters.minimumCandles}, actual=$historyCount"
        }
        requireContiguousM5(candles)

        val config =
            profile
                .backtestConfig()
                .copy(
                    replayStartAt = replayStartAt,
                    replayEndAtExclusive = replayEndAtExclusive,
                )
        val result = BacktestRunner(profile.strategy()).run(candles, config)
        val observedDays =
            maxOf(1.0, Duration.between(replayStartAt, replayEndAtExclusive).seconds / 86_400.0)
        val output =
            MultiHorizonParityOutput(
                profileId = profile.profileId,
                executionContract = profile.executionContract,
                historyStartAt = historyStartAt.toString(),
                report =
                    MultiHorizonParityReport(
                        id = options["windowId"] ?: "PARITY",
                        replayStartAt = replayStartAt.toString(),
                        replayEndAt = replayEndAtExclusive.toString(),
                        candleCount = result.candleCount,
                        finalEquity = result.finalEquity.roundForTrace(),
                        netReturnPct = result.netReturnPct.roundForTrace(),
                        compoundDailyReturnPct =
                            (((result.finalEquity / result.initialEquity).pow(1.0 / observedDays) - 1.0) * 100.0)
                                .roundForTrace(),
                        drawdownPct = result.maxDrawdownPct.roundForTrace(),
                        tradeCount = result.trades.size,
                        trades =
                            result.trades.map { trade ->
                                val equityBefore = trade.equityAfter - trade.pnl
                                val riskAmount = equityBefore * config.riskFraction
                                MultiHorizonParityTrade(
                                    signalAt = trade.signalAt.toString(),
                                    openedAt = trade.entryAt.toString(),
                                    closedAt = trade.exitAt.toString(),
                                    side = trade.side.name,
                                    exitReason = trade.exitReason.name,
                                    entryPrice = trade.entryPrice.roundForTrace(),
                                    stopPrice = trade.initialStopPrice.roundForTrace(),
                                    targetPrice = trade.targetPrice?.roundForTrace(),
                                    exitTriggerPrice = trade.exitTriggerPrice.roundForTrace(),
                                    exitPrice = trade.exitPrice.roundForTrace(),
                                    riskPerUnit = abs(trade.entryPrice - trade.initialStopPrice).roundForTrace(),
                                    riskFraction = config.riskFraction.roundForTrace(),
                                    stopAtr = profile.parameters.stopAtr.roundForTrace(),
                                    targetR = profile.parameters.expectedR.roundForTrace(),
                                    rMultipleGross = (trade.grossPnl / riskAmount).roundForTrace(),
                                    rMultipleNet = trade.returnR.roundForTrace(),
                                    pnl = trade.pnl.roundForTrace(),
                                    equityAfter = trade.equityAfter.roundForTrace(),
                                )
                            },
                    ),
            )
        val payload = Json { prettyPrint = true }.encodeToString(output)
        options["out"]?.let { outputPath ->
            val path = Path.of(outputPath).toAbsolutePath()
            path.parent?.let(Files::createDirectories)
            Files.writeString(path, payload)
        }
        println(payload)
    }

private fun parseArgs(args: Array<String>): Map<String, String> {
    val parsed = mutableMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val argument = args[index]
        require(argument.startsWith("--")) { "Unexpected argument: $argument" }
        require(index + 1 < args.size) { "Missing value for argument: $argument" }
        parsed[argument.removePrefix("--")] = args[index + 1]
        index += 2
    }
    return parsed
}

private fun Map<String, String>.required(name: String): String = requireNotNull(this[name]) { "Missing required argument: --$name" }

private fun requireContiguousM5(candles: List<dev.yaklede.bybittrader.domain.Candle>) {
    candles.zipWithNext().forEach { (previous, current) ->
        require(current.openedAt == previous.openedAt.plusSeconds(Timeframe.M5.secondsPerCandle())) {
            "M5 candle gap detected between ${previous.openedAt} and ${current.openedAt}."
        }
    }
}

private fun Timeframe.secondsPerCandle(): Long =
    when (this) {
        Timeframe.M1 -> 60L
        Timeframe.M5 -> 300L
        Timeframe.M15 -> 900L
        Timeframe.H1 -> 3_600L
    }

private fun Double.roundForTrace(): Double = round(this * 100_000.0) / 100_000.0

@Serializable
private data class MultiHorizonParityOutput(
    val profileId: String,
    val executionContract: String,
    val historyStartAt: String,
    val report: MultiHorizonParityReport,
)

@Serializable
private data class MultiHorizonParityReport(
    val id: String,
    val replayStartAt: String,
    val replayEndAt: String,
    val candleCount: Int,
    val finalEquity: Double,
    val netReturnPct: Double,
    val compoundDailyReturnPct: Double,
    val drawdownPct: Double,
    val tradeCount: Int,
    val trades: List<MultiHorizonParityTrade>,
)

@Serializable
private data class MultiHorizonParityTrade(
    val signalAt: String,
    val openedAt: String,
    val closedAt: String,
    val side: String,
    val exitReason: String,
    val entryPrice: Double,
    val stopPrice: Double,
    val targetPrice: Double?,
    val exitTriggerPrice: Double,
    val exitPrice: Double,
    val riskPerUnit: Double,
    val riskFraction: Double,
    val stopAtr: Double,
    val targetR: Double,
    val rMultipleGross: Double,
    val rMultipleNet: Double,
    val pnl: Double,
    val equityAfter: Double,
)
