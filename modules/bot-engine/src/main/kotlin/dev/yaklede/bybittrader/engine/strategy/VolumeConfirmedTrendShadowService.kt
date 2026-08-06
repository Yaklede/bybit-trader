package dev.yaklede.bybittrader.engine.strategy

import dev.yaklede.bybittrader.domain.ResearchCandleLimits
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.market.MarketCandleStore
import dev.yaklede.bybittrader.engine.market.MarketTicker
import dev.yaklede.bybittrader.engine.market.flow.FlowMarketDataStore
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.max

private const val SHADOW_H4_SECONDS = 4L * 60L * 60L
private const val SHADOW_M15_SECONDS = 15L * 60L
private const val SHADOW_FUNDING_SECONDS = 8L * 60L * 60L

data class VolumeConfirmedTrendBootstrap(
    val protocolId: String,
    val candidateId: String,
    val protocolSha256: String,
    val sourceFeatureSha256: String,
    val sourceH4BarCount: Int,
    val indicatorState: VolumeConfirmedTrendIndicatorState,
) {
    init {
        require(protocolId.isNotBlank() && candidateId.isNotBlank()) { "Trend bootstrap identity must not be blank." }
        require(protocolSha256.isSha256() && sourceFeatureSha256.isSha256()) {
            "Trend bootstrap hashes must be lowercase SHA-256 values."
        }
        require(sourceH4BarCount > 0 && indicatorState.processedBars == sourceH4BarCount.toLong()) {
            "Trend bootstrap source count must match the indicator state."
        }
    }
}

data class VolumeConfirmedTrendShadowConfig(
    val symbol: Symbol,
    val bootstrap: VolumeConfirmedTrendBootstrap,
    val initialEquity: Double = 660.0,
    val parameters: VolumeConfirmedTrendParameters = VolumeConfirmedTrendParameters(),
    val executionContract: VolumeConfirmedTrendExecutionContract = VolumeConfirmedTrendExecutionContract(),
    val maximumObservationDelay: Duration = Duration.ofMinutes(20),
) {
    init {
        require(symbol.value == "BTCUSDT") { "The frozen trend shadow supports BTCUSDT only." }
        require(initialEquity > 0.0 && initialEquity.isFinite()) { "Trend shadow initial equity must be positive." }
        require(!maximumObservationDelay.isNegative && !maximumObservationDelay.isZero) {
            "Trend shadow maximum observation delay must be positive."
        }
        require(bootstrap.indicatorState.emaStates.size == parameters.emaVotePairs.size) {
            "Trend bootstrap EMA state does not match the configured strategy."
        }
    }
}

enum class VolumeConfirmedTrendShadowStatus {
    BOOTSTRAPPING,
    OBSERVING,
}

typealias VolumeConfirmedTrendShadowPosition = VolumeConfirmedTrendOpenPosition

data class VolumeConfirmedTrendShadowState(
    val protocolId: String,
    val candidateId: String,
    val protocolSha256: String,
    val symbol: Symbol,
    val sessionId: String,
    val status: VolumeConfirmedTrendShadowStatus,
    val sessionStartedAt: Instant?,
    val indicatorState: VolumeConfirmedTrendIndicatorState,
    val lastAppliedFundingAt: Instant,
    val lastObservedAt: Instant?,
    val position: VolumeConfirmedTrendShadowPosition?,
    val sessionStartingEquity: Double,
    val cash: Double,
    val equity: Double,
    val peakEquity: Double,
    val maximumDrawdownPct: Double,
    val totalFees: Double,
    val totalSlippage: Double,
    val totalFundingPnl: Double,
    val closedTrades: Int,
    val executedTransitions: Int,
    val invalidatedSessionCount: Int,
    val updatedAt: Instant,
    val maximumEntryExposureFraction: Double = 0.0,
    val maximumAdverseExposureFraction: Double = 0.0,
    val liquidationCount: Int = 0,
) {
    init {
        require(sessionId.isNotBlank()) { "Trend shadow session ID must not be blank." }
        require(sessionStartingEquity > 0.0 && cash.isFinite() && equity.isFinite() && peakEquity > 0.0) {
            "Trend shadow account state is invalid."
        }
        require(maximumDrawdownPct >= 0.0 && totalFees >= 0.0 && totalSlippage >= 0.0) {
            "Trend shadow cost and drawdown state must not be negative."
        }
        require(closedTrades >= 0 && executedTransitions >= 0 && invalidatedSessionCount >= 0) {
            "Trend shadow counters must not be negative."
        }
        require(maximumEntryExposureFraction >= 0.0 && maximumAdverseExposureFraction >= 0.0 && liquidationCount >= 0) {
            "Trend shadow exposure and liquidation state must not be negative."
        }
    }
}

enum class VolumeConfirmedTrendShadowEventType {
    SESSION_STARTED,
    SESSION_INVALIDATED,
    H4_EVALUATED,
    FUNDING_APPLIED,
    POSITION_OPENED,
    POSITION_CLOSED,
    MINIMUM_QUANTITY_SKIPPED,
}

data class VolumeConfirmedTrendShadowEvent(
    val eventId: String,
    val sessionId: String,
    val protocolId: String,
    val protocolSha256: String,
    val symbol: Symbol,
    val type: VolumeConfirmedTrendShadowEventType,
    val eventAt: Instant,
    val observedAt: Instant,
    val h4OpenedAt: Instant?,
    val side: Side?,
    val referencePrice: Double?,
    val fillPrice: Double?,
    val quantity: Double?,
    val fee: Double,
    val slippage: Double,
    val fundingPnl: Double,
    val grossPnl: Double,
    val netPnl: Double,
    val cash: Double,
    val equity: Double,
    val reason: String,
)

interface VolumeConfirmedTrendShadowStore {
    suspend fun trendShadowState(
        protocolId: String,
        symbol: Symbol,
    ): VolumeConfirmedTrendShadowState?

    suspend fun commitTrendShadow(
        state: VolumeConfirmedTrendShadowState,
        events: List<VolumeConfirmedTrendShadowEvent>,
    )

    suspend fun trendShadowEvents(
        sessionId: String,
        limit: Int,
    ): List<VolumeConfirmedTrendShadowEvent>

    suspend fun trendShadowEvents(
        protocolId: String,
        symbol: Symbol,
        limit: Int,
    ): List<VolumeConfirmedTrendShadowEvent> {
        val state = trendShadowState(protocolId, symbol) ?: return emptyList()
        return trendShadowEvents(state.sessionId, limit)
    }
}

data class VolumeConfirmedTrendShadowReport(
    val protocolId: String,
    val candidateId: String,
    val protocolSha256: String,
    val symbol: Symbol,
    val state: VolumeConfirmedTrendShadowState?,
    val recentEvents: List<VolumeConfirmedTrendShadowEvent>,
)

enum class VolumeConfirmedTrendShadowEvaluationStatus {
    BOOTSTRAPPED,
    NO_NEW_H4,
    EVALUATED,
    SESSION_RESET,
}

data class VolumeConfirmedTrendShadowEvaluationResult(
    val status: VolumeConfirmedTrendShadowEvaluationStatus,
    val evaluatedH4Bars: Int,
    val emittedEvents: Int,
    val state: VolumeConfirmedTrendShadowState,
)

class VolumeConfirmedTrendShadowDataException(
    message: String,
) : IllegalStateException(message)

class VolumeConfirmedTrendShadowService(
    private val candleStore: MarketCandleStore,
    private val flowStore: FlowMarketDataStore,
    private val shadowStore: VolumeConfirmedTrendShadowStore,
    private val config: VolumeConfirmedTrendShadowConfig,
    private val sessionIdFactory: () -> String = { "trend-shadow-${UUID.randomUUID()}" },
) {
    suspend fun nextRequiredM15OpenedAt(): Instant {
        val state = shadowStore.trendShadowState(config.bootstrap.protocolId, config.symbol)
        return (state?.indicatorState ?: config.bootstrap.indicatorState)
            .lastBarOpenedAt!!
            .plusSeconds(SHADOW_H4_SECONDS)
    }

    suspend fun nextFundingSyncAt(): Instant {
        val state = shadowStore.trendShadowState(config.bootstrap.protocolId, config.symbol)
        return state?.lastAppliedFundingAt
            ?: config.bootstrap.indicatorState.lastBarOpenedAt!!
                .plusSeconds(SHADOW_H4_SECONDS)
    }

    suspend fun state(): VolumeConfirmedTrendShadowState? = shadowStore.trendShadowState(config.bootstrap.protocolId, config.symbol)

    suspend fun report(limit: Int): VolumeConfirmedTrendShadowReport {
        require(limit in 1..100_000) { "Trend shadow report event limit must be between 1 and 100000." }
        return VolumeConfirmedTrendShadowReport(
            protocolId = config.bootstrap.protocolId,
            candidateId = config.bootstrap.candidateId,
            protocolSha256 = config.bootstrap.protocolSha256,
            symbol = config.symbol,
            state = shadowStore.trendShadowState(config.bootstrap.protocolId, config.symbol),
            recentEvents = shadowStore.trendShadowEvents(config.bootstrap.protocolId, config.symbol, limit),
        )
    }

    suspend fun evaluate(ticker: MarketTicker): VolumeConfirmedTrendShadowEvaluationResult {
        require(ticker.symbol == config.symbol) { "Trend shadow ticker symbol does not match its configuration." }
        require(ticker.lastPrice.signum() > 0) { "Trend shadow ticker price must be positive." }
        val observedAt = ticker.capturedAt
        val referencePrice = ticker.lastPrice.toDouble()
        require(referencePrice.isFinite()) { "Trend shadow ticker price must be finite." }

        val stored = shadowStore.trendShadowState(config.bootstrap.protocolId, config.symbol)
        val initial = stored ?: initialState(observedAt)
        validateStoredState(initial)
        val latestClosedH4OpenedAt = latestClosedH4OpenedAt(observedAt)
        val nextH4OpenedAt = initial.indicatorState.lastBarOpenedAt!!.plusSeconds(SHADOW_H4_SECONDS)
        val bars =
            if (nextH4OpenedAt.isAfter(latestClosedH4OpenedAt)) {
                emptyList()
            } else {
                loadCompleteH4Bars(nextH4OpenedAt, latestClosedH4OpenedAt)
            }

        if (initial.status == VolumeConfirmedTrendShadowStatus.BOOTSTRAPPING) {
            return bootstrap(initial, bars, observedAt, referencePrice)
        }
        if (bars.isEmpty()) {
            val marked = initial.mark(referencePrice, observedAt)
            shadowStore.commitTrendShadow(marked, emptyList())
            return VolumeConfirmedTrendShadowEvaluationResult(
                status = VolumeConfirmedTrendShadowEvaluationStatus.NO_NEW_H4,
                evaluatedH4Bars = 0,
                emittedEvents = 0,
                state = marked,
            )
        }

        val latestDecisionAt = bars.last().openedAt.plusSeconds(SHADOW_H4_SECONDS)
        val observationDelay = Duration.between(latestDecisionAt, observedAt)
        require(!observationDelay.isNegative) { "Trend shadow cannot observe an H4 decision before it closes." }
        val continuous = bars.size == 1 && observationDelay <= config.maximumObservationDelay
        return if (continuous) {
            evaluateContinuous(initial, bars.single(), observedAt, referencePrice)
        } else {
            resetAfterGap(initial, bars, observedAt, referencePrice, observationDelay)
        }
    }

    private suspend fun bootstrap(
        initial: VolumeConfirmedTrendShadowState,
        bars: List<VolumeConfirmedTrendBar>,
        observedAt: Instant,
        referencePrice: Double,
    ): VolumeConfirmedTrendShadowEvaluationResult {
        val evaluator = VolumeConfirmedTrendEvaluator.restore(initial.indicatorState, config.parameters)
        bars.forEach(evaluator::evaluate)
        val indicator = evaluator.snapshot()
        val sessionStarted =
            initial.copy(
                status = VolumeConfirmedTrendShadowStatus.OBSERVING,
                sessionStartedAt = observedAt,
                indicatorState = indicator,
                lastAppliedFundingAt = latestFundingBoundary(indicator.lastBarOpenedAt!!.plusSeconds(SHADOW_H4_SECONDS)),
                lastObservedAt = observedAt,
                equity = initial.cash,
                peakEquity = initial.cash,
                updatedAt = observedAt,
            )
        val event =
            event(
                state = sessionStarted,
                type = VolumeConfirmedTrendShadowEventType.SESSION_STARTED,
                eventAt = observedAt,
                observedAt = observedAt,
                h4OpenedAt = indicator.lastBarOpenedAt,
                referencePrice = referencePrice,
                reason = "WAIT_FOR_NEXT_CONFIRMED_TRANSITION",
            )
        shadowStore.commitTrendShadow(sessionStarted, listOf(event))
        return VolumeConfirmedTrendShadowEvaluationResult(
            status = VolumeConfirmedTrendShadowEvaluationStatus.BOOTSTRAPPED,
            evaluatedH4Bars = bars.size,
            emittedEvents = 1,
            state = sessionStarted,
        )
    }

    private suspend fun evaluateContinuous(
        initial: VolumeConfirmedTrendShadowState,
        bar: VolumeConfirmedTrendBar,
        observedAt: Instant,
        referencePrice: Double,
    ): VolumeConfirmedTrendShadowEvaluationResult {
        val working = MutableShadowState(initial)
        val events = mutableListOf<VolumeConfirmedTrendShadowEvent>()
        working.observeIntrabar(bar)
        applyFundingThrough(
            working = working,
            through = bar.openedAt.plusSeconds(SHADOW_H4_SECONDS),
            observedAt = observedAt,
            priceAt = { referencePrice },
            events = events,
        )
        val evaluator = VolumeConfirmedTrendEvaluator.restore(working.indicatorState, config.parameters)
        val transition = evaluator.evaluate(bar)
        working.indicatorState = evaluator.snapshot()
        if (transition != null) {
            working.executedTransitions += 1
            working.closePosition(
                referencePrice = referencePrice,
                at = transition.decisionAt,
                observedAt = observedAt,
                reason = "OPPOSITE_VOLUME_CONFIRMED_TREND",
                events = events,
            )
            working.openPosition(
                side = transition.side,
                referencePrice = referencePrice,
                at = transition.decisionAt,
                observedAt = observedAt,
                h4OpenedAt = bar.openedAt,
                events = events,
            )
        }
        working.mark(referencePrice)
        events +=
            working.event(
                type = VolumeConfirmedTrendShadowEventType.H4_EVALUATED,
                eventAt = bar.openedAt.plusSeconds(SHADOW_H4_SECONDS),
                observedAt = observedAt,
                h4OpenedAt = bar.openedAt,
                side = transition?.side,
                referencePrice = referencePrice,
                reason = if (transition == null) "NO_CONFIRMED_SIDE_CHANGE" else "CONFIRMED_SIDE_CHANGE",
            )
        val persisted = working.toState(observedAt)
        shadowStore.commitTrendShadow(persisted, events)
        return VolumeConfirmedTrendShadowEvaluationResult(
            status = VolumeConfirmedTrendShadowEvaluationStatus.EVALUATED,
            evaluatedH4Bars = 1,
            emittedEvents = events.size,
            state = persisted,
        )
    }

    private suspend fun resetAfterGap(
        initial: VolumeConfirmedTrendShadowState,
        bars: List<VolumeConfirmedTrendBar>,
        observedAt: Instant,
        referencePrice: Double,
        observationDelay: Duration,
    ): VolumeConfirmedTrendShadowEvaluationResult {
        val working = MutableShadowState(initial)
        val events = mutableListOf<VolumeConfirmedTrendShadowEvent>()
        val evaluator = VolumeConfirmedTrendEvaluator.restore(working.indicatorState, config.parameters)
        bars.forEach { bar ->
            working.observeIntrabar(bar)
            val boundary = bar.openedAt.plusSeconds(SHADOW_H4_SECONDS)
            applyFundingThrough(
                working = working,
                through = boundary,
                observedAt = observedAt,
                priceAt = { timestamp ->
                    bars.firstOrNull { it.openedAt == timestamp }?.open ?: referencePrice
                },
                events = events,
            )
            evaluator.evaluate(bar)
        }
        working.indicatorState = evaluator.snapshot()
        working.closePosition(
            referencePrice = referencePrice,
            at = observedAt,
            observedAt = observedAt,
            reason = "SHADOW_CONTINUITY_GAP",
            events = events,
        )
        working.mark(referencePrice)
        events +=
            working.event(
                type = VolumeConfirmedTrendShadowEventType.SESSION_INVALIDATED,
                eventAt = observedAt,
                observedAt = observedAt,
                h4OpenedAt = bars.last().openedAt,
                referencePrice = referencePrice,
                reason = "MISSED_H4_COUNT=${bars.size};OBSERVATION_DELAY_SECONDS=${observationDelay.seconds}",
            )

        val newSessionId = requireValidSessionId(sessionIdFactory())
        working.resetSession(newSessionId, observedAt)
        events +=
            working.event(
                type = VolumeConfirmedTrendShadowEventType.SESSION_STARTED,
                eventAt = observedAt,
                observedAt = observedAt,
                h4OpenedAt = bars.last().openedAt,
                referencePrice = referencePrice,
                reason = "WAIT_FOR_NEXT_CONFIRMED_TRANSITION_AFTER_GAP",
            )
        val persisted = working.toState(observedAt)
        shadowStore.commitTrendShadow(persisted, events)
        return VolumeConfirmedTrendShadowEvaluationResult(
            status = VolumeConfirmedTrendShadowEvaluationStatus.SESSION_RESET,
            evaluatedH4Bars = bars.size,
            emittedEvents = events.size,
            state = persisted,
        )
    }

    private suspend fun applyFundingThrough(
        working: MutableShadowState,
        through: Instant,
        observedAt: Instant,
        priceAt: (Instant) -> Double,
        events: MutableList<VolumeConfirmedTrendShadowEvent>,
    ) {
        val expected = fundingBoundariesAfter(working.lastAppliedFundingAt, through)
        if (expected.isEmpty()) return
        val snapshots =
            flowStore
                .fundingRateSnapshotsBetween(
                    symbol = config.symbol,
                    startAt = expected.first(),
                    endAt = expected.last(),
                    limit = expected.size.coerceAtLeast(1),
                ).associateBy { it.timestamp }
        expected.forEach { timestamp ->
            val position = working.position
            if (position != null) {
                val rate =
                    snapshots[timestamp]?.fundingRate?.toDouble()
                        ?: throw VolumeConfirmedTrendShadowDataException(
                            "Funding rate is missing at $timestamp while a shadow position is open.",
                        )
                val price = priceAt(timestamp)
                val execution =
                    VolumeConfirmedTrendExecutionModel.applyFunding(
                        cash = working.cash,
                        position = position,
                        settlementPrice = price,
                        fundingRate = rate,
                    )
                working.cash = execution.cashAfter
                working.totalFundingPnl += execution.fundingPnl
                working.position = execution.position
                working.equity = execution.equityAfter
                working.mark(price)
                events +=
                    working.event(
                        type = VolumeConfirmedTrendShadowEventType.FUNDING_APPLIED,
                        eventAt = timestamp,
                        observedAt = observedAt,
                        side = position.side,
                        referencePrice = price,
                        quantity = position.quantity,
                        fundingPnl = execution.fundingPnl,
                        netPnl = execution.fundingPnl,
                        reason = "ACTUAL_SETTLED_FUNDING_RATE=$rate",
                    )
            }
            working.lastAppliedFundingAt = timestamp
        }
    }

    private suspend fun loadCompleteH4Bars(
        firstOpenedAt: Instant,
        lastOpenedAt: Instant,
    ): List<VolumeConfirmedTrendBar> {
        val expectedH4Count = (Duration.between(firstOpenedAt, lastOpenedAt).seconds / SHADOW_H4_SECONDS).toInt() + 1
        val expectedM15Count = expectedH4Count * 16
        require(expectedM15Count <= ResearchCandleLimits.MAX_M15_REPLAY_CANDLES) {
            "Trend shadow catch-up exceeds the M15 replay limit."
        }
        val lastM15OpenedAt = lastOpenedAt.plusSeconds(15L * SHADOW_M15_SECONDS)
        val candles =
            candleStore.candlesBetween(
                symbol = config.symbol,
                timeframe = Timeframe.M15,
                startAt = firstOpenedAt,
                endAt = lastM15OpenedAt,
                limit = expectedM15Count,
            )
        val bars =
            try {
                VolumeConfirmedTrendEngine.aggregateM15(candles)
            } catch (error: IllegalArgumentException) {
                throw VolumeConfirmedTrendShadowDataException(error.message ?: "Invalid M15 trend evidence.")
            } catch (error: IllegalStateException) {
                throw VolumeConfirmedTrendShadowDataException(error.message ?: "Invalid M15 trend evidence.")
            }
        if (bars.size != expectedH4Count || bars.first().openedAt != firstOpenedAt || bars.last().openedAt != lastOpenedAt) {
            throw VolumeConfirmedTrendShadowDataException(
                "Expected $expectedH4Count complete H4 bars from $firstOpenedAt through $lastOpenedAt, found ${bars.size}.",
            )
        }
        return bars
    }

    private fun initialState(observedAt: Instant): VolumeConfirmedTrendShadowState {
        val sessionId = requireValidSessionId(sessionIdFactory())
        val bootstrapClosedAt =
            config.bootstrap.indicatorState.lastBarOpenedAt!!
                .plusSeconds(SHADOW_H4_SECONDS)
        return VolumeConfirmedTrendShadowState(
            protocolId = config.bootstrap.protocolId,
            candidateId = config.bootstrap.candidateId,
            protocolSha256 = config.bootstrap.protocolSha256,
            symbol = config.symbol,
            sessionId = sessionId,
            status = VolumeConfirmedTrendShadowStatus.BOOTSTRAPPING,
            sessionStartedAt = null,
            indicatorState = config.bootstrap.indicatorState,
            lastAppliedFundingAt = latestFundingBoundary(bootstrapClosedAt),
            lastObservedAt = null,
            position = null,
            sessionStartingEquity = config.initialEquity,
            cash = config.initialEquity,
            equity = config.initialEquity,
            peakEquity = config.initialEquity,
            maximumDrawdownPct = 0.0,
            totalFees = 0.0,
            totalSlippage = 0.0,
            totalFundingPnl = 0.0,
            closedTrades = 0,
            executedTransitions = 0,
            invalidatedSessionCount = 0,
            updatedAt = observedAt,
            maximumEntryExposureFraction = 0.0,
            maximumAdverseExposureFraction = 0.0,
            liquidationCount = 0,
        )
    }

    private fun validateStoredState(state: VolumeConfirmedTrendShadowState) {
        require(state.protocolId == config.bootstrap.protocolId) { "Trend shadow protocol ID changed." }
        require(state.candidateId == config.bootstrap.candidateId) { "Trend shadow candidate ID changed." }
        require(state.protocolSha256 == config.bootstrap.protocolSha256) { "Trend shadow protocol hash changed." }
        require(state.symbol == config.symbol) { "Trend shadow symbol changed." }
    }

    private inner class MutableShadowState(
        state: VolumeConfirmedTrendShadowState,
    ) {
        var sessionId = state.sessionId
        var sessionStartedAt = state.sessionStartedAt
        var indicatorState = state.indicatorState
        var lastAppliedFundingAt = state.lastAppliedFundingAt
        var position = state.position
        var sessionStartingEquity = state.sessionStartingEquity
        var cash = state.cash
        var equity = state.equity
        var peakEquity = state.peakEquity
        var maximumDrawdownPct = state.maximumDrawdownPct
        var totalFees = state.totalFees
        var totalSlippage = state.totalSlippage
        var totalFundingPnl = state.totalFundingPnl
        var closedTrades = state.closedTrades
        var executedTransitions = state.executedTransitions
        var invalidatedSessionCount = state.invalidatedSessionCount
        var maximumEntryExposureFraction = state.maximumEntryExposureFraction
        var maximumAdverseExposureFraction = state.maximumAdverseExposureFraction
        var liquidationCount = state.liquidationCount

        fun observeIntrabar(bar: VolumeConfirmedTrendBar) {
            val current = position ?: return
            val risk = VolumeConfirmedTrendExecutionModel.observeIntrabar(cash, current, bar, peakEquity)
            peakEquity = risk.peakEquity
            maximumDrawdownPct = max(maximumDrawdownPct, risk.drawdownPct)
            maximumAdverseExposureFraction = max(maximumAdverseExposureFraction, risk.adverseExposureFraction)
            if (risk.liquidationObserved) liquidationCount += 1
        }

        fun closePosition(
            referencePrice: Double,
            at: Instant,
            observedAt: Instant,
            reason: String,
            events: MutableList<VolumeConfirmedTrendShadowEvent>,
        ) {
            val current = position ?: return
            val execution =
                VolumeConfirmedTrendExecutionModel.close(
                    cash = cash,
                    position = current,
                    referencePrice = referencePrice,
                    contract = config.executionContract,
                )
            cash = execution.cashAfter
            totalFees += execution.fee
            totalSlippage += execution.slippage
            closedTrades += 1
            position = null
            mark(referencePrice)
            events +=
                event(
                    type = VolumeConfirmedTrendShadowEventType.POSITION_CLOSED,
                    eventAt = at,
                    observedAt = observedAt,
                    side = current.side,
                    referencePrice = referencePrice,
                    fillPrice = execution.fillPrice,
                    quantity = current.quantity,
                    fee = execution.fee,
                    slippage = execution.slippage,
                    fundingPnl = current.fundingPnl,
                    grossPnl = execution.grossPnl,
                    netPnl = execution.netPnl,
                    reason = reason,
                )
        }

        fun openPosition(
            side: Side,
            referencePrice: Double,
            at: Instant,
            observedAt: Instant,
            h4OpenedAt: Instant,
            events: MutableList<VolumeConfirmedTrendShadowEvent>,
        ) {
            val execution =
                VolumeConfirmedTrendExecutionModel.open(
                    cash = cash,
                    side = side,
                    referencePrice = referencePrice,
                    at = at,
                    contract = config.executionContract,
                )
            if (execution == null) {
                events +=
                    event(
                        type = VolumeConfirmedTrendShadowEventType.MINIMUM_QUANTITY_SKIPPED,
                        eventAt = at,
                        observedAt = observedAt,
                        h4OpenedAt = h4OpenedAt,
                        side = side,
                        referencePrice = referencePrice,
                        reason = "MINIMUM_QUANTITY_EXCEEDS_ROUNDED_EXPOSURE_LIMIT",
                    )
                return
            }
            cash = execution.cashAfter
            totalFees += execution.fee
            totalSlippage += execution.slippage
            maximumEntryExposureFraction = max(maximumEntryExposureFraction, execution.exposureFraction)
            position = execution.position
            mark(referencePrice)
            events +=
                event(
                    type = VolumeConfirmedTrendShadowEventType.POSITION_OPENED,
                    eventAt = at,
                    observedAt = observedAt,
                    h4OpenedAt = h4OpenedAt,
                    side = side,
                    referencePrice = referencePrice,
                    fillPrice = execution.fillPrice,
                    quantity = execution.quantity,
                    fee = execution.fee,
                    slippage = execution.slippage,
                    netPnl = -execution.fee,
                    reason = "VOLUME_CONFIRMED_TREND_TRANSITION",
                )
        }

        fun mark(price: Double) {
            equity = VolumeConfirmedTrendExecutionModel.markEquity(cash, position, price)
            peakEquity = max(peakEquity, equity)
            maximumDrawdownPct = max(maximumDrawdownPct, trendDrawdownPct(peakEquity, equity))
        }

        fun event(
            type: VolumeConfirmedTrendShadowEventType,
            eventAt: Instant,
            observedAt: Instant,
            h4OpenedAt: Instant? = null,
            side: Side? = null,
            referencePrice: Double? = null,
            fillPrice: Double? = null,
            quantity: Double? = null,
            fee: Double = 0.0,
            slippage: Double = 0.0,
            fundingPnl: Double = 0.0,
            grossPnl: Double = 0.0,
            netPnl: Double = 0.0,
            reason: String,
        ): VolumeConfirmedTrendShadowEvent =
            event(
                state = toState(observedAt),
                type = type,
                eventAt = eventAt,
                observedAt = observedAt,
                h4OpenedAt = h4OpenedAt,
                side = side,
                referencePrice = referencePrice,
                fillPrice = fillPrice,
                quantity = quantity,
                fee = fee,
                slippage = slippage,
                fundingPnl = fundingPnl,
                grossPnl = grossPnl,
                netPnl = netPnl,
                reason = reason,
            )

        fun resetSession(
            nextSessionId: String,
            at: Instant,
        ) {
            sessionId = nextSessionId
            sessionStartedAt = at
            sessionStartingEquity = cash
            equity = cash
            peakEquity = cash
            maximumDrawdownPct = 0.0
            totalFees = 0.0
            totalSlippage = 0.0
            totalFundingPnl = 0.0
            closedTrades = 0
            executedTransitions = 0
            maximumEntryExposureFraction = 0.0
            maximumAdverseExposureFraction = 0.0
            liquidationCount = 0
            invalidatedSessionCount += 1
        }

        fun toState(updatedAt: Instant): VolumeConfirmedTrendShadowState =
            VolumeConfirmedTrendShadowState(
                protocolId = config.bootstrap.protocolId,
                candidateId = config.bootstrap.candidateId,
                protocolSha256 = config.bootstrap.protocolSha256,
                symbol = config.symbol,
                sessionId = sessionId,
                status = VolumeConfirmedTrendShadowStatus.OBSERVING,
                sessionStartedAt = sessionStartedAt,
                indicatorState = indicatorState,
                lastAppliedFundingAt = lastAppliedFundingAt,
                lastObservedAt = updatedAt,
                position = position,
                sessionStartingEquity = sessionStartingEquity,
                cash = cash,
                equity = equity,
                peakEquity = peakEquity,
                maximumDrawdownPct = maximumDrawdownPct,
                totalFees = totalFees,
                totalSlippage = totalSlippage,
                totalFundingPnl = totalFundingPnl,
                closedTrades = closedTrades,
                executedTransitions = executedTransitions,
                invalidatedSessionCount = invalidatedSessionCount,
                updatedAt = updatedAt,
                maximumEntryExposureFraction = maximumEntryExposureFraction,
                maximumAdverseExposureFraction = maximumAdverseExposureFraction,
                liquidationCount = liquidationCount,
            )
    }

    private fun VolumeConfirmedTrendShadowState.mark(
        referencePrice: Double,
        observedAt: Instant,
    ): VolumeConfirmedTrendShadowState {
        val markedEquity = VolumeConfirmedTrendExecutionModel.markEquity(cash, position, referencePrice)
        val nextPeak = max(peakEquity, markedEquity)
        return copy(
            lastObservedAt = observedAt,
            equity = markedEquity,
            peakEquity = nextPeak,
            maximumDrawdownPct = max(maximumDrawdownPct, trendDrawdownPct(nextPeak, markedEquity)),
            updatedAt = observedAt,
        )
    }

    private fun event(
        state: VolumeConfirmedTrendShadowState,
        type: VolumeConfirmedTrendShadowEventType,
        eventAt: Instant,
        observedAt: Instant,
        h4OpenedAt: Instant? = null,
        side: Side? = null,
        referencePrice: Double? = null,
        fillPrice: Double? = null,
        quantity: Double? = null,
        fee: Double = 0.0,
        slippage: Double = 0.0,
        fundingPnl: Double = 0.0,
        grossPnl: Double = 0.0,
        netPnl: Double = 0.0,
        reason: String,
    ): VolumeConfirmedTrendShadowEvent {
        val identity =
            listOf(
                state.sessionId,
                type.name,
                eventAt.toString(),
                h4OpenedAt?.toString().orEmpty(),
                side?.name.orEmpty(),
                reason,
            ).joinToString("|")
        return VolumeConfirmedTrendShadowEvent(
            eventId = identity.sha256(),
            sessionId = state.sessionId,
            protocolId = state.protocolId,
            protocolSha256 = state.protocolSha256,
            symbol = state.symbol,
            type = type,
            eventAt = eventAt,
            observedAt = observedAt,
            h4OpenedAt = h4OpenedAt,
            side = side,
            referencePrice = referencePrice,
            fillPrice = fillPrice,
            quantity = quantity,
            fee = fee,
            slippage = slippage,
            fundingPnl = fundingPnl,
            grossPnl = grossPnl,
            netPnl = netPnl,
            cash = state.cash,
            equity = state.equity,
            reason = reason,
        )
    }
}

private fun latestClosedH4OpenedAt(observedAt: Instant): Instant =
    Instant.ofEpochSecond((observedAt.epochSecond / SHADOW_H4_SECONDS) * SHADOW_H4_SECONDS - SHADOW_H4_SECONDS)

private fun latestFundingBoundary(at: Instant): Instant =
    Instant.ofEpochSecond((at.epochSecond / SHADOW_FUNDING_SECONDS) * SHADOW_FUNDING_SECONDS)

private fun fundingBoundariesAfter(
    afterExclusive: Instant,
    throughInclusive: Instant,
): List<Instant> {
    var cursor = latestFundingBoundary(afterExclusive).plusSeconds(SHADOW_FUNDING_SECONDS)
    return buildList {
        while (!cursor.isAfter(throughInclusive)) {
            add(cursor)
            cursor = cursor.plusSeconds(SHADOW_FUNDING_SECONDS)
        }
    }
}

private fun requireValidSessionId(value: String): String {
    require(value.isNotBlank()) { "Trend shadow session ID must not be blank." }
    return value
}

private fun String.isSha256(): Boolean = length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

private fun String.sha256(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
