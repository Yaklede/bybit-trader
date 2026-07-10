package dev.yaklede.bybittrader.engine.strategy

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Price
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.SignalIntent
import dev.yaklede.bybittrader.domain.SignalScore
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowAggressiveBacktestConfig
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowAggressiveProfiles
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowSideMode
import dev.yaklede.bybittrader.strategy.StrategyDecision
import dev.yaklede.bybittrader.strategy.TradingStrategy
import dev.yaklede.bybittrader.strategy.volume.VolumeFlowIndicators
import java.math.BigDecimal
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.roundToInt

private const val AGGRESSIVE_BASE_WARMUP_CANDLES = 60
private const val MIN_ENTRY_RISK_PCT = 0.002
private const val MAX_ENTRY_RISK_PCT = 0.035

class VolumeFlowAggressiveStrategy(
    private val config: VolumeFlowAggressiveBacktestConfig = VolumeFlowAggressiveProfiles.finalUsV1(),
) : TradingStrategy {
    override val name: String = "volume-flow-aggressive-${config.profileId}"
    override val warmupCandles: Int = config.requiredRuntimeWarmupCandles()

    override fun evaluate(candles: List<Candle>): StrategyDecision {
        if (candles.size < warmupCandles) {
            return StrategyDecision.noTrade("INSUFFICIENT_AGGRESSIVE_HISTORY")
        }

        val sortedCandles = candles.sortedBy { it.openedAt }
        if (sortedCandles.any { it.timeframe != Timeframe.M5 }) {
            return StrategyDecision.noTrade("AGGRESSIVE_REQUIRES_M5")
        }

        val enriched = sortedCandles.toAggressiveCandles(config)
        val latestIndex = enriched.lastIndex
        val firstSetupIndex =
            maxOf(
                config.clusterCandles - 1,
                latestIndex - config.entryLookaheadCandles,
                AGGRESSIVE_BASE_WARMUP_CANDLES,
            )

        for (setupIndex in firstSetupIndex until latestIndex) {
            val setup = findSetup(enriched, setupIndex, config)
            if (setup != null && setup.signalIndex == latestIndex) {
                return setup.toDecision(config)
            }
        }

        return StrategyDecision.noTrade("NO_AGGRESSIVE_ABSORPTION_BREAKOUT")
    }
}

internal fun VolumeFlowAggressiveBacktestConfig.requiredRuntimeWarmupCandles(): Int {
    val sideLookback =
        sideRegimeBlocks
            .flatMap { listOf(it.lookbackCandles, it.confirmLookbackCandles ?: 0) }
            .maxOrNull()
            ?: 0
    val adaptiveLookback = maxOf(adaptiveStop?.lookbackCandles ?: 0, adaptiveTarget?.lookbackCandles ?: 0)
    return maxOf(
        AGGRESSIVE_BASE_WARMUP_CANDLES,
        volumeLookback + 1,
        atrLookback + 1,
        sideLookback + 1,
        adaptiveLookback + 1,
        clusterCandles + entryLookaheadCandles + 1,
    )
}

private fun AggressiveSetup.toDecision(config: VolumeFlowAggressiveBacktestConfig): StrategyDecision {
    val relativeVolume = signal.relativeVolume ?: 0.0
    val score =
        (80 + ((relativeVolume - 1.0) * 10.0) + ((targetR - 1.0) * 4.0))
            .roundToInt()
            .coerceIn(75, 95)
    val reasonCodes =
        listOf(
            "AGGRESSIVE_ABSORPTION_BREAKOUT",
            "PROFILE_${config.profileId.uppercase()}",
            "SIGNAL_AT_${signal.candle.openedAt}",
            "SIDE_${side.name}",
            "TARGET_R_${targetR.toReasonNumber()}",
            "RELATIVE_VOLUME_${relativeVolume.toReasonNumber()}",
        )

    return StrategyDecision(
        intent =
            SignalIntent(
                symbol = signal.candle.symbol,
                side = side,
                strategy = "volume-flow-aggressive-${config.profileId}",
                score = SignalScore(total = score, reasonCodes = reasonCodes),
                invalidationPrice = Price(BigDecimal.valueOf(stopPrice)),
                expectedR = BigDecimal.valueOf(targetR),
            ),
        reasonCodes = reasonCodes,
    )
}

private fun findSetup(
    candles: List<AggressiveCandle>,
    index: Int,
    config: VolumeFlowAggressiveBacktestConfig,
): AggressiveSetup? {
    val candle = candles[index]
    if (!config.sessionHoursUtc.contains(candle.hourUtc)) return null
    val atr = candle.atr ?: return null
    if (atr <= 0.0) return null
    val relativeVolume = candle.relativeVolume ?: return null
    if (relativeVolume < config.relativeVolumeMin) return null
    val cluster = clusterRange(candles, index - config.clusterCandles + 1, index + 1) ?: return null
    val averageVolume = candle.avgVolume ?: return null
    if (averageVolume <= 0.0) return null
    val clusterVolumeRatio = cluster.volume / (averageVolume * config.clusterCandles)
    if (clusterVolumeRatio < config.clusterVolumeMin) return null
    val firstClusterOpen = candles[index - config.clusterCandles + 1].candle.open.toDouble()
    val displacementAtr = abs(candle.candle.close.toDouble() - firstClusterOpen) / atr
    val rangeAtr = (cluster.high - cluster.low) / atr
    if (displacementAtr > config.maxDisplacementAtr || rangeAtr > config.maxRangeAtr) return null

    val latestSignal = minOf(index + config.entryLookaheadCandles, candles.lastIndex)
    for (signalIndex in index + 1..latestSignal) {
        val signal = candles[signalIndex]
        if (!config.sessionHoursUtc.contains(signal.hourUtc)) continue
        if (config.sideMode != VolumeFlowSideMode.SHORT_ONLY && signal.candle.close.toDouble() > cluster.high) {
            return buildSetup(candles, Side.BUY, signalIndex, cluster, config)
        }
        if (config.sideMode != VolumeFlowSideMode.LONG_ONLY && signal.candle.close.toDouble() < cluster.low) {
            return buildSetup(candles, Side.SELL, signalIndex, cluster, config)
        }
    }
    return null
}

private fun buildSetup(
    candles: List<AggressiveCandle>,
    side: Side,
    signalIndex: Int,
    cluster: AggressiveCluster,
    config: VolumeFlowAggressiveBacktestConfig,
): AggressiveSetup? {
    if (!sideAllowedForRegime(candles, side, signalIndex, config)) return null
    val signal = candles[signalIndex]
    val entryReference = signal.candle.close.toDouble()
    val stopAtr = stopAtrFor(candles, signalIndex, config)
    val atr = signal.atr ?: return null
    val atrStop = atr * stopAtr
    val structuralStop =
        when (side) {
            Side.BUY -> cluster.low
            Side.SELL -> cluster.high
        }
    val stopPrice =
        when (side) {
            Side.BUY -> minOf(structuralStop, entryReference - atrStop)
            Side.SELL -> maxOf(structuralStop, entryReference + atrStop)
        }
    val riskPerUnit = abs(entryReference - stopPrice)
    val entryRiskPct = riskPerUnit / entryReference
    if (riskPerUnit <= 0.0 || entryRiskPct < MIN_ENTRY_RISK_PCT || entryRiskPct > MAX_ENTRY_RISK_PCT) return null
    val targetR = targetRFor(candles, signalIndex, config)
    return AggressiveSetup(
        side = side,
        signalIndex = signalIndex,
        signal = signal,
        stopPrice = stopPrice,
        targetR = targetR,
    )
}

private fun sideAllowedForRegime(
    candles: List<AggressiveCandle>,
    side: Side,
    entryIndex: Int,
    config: VolumeFlowAggressiveBacktestConfig,
): Boolean {
    for (rule in config.sideRegimeBlocks) {
        if (rule.side != side) continue
        val stats = priorStatsFor(candles, entryIndex, rule.lookbackCandles) ?: continue
        if (rule.returnMinPct != null && stats.returnPct < rule.returnMinPct) continue
        if (rule.returnMaxPct != null && stats.returnPct >= rule.returnMaxPct) continue
        if (rule.avgVolumeMin != null && stats.avgVolume < rule.avgVolumeMin) continue
        if (rule.avgVolumeMax != null && stats.avgVolume >= rule.avgVolumeMax) continue
        if (rule.avgRangePctMin != null && stats.avgRangePct < rule.avgRangePctMin) continue
        if (rule.confirmLookbackCandles != null) {
            val confirmStats = priorStatsFor(candles, entryIndex, rule.confirmLookbackCandles) ?: continue
            if (rule.confirmReturnMinPct != null && confirmStats.returnPct < rule.confirmReturnMinPct) continue
            if (rule.confirmReturnMaxPct != null && confirmStats.returnPct >= rule.confirmReturnMaxPct) continue
            if (rule.confirmAvgVolumeMin != null && confirmStats.avgVolume < rule.confirmAvgVolumeMin) continue
            if (rule.confirmAvgVolumeMax != null && confirmStats.avgVolume >= rule.confirmAvgVolumeMax) continue
            if (rule.confirmAvgRangePctMin != null && confirmStats.avgRangePct < rule.confirmAvgRangePctMin) continue
        }
        return false
    }
    return true
}

private fun targetRFor(
    candles: List<AggressiveCandle>,
    entryIndex: Int,
    config: VolumeFlowAggressiveBacktestConfig,
): Double {
    val adaptive = config.adaptiveTarget ?: return config.targetR
    val stats = priorStatsFor(candles, entryIndex, adaptive.lookbackCandles) ?: return config.targetR
    if (stats.returnPct > adaptive.returnMaxPct) return config.targetR
    if (stats.avgVolume < adaptive.avgVolumeMin) return config.targetR
    if (stats.avgRangePct < adaptive.avgRangePctMin) return config.targetR
    return adaptive.targetR
}

private fun stopAtrFor(
    candles: List<AggressiveCandle>,
    entryIndex: Int,
    config: VolumeFlowAggressiveBacktestConfig,
): Double {
    val adaptive = config.adaptiveStop ?: return config.stopAtr
    val stats = priorStatsFor(candles, entryIndex, adaptive.lookbackCandles) ?: return config.stopAtr
    if (stats.returnPct < adaptive.returnMinPct) return config.stopAtr
    if (stats.avgVolume < adaptive.avgVolumeMin) return config.stopAtr
    if (stats.avgRangePct < adaptive.avgRangePctMin) return config.stopAtr
    return adaptive.stopAtr
}

private fun List<Candle>.toAggressiveCandles(config: VolumeFlowAggressiveBacktestConfig): List<AggressiveCandle> {
    val result = ArrayList<AggressiveCandle>(size)
    val volumeQueue = ArrayDeque<Double>()
    val trueRangeQueue = ArrayDeque<Double>()
    var volumeSum = 0.0
    var trueRangeSum = 0.0
    var cumulativeVolume = 0.0
    var cumulativeRangePct = 0.0
    forEachIndexed { index, candle ->
        val previousClose = if (index > 0) this[index - 1].close.toDouble() else candle.close.toDouble()
        val trueRange =
            maxOf(
                candle.high.toDouble() - candle.low.toDouble(),
                abs(candle.high.toDouble() - previousClose),
                abs(candle.low.toDouble() - previousClose),
            )
        val avgVolume = if (volumeQueue.size == config.volumeLookback) volumeSum / config.volumeLookback else null
        val atr = if (trueRangeQueue.size == config.atrLookback) trueRangeSum / config.atrLookback else null
        val range = candle.high.toDouble() - candle.low.toDouble()
        val close = candle.close.toDouble()
        val rangePct = if (close > 0.0) (range / close) * 100.0 else 0.0
        cumulativeVolume += candle.volume.toDouble()
        cumulativeRangePct += rangePct
        val shape = VolumeFlowIndicators.candleShape(candle)
        result +=
            AggressiveCandle(
                candle = candle,
                index = index,
                hourUtc = candle.openedAt.atZone(ZoneOffset.UTC).hour,
                avgVolume = avgVolume,
                atr = atr,
                relativeVolume = if (avgVolume != null && avgVolume > 0.0) candle.volume.toDouble() / avgVolume else null,
                bodyRatio = shape.bodyRatio,
                closeLocation = shape.closeLocation,
                rangePct = rangePct,
                cumulativeVolume = cumulativeVolume,
                cumulativeRangePct = cumulativeRangePct,
            )
        volumeQueue += candle.volume.toDouble()
        volumeSum += candle.volume.toDouble()
        if (volumeQueue.size > config.volumeLookback) {
            volumeSum -= volumeQueue.removeFirst()
        }
        trueRangeQueue += trueRange
        trueRangeSum += trueRange
        if (trueRangeQueue.size > config.atrLookback) {
            trueRangeSum -= trueRangeQueue.removeFirst()
        }
    }
    return result
}

private fun clusterRange(
    candles: List<AggressiveCandle>,
    start: Int,
    end: Int,
): AggressiveCluster? {
    if (start < 0 || end > candles.size || start >= end) return null
    var high = Double.NEGATIVE_INFINITY
    var low = Double.POSITIVE_INFINITY
    var volume = 0.0
    for (index in start until end) {
        val candle = candles[index].candle
        high = maxOf(high, candle.high.toDouble())
        low = minOf(low, candle.low.toDouble())
        volume += candle.volume.toDouble()
    }
    return AggressiveCluster(high = high, low = low, volume = volume)
}

private fun priorStatsFor(
    candles: List<AggressiveCandle>,
    index: Int,
    lookbackCandles: Int,
): AggressivePriorStats? {
    val start = index - lookbackCandles
    if (start < 0 || start >= index) return null
    val first = candles[start]
    val last = candles[index - 1]
    val firstOpen = first.candle.open.toDouble()
    if (firstOpen <= 0.0) return null
    val previous = if (start > 0) candles[start - 1] else null
    val volume = last.cumulativeVolume - (previous?.cumulativeVolume ?: 0.0)
    val rangePct = last.cumulativeRangePct - (previous?.cumulativeRangePct ?: 0.0)
    return AggressivePriorStats(
        returnPct = ((last.candle.close.toDouble() / firstOpen) - 1.0) * 100.0,
        avgVolume = volume / lookbackCandles,
        avgRangePct = rangePct / lookbackCandles,
    )
}

private fun Double.toReasonNumber(): String = "%.4f".format(this).trimEnd('0').trimEnd('.')

private data class AggressiveCandle(
    val candle: Candle,
    val index: Int,
    val hourUtc: Int,
    val avgVolume: Double?,
    val atr: Double?,
    val relativeVolume: Double?,
    val bodyRatio: Double,
    val closeLocation: Double,
    val rangePct: Double,
    val cumulativeVolume: Double,
    val cumulativeRangePct: Double,
)

private data class AggressiveCluster(
    val high: Double,
    val low: Double,
    val volume: Double,
)

private data class AggressiveSetup(
    val side: Side,
    val signalIndex: Int,
    val signal: AggressiveCandle,
    val stopPrice: Double,
    val targetR: Double,
)

private data class AggressivePriorStats(
    val returnPct: Double,
    val avgVolume: Double,
    val avgRangePct: Double,
)
