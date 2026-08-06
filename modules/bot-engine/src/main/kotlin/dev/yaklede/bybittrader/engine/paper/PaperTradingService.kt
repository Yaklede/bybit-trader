package dev.yaklede.bybittrader.engine.paper

import dev.yaklede.bybittrader.domain.BotMode
import dev.yaklede.bybittrader.domain.OrderStatus
import dev.yaklede.bybittrader.domain.OrderType
import dev.yaklede.bybittrader.domain.Price
import dev.yaklede.bybittrader.domain.ResearchCandleLimits
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.SignalIntent
import dev.yaklede.bybittrader.domain.SignalScore
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.backtest.CausalReplay
import dev.yaklede.bybittrader.engine.backtest.replayDuration
import dev.yaklede.bybittrader.engine.control.BotStateStore
import dev.yaklede.bybittrader.engine.market.MarketCandleStore
import dev.yaklede.bybittrader.engine.position.CausalEntryPlanRequest
import dev.yaklede.bybittrader.engine.position.CausalEntryPlanner
import dev.yaklede.bybittrader.engine.position.CausalEntryPolicyConfig
import dev.yaklede.bybittrader.engine.position.CausalPartialExit
import dev.yaklede.bybittrader.engine.position.CausalPositionExit
import dev.yaklede.bybittrader.engine.position.CausalPositionOpenRequest
import dev.yaklede.bybittrader.engine.position.CausalPositionPolicy
import dev.yaklede.bybittrader.engine.position.CausalPositionPolicyConfig
import dev.yaklede.bybittrader.strategy.TradingStrategy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.abs

class PaperTradingService(
    private val stateStore: BotStateStore,
    private val candleStore: MarketCandleStore,
    private val paperTradingStore: PaperTradingStore,
    private val runtimeStateStore: PaperRuntimeStateStore,
    private val strategy: TradingStrategy,
    private val config: PaperTradingConfig = PaperTradingConfig(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val evaluationMutex = Mutex()
    private val entryPlanner =
        CausalEntryPlanner(
            CausalEntryPolicyConfig(
                riskFraction = config.riskFraction.toDouble(),
                entrySlippageRate = config.entrySlippageRate.toDouble(),
                maxTradesPerUtcDay = config.maxTradesPerUtcDay,
                minimumEntryRiskFraction = config.minimumEntryRiskFraction?.toDouble(),
                maximumEntryRiskFraction = config.maximumEntryRiskFraction?.toDouble(),
            ),
        )
    private val positionPolicy =
        CausalPositionPolicy(
            CausalPositionPolicyConfig(
                feeRate = config.feeRate.toDouble(),
                partialTakeProfitR = config.partialTakeProfitR.toDouble(),
                partialTakeProfitFraction = config.partialTakeProfitFraction.toDouble(),
                breakevenAfterPartialTakeProfit = config.breakevenAfterPartialTakeProfit,
                atrTrailingMultiplier = config.atrTrailingMultiplier.toDouble(),
                fixedTargetEnabled = config.fixedTargetEnabled,
                maxHoldCandles = config.maxHoldCandles,
            ),
        )

    val requiredCandleCount: Int = strategy.warmupCandles + 2

    suspend fun evaluateOnce(
        symbol: Symbol,
        timeframe: Timeframe,
        candleLimit: Int,
    ): PaperEvaluationResult =
        evaluationMutex.withLock {
            require(candleLimit in requiredCandleCount..ResearchCandleLimits.MAX_M5_REPLAY_CANDLES) {
                "Candle limit must be between $requiredCandleCount and ${ResearchCandleLimits.MAX_M5_REPLAY_CANDLES}."
            }

            val now = Instant.now(clock)
            val mode = stateStore.current().mode
            val storedCandles = candleStore.recentCandles(symbol, timeframe, candleLimit).sortedBy { it.openedAt }
            val closedCandles = CausalReplay.closedCandlesAt(storedCandles, now)
            if (closedCandles.size < strategy.warmupCandles + 1) {
                return@withLock result(
                    context = EvaluationContext(symbol, timeframe, mode, now, closedCandles.size),
                    status = PaperEvaluationStatus.NO_TRADE,
                    reasonCodes = listOf("INSUFFICIENT_CLOSED_HISTORY"),
                    state =
                        runtimeStateStore.paperRuntimeState(strategy.name, symbol, timeframe)
                            ?: PaperRuntimeState.initial(
                                strategy = strategy.name,
                                symbol = symbol,
                                timeframe = timeframe,
                                initialEquity = config.initialEquity.toDouble(),
                                updatedAt = now,
                            ),
                )
            }

            var runtime =
                runtimeStateStore.paperRuntimeState(strategy.name, symbol, timeframe)
                    ?: PaperRuntimeState.initial(
                        strategy = strategy.name,
                        symbol = symbol,
                        timeframe = timeframe,
                        initialEquity = config.initialEquity.toDouble(),
                        updatedAt = now,
                    )
            val candlesToProcess =
                if (runtime.lastProcessedCandleAt == null) {
                    listOf(closedCandles.last())
                } else {
                    closedCandles.filter { it.openedAt.isAfter(runtime.lastProcessedCandleAt) }
                }
            val baseContext = EvaluationContext(symbol, timeframe, mode, now, closedCandles.size)
            if (candlesToProcess.isEmpty()) {
                return@withLock result(
                    context = baseContext,
                    status = PaperEvaluationStatus.NO_TRADE,
                    reasonCodes = listOf("NO_NEW_CLOSED_CANDLE"),
                    state = runtime,
                )
            }

            var latestResult =
                result(
                    context = baseContext,
                    status = PaperEvaluationStatus.NO_TRADE,
                    reasonCodes = listOf("NO_SIGNAL"),
                    state = runtime,
                )
            var noteworthyResult: PaperEvaluationResult? = null
            val latestCandleAt = candlesToProcess.last().openedAt
            candlesToProcess.forEach { candle ->
                runtime.requireContiguousNextCandle(candle, timeframe)
                val candleIndex = closedCandles.indexOfFirst { it.openedAt == candle.openedAt }
                check(candleIndex >= 0) { "Paper candle is missing from the closed timeline." }
                val context = baseContext.copy(allowsNewEntries = candle.openedAt == latestCandleAt)
                val outcome =
                    when (runtime.phase) {
                        PaperRuntimePhase.FLAT -> processFlat(runtime, closedCandles, candleIndex, context)
                        PaperRuntimePhase.ENTRY_PENDING ->
                            processPending(runtime, closedCandles, candleIndex, context)
                        PaperRuntimePhase.OPEN -> processOpen(runtime, closedCandles, candleIndex, context)
                    }
                runtime =
                    outcome.state.copy(
                        lastProcessedCandleAt = candle.openedAt,
                        updatedAt = now,
                    )
                runtimeStateStore.upsertPaperRuntimeState(runtime)
                latestResult = outcome.result.copy(phase = runtime.phase, equity = runtime.equity.toBigDecimal())
                if (outcome.result.status.isNoteworthy()) {
                    noteworthyResult =
                        noteworthyResult
                            ?.takeIf { it.status.priority >= outcome.result.status.priority }
                            ?: latestResult
                }
            }
            (noteworthyResult ?: latestResult).copy(phase = runtime.phase, equity = runtime.equity.toBigDecimal())
        }

    private suspend fun processFlat(
        state: PaperRuntimeState,
        candles: List<dev.yaklede.bybittrader.domain.Candle>,
        candleIndex: Int,
        context: EvaluationContext,
    ): PaperProcessOutcome {
        if (context.mode.blocksNewEntries()) {
            return PaperProcessOutcome(
                state = state,
                result =
                    result(
                        context = context,
                        status = PaperEvaluationStatus.SKIPPED_BY_MODE,
                        reasonCodes = listOf("MODE_${context.mode.name}_BLOCKS_NEW_ENTRIES"),
                        state = state,
                    ),
            )
        }
        if (!context.allowsNewEntries) {
            return PaperProcessOutcome(
                state = state,
                result =
                    result(
                        context = context,
                        status = PaperEvaluationStatus.NO_TRADE,
                        reasonCodes = listOf("DOWNTIME_CANDLE_NEW_ENTRIES_BLOCKED"),
                        state = state,
                    ),
            )
        }
        val decision = strategy.evaluate(candles.subList(0, candleIndex + 1))
        val signal =
            decision.intent ?: return PaperProcessOutcome(
                state = state,
                result =
                    result(
                        context = context,
                        status = PaperEvaluationStatus.NO_TRADE,
                        reasonCodes = decision.reasonCodes.ifEmpty { listOf("NO_SIGNAL") },
                        state = state,
                    ),
            )
        require(signal.symbol == state.symbol) { "Paper signal symbol does not match runtime state." }
        val signalAt = candles[candleIndex].openedAt
        val signalId =
            paperTradingStore.recordSignal(
                signal.toRecord(
                    accepted = true,
                    rejectionReason = null,
                    createdAt = signalAt,
                ),
            )
        val pending =
            PaperPendingEntry(
                signalId = signalId,
                signalAt = signalAt,
                side = signal.side,
                structuralStopPrice = signal.invalidationPrice.value.toDouble(),
                entryAnchoredStopDistance = signal.entryAnchoredStopDistance?.toDouble(),
                expectedR = signal.expectedR.toDouble(),
            )
        val pendingState =
            state.copy(
                phase = PaperRuntimePhase.ENTRY_PENDING,
                pendingEntry = pending,
                openPosition = null,
            )
        return PaperProcessOutcome(
            state = pendingState,
            result =
                result(
                    context = context,
                    status = PaperEvaluationStatus.ENTRY_PENDING,
                    reasonCodes = decision.reasonCodes.ifEmpty { signal.score.reasonCodes },
                    state = pendingState,
                    signalId = signalId,
                ),
        )
    }

    private suspend fun processPending(
        state: PaperRuntimeState,
        candles: List<dev.yaklede.bybittrader.domain.Candle>,
        candleIndex: Int,
        context: EvaluationContext,
    ): PaperProcessOutcome {
        val pending = requireNotNull(state.pendingEntry)
        val candle = candles[candleIndex]
        if (context.mode.blocksNewEntries()) {
            val flat = state.toFlat()
            return PaperProcessOutcome(
                state = flat,
                result =
                    result(
                        context = context,
                        status = PaperEvaluationStatus.SKIPPED_BY_MODE,
                        reasonCodes = listOf("PENDING_ENTRY_CANCELLED_BY_MODE_${context.mode.name}"),
                        state = flat,
                        signalId = pending.signalId,
                    ),
            )
        }
        if (!context.allowsNewEntries) {
            val flat = state.toFlat()
            return PaperProcessOutcome(
                state = flat,
                result =
                    result(
                        context = context,
                        status = PaperEvaluationStatus.REJECTED,
                        reasonCodes = listOf("PENDING_ENTRY_EXPIRED_DURING_DOWNTIME"),
                        state = flat,
                        signalId = pending.signalId,
                    ),
            )
        }
        val expectedEntryAt = pending.signalAt.plus(state.timeframe.replayDuration())
        if (candle.openedAt != expectedEntryAt) {
            val flat = state.toFlat()
            return processFlat(flat, candles, candleIndex, context).withPrependedReason("NO_CONTIGUOUS_ENTRY_CANDLE")
        }

        val entryDate = candle.openedAt.atZone(ZoneOffset.UTC).toLocalDate()
        val entriesToday = if (state.entryCountDate == entryDate) state.entryCount else 0
        val signal = pending.toSignalIntent(state.symbol, state.strategy)
        val planning =
            entryPlanner.plan(
                CausalEntryPlanRequest(
                    signal = signal,
                    signalAt = pending.signalAt,
                    entryCandle = candle,
                    equity = state.equity,
                    entriesOnEntryUtcDay = entriesToday,
                ),
            )
        val plan = planning.plan
        if (plan == null) {
            val reason = requireNotNull(planning.rejectionReason)
            val flat = state.toFlat()
            return PaperProcessOutcome(
                state = flat,
                result =
                    result(
                        context = context,
                        status = PaperEvaluationStatus.REJECTED,
                        reasonCodes = listOf(reason),
                        state = flat,
                        signalId = pending.signalId,
                    ),
            )
        }

        val entryFee = plan.entryPrice * plan.quantity * config.feeRate.toDouble()
        val orderId =
            paperTradingStore.recordOrder(
                PaperOrderRecord(
                    clientOrderId = "paper-entry-${state.strategy}-${pending.signalId}-${candle.openedAt.toEpochMilli()}",
                    signalId = pending.signalId,
                    side = pending.side,
                    orderType = OrderType.MARKET,
                    orderStatus = OrderStatus.FILLED,
                    intendedRisk = plan.riskAmount.toBigDecimal(),
                    createdAt = candle.openedAt,
                ),
            )
        paperTradingStore.recordFill(
            PaperFillRecord(
                orderId = orderId,
                fillPrice = plan.entryPrice.toBigDecimal(),
                quantity = plan.quantity.toBigDecimal(),
                fee = entryFee.toBigDecimal(),
                liquidityRole = PAPER_TAKER_LIQUIDITY_ROLE,
                filledAt = candle.openedAt,
            ),
        )
        val opened =
            positionPolicy.open(
                CausalPositionOpenRequest(
                    side = plan.side,
                    entryAt = plan.entryAt,
                    entryPrice = plan.entryPrice,
                    initialStopPrice = plan.initialStopPrice,
                    riskPerUnit = plan.riskPerUnit,
                    expectedR = plan.expectedR,
                    quantity = plan.quantity,
                ),
            )
        val openState =
            state.copy(
                phase = PaperRuntimePhase.OPEN,
                entryCountDate = entryDate,
                entryCount = entriesToday + 1,
                pendingEntry = null,
                openPosition =
                    PaperOpenPosition(
                        signalId = pending.signalId,
                        signalAt = pending.signalAt,
                        entryOrderId = orderId,
                        entryFee = entryFee,
                        riskAmount = plan.riskAmount,
                        policyState = opened,
                    ),
            )
        val processed = processOpen(openState, candles, candleIndex, context)
        return if (processed.result.status == PaperEvaluationStatus.POSITION_UPDATED) {
            processed.copy(
                result =
                    processed.result.copy(
                        status = PaperEvaluationStatus.FILLED,
                        reasonCodes = listOf("CAUSAL_NEXT_OPEN_FILLED"),
                        signalId = pending.signalId,
                        orderId = orderId,
                        fillPrice = plan.entryPrice.toBigDecimal(),
                        quantity = plan.quantity.toBigDecimal(),
                        fee = entryFee.toBigDecimal(),
                    ),
            )
        } else {
            processed
        }
    }

    private suspend fun processOpen(
        state: PaperRuntimeState,
        candles: List<dev.yaklede.bybittrader.domain.Candle>,
        candleIndex: Int,
        context: EvaluationContext,
    ): PaperProcessOutcome {
        val position = requireNotNull(state.openPosition)
        val candle = candles[candleIndex]
        val step =
            positionPolicy.onCandle(
                state = position.policyState,
                candle = candle,
                trailingAtr =
                    CausalPositionPolicy.trailingAtr(
                        candles = candles,
                        currentIndex = candleIndex,
                        period = config.atrTrailingPeriod,
                    ),
            )
        step.partialExit?.let { partial -> recordPartialExit(state, position, partial) }
        val updatedPosition = position.copy(policyState = step.state)
        val exit = step.exit
        if (exit != null) return closePosition(state, updatedPosition, exit, context)

        val unrealizedPnl =
            step.state.partialGrossPnl -
                position.entryFee -
                step.state.partialFees +
                grossPnl(
                    side = step.state.side,
                    entryPrice = step.state.entryPrice,
                    exitPrice = candle.close.toDouble(),
                    quantity = step.state.remainingQuantity,
                )
        paperTradingStore.recordPosition(
            PaperPositionRecord(
                symbol = state.symbol,
                side = step.state.side,
                quantity = step.state.remainingQuantity.toBigDecimal(),
                entryPrice = step.state.entryPrice.toBigDecimal(),
                realizedPnl =
                    (step.state.partialGrossPnl - position.entryFee - step.state.partialFees).toBigDecimal(),
                unrealizedPnl = unrealizedPnl.toBigDecimal(),
                capturedAt = candle.openedAt,
            ),
        )
        val updated = state.copy(openPosition = updatedPosition)
        return PaperProcessOutcome(
            state = updated,
            result =
                result(
                    context = context,
                    status = PaperEvaluationStatus.POSITION_UPDATED,
                    reasonCodes =
                        if (step.partialExit == null) {
                            listOf("OPEN_POSITION_UPDATED")
                        } else {
                            listOf("PARTIAL_TAKE_PROFIT_FILLED")
                        },
                    state = updated,
                    signalId = position.signalId,
                    orderId = position.entryOrderId,
                    fillPrice = step.state.entryPrice.toBigDecimal(),
                    quantity = step.state.remainingQuantity.toBigDecimal(),
                    fee = position.entryFee.toBigDecimal(),
                ),
        )
    }

    private suspend fun recordPartialExit(
        state: PaperRuntimeState,
        position: PaperOpenPosition,
        partial: CausalPartialExit,
    ) {
        val orderId =
            paperTradingStore.recordOrder(
                PaperOrderRecord(
                    clientOrderId =
                        "paper-partial-${state.strategy}-${position.entryOrderId}-${partial.exitedAt.toEpochMilli()}",
                    signalId = position.signalId,
                    side = position.policyState.side.opposite(),
                    orderType = OrderType.MARKET,
                    orderStatus = OrderStatus.FILLED,
                    intendedRisk = BigDecimal.ZERO,
                    createdAt = partial.exitedAt,
                ),
            )
        paperTradingStore.recordFill(
            PaperFillRecord(
                orderId = orderId,
                fillPrice = partial.price.toBigDecimal(),
                quantity = partial.quantity.toBigDecimal(),
                fee = partial.fee.toBigDecimal(),
                liquidityRole = PAPER_TAKER_LIQUIDITY_ROLE,
                filledAt = partial.exitedAt,
            ),
        )
    }

    private suspend fun closePosition(
        state: PaperRuntimeState,
        position: PaperOpenPosition,
        exit: CausalPositionExit,
        context: EvaluationContext,
    ): PaperProcessOutcome {
        val policyState = position.policyState
        val exitPrice = applyExitSlippage(policyState.side, exit.triggerPrice)
        val finalGrossPnl =
            grossPnl(
                side = policyState.side,
                entryPrice = policyState.entryPrice,
                exitPrice = exitPrice,
                quantity = exit.remainingQuantity,
            )
        val grossPnl = policyState.partialGrossPnl + finalGrossPnl
        val finalFee = exitPrice * exit.remainingQuantity * config.feeRate.toDouble()
        val totalFees = position.entryFee + policyState.partialFees + finalFee
        val fundingCost =
            fundingCost(
                side = policyState.side,
                notional = policyState.entryPrice * policyState.initialQuantity,
                entryAt = policyState.entryAt,
                exitAt = exit.exitedAt,
            )
        val pnl = grossPnl - totalFees - fundingCost
        val exitOrderId =
            paperTradingStore.recordOrder(
                PaperOrderRecord(
                    clientOrderId =
                        "paper-exit-${state.strategy}-${position.entryOrderId}-${exit.exitedAt.toEpochMilli()}-${exit.reason.name}",
                    signalId = position.signalId,
                    side = policyState.side.opposite(),
                    orderType = OrderType.MARKET,
                    orderStatus = OrderStatus.FILLED,
                    intendedRisk = BigDecimal.ZERO,
                    createdAt = exit.exitedAt,
                ),
            )
        paperTradingStore.recordFill(
            PaperFillRecord(
                orderId = exitOrderId,
                fillPrice = exitPrice.toBigDecimal(),
                quantity = exit.remainingQuantity.toBigDecimal(),
                fee = finalFee.toBigDecimal(),
                liquidityRole = PAPER_TAKER_LIQUIDITY_ROLE,
                filledAt = exit.exitedAt,
            ),
        )

        val equity = state.equity + pnl
        val peakEquity = maxOf(state.peakEquity, equity)
        val maxDrawdownPct = maxOf(state.maxDrawdownPct, ((peakEquity - equity) / peakEquity) * 100.0)
        val returnR = pnl / position.riskAmount
        val grossProfit = state.grossProfit + if (pnl > 0.0) pnl else 0.0
        val grossLoss = state.grossLoss + if (pnl < 0.0) abs(pnl) else 0.0
        val closedTrades = state.closedTrades + 1
        paperTradingStore.recordPosition(
            PaperPositionRecord(
                symbol = state.symbol,
                side = policyState.side,
                quantity = BigDecimal.ZERO,
                entryPrice = policyState.entryPrice.toBigDecimal(),
                realizedPnl = pnl.toBigDecimal(),
                unrealizedPnl = BigDecimal.ZERO,
                capturedAt = exit.exitedAt,
            ),
        )
        paperTradingStore.recordPerformanceSnapshot(
            PaperPerformanceSnapshot(
                period = PAPER_RUNTIME_PERIOD,
                netPnl = (equity - config.initialEquity.toDouble()).toBigDecimal(),
                profitFactor = if (grossLoss == 0.0) null else (grossProfit / grossLoss).toBigDecimal(),
                expectancy = ((state.sumReturnR + returnR) / closedTrades).toBigDecimal(),
                maxDrawdown = maxDrawdownPct.toBigDecimal(),
                capturedAt = exit.exitedAt,
            ),
        )
        val closedState =
            state.copy(
                phase = PaperRuntimePhase.FLAT,
                equity = equity,
                peakEquity = peakEquity,
                maxDrawdownPct = maxDrawdownPct,
                grossProfit = grossProfit,
                grossLoss = grossLoss,
                sumReturnR = state.sumReturnR + returnR,
                closedTrades = closedTrades,
                pendingEntry = null,
                openPosition = null,
            )
        return PaperProcessOutcome(
            state = closedState,
            result =
                result(
                    context = context,
                    status = PaperEvaluationStatus.CLOSED,
                    reasonCodes = listOf("POSITION_${exit.reason.name}"),
                    state = closedState,
                    signalId = position.signalId,
                    orderId = exitOrderId,
                    fillPrice = exitPrice.toBigDecimal(),
                    quantity = exit.remainingQuantity.toBigDecimal(),
                    fee = totalFees.toBigDecimal(),
                    exitReason = exit.reason.name,
                    realizedPnl = pnl.toBigDecimal(),
                ),
        )
    }

    private fun result(
        context: EvaluationContext,
        status: PaperEvaluationStatus,
        reasonCodes: List<String>,
        state: PaperRuntimeState,
        signalId: Long? = null,
        orderId: Long? = null,
        fillPrice: BigDecimal? = null,
        quantity: BigDecimal? = null,
        fee: BigDecimal? = null,
        exitReason: String? = null,
        realizedPnl: BigDecimal? = null,
    ): PaperEvaluationResult =
        PaperEvaluationResult(
            symbol = context.symbol,
            timeframe = context.timeframe,
            mode = context.mode.name,
            status = status,
            evaluatedAt = context.evaluatedAt,
            candleCount = context.candleCount,
            reasonCodes = reasonCodes,
            signalId = signalId,
            orderId = orderId,
            fillPrice = fillPrice,
            quantity = quantity,
            fee = fee,
            phase = state.phase,
            exitReason = exitReason,
            realizedPnl = realizedPnl,
            equity = state.equity.toBigDecimal(),
        )

    private fun applyExitSlippage(
        side: Side,
        triggerPrice: Double,
    ): Double =
        when (side) {
            Side.BUY -> triggerPrice * (1.0 - config.exitSlippageRate.toDouble())
            Side.SELL -> triggerPrice * (1.0 + config.exitSlippageRate.toDouble())
        }

    private fun fundingCost(
        side: Side,
        notional: Double,
        entryAt: Instant,
        exitAt: Instant,
    ): Double {
        val rate = config.fundingRatePer8h.toDouble()
        if (rate == 0.0) return 0.0
        val heldHours = ChronoUnit.SECONDS.between(entryAt, exitAt) / 3_600.0
        val sideMultiplier = if (side == Side.BUY) 1.0 else -1.0
        return notional * rate * (heldHours / 8.0) * sideMultiplier
    }
}

private data class EvaluationContext(
    val symbol: Symbol,
    val timeframe: Timeframe,
    val mode: BotMode,
    val evaluatedAt: Instant,
    val candleCount: Int,
    val allowsNewEntries: Boolean = true,
)

private data class PaperProcessOutcome(
    val state: PaperRuntimeState,
    val result: PaperEvaluationResult,
) {
    fun withPrependedReason(reason: String): PaperProcessOutcome =
        copy(result = result.copy(reasonCodes = listOf(reason) + result.reasonCodes))
}

private fun PaperRuntimeState.toFlat(): PaperRuntimeState =
    copy(
        phase = PaperRuntimePhase.FLAT,
        pendingEntry = null,
        openPosition = null,
    )

private fun PaperRuntimeState.requireContiguousNextCandle(
    candle: dev.yaklede.bybittrader.domain.Candle,
    timeframe: Timeframe,
) {
    val previous = lastProcessedCandleAt ?: return
    require(candle.openedAt == previous.plus(timeframe.replayDuration())) {
        "PAPER_CANDLE_GAP expected=${previous.plus(timeframe.replayDuration())} actual=${candle.openedAt}"
    }
}

private fun PaperPendingEntry.toSignalIntent(
    symbol: Symbol,
    strategy: String,
): SignalIntent =
    SignalIntent(
        symbol = symbol,
        side = side,
        strategy = strategy,
        score = SignalScore(0, listOf("PERSISTED_PENDING_SIGNAL")),
        invalidationPrice = Price(structuralStopPrice.toBigDecimal()),
        expectedR = expectedR.toBigDecimal(),
        entryAnchoredStopDistance = entryAnchoredStopDistance?.toBigDecimal(),
    )

private fun SignalIntent.toRecord(
    accepted: Boolean,
    rejectionReason: String?,
    createdAt: Instant,
): PaperSignalRecord =
    PaperSignalRecord(
        strategy = strategy,
        symbol = symbol,
        side = side,
        score = score.total,
        grade = score.total.toGrade(),
        reasonCodes = score.reasonCodes,
        accepted = accepted,
        rejectionReason = rejectionReason,
        createdAt = createdAt,
    )

private fun Int.toGrade(): String =
    when {
        this >= 85 -> "A"
        this >= 75 -> "B"
        else -> "C"
    }

private fun Side.opposite(): Side = if (this == Side.BUY) Side.SELL else Side.BUY

private fun grossPnl(
    side: Side,
    entryPrice: Double,
    exitPrice: Double,
    quantity: Double,
): Double =
    when (side) {
        Side.BUY -> (exitPrice - entryPrice) * quantity
        Side.SELL -> (entryPrice - exitPrice) * quantity
    }

private fun Double.toBigDecimal(): BigDecimal = BigDecimal.valueOf(this)

private fun BotMode.blocksNewEntries(): Boolean = this != BotMode.RUNNING

private val PaperEvaluationStatus.priority: Int
    get() =
        when (this) {
            PaperEvaluationStatus.CLOSED -> 3
            PaperEvaluationStatus.FILLED -> 2
            PaperEvaluationStatus.REJECTED -> 1
            else -> 0
        }

private fun PaperEvaluationStatus.isNoteworthy(): Boolean = priority > 0

private const val PAPER_TAKER_LIQUIDITY_ROLE = "PAPER_TAKER"
private const val PAPER_RUNTIME_PERIOD = "paper-runtime-causal"
