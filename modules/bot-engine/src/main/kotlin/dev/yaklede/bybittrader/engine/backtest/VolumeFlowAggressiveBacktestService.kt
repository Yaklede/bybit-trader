package dev.yaklede.bybittrader.engine.backtest

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.ResearchCandleLimits
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.execution.ExecutionSizingConstraints
import dev.yaklede.bybittrader.engine.execution.ExecutionTradePlanCalculator
import dev.yaklede.bybittrader.engine.market.MarketCandleStore
import dev.yaklede.bybittrader.engine.strategy.aggressiveBreakoutAllowed
import dev.yaklede.bybittrader.engine.strategy.aggressiveDirectionalClose
import dev.yaklede.bybittrader.engine.strategy.aggressiveRetestConfirmed
import dev.yaklede.bybittrader.strategy.volume.VolumeFlowIndicators
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

private const val AGGRESSIVE_WARMUP_CANDLES = 60
private const val M5_CANDLE_SECONDS = 300L
private const val MIN_ENTRY_RISK_PCT = 0.002
private const val MAX_ENTRY_RISK_PCT = 0.035

class VolumeFlowAggressiveBacktestService(
    private val candleStore: MarketCandleStore,
) {
    suspend fun run(
        symbol: Symbol,
        m5Limit: Int,
        config: VolumeFlowAggressiveBacktestConfig = VolumeFlowAggressiveProfiles.finalUsV1(),
        m1Limit: Int = ResearchCandleLimits.MAX_M1_REPLAY_CANDLES,
        replayStartAt: Instant? = null,
        replayEndAt: Instant? = null,
    ): VolumeFlowAggressiveBacktestReport {
        require(m1Limit in 1..ResearchCandleLimits.MAX_M1_REPLAY_CANDLES) {
            "M1 limit must be between 1 and ${ResearchCandleLimits.MAX_M1_REPLAY_CANDLES}."
        }
        require(m5Limit in 30..ResearchCandleLimits.MAX_M5_REPLAY_CANDLES) {
            "M5 limit must be between 30 and ${ResearchCandleLimits.MAX_M5_REPLAY_CANDLES}."
        }
        require((replayStartAt == null) == (replayEndAt == null)) {
            "Replay start and end timestamps must both be set or both be omitted."
        }
        require(replayStartAt == null || replayEndAt == null || replayEndAt.isAfter(replayStartAt)) {
            "Replay end timestamp must be after replay start timestamp."
        }
        val loaded =
            if (replayStartAt != null && replayEndAt != null) {
                val warmupLimit = config.requiredWarmupCandles()
                val warmup = candleStore.candlesBefore(symbol, Timeframe.M5, replayStartAt, warmupLimit).sortedBy { it.openedAt }
                require(warmup.size >= warmupLimit) {
                    "Not enough M5 warmup candles before the aggressive replay start."
                }
                val replay = candleStore.candlesBetween(symbol, Timeframe.M5, replayStartAt, replayEndAt, m5Limit).sortedBy { it.openedAt }
                LoadedAggressiveCandles(warmup + replay, replay.size, warmup.size)
            } else {
                val recent = candleStore.recentCandles(symbol, Timeframe.M5, m5Limit).sortedBy { it.openedAt }
                LoadedAggressiveCandles(recent, recent.size, 0)
            }
        require(loaded.candles.size >= config.requiredWarmupCandles() + config.maxHoldCandles + 2) {
            "Not enough M5 candles for aggressive absorption replay."
        }
        val m1Candles =
            if (config.executionPathMode == AggressiveExecutionPathMode.M1_REQUIRED) {
                val pathStartAt = replayStartAt ?: loaded.candles[config.requiredWarmupCandles()].openedAt
                val pathEndAt =
                    replayEndAt
                        ?: loaded.candles
                            .last()
                            .openedAt
                            .plusSeconds(M5_CANDLE_SECONDS - 60L)
                candleStore
                    .candlesBetween(symbol, Timeframe.M1, pathStartAt, pathEndAt, m1Limit)
                    .sortedBy { it.openedAt }
                    .also { require(it.isNotEmpty()) { "M1 execution candles are required for the aggressive replay." } }
            } else {
                emptyList()
            }
        return runLoadedCandles(
            symbol = symbol,
            candles = loaded.candles,
            m1Candles = m1Candles,
            config = config,
            replayStartAt = replayStartAt,
            replayEndAt = replayEndAt,
            replayCandleCount = loaded.replayCandleCount,
            warmupCandleCount = loaded.warmupCandleCount,
        )
    }

    internal fun runLoadedCandles(
        symbol: Symbol,
        candles: List<Candle>,
        m1Candles: List<Candle> = emptyList(),
        config: VolumeFlowAggressiveBacktestConfig = VolumeFlowAggressiveProfiles.finalUsV1(),
        replayStartAt: Instant? = null,
        replayEndAt: Instant? = null,
        replayCandleCount: Int = candles.size,
        warmupCandleCount: Int = 0,
    ): VolumeFlowAggressiveBacktestReport {
        val enriched = candles.enriched(config)
        var equity = config.initialEquity
        var peakEquity = equity
        var maxDrawdownPct = 0.0
        var wins = 0
        var losses = 0
        var grossProfit = 0.0
        var grossLoss = 0.0
        var totalGrossPnl = 0.0
        var totalFees = 0.0
        var totalFundingPnl = 0.0
        var totalSlippageCost = 0.0
        var liquidationCount = 0
        var skippedSignalCount = 0
        var skippedDataGapCount = 0
        val activeDays = linkedSetOf<String>()
        val tradesByDay = mutableMapOf<String, Int>()
        val trades = mutableListOf<VolumeFlowAggressiveBacktestTrade>()

        val firstReplayIndex =
            replayStartAt
                ?.let { startAt -> enriched.indexOfFirst { !it.candle.openedAt.isBefore(startAt) } }
                ?.takeIf { it >= 0 }
                ?: config.requiredWarmupCandles()
        var index = maxOf(config.requiredWarmupCandles(), firstReplayIndex)
        val replayEndIndex = enriched.size
        while (index < replayEndIndex - config.maxHoldCandles - 2) {
            val setup = findSetup(enriched, index, replayEndIndex, config)
            if (setup == null) {
                index += 1
                continue
            }
            val dayTradeCount = tradesByDay[setup.entry.day] ?: 0
            if (dayTradeCount >= config.maxTradesPerDay) {
                index += 1
                continue
            }
            val riskAmount = equity * config.riskFraction
            val quantity = calculateQuantity(equity, setup, config)
            if (quantity == null) {
                skippedSignalCount += 1
                index += 1
                continue
            }
            val exit =
                when (config.executionPathMode) {
                    AggressiveExecutionPathMode.M1_REQUIRED -> simulateM1Exit(m1Candles, setup, config)
                    AggressiveExecutionPathMode.M5_CONSERVATIVE -> simulateM5Exit(enriched, setup, replayEndIndex, config)
                }
            if (exit == null) {
                skippedDataGapCount += 1
                index += 1
                continue
            }
            val effectiveExitPrice = exit.exitPrice.withExitSlippage(setup.side, config.exitSlippageRate)
            val grossPnl =
                when (setup.side) {
                    Side.BUY -> (effectiveExitPrice - setup.entryPrice) * quantity
                    Side.SELL -> (setup.entryPrice - effectiveExitPrice) * quantity
                }
            val fees = ((setup.entryPrice + effectiveExitPrice) * quantity) * config.feeRate
            val fundingPnl = fundingPnl(setup, exit, quantity, config.fundingRatePer8h)
            val slippageCost =
                (abs(setup.entryPrice - setup.rawEntryPrice) + abs(effectiveExitPrice - exit.exitPrice)) * quantity
            val pnl = grossPnl - fees + fundingPnl
            totalGrossPnl += grossPnl
            totalFees += fees
            totalFundingPnl += fundingPnl
            totalSlippageCost += slippageCost
            if (exit.reason == VolumeFlowExitReason.LIQUIDATION) liquidationCount += 1
            val equityBefore = equity
            equity += pnl
            peakEquity = maxOf(peakEquity, equity)
            val drawdownPct = ((peakEquity - equity) / peakEquity) * 100.0
            maxDrawdownPct = maxOf(maxDrawdownPct, drawdownPct)
            if (pnl > 0.0) {
                wins += 1
                grossProfit += pnl
            } else {
                losses += 1
                grossLoss += abs(pnl)
            }
            activeDays += setup.entry.day
            tradesByDay[setup.entry.day] = dayTradeCount + 1
            trades +=
                VolumeFlowAggressiveBacktestTrade(
                    signalAt = setup.signal.candle.openedAt,
                    openedAt = setup.entry.candle.openedAt,
                    closedAt = exit.closedAt,
                    side = setup.side,
                    exitReason = exit.reason,
                    entryPrice = setup.entryPrice,
                    stopPrice = setup.stopPrice,
                    targetPrice = setup.targetPrice,
                    exitPrice = effectiveExitPrice,
                    triggerExitPrice = exit.exitPrice,
                    riskPerUnit = setup.riskPerUnit,
                    riskFraction = config.riskFraction,
                    quantity = quantity,
                    notional = setup.entryPrice * quantity,
                    stopAtr = setup.stopAtr,
                    targetR = setup.targetR,
                    rMultipleGross = grossPnl / riskAmount,
                    rMultipleNet = pnl / riskAmount,
                    pnl = pnl,
                    fees = fees,
                    fundingPnl = fundingPnl,
                    slippageCost = slippageCost,
                    holdingMinutes =
                        (Duration.between(setup.entry.candle.openedAt, exit.closedAt).toMinutes() + 1L)
                            .coerceAtLeast(1L),
                    mfeR = exit.mfeR,
                    maeR = exit.maeR,
                    returnPct = (pnl / equityBefore) * 100.0,
                    equityAfter = equity,
                    drawdownPct = drawdownPct,
                    entryRelativeVolume = setup.entry.relativeVolume ?: 0.0,
                    entryRangePct = setup.entry.rangePct,
                    entryBodyRatio = setup.entry.bodyRatio,
                    entryCloseLocation = setup.entry.closeLocation,
                    absorptionAt = setup.absorption.candle.openedAt,
                    absorptionRelativeVolume = setup.absorption.relativeVolume ?: 0.0,
                    clusterVolumeRatio = setup.clusterVolumeRatio,
                    clusterDisplacementAtr = setup.clusterDisplacementAtr,
                    clusterRangeAtr = setup.clusterRangeAtr,
                    breakoutAt = setup.breakout.candle.openedAt,
                    breakoutRelativeVolume = setup.breakout.relativeVolume ?: 0.0,
                    breakoutBodyRatio = setup.breakout.bodyRatio,
                    breakoutDirectionalClose = aggressiveDirectionalClose(setup.side, setup.breakout.closeLocation),
                    breakoutDistanceAtr = setup.breakoutDistanceAtr,
                    signalRelativeVolume = setup.signal.relativeVolume ?: 0.0,
                    signalRangePct = setup.signal.rangePct,
                    signalBodyRatio = setup.signal.bodyRatio,
                    signalCloseLocation = setup.signal.closeLocation,
                )
            index = exit.exitM5Index + 1
        }

        val startAt = replayStartAt ?: candles.firstOrNull()?.openedAt
        val endAt = replayEndAt ?: candles.lastOrNull()?.openedAt
        val observedDays = observedDaysBetween(startAt, endAt)
        val netPnl = equity - config.initialEquity
        val positiveR = trades.filter { it.rMultipleNet > 0.0 }.sumOf { it.rMultipleNet }
        val negativeR = abs(trades.filter { it.rMultipleNet <= 0.0 }.sumOf { it.rMultipleNet })
        val compoundDailyReturnPct =
            if (equity > 0.0 && observedDays > 0) {
                ((equity / config.initialEquity).pow(1.0 / observedDays) - 1.0) * 100.0
            } else {
                -100.0
            }
        return VolumeFlowAggressiveBacktestReport(
            symbol = symbol,
            profileId = config.profileId,
            m5CandleCount = replayCandleCount,
            m1CandleCount = m1Candles.size,
            warmupCandleCount = warmupCandleCount,
            executionPathMode = config.executionPathMode,
            startAt = startAt,
            endAt = endAt,
            initialEquity = config.initialEquity,
            finalEquity = equity,
            netPnl = netPnl,
            grossPnl = totalGrossPnl,
            totalFees = totalFees,
            totalFundingPnl = totalFundingPnl,
            totalSlippageCost = totalSlippageCost,
            netReturnPct = (netPnl / config.initialEquity) * 100.0,
            compoundDailyReturnPct = compoundDailyReturnPct,
            maxDrawdownPct = maxDrawdownPct,
            tradeCount = trades.size,
            activeDays = activeDays.size,
            observedDays = observedDays,
            activeDayCoveragePct = if (observedDays == 0) 0.0 else (activeDays.size.toDouble() / observedDays) * 100.0,
            skippedSignalCount = skippedSignalCount,
            skippedDataGapCount = skippedDataGapCount,
            liquidationCount = liquidationCount,
            wins = wins,
            losses = losses,
            winRatePct = if (trades.isEmpty()) 0.0 else (wins.toDouble() / trades.size) * 100.0,
            profitFactor =
                when {
                    grossLoss > 0.0 -> grossProfit / grossLoss
                    grossProfit > 0.0 -> 999.0
                    else -> null
                },
            rProfitFactor =
                when {
                    negativeR > 0.0 -> positiveR / negativeR
                    positiveR > 0.0 -> 999.0
                    else -> null
                },
            averageGrossR = trades.map { it.rMultipleGross }.averageOrZero(),
            averageNetR = trades.map { it.rMultipleNet }.averageOrZero(),
            averageCostR = trades.map { it.rMultipleGross - it.rMultipleNet }.averageOrZero(),
            performanceBySide = trades.toPerformanceSlices { it.side.name },
            performanceByExitReason = trades.toPerformanceSlices { it.exitReason.name },
            performanceBySignalHourUtc =
                trades.toPerformanceSlices { trade ->
                    val hour = trade.signalAt.atZone(ZoneOffset.UTC).hour
                    hour.toString().padStart(2, '0')
                },
            performanceByAbsorptionRelativeVolume =
                trades.toPerformanceSlices { it.absorptionRelativeVolume.relativeVolumeBand() },
            trades = trades,
        )
    }

    private fun calculateQuantity(
        equity: Double,
        setup: AggressiveSetup,
        config: VolumeFlowAggressiveBacktestConfig,
    ): Double? {
        val sizing =
            ExecutionTradePlanCalculator.calculateSizing(
                entryPrice = BigDecimal.valueOf(setup.plannedEntryPrice),
                riskPerUnit = BigDecimal.valueOf(setup.riskPerUnit),
                intendedRisk = BigDecimal.valueOf(equity * config.riskFraction),
                accountEquity = BigDecimal.valueOf(equity),
                constraints =
                    ExecutionSizingConstraints(
                        quantityStep = config.quantityStep?.let(BigDecimal::valueOf),
                        minQuantity = config.minQuantity?.let(BigDecimal::valueOf),
                        maxQuantity = config.maxQuantity?.let(BigDecimal::valueOf),
                        maxNotional = config.maxNotional?.let(BigDecimal::valueOf),
                        leverage = config.leverage?.let(BigDecimal::valueOf),
                    ),
            ) ?: return null
        return sizing.quantity.toDouble()
    }

    private fun findSetup(
        candles: List<AggressiveCandle>,
        index: Int,
        replayEndIndex: Int,
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

        val latestSignal = minOf(index + config.entryLookaheadCandles, replayEndIndex - 2)
        for (breakoutIndex in index + 1..latestSignal) {
            val breakout = candles[breakoutIndex]
            if (!config.sessionHoursUtc.contains(breakout.hourUtc)) continue
            if (
                config.sideMode != VolumeFlowSideMode.SHORT_ONLY &&
                aggressiveBreakoutAllowed(
                    side = Side.BUY,
                    close = breakout.candle.close.toDouble(),
                    boundary = cluster.high,
                    atr = atr,
                    relativeVolume = breakout.relativeVolume,
                    bodyRatio = breakout.bodyRatio,
                    closeLocation = breakout.closeLocation,
                    config = config,
                )
            ) {
                val signalIndex =
                    confirmationIndex(
                        candles,
                        Side.BUY,
                        breakoutIndex,
                        cluster.high,
                        atr,
                        replayEndIndex,
                        config,
                    ) ?: continue
                return buildSetup(
                    candles,
                    Side.BUY,
                    signalIndex,
                    breakout,
                    candle,
                    cluster,
                    clusterVolumeRatio,
                    displacementAtr,
                    rangeAtr,
                    config,
                )
            }
            if (
                config.sideMode != VolumeFlowSideMode.LONG_ONLY &&
                aggressiveBreakoutAllowed(
                    side = Side.SELL,
                    close = breakout.candle.close.toDouble(),
                    boundary = cluster.low,
                    atr = atr,
                    relativeVolume = breakout.relativeVolume,
                    bodyRatio = breakout.bodyRatio,
                    closeLocation = breakout.closeLocation,
                    config = config,
                )
            ) {
                val signalIndex =
                    confirmationIndex(
                        candles,
                        Side.SELL,
                        breakoutIndex,
                        cluster.low,
                        atr,
                        replayEndIndex,
                        config,
                    ) ?: continue
                return buildSetup(
                    candles,
                    Side.SELL,
                    signalIndex,
                    breakout,
                    candle,
                    cluster,
                    clusterVolumeRatio,
                    displacementAtr,
                    rangeAtr,
                    config,
                )
            }
        }
        return null
    }

    private fun confirmationIndex(
        candles: List<AggressiveCandle>,
        side: Side,
        breakoutIndex: Int,
        boundary: Double,
        atr: Double,
        replayEndIndex: Int,
        config: VolumeFlowAggressiveBacktestConfig,
    ): Int? {
        if (config.entryMode == VolumeFlowAggressiveEntryMode.BREAKOUT_NEXT_OPEN) return breakoutIndex
        val lastRetestIndex = minOf(breakoutIndex + config.retestLookaheadCandles, replayEndIndex - 2)
        if (breakoutIndex + 1 > lastRetestIndex) return null
        for (index in breakoutIndex + 1..lastRetestIndex) {
            val candidate = candles[index]
            if (!config.sessionHoursUtc.contains(candidate.hourUtc)) continue
            if (
                aggressiveRetestConfirmed(
                    side = side,
                    open = candidate.candle.open.toDouble(),
                    high = candidate.candle.high.toDouble(),
                    low = candidate.candle.low.toDouble(),
                    close = candidate.candle.close.toDouble(),
                    closeLocation = candidate.closeLocation,
                    boundary = boundary,
                    atr = atr,
                    config = config,
                )
            ) {
                return index
            }
        }
        return null
    }

    private fun buildSetup(
        candles: List<AggressiveCandle>,
        side: Side,
        signalIndex: Int,
        breakout: AggressiveCandle,
        absorption: AggressiveCandle,
        cluster: AggressiveCluster,
        clusterVolumeRatio: Double,
        clusterDisplacementAtr: Double,
        clusterRangeAtr: Double,
        config: VolumeFlowAggressiveBacktestConfig,
    ): AggressiveSetup? {
        if (!sideAllowedForRegime(candles, side, signalIndex, config)) return null
        val signal = candles[signalIndex]
        val entryIndex = signalIndex + 1
        val entry = candles[entryIndex]
        if (entry.candle.openedAt != signal.candle.openedAt.plusSeconds(M5_CANDLE_SECONDS)) return null
        val entryOpen = entry.candle.open.toDouble()
        val entryPrice =
            when (side) {
                Side.BUY -> entryOpen * (1.0 + config.slippageRate)
                Side.SELL -> entryOpen * (1.0 - config.slippageRate)
            }
        val signalReference = signal.candle.close.toDouble()
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
                Side.BUY -> minOf(structuralStop, signalReference - atrStop)
                Side.SELL -> maxOf(structuralStop, signalReference + atrStop)
            }
        val riskPerUnit = abs(signalReference - stopPrice)
        val entryRiskPct = riskPerUnit / signalReference
        if (riskPerUnit <= 0.0 || entryRiskPct < MIN_ENTRY_RISK_PCT || entryRiskPct > MAX_ENTRY_RISK_PCT) return null
        val targetR = targetRFor(candles, signalIndex, config)
        val targetPrice =
            ExecutionTradePlanCalculator
                .calculateTakeProfit(
                    side = side,
                    entryPrice = BigDecimal.valueOf(signalReference),
                    riskPerUnit = BigDecimal.valueOf(riskPerUnit),
                    expectedR = BigDecimal.valueOf(targetR),
                ).toDouble()
        val validFillGeometry =
            when (side) {
                Side.BUY -> stopPrice < entryPrice && entryPrice < targetPrice
                Side.SELL -> targetPrice < entryPrice && entryPrice < stopPrice
            }
        if (!validFillGeometry) return null
        val targetStopRejection =
            ExecutionTradePlanCalculator.targetStopRejection(
                side = side,
                entryPrice = BigDecimal.valueOf(signalReference),
                takeProfit = BigDecimal.valueOf(targetPrice),
                stopLoss = BigDecimal.valueOf(stopPrice),
                feeRate = BigDecimal.valueOf(config.feeRate),
                slippageBufferRate = BigDecimal.valueOf(config.slippageRate + config.exitSlippageRate),
            )
        if (targetStopRejection != null) return null
        val leverageStopRejection =
            ExecutionTradePlanCalculator.leverageStopRejection(
                side = side,
                entryPrice = BigDecimal.valueOf(entryPrice),
                stopLoss = BigDecimal.valueOf(stopPrice),
                leverage = config.leverage?.let(BigDecimal::valueOf),
                liquidationBufferPct = BigDecimal.valueOf(config.liquidationBufferPct),
            )
        if (leverageStopRejection != null) return null
        return AggressiveSetup(
            side = side,
            absorption = absorption,
            breakout = breakout,
            signal = signal,
            entryIndex = entryIndex,
            entry = entry,
            plannedEntryPrice = signalReference,
            rawEntryPrice = entryOpen,
            entryPrice = entryPrice,
            stopPrice = stopPrice,
            targetPrice = targetPrice,
            riskPerUnit = riskPerUnit,
            stopAtr = stopAtr,
            targetR = targetR,
            clusterVolumeRatio = clusterVolumeRatio,
            clusterDisplacementAtr = clusterDisplacementAtr,
            clusterRangeAtr = clusterRangeAtr,
            breakoutDistanceAtr =
                when (side) {
                    Side.BUY -> (breakout.candle.close.toDouble() - cluster.high) / (absorption.atr ?: 1.0)
                    Side.SELL -> (cluster.low - breakout.candle.close.toDouble()) / (absorption.atr ?: 1.0)
                },
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

    private fun simulateM5Exit(
        candles: List<AggressiveCandle>,
        setup: AggressiveSetup,
        replayEndIndex: Int,
        config: VolumeFlowAggressiveBacktestConfig,
    ): AggressiveExit {
        val end = minOf(setup.entryIndex + config.maxHoldCandles - 1, replayEndIndex - 1)
        val liquidationPrice = approximateLiquidationPrice(setup, config)
        var mfeR = 0.0
        var maeR = 0.0
        var activeStop = AggressiveManagedStop(setup.stopPrice, VolumeFlowExitReason.STOP)
        for (index in setup.entryIndex..end) {
            val candle = candles[index].candle
            val excursion = candle.excursionR(setup)
            mfeR = maxOf(mfeR, excursion.first)
            maeR = maxOf(maeR, excursion.second)
            val protectiveExit = candle.protectiveExit(setup, liquidationPrice, activeStop)
            if (protectiveExit != null) {
                return AggressiveExit(index, candle.openedAt, protectiveExit.price, protectiveExit.reason, mfeR, maeR)
            }
            if (setup.side == Side.BUY) {
                if (candle.high.toDouble() >= setup.targetPrice) {
                    return AggressiveExit(index, candle.openedAt, setup.targetPrice, VolumeFlowExitReason.TARGET, mfeR, maeR)
                }
            } else {
                if (candle.low.toDouble() <= setup.targetPrice) {
                    return AggressiveExit(index, candle.openedAt, setup.targetPrice, VolumeFlowExitReason.TARGET, mfeR, maeR)
                }
            }
            activeStop = managedStopAfterClose(candle, setup, config, activeStop)
        }
        return AggressiveExit(
            end,
            candles[end].candle.openedAt,
            candles[end].candle.close.toDouble(),
            VolumeFlowExitReason.TIME,
            mfeR,
            maeR,
        )
    }

    private fun simulateM1Exit(
        candles: List<Candle>,
        setup: AggressiveSetup,
        config: VolumeFlowAggressiveBacktestConfig,
    ): AggressiveExit? {
        val entryAt = setup.entry.candle.openedAt
        val startIndex = candles.binarySearchBy(entryAt) { it.openedAt }
        if (startIndex < 0) return null
        val liquidationPrice = approximateLiquidationPrice(setup, config)
        val pathMinutes = config.maxHoldCandles * 5
        var lastCandle: Candle? = null
        var mfeR = 0.0
        var maeR = 0.0
        var activeStop = AggressiveManagedStop(setup.stopPrice, VolumeFlowExitReason.STOP)
        repeat(pathMinutes) { minuteOffset ->
            val candle = candles.getOrNull(startIndex + minuteOffset) ?: return null
            val expectedAt = entryAt.plusSeconds(minuteOffset * 60L)
            if (candle.timeframe != Timeframe.M1 || candle.openedAt != expectedAt) return null
            lastCandle = candle
            val excursion = candle.excursionR(setup)
            mfeR = maxOf(mfeR, excursion.first)
            maeR = maxOf(maeR, excursion.second)
            val exitM5Index = setup.entryIndex + (minuteOffset / 5)
            val protectiveExit = candle.protectiveExit(setup, liquidationPrice, activeStop)
            if (protectiveExit != null) {
                return AggressiveExit(
                    exitM5Index,
                    candle.openedAt,
                    protectiveExit.price,
                    protectiveExit.reason,
                    mfeR,
                    maeR,
                )
            }
            if (setup.side == Side.BUY) {
                if (candle.high.toDouble() >= setup.targetPrice) {
                    return AggressiveExit(exitM5Index, candle.openedAt, setup.targetPrice, VolumeFlowExitReason.TARGET, mfeR, maeR)
                }
            } else {
                if (candle.low.toDouble() <= setup.targetPrice) {
                    return AggressiveExit(exitM5Index, candle.openedAt, setup.targetPrice, VolumeFlowExitReason.TARGET, mfeR, maeR)
                }
            }
            activeStop = managedStopAfterClose(candle, setup, config, activeStop)
        }
        val timeExit = lastCandle ?: return null
        return AggressiveExit(
            exitM5Index = setup.entryIndex + config.maxHoldCandles - 1,
            closedAt = timeExit.openedAt,
            exitPrice = timeExit.close.toDouble(),
            reason = VolumeFlowExitReason.TIME,
            mfeR = mfeR,
            maeR = maeR,
        )
    }

    private fun approximateLiquidationPrice(
        setup: AggressiveSetup,
        config: VolumeFlowAggressiveBacktestConfig,
    ): Double? {
        val leverage = config.leverage ?: return null
        val liquidationDistancePct = (100.0 / leverage) - config.liquidationBufferPct
        if (liquidationDistancePct <= 0.0) return setup.entryPrice
        val liquidationDistance = liquidationDistancePct / 100.0
        return when (setup.side) {
            Side.BUY -> setup.entryPrice * (1.0 - liquidationDistance)
            Side.SELL -> setup.entryPrice * (1.0 + liquidationDistance)
        }
    }
}

private data class LoadedAggressiveCandles(
    val candles: List<Candle>,
    val replayCandleCount: Int,
    val warmupCandleCount: Int,
)

private fun List<Candle>.enriched(config: VolumeFlowAggressiveBacktestConfig): List<AggressiveCandle> {
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
                day =
                    candle
                        .openedAt
                        .atZone(ZoneOffset.UTC)
                        .toLocalDate()
                        .toString(),
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

private fun observedDaysBetween(
    startAt: Instant?,
    endAt: Instant?,
): Int {
    if (startAt == null || endAt == null) return 1
    val days = Duration.between(startAt, endAt).toMillis().toDouble() / 86_400_000.0
    return maxOf(1, days.roundToInt())
}

private data class AggressiveCandle(
    val candle: Candle,
    val index: Int,
    val hourUtc: Int,
    val day: String,
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
    val absorption: AggressiveCandle,
    val breakout: AggressiveCandle,
    val signal: AggressiveCandle,
    val entryIndex: Int,
    val entry: AggressiveCandle,
    val plannedEntryPrice: Double,
    val rawEntryPrice: Double,
    val entryPrice: Double,
    val stopPrice: Double,
    val targetPrice: Double,
    val riskPerUnit: Double,
    val stopAtr: Double,
    val targetR: Double,
    val clusterVolumeRatio: Double,
    val clusterDisplacementAtr: Double,
    val clusterRangeAtr: Double,
    val breakoutDistanceAtr: Double,
)

private data class AggressiveExit(
    val exitM5Index: Int,
    val closedAt: Instant,
    val exitPrice: Double,
    val reason: VolumeFlowExitReason,
    val mfeR: Double = 0.0,
    val maeR: Double = 0.0,
)

private data class AggressiveProtectiveExit(
    val price: Double,
    val reason: VolumeFlowExitReason,
)

private data class AggressiveManagedStop(
    val price: Double,
    val reason: VolumeFlowExitReason,
)

private data class AggressivePriorStats(
    val returnPct: Double,
    val avgVolume: Double,
    val avgRangePct: Double,
)

private fun Double.withExitSlippage(
    side: Side,
    rate: Double,
): Double =
    when (side) {
        Side.BUY -> this * (1.0 - rate)
        Side.SELL -> this * (1.0 + rate)
    }

private fun fundingPnl(
    setup: AggressiveSetup,
    exit: AggressiveExit,
    quantity: Double,
    fundingRatePer8h: Double,
): Double {
    if (fundingRatePer8h == 0.0) return 0.0
    val heldSeconds = Duration.between(setup.entry.candle.openedAt, exit.closedAt).seconds.coerceAtLeast(0L)
    val periods = heldSeconds.toDouble() / Duration.ofHours(8).seconds
    val fundingMagnitude = setup.entryPrice * quantity * fundingRatePer8h * periods
    return when (setup.side) {
        Side.BUY -> -fundingMagnitude
        Side.SELL -> fundingMagnitude
    }
}

private fun Candle.excursionR(setup: AggressiveSetup): Pair<Double, Double> {
    if (setup.riskPerUnit <= 0.0) return 0.0 to 0.0
    val candleHigh = high.toDouble()
    val candleLow = low.toDouble()
    return when (setup.side) {
        Side.BUY ->
            maxOf(0.0, (candleHigh - setup.entryPrice) / setup.riskPerUnit) to
                maxOf(0.0, (setup.entryPrice - candleLow) / setup.riskPerUnit)
        Side.SELL ->
            maxOf(0.0, (setup.entryPrice - candleLow) / setup.riskPerUnit) to
                maxOf(0.0, (candleHigh - setup.entryPrice) / setup.riskPerUnit)
    }
}

private fun Candle.protectiveExit(
    setup: AggressiveSetup,
    liquidationPrice: Double?,
    activeStop: AggressiveManagedStop,
): AggressiveProtectiveExit? {
    val candleOpen = open.toDouble()
    return when (setup.side) {
        Side.BUY -> {
            when {
                liquidationPrice != null && candleOpen <= liquidationPrice ->
                    AggressiveProtectiveExit(candleOpen, VolumeFlowExitReason.LIQUIDATION)
                candleOpen <= activeStop.price -> AggressiveProtectiveExit(candleOpen, activeStop.reason)
                liquidationPrice != null && liquidationPrice >= activeStop.price && low.toDouble() <= liquidationPrice ->
                    AggressiveProtectiveExit(liquidationPrice, VolumeFlowExitReason.LIQUIDATION)
                low.toDouble() <= activeStop.price -> AggressiveProtectiveExit(activeStop.price, activeStop.reason)
                else -> null
            }
        }
        Side.SELL -> {
            when {
                liquidationPrice != null && candleOpen >= liquidationPrice ->
                    AggressiveProtectiveExit(candleOpen, VolumeFlowExitReason.LIQUIDATION)
                candleOpen >= activeStop.price -> AggressiveProtectiveExit(candleOpen, activeStop.reason)
                liquidationPrice != null && liquidationPrice <= activeStop.price && high.toDouble() >= liquidationPrice ->
                    AggressiveProtectiveExit(liquidationPrice, VolumeFlowExitReason.LIQUIDATION)
                high.toDouble() >= activeStop.price -> AggressiveProtectiveExit(activeStop.price, activeStop.reason)
                else -> null
            }
        }
    }
}

private fun managedStopAfterClose(
    candle: Candle,
    setup: AggressiveSetup,
    config: VolumeFlowAggressiveBacktestConfig,
    current: AggressiveManagedStop,
): AggressiveManagedStop {
    val close = candle.close.toDouble()
    val favorableCloseR =
        when (setup.side) {
            Side.BUY -> (close - setup.entryPrice) / setup.riskPerUnit
            Side.SELL -> (setup.entryPrice - close) / setup.riskPerUnit
        }
    var next = current
    config.breakEvenTriggerR?.let { triggerR ->
        if (favorableCloseR >= triggerR) {
            val candidate =
                when (setup.side) {
                    Side.BUY -> setup.entryPrice + (setup.riskPerUnit * config.breakEvenLockR)
                    Side.SELL -> setup.entryPrice - (setup.riskPerUnit * config.breakEvenLockR)
                }
            next = next.tighter(candidate, VolumeFlowExitReason.BREAKEVEN_STOP, setup.side)
        }
    }
    val trailingTriggerR = config.trailingTriggerR
    val trailingDistanceR = config.trailingDistanceR
    if (trailingTriggerR != null && trailingDistanceR != null && favorableCloseR >= trailingTriggerR) {
        val candidate =
            when (setup.side) {
                Side.BUY -> close - (setup.riskPerUnit * trailingDistanceR)
                Side.SELL -> close + (setup.riskPerUnit * trailingDistanceR)
            }
        next = next.tighter(candidate, VolumeFlowExitReason.TRAILING_STOP, setup.side)
    }
    return next
}

private fun AggressiveManagedStop.tighter(
    candidate: Double,
    candidateReason: VolumeFlowExitReason,
    side: Side,
): AggressiveManagedStop =
    when (side) {
        Side.BUY -> if (candidate > price) AggressiveManagedStop(candidate, candidateReason) else this
        Side.SELL -> if (candidate < price) AggressiveManagedStop(candidate, candidateReason) else this
    }

private fun List<VolumeFlowAggressiveBacktestTrade>.toPerformanceSlices(
    keySelector: (VolumeFlowAggressiveBacktestTrade) -> String,
): List<VolumeFlowAggressivePerformanceSlice> =
    groupBy(keySelector)
        .toSortedMap()
        .map { (key, trades) ->
            val wins = trades.count { it.pnl > 0.0 }
            val grossProfit = trades.filter { it.pnl > 0.0 }.sumOf { it.pnl }
            val grossLoss = abs(trades.filter { it.pnl <= 0.0 }.sumOf { it.pnl })
            val positiveR = trades.filter { it.rMultipleNet > 0.0 }.sumOf { it.rMultipleNet }
            val negativeR = abs(trades.filter { it.rMultipleNet <= 0.0 }.sumOf { it.rMultipleNet })
            VolumeFlowAggressivePerformanceSlice(
                key = key,
                tradeCount = trades.size,
                wins = wins,
                losses = trades.size - wins,
                netPnl = trades.sumOf { it.pnl },
                winRatePct = if (trades.isEmpty()) 0.0 else wins.toDouble() / trades.size * 100.0,
                profitFactor =
                    when {
                        grossLoss > 0.0 -> grossProfit / grossLoss
                        grossProfit > 0.0 -> 999.0
                        else -> null
                    },
                rProfitFactor =
                    when {
                        negativeR > 0.0 -> positiveR / negativeR
                        positiveR > 0.0 -> 999.0
                        else -> null
                    },
                averageGrossR = trades.map { it.rMultipleGross }.average(),
                averageNetR = trades.map { it.rMultipleNet }.average(),
                averageCostR = trades.map { it.rMultipleGross - it.rMultipleNet }.average(),
                averageMfeR = trades.map { it.mfeR }.average(),
                averageMaeR = trades.map { it.maeR }.average(),
            )
        }

private fun Double.relativeVolumeBand(): String =
    when {
        this < 1.2 -> "1.0-1.2"
        this < 1.5 -> "1.2-1.5"
        this < 2.0 -> "1.5-2.0"
        else -> "2.0+"
    }

private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
