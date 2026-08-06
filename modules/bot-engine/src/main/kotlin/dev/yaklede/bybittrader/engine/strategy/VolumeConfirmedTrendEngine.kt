package dev.yaklede.bybittrader.engine.strategy

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Timeframe
import java.time.Duration
import java.time.Instant
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign

private const val H4_SECONDS = 4L * 60L * 60L
private const val M15_SECONDS = 15L * 60L
private const val ROUND_EPSILON = 1e-12

data class VolumeConfirmedTrendEmaPair(
    val fast: Int,
    val slow: Int,
) {
    init {
        require(fast >= 1 && slow > fast) { "Trend EMA periods must satisfy 1 <= fast < slow." }
    }
}

data class VolumeConfirmedTrendParameters(
    val emaVotePairs: List<VolumeConfirmedTrendEmaPair> =
        listOf(
            VolumeConfirmedTrendEmaPair(3, 72),
            VolumeConfirmedTrendEmaPair(6, 42),
            VolumeConfirmedTrendEmaPair(18, 180),
            VolumeConfirmedTrendEmaPair(42, 180),
            VolumeConfirmedTrendEmaPair(42, 540),
        ),
    val minimumMajorityVotes: Int = 3,
    val volumeMedianLookbackBars: Int = 42,
    val executionDelayBars: Int = 1,
    val warmupDecisionBars: Int = 540,
) {
    init {
        require(emaVotePairs.isNotEmpty() && emaVotePairs.size % 2 == 1) {
            "Trend EMA vote pairs must contain an odd number of entries."
        }
        require(minimumMajorityVotes > emaVotePairs.size / 2 && minimumMajorityVotes <= emaVotePairs.size) {
            "Trend minimum majority must be a strict majority."
        }
        require(volumeMedianLookbackBars >= 1) { "Trend volume median lookback must be positive." }
        require(executionDelayBars == 1) { "Frozen trend execution delay must be one H4 bar." }
        require(warmupDecisionBars >= emaVotePairs.maxOf { it.slow }) {
            "Trend warmup must cover the longest EMA."
        }
    }
}

data class VolumeConfirmedTrendExecutionContract(
    val targetExposureFraction: Double = 0.65,
    val maximumRoundedExposureFraction: Double = 0.85,
    val quantityStepBtc: Double = 0.001,
    val minimumQuantityBtc: Double = 0.001,
    val absoluteMaximumNotionalUsdt: Double? = null,
    val oneWayFeeRate: Double = 0.0006,
    val oneWaySlippageRate: Double = 0.0002,
) {
    init {
        require(targetExposureFraction > 0.0 && targetExposureFraction <= maximumRoundedExposureFraction) {
            "Trend target exposure must be positive and no greater than its rounded ceiling."
        }
        require(maximumRoundedExposureFraction in 0.0..1.0 && maximumRoundedExposureFraction < 1.0) {
            "Trend rounded exposure ceiling must be below one."
        }
        require(quantityStepBtc > 0.0 && minimumQuantityBtc > 0.0) {
            "Trend quantity step and minimum must be positive."
        }
        require(absoluteMaximumNotionalUsdt == null || absoluteMaximumNotionalUsdt > 0.0) {
            "Trend absolute maximum notional must be positive when configured."
        }
        require(oneWayFeeRate >= 0.0 && oneWaySlippageRate >= 0.0) {
            "Trend costs must not be negative."
        }
    }
}

data class VolumeConfirmedTrendBar(
    val openedAt: Instant,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
) {
    init {
        require(open > 0.0 && high > 0.0 && low > 0.0 && close > 0.0) { "Trend OHLC must be positive." }
        require(high >= max(open, close) && low <= min(open, close) && high >= low) { "Trend OHLC is invalid." }
        require(volume >= 0.0) { "Trend volume must not be negative." }
        require(openedAt.epochSecond % H4_SECONDS == 0L && openedAt.nano == 0) {
            "Trend H4 bar must open on a UTC four-hour boundary."
        }
    }
}

data class VolumeConfirmedTrendCommand(
    val side: Side,
    val decisionAt: Instant,
    val executionAt: Instant,
    val decisionIndex: Int,
    val executionIndex: Int,
    val netVotes: Int,
    val decisionVolume: Double,
    val priorVolumeMedian: Double,
)

data class VolumeConfirmedTrendEmaState(
    val fast: Double?,
    val slow: Double?,
) {
    init {
        require(fast == null || fast.isFinite()) { "Trend fast EMA must be finite when present." }
        require(slow == null || slow.isFinite()) { "Trend slow EMA must be finite when present." }
        require((fast == null) == (slow == null)) { "Trend EMA values must either both be present or both be absent." }
    }
}

data class VolumeConfirmedTrendIndicatorState(
    val processedBars: Long,
    val lastBarOpenedAt: Instant?,
    val emaStates: List<VolumeConfirmedTrendEmaState>,
    val targetSide: Side?,
    val recentVolumes: List<Double>,
) {
    init {
        require(processedBars >= 0) { "Trend processed bar count must not be negative." }
        require((processedBars == 0L) == (lastBarOpenedAt == null)) {
            "Trend last bar timestamp must match whether any bars were processed."
        }
        require(recentVolumes.all { it.isFinite() && it >= 0.0 }) {
            "Trend recent volumes must be finite and non-negative."
        }
    }
}

data class VolumeConfirmedTrendTransition(
    val side: Side,
    val decisionAt: Instant,
    val decisionOrdinal: Long,
    val netVotes: Int,
    val decisionVolume: Double,
    val priorVolumeMedian: Double,
)

class VolumeConfirmedTrendEvaluator private constructor(
    private val parameters: VolumeConfirmedTrendParameters,
    initialState: VolumeConfirmedTrendIndicatorState,
) {
    private var processedBars = initialState.processedBars
    private var lastBarOpenedAt = initialState.lastBarOpenedAt
    private val emaStates = initialState.emaStates.map { MutableEmaState(it.fast, it.slow) }.toMutableList()
    private var targetSide = initialState.targetSide
    private val recentVolumes = ArrayDeque(initialState.recentVolumes)

    init {
        require(emaStates.size == parameters.emaVotePairs.size) {
            "Trend EMA state count must match the configured vote pairs."
        }
        require(recentVolumes.size <= parameters.volumeMedianLookbackBars) {
            "Trend recent volume state exceeds its configured lookback."
        }
        require(processedBars == 0L || emaStates.all { it.fast != null && it.slow != null }) {
            "Trend EMA state must be initialized after processing bars."
        }
    }

    constructor(parameters: VolumeConfirmedTrendParameters = VolumeConfirmedTrendParameters()) :
        this(
            parameters = parameters,
            initialState =
                VolumeConfirmedTrendIndicatorState(
                    processedBars = 0,
                    lastBarOpenedAt = null,
                    emaStates = parameters.emaVotePairs.map { VolumeConfirmedTrendEmaState(null, null) },
                    targetSide = null,
                    recentVolumes = emptyList(),
                ),
        )

    fun evaluate(bar: VolumeConfirmedTrendBar): VolumeConfirmedTrendTransition? {
        lastBarOpenedAt?.let { previous ->
            require(bar.openedAt == previous.plusSeconds(H4_SECONDS)) {
                "H4 trend evidence gap before ${bar.openedAt}."
            }
        }
        val votes =
            parameters.emaVotePairs.mapIndexed { pairIndex, pair ->
                emaStates[pairIndex].vote(bar.close, pair)
            }
        processedBars += 1
        lastBarOpenedAt = bar.openedAt

        var transition: VolumeConfirmedTrendTransition? = null
        if (processedBars >= parameters.warmupDecisionBars) {
            val positiveVotes = votes.count { it > 0 }
            val negativeVotes = votes.count { it < 0 }
            val desiredSide =
                when {
                    positiveVotes >= parameters.minimumMajorityVotes -> Side.BUY
                    negativeVotes >= parameters.minimumMajorityVotes -> Side.SELL
                    else -> targetSide
                }
            if (desiredSide != null && desiredSide != targetSide && recentVolumes.size == parameters.volumeMedianLookbackBars) {
                val priorMedian = median(recentVolumes.toList())
                if (bar.volume >= priorMedian) {
                    targetSide = desiredSide
                    transition =
                        VolumeConfirmedTrendTransition(
                            side = desiredSide,
                            decisionAt = bar.openedAt.plusSeconds(H4_SECONDS),
                            decisionOrdinal = processedBars - 1,
                            netVotes = positiveVotes - negativeVotes,
                            decisionVolume = bar.volume,
                            priorVolumeMedian = priorMedian,
                        )
                }
            }
        }

        recentVolumes.addLast(bar.volume)
        while (recentVolumes.size > parameters.volumeMedianLookbackBars) {
            recentVolumes.removeFirst()
        }
        return transition
    }

    fun snapshot(): VolumeConfirmedTrendIndicatorState =
        VolumeConfirmedTrendIndicatorState(
            processedBars = processedBars,
            lastBarOpenedAt = lastBarOpenedAt,
            emaStates = emaStates.map { VolumeConfirmedTrendEmaState(it.fast, it.slow) },
            targetSide = targetSide,
            recentVolumes = recentVolumes.toList(),
        )

    companion object {
        fun restore(
            state: VolumeConfirmedTrendIndicatorState,
            parameters: VolumeConfirmedTrendParameters = VolumeConfirmedTrendParameters(),
        ): VolumeConfirmedTrendEvaluator = VolumeConfirmedTrendEvaluator(parameters, state)
    }

    private data class MutableEmaState(
        var fast: Double?,
        var slow: Double?,
    ) {
        fun vote(
            close: Double,
            pair: VolumeConfirmedTrendEmaPair,
        ): Int {
            fast = nextEma(fast, close, pair.fast)
            slow = nextEma(slow, close, pair.slow)
            return (fast!! - slow!!).sign.toInt()
        }
    }
}

object VolumeConfirmedTrendEngine {
    fun aggregateM15(candles: List<Candle>): List<VolumeConfirmedTrendBar> {
        require(candles.isNotEmpty()) { "M15 trend evidence is empty." }
        val ordered = candles.sortedBy(Candle::openedAt)
        require(ordered.all { it.timeframe == Timeframe.M15 }) { "Trend aggregation requires M15 candles." }
        ordered.zipWithNext().forEach { (previous, current) ->
            require(current.openedAt.isAfter(previous.openedAt)) { "M15 trend evidence must be strictly ordered." }
        }
        val groups = ordered.groupBy { candle -> h4Bucket(candle.openedAt) }.toSortedMap()
        val entries = groups.entries.toList()
        val bars = mutableListOf<VolumeConfirmedTrendBar>()
        entries.forEachIndexed { groupIndex, (openedAt, group) ->
            val boundaryPartial = groupIndex == 0 || groupIndex == entries.lastIndex
            if (group.size != 16) {
                if (boundaryPartial) return@forEachIndexed
                error("Incomplete internal H4 bucket at $openedAt: ${group.size} bars.")
            }
            group.forEachIndexed { index, candle ->
                val expected = openedAt.plusSeconds(index * M15_SECONDS)
                require(candle.openedAt == expected) { "Non-contiguous M15 evidence at $expected." }
            }
            bars +=
                VolumeConfirmedTrendBar(
                    openedAt = openedAt,
                    open = group.first().open.toDouble(),
                    high = group.maxOf { it.high.toDouble() },
                    low = group.minOf { it.low.toDouble() },
                    close = group.last().close.toDouble(),
                    volume = group.sumOf { it.volume.toDouble() },
                )
        }
        require(bars.isNotEmpty()) { "M15 trend evidence contains no complete H4 bars." }
        requireContiguousH4(bars)
        return bars
    }

    fun commands(
        bars: List<VolumeConfirmedTrendBar>,
        parameters: VolumeConfirmedTrendParameters = VolumeConfirmedTrendParameters(),
    ): List<VolumeConfirmedTrendCommand?> {
        require(bars.size >= parameters.warmupDecisionBars + parameters.executionDelayBars) {
            "Trend evidence is shorter than the configured warmup."
        }
        requireContiguousH4(bars)
        val evaluator = VolumeConfirmedTrendEvaluator(parameters)
        val commands = MutableList<VolumeConfirmedTrendCommand?>(bars.size) { null }
        bars.forEachIndexed { index, bar ->
            val transition = evaluator.evaluate(bar) ?: return@forEachIndexed
            val executionIndex = index + parameters.executionDelayBars
            if (executionIndex >= bars.size) return@forEachIndexed
            check(commands[executionIndex] == null) { "Trend command collision detected." }
            commands[executionIndex] =
                VolumeConfirmedTrendCommand(
                    side = transition.side,
                    decisionAt = transition.decisionAt,
                    executionAt = bars[executionIndex].openedAt,
                    decisionIndex = index,
                    executionIndex = executionIndex,
                    netVotes = transition.netVotes,
                    decisionVolume = transition.decisionVolume,
                    priorVolumeMedian = transition.priorVolumeMedian,
                )
        }
        return commands
    }

    fun quantity(
        equity: Double,
        price: Double,
        contract: VolumeConfirmedTrendExecutionContract = VolumeConfirmedTrendExecutionContract(),
    ): Double {
        require(equity > 0.0 && price > 0.0) { "Trend quantity equity and price must be positive." }
        val absoluteMaximum = contract.absoluteMaximumNotionalUsdt ?: Double.POSITIVE_INFINITY
        val targetNotional = min(equity * contract.targetExposureFraction, absoluteMaximum)
        val maximumNotional = min(equity * contract.maximumRoundedExposureFraction, absoluteMaximum)
        var quantity = floorStep(targetNotional / price, contract.quantityStepBtc)
        if (quantity + ROUND_EPSILON < contract.minimumQuantityBtc &&
            contract.minimumQuantityBtc * price <= maximumNotional + 1e-9
        ) {
            quantity = contract.minimumQuantityBtc
        }
        if (quantity + ROUND_EPSILON < contract.minimumQuantityBtc || quantity * price > maximumNotional + 1e-9) {
            return 0.0
        }
        return round12(quantity)
    }

    private fun requireContiguousH4(bars: List<VolumeConfirmedTrendBar>) {
        bars.zipWithNext().forEach { (previous, current) ->
            require(current.openedAt == previous.openedAt.plusSeconds(H4_SECONDS)) {
                "H4 trend evidence gap before ${current.openedAt}."
            }
        }
    }

    private fun h4Bucket(value: Instant): Instant = Instant.ofEpochSecond((value.epochSecond / H4_SECONDS) * H4_SECONDS)
}

data class VolumeConfirmedTrendFundingRate(
    val timestamp: Instant,
    val rate: Double,
)

data class VolumeConfirmedTrendTrade(
    val side: Side,
    val quantity: Double,
    val entryAt: Instant,
    val exitAt: Instant,
    val entryPrice: Double,
    val exitPrice: Double,
    val grossPnl: Double,
    val fundingPnl: Double,
    val fees: Double,
    val netPnl: Double,
    val reason: String,
)

data class VolumeConfirmedTrendSimulation(
    val startingEquity: Double,
    val endingCash: Double,
    val endingEquity: Double,
    val endingOpenPosition: VolumeConfirmedTrendOpenPosition?,
    val maximumConservativeIntrabarDrawdownPct: Double,
    val maximumEntryExposureFraction: Double,
    val maximumAdverseExposureFraction: Double,
    val totalFees: Double,
    val totalSlippage: Double,
    val totalFundingPnl: Double,
    val liquidationCount: Int,
    val firstActiveAt: Instant?,
    val evaluationEndAt: Instant,
    val trades: List<VolumeConfirmedTrendTrade>,
) {
    val netReturnPct: Double = ((endingEquity / startingEquity) - 1.0) * 100.0
    val compoundDailyReturnPct: Double =
        firstActiveAt?.let { start ->
            val days = Duration.between(start, evaluationEndAt).seconds / 86_400.0
            if (days <= 0.0 || endingEquity <= 0.0) -100.0 else ((endingEquity / startingEquity).pow(1.0 / days) - 1.0) * 100.0
        } ?: 0.0
}

object VolumeConfirmedTrendSimulator {
    fun run(
        bars: List<VolumeConfirmedTrendBar>,
        fundingRates: List<VolumeConfirmedTrendFundingRate>,
        commands: List<VolumeConfirmedTrendCommand?>,
        startingEquity: Double,
        costMultiplier: Double,
        contract: VolumeConfirmedTrendExecutionContract = VolumeConfirmedTrendExecutionContract(),
        closeAtEvidenceEnd: Boolean = true,
    ): VolumeConfirmedTrendSimulation {
        require(bars.isNotEmpty() && commands.size == bars.size) { "Trend simulation evidence and commands must align." }
        require(startingEquity > 0.0 && costMultiplier >= 1.0) { "Trend simulation capital and cost multiplier are invalid." }
        val fundingByTimestamp = fundingRates.associate { funding -> funding.timestamp to funding.rate }
        var cash = startingEquity
        var position: VolumeConfirmedTrendOpenPosition? = null
        var peakEquity = startingEquity
        var maximumDrawdown = 0.0
        var maximumEntryExposure = 0.0
        var maximumAdverseExposure = 0.0
        var totalFees = 0.0
        var totalSlippage = 0.0
        var totalFunding = 0.0
        var liquidationCount = 0
        var firstActiveAt: Instant? = null
        val trades = mutableListOf<VolumeConfirmedTrendTrade>()

        fun markEquity(price: Double): Double = VolumeConfirmedTrendExecutionModel.markEquity(cash, position, price)

        fun closePosition(
            referencePrice: Double,
            at: Instant,
            reason: String,
        ) {
            val current = position ?: return
            val execution =
                VolumeConfirmedTrendExecutionModel.close(
                    cash = cash,
                    position = current,
                    referencePrice = referencePrice,
                    contract = contract,
                    costMultiplier = costMultiplier,
                )
            cash = execution.cashAfter
            totalFees += execution.fee
            totalSlippage += execution.slippage
            trades +=
                VolumeConfirmedTrendTrade(
                    side = current.side,
                    quantity = current.quantity,
                    entryAt = current.entryAt,
                    exitAt = at,
                    entryPrice = current.entryPrice,
                    exitPrice = execution.fillPrice,
                    grossPnl = execution.grossPnl,
                    fundingPnl = current.fundingPnl,
                    fees = current.entryFee + execution.fee,
                    netPnl = execution.netPnl,
                    reason = reason,
                )
            position = null
        }

        bars.forEachIndexed { index, bar ->
            val fundingRate = fundingByTimestamp[bar.openedAt] ?: 0.0
            position?.let { current ->
                if (fundingRate != 0.0) {
                    val execution =
                        VolumeConfirmedTrendExecutionModel.applyFunding(
                            cash = cash,
                            position = current,
                            settlementPrice = bar.open,
                            fundingRate = fundingRate,
                        )
                    cash = execution.cashAfter
                    position = execution.position
                    totalFunding += execution.fundingPnl
                }
            }
            val command = commands[index]
            if (command != null && command.side != position?.side) {
                closePosition(bar.open, bar.openedAt, "OPPOSITE_VOLUME_CONFIRMED_TREND")
                val equityBeforeEntry = cash
                val execution =
                    VolumeConfirmedTrendExecutionModel.open(
                        cash = equityBeforeEntry,
                        side = command.side,
                        referencePrice = bar.open,
                        at = bar.openedAt,
                        contract = contract,
                        costMultiplier = costMultiplier,
                    )
                if (execution != null) {
                    maximumEntryExposure = max(maximumEntryExposure, execution.exposureFraction)
                    cash = execution.cashAfter
                    totalFees += execution.fee
                    totalSlippage += execution.slippage
                    position = execution.position
                    if (firstActiveAt == null) firstActiveAt = bar.openedAt
                }
            }
            position?.let { current ->
                val risk = VolumeConfirmedTrendExecutionModel.observeIntrabar(cash, current, bar, peakEquity)
                peakEquity = risk.peakEquity
                if (risk.liquidationObserved) liquidationCount += 1
                maximumAdverseExposure = max(maximumAdverseExposure, risk.adverseExposureFraction)
                maximumDrawdown = max(maximumDrawdown, risk.drawdownPct)
            }
            val closeEquity = markEquity(bar.close)
            peakEquity = max(peakEquity, closeEquity)
        }
        val finalBar = bars.last()
        if (closeAtEvidenceEnd) {
            closePosition(finalBar.close, finalBar.openedAt.plusSeconds(H4_SECONDS), "EVIDENCE_END")
        }
        val endingEquity = markEquity(finalBar.close)
        return VolumeConfirmedTrendSimulation(
            startingEquity = startingEquity,
            endingCash = cash,
            endingEquity = endingEquity,
            endingOpenPosition = position,
            maximumConservativeIntrabarDrawdownPct = maximumDrawdown,
            maximumEntryExposureFraction = maximumEntryExposure,
            maximumAdverseExposureFraction = maximumAdverseExposure,
            totalFees = totalFees,
            totalSlippage = totalSlippage,
            totalFundingPnl = totalFunding,
            liquidationCount = liquidationCount,
            firstActiveAt = firstActiveAt,
            evaluationEndAt = finalBar.openedAt.plusSeconds(H4_SECONDS),
            trades = trades,
        )
    }
}

private fun nextEma(
    previous: Double?,
    value: Double,
    period: Int,
): Double {
    if (previous == null) return value
    val alpha = 2.0 / (period + 1.0)
    return alpha * value + (1.0 - alpha) * previous
}

private fun median(values: List<Double>): Double {
    require(values.isNotEmpty()) { "Median requires at least one value." }
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
}

private fun floorStep(
    value: Double,
    step: Double,
): Double = floor((value + ROUND_EPSILON) / step) * step

private fun round12(value: Double): Double = round((value + Math.ulp(1.0)) * 1e12) / 1e12
