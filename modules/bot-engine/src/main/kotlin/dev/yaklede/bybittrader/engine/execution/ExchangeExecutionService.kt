package dev.yaklede.bybittrader.engine.execution

import dev.yaklede.bybittrader.domain.BotMode
import dev.yaklede.bybittrader.domain.OrderStatus
import dev.yaklede.bybittrader.domain.OrderType
import dev.yaklede.bybittrader.domain.ResearchCandleLimits
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.SignalIntent
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.control.BotStateStore
import dev.yaklede.bybittrader.engine.market.MarketCandleStore
import dev.yaklede.bybittrader.engine.paper.PaperOrderRecord
import dev.yaklede.bybittrader.engine.paper.PaperSignalRecord
import dev.yaklede.bybittrader.engine.paper.PaperTradingStore
import dev.yaklede.bybittrader.strategy.TradingStrategy
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class ExchangeExecutionService(
    private val stateStore: BotStateStore,
    private val candleStore: MarketCandleStore,
    private val tradingStore: PaperTradingStore,
    private val strategy: TradingStrategy,
    private val gateway: ExchangeExecutionGateway,
    private val config: ExchangeExecutionConfig = ExchangeExecutionConfig(),
    private val projectionStore: ExecutionProjectionStore? = tradingStore as? ExecutionProjectionStore,
    private val lifecycleStore: ExecutionLifecycleStore? = tradingStore as? ExecutionLifecycleStore,
    private val runtimeMode: ExecutionRuntimeMode = ExecutionRuntimeMode.TESTNET,
    private val positionPolicy: AutomaticPositionPolicy? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(ExchangeExecutionService::class.java)
    private val sessionStartedAt = Instant.now(clock)

    suspend fun evaluateAndSubmit(
        symbol: Symbol,
        timeframe: Timeframe,
        candleLimit: Int,
    ): ExchangeEvaluationResult {
        require(candleLimit in strategy.warmupCandles..ResearchCandleLimits.MAX_M5_REPLAY_CANDLES) {
            "Candle limit must be between strategy warmup candles and ${ResearchCandleLimits.MAX_M5_REPLAY_CANDLES}."
        }

        val now = Instant.now(clock)
        val mode = stateStore.current().mode
        logger.info(
            "execution evaluate requested symbol={} timeframe={} candleLimit={} mode={} enabled={}",
            symbol.value,
            timeframe.name,
            candleLimit,
            mode.name,
            config.enabled,
        )
        if (!config.enabled) {
            val result =
                ExchangeEvaluationResult(
                    symbol = symbol,
                    timeframe = timeframe,
                    mode = mode.name,
                    status = ExchangeEvaluationStatus.DISABLED,
                    evaluatedAt = now,
                    candleCount = 0,
                    reasonCodes = listOf("PRIVATE_EXECUTION_DISABLED"),
                    signalId = null,
                    orderId = null,
                    exchangeOrderId = null,
                    clientOrderId = null,
                    entryPrice = null,
                    takeProfit = null,
                    stopLoss = null,
                    quantity = null,
                    intendedRisk = null,
                )
            logger.info("execution evaluate skipped symbol={} status={}", symbol.value, result.status.name)
            return result
        }
        val activePositionPolicy = positionPolicy
        val managedPositions =
            if (activePositionPolicy != null && mode.allowsPositionManagement()) {
                gateway.positions(symbol)
            } else {
                null
            }
        val expiredPosition =
            managedPositions
                ?.firstOrNull { position ->
                    val openedAt = position.openedAt
                    position.size > BigDecimal.ZERO &&
                        openedAt != null &&
                        activePositionPolicy?.isExpired(openedAt, now) == true
                }
        if (expiredPosition != null) {
            val pendingTimeExit =
                gateway.openOrders(symbol).firstOrNull { order ->
                    order.status.isActive() && order.clientOrderId?.startsWith("time-${symbol.value}-") == true
                }
            if (pendingTimeExit != null) {
                return ExchangeEvaluationResult(
                    symbol = symbol,
                    timeframe = timeframe,
                    mode = mode.name,
                    status = ExchangeEvaluationStatus.NO_TRADE,
                    evaluatedAt = now,
                    candleCount = 0,
                    reasonCodes = listOf("MAX_HOLD_EXIT_PENDING"),
                    signalId = null,
                    orderId = null,
                    exchangeOrderId = pendingTimeExit.exchangeOrderId,
                    clientOrderId = pendingTimeExit.clientOrderId,
                    entryPrice = expiredPosition.entryPrice,
                    takeProfit = null,
                    stopLoss = null,
                    quantity = expiredPosition.size,
                    intendedRisk = null,
                )
            }
            return submitPolicyTimeExit(expiredPosition, timeframe, mode, now)
        }
        if (mode.blocksNewEntries()) {
            val result =
                ExchangeEvaluationResult(
                    symbol = symbol,
                    timeframe = timeframe,
                    mode = mode.name,
                    status = ExchangeEvaluationStatus.SKIPPED_BY_MODE,
                    evaluatedAt = now,
                    candleCount = 0,
                    reasonCodes = listOf("MODE_${mode.name}_BLOCKS_NEW_ENTRIES"),
                    signalId = null,
                    orderId = null,
                    exchangeOrderId = null,
                    clientOrderId = null,
                    entryPrice = null,
                    takeProfit = null,
                    stopLoss = null,
                    quantity = null,
                    intendedRisk = null,
                )
            logger.info(
                "execution evaluate skipped symbol={} status={} mode={}",
                symbol.value,
                result.status.name,
                mode.name,
            )
            return result
        }
        if (activePositionPolicy != null) {
            val completedTradesToday = completedTradeCountForUtcDay(symbol, now)
            if (completedTradesToday >= activePositionPolicy.maxTradesPerUtcDay) {
                return ExchangeEvaluationResult(
                    symbol = symbol,
                    timeframe = timeframe,
                    mode = mode.name,
                    status = ExchangeEvaluationStatus.NO_TRADE,
                    evaluatedAt = now,
                    candleCount = 0,
                    reasonCodes = listOf("DAILY_TRADE_LIMIT_REACHED"),
                    signalId = null,
                    orderId = null,
                    exchangeOrderId = null,
                    clientOrderId = null,
                    entryPrice = null,
                    takeProfit = null,
                    stopLoss = null,
                    quantity = null,
                    intendedRisk = null,
                )
            }
        }

        val closedBefore = closedCandleBoundary(now, timeframe)
        val candles =
            candleStore
                .recentCandles(symbol, timeframe, candleLimit)
                .filter { candle -> candle.openedAt.isBefore(closedBefore) }
                .sortedBy { it.openedAt }
        if (candles.size < strategy.warmupCandles) {
            return ExchangeEvaluationResult(
                symbol = symbol,
                timeframe = timeframe,
                mode = mode.name,
                status = ExchangeEvaluationStatus.NO_TRADE,
                evaluatedAt = now,
                candleCount = candles.size,
                reasonCodes = listOf("INSUFFICIENT_CLOSED_CANDLE_HISTORY"),
                signalId = null,
                orderId = null,
                exchangeOrderId = null,
                clientOrderId = null,
                entryPrice = null,
                takeProfit = null,
                stopLoss = null,
                quantity = null,
                intendedRisk = null,
            )
        }

        val decision = strategy.evaluate(candles)
        val signal = decision.intent
        if (signal == null) {
            val result =
                ExchangeEvaluationResult(
                    symbol = symbol,
                    timeframe = timeframe,
                    mode = mode.name,
                    status = ExchangeEvaluationStatus.NO_TRADE,
                    evaluatedAt = now,
                    candleCount = candles.size,
                    reasonCodes = decision.reasonCodes.ifEmpty { listOf("NO_SIGNAL") },
                    signalId = null,
                    orderId = null,
                    exchangeOrderId = null,
                    clientOrderId = null,
                    entryPrice = null,
                    takeProfit = null,
                    stopLoss = null,
                    quantity = null,
                    intendedRisk = null,
                )
            logger.info(
                "execution evaluate completed symbol={} status={} candleCount={} reasonCodes={}",
                symbol.value,
                result.status.name,
                result.candleCount,
                result.reasonCodes.joinToString(","),
            )
            return result
        }
        if (signal.isDuplicate()) {
            val signalKey = signal.score.reasonCodes.first { it.startsWith(SIGNAL_KEY_PREFIX) }
            val result =
                ExchangeEvaluationResult(
                    symbol = symbol,
                    timeframe = timeframe,
                    mode = mode.name,
                    status = ExchangeEvaluationStatus.NO_TRADE,
                    evaluatedAt = now,
                    candleCount = candles.size,
                    reasonCodes = listOf("DUPLICATE_SIGNAL", signalKey),
                    signalId = null,
                    orderId = null,
                    exchangeOrderId = null,
                    clientOrderId = null,
                    entryPrice = null,
                    takeProfit = null,
                    stopLoss = null,
                    quantity = null,
                    intendedRisk = null,
                )
            logger.info("execution evaluate completed symbol={} status={} reason={}", symbol.value, result.status.name, signalKey)
            return result
        }

        val entryPrice = candles.last().close
        val riskPerUnit = entryPrice.subtract(signal.invalidationPrice.value).abs()
        val accountEquity = executionAccountEquity()
        val intendedRisk = accountEquity.multiply(config.riskFraction, MathContext.DECIMAL64)
        val sizing =
            ExecutionTradePlanCalculator.calculateSizing(
                entryPrice = entryPrice,
                riskPerUnit = riskPerUnit,
                intendedRisk = intendedRisk,
                accountEquity = accountEquity,
                constraints = config.sizingConstraints(),
            )
        if (sizing == null) {
            val rejectedSignalId =
                tradingStore.recordSignal(
                    signal.toRecord(
                        accepted = false,
                        rejectionReason = "INVALID_EXECUTION_SIZE",
                        createdAt = now,
                    ),
                )
            val result =
                ExchangeEvaluationResult(
                    symbol = symbol,
                    timeframe = timeframe,
                    mode = mode.name,
                    status = ExchangeEvaluationStatus.REJECTED,
                    evaluatedAt = now,
                    candleCount = candles.size,
                    reasonCodes = listOf("INVALID_EXECUTION_SIZE"),
                    signalId = rejectedSignalId,
                    orderId = null,
                    exchangeOrderId = null,
                    clientOrderId = null,
                    entryPrice = entryPrice,
                    takeProfit = null,
                    stopLoss = signal.invalidationPrice.value,
                    quantity = null,
                    intendedRisk = intendedRisk,
                )
            logger.info("execution evaluate rejected symbol={} signalId={} reason=INVALID_EXECUTION_SIZE", symbol.value, rejectedSignalId)
            return result
        }

        val takeProfit =
            ExecutionTradePlanCalculator.calculateTakeProfit(
                side = signal.side,
                entryPrice = entryPrice,
                riskPerUnit = riskPerUnit,
                expectedR = signal.expectedR,
            )
        val targetStopRejection =
            ExecutionTradePlanCalculator.targetStopRejection(
                side = signal.side,
                entryPrice = entryPrice,
                takeProfit = takeProfit,
                stopLoss = signal.invalidationPrice.value,
                feeRate = config.feeRate,
                slippageBufferRate = config.slippageBufferRate,
            ) ?: ExecutionTradePlanCalculator.leverageStopRejection(
                side = signal.side,
                entryPrice = entryPrice,
                stopLoss = signal.invalidationPrice.value,
                leverage = config.leverage,
                liquidationBufferPct = config.liquidationBufferPct,
            )
        if (targetStopRejection != null) {
            val rejectedSignalId =
                tradingStore.recordSignal(
                    signal.toRecord(
                        accepted = false,
                        rejectionReason = targetStopRejection,
                        createdAt = now,
                    ),
                )
            return ExchangeEvaluationResult(
                symbol = symbol,
                timeframe = timeframe,
                mode = mode.name,
                status = ExchangeEvaluationStatus.REJECTED,
                evaluatedAt = now,
                candleCount = candles.size,
                reasonCodes = listOf(targetStopRejection),
                signalId = rejectedSignalId,
                orderId = null,
                exchangeOrderId = null,
                clientOrderId = null,
                entryPrice = entryPrice,
                takeProfit = takeProfit,
                stopLoss = signal.invalidationPrice.value,
                quantity = sizing.quantity,
                intendedRisk = intendedRisk,
            )
        }
        val hasActiveOpenOrder =
            gateway.openOrders(symbol).any { order ->
                order.status == OrderStatus.SUBMITTED || order.status == OrderStatus.PARTIALLY_FILLED
            }
        val hasOpenPosition =
            (managedPositions ?: gateway.positions(symbol)).any { position -> position.size > BigDecimal.ZERO }
        if (hasActiveOpenOrder || hasOpenPosition) {
            val rejectionReason = "EXISTING_EXCHANGE_EXPOSURE"
            val rejectedSignalId =
                tradingStore.recordSignal(
                    signal.toRecord(
                        accepted = false,
                        rejectionReason = rejectionReason,
                        createdAt = now,
                    ),
                )
            return ExchangeEvaluationResult(
                symbol = symbol,
                timeframe = timeframe,
                mode = mode.name,
                status = ExchangeEvaluationStatus.REJECTED,
                evaluatedAt = now,
                candleCount = candles.size,
                reasonCodes = listOf(rejectionReason),
                signalId = rejectedSignalId,
                orderId = null,
                exchangeOrderId = null,
                clientOrderId = null,
                entryPrice = entryPrice,
                takeProfit = takeProfit,
                stopLoss = signal.invalidationPrice.value,
                quantity = sizing.quantity,
                intendedRisk = intendedRisk,
            )
        }
        val signalId =
            tradingStore.recordSignal(
                signal.toRecord(
                    accepted = true,
                    rejectionReason = null,
                    createdAt = now,
                ),
            )
        val clientOrderId = clientOrderId(symbol = symbol, side = signal.side, now = now, signalId = signalId)
        syncLeverage(symbol)
        val orderResult =
            gateway.placeOrder(
                ExchangeOrderRequest(
                    symbol = symbol,
                    side = signal.side,
                    orderType = OrderType.MARKET,
                    quantity = sizing.quantity,
                    clientOrderId = clientOrderId,
                    takeProfit = takeProfit,
                    stopLoss = signal.invalidationPrice.value,
                ),
            )
        val orderId =
            tradingStore.recordOrder(
                PaperOrderRecord(
                    exchangeOrderId = orderResult.exchangeOrderId,
                    clientOrderId = clientOrderId,
                    signalId = signalId,
                    side = signal.side,
                    orderType = OrderType.MARKET,
                    orderStatus = orderResult.status,
                    intendedRisk = intendedRisk,
                    createdAt = now,
                ),
            )
        recordSubmissionLifecycle(
            state = ExecutionLifecycleState.ENTRY_SUBMITTED,
            lifecycleId = clientOrderId,
            symbol = symbol,
            side = signal.side,
            requestedQuantity = sizing.quantity,
            takeProfit = takeProfit,
            stopLoss = signal.invalidationPrice.value,
            exchangeOrderId = orderResult.exchangeOrderId,
            clientOrderId = clientOrderId,
            reasonCode = "AUTOMATIC_ENTRY_SUBMITTED",
            occurredAt = now,
        )

        val result =
            ExchangeEvaluationResult(
                symbol = symbol,
                timeframe = timeframe,
                mode = mode.name,
                status = ExchangeEvaluationStatus.SUBMITTED,
                evaluatedAt = now,
                candleCount = candles.size,
                reasonCodes = decision.reasonCodes.ifEmpty { signal.score.reasonCodes },
                signalId = signalId,
                orderId = orderId,
                exchangeOrderId = orderResult.exchangeOrderId,
                clientOrderId = clientOrderId,
                entryPrice = entryPrice,
                takeProfit = takeProfit,
                stopLoss = signal.invalidationPrice.value,
                quantity = sizing.quantity,
                intendedRisk = intendedRisk,
            )
        logger.info(
            "execution order submitted symbol={} side={} signalId={} orderId={} exchangeOrderId={} qty={}",
            symbol.value,
            signal.side.name,
            signalId,
            orderId,
            orderResult.exchangeOrderId,
            sizing.quantity.toPlainString(),
        )
        return result
    }

    suspend fun reconcile(symbol: Symbol): ExchangeReconciliationReport = fetchReconciliation(symbol)

    suspend fun persistDiscoveredClosures(symbol: Symbol): List<ExecutionTradeClosure> {
        logger.info("execution closure discovery requested symbol={}", symbol.value)
        return persistDiscoveredClosures(symbol, gateway.closedPnls(symbol))
    }

    suspend fun persistExchangeState(symbol: Symbol): ExchangeReconciliationReport {
        val report = fetchReconciliation(symbol)
        val persistedClosures = persistDiscoveredClosures(symbol, report.closedPnls)
        val lifecycleEvent = persistLifecycleObservation(report)
        return report.copy(
            persistedClosures = persistedClosures,
            lifecycleEvent = lifecycleEvent,
        )
    }

    private suspend fun persistDiscoveredClosures(
        symbol: Symbol,
        closedPnls: List<ExchangeClosedPnl>,
    ): List<ExecutionTradeClosure> {
        val store = projectionStore ?: return emptyList()
        val firstBootstrap = !store.hasClosureHistory(runtimeMode, symbol)
        val persistedClosures =
            closedPnls.mapNotNull { closedPnl ->
                val closure = closedPnl.toTradeClosure(runtimeMode)
                val suppressedAt = sessionStartedAt.takeIf { firstBootstrap && closure.closedAt.isBefore(it) }
                store
                    .recordTradeClosure(closure, suppressedAt = suppressedAt)
                    ?.let { id -> closure.copy(id = id) }
            }
        if (persistedClosures.isNotEmpty()) {
            refreshPerformanceSnapshots()
        }
        logger.info(
            "execution closure discovery completed symbol={} newClosures={}",
            symbol.value,
            persistedClosures.size,
        )
        return persistedClosures
    }

    private suspend fun persistLifecycleObservation(report: ExchangeReconciliationReport): ExecutionLifecycleEvent? {
        val store = lifecycleStore ?: return null
        val latest = store.latestLifecycleEvent(runtimeMode, report.symbol)
        val activePosition = report.positions.firstOrNull { position -> position.size > BigDecimal.ZERO }
        val observedClosure =
            latest
                ?.takeIf { event -> event.state != ExecutionLifecycleState.CLOSED }
                ?.let { event ->
                    report.closedPnls
                        .filter { closure -> !closure.closedAt.isBefore(event.occurredAt) }
                        .maxByOrNull(ExchangeClosedPnl::closedAt)
                }
        if (latest != null && observedClosure != null) {
            return recordObservedLifecycle(
                latest.copy(
                    id = 0,
                    state = ExecutionLifecycleState.CLOSED,
                    filledQuantity = observedClosure.quantity,
                    fillVwap = observedClosure.entryPrice,
                    exchangeOrderId = observedClosure.exchangeOrderId,
                    clientOrderId = observedClosure.clientOrderId,
                    reasonCode = observedClosure.exitReason ?: "CLOSED_PNL_OBSERVED",
                    occurredAt = observedClosure.closedAt,
                ),
            )
        }
        val relatedOpenOrder =
            latest?.let { event ->
                report.openOrders.firstOrNull { order ->
                    order.status.isActive() && order.matches(event)
                }
            }
        if (
            activePosition != null &&
            latest?.state == ExecutionLifecycleState.EXIT_SUBMITTED &&
            relatedOpenOrder != null
        ) {
            return null
        }
        if (activePosition != null) {
            val base =
                latest?.takeIf { event -> event.state != ExecutionLifecycleState.CLOSED }
                    ?: recoveredLifecycleEvent(activePosition, report.reconciledAt)
            val protected = activePosition.takeProfit != null && activePosition.stopLoss != null
            return recordObservedLifecycle(
                base.copy(
                    id = 0,
                    state =
                        if (protected) {
                            ExecutionLifecycleState.OPEN_PROTECTED
                        } else {
                            ExecutionLifecycleState.OPEN_UNPROTECTED
                        },
                    side = activePosition.side,
                    filledQuantity = activePosition.size,
                    fillVwap = activePosition.entryPrice,
                    takeProfit = activePosition.takeProfit,
                    stopLoss = activePosition.stopLoss,
                    reasonCode =
                        if (protected) {
                            "PROTECTED_POSITION_OBSERVED"
                        } else {
                            "UNPROTECTED_POSITION_OBSERVED"
                        },
                    occurredAt = activePosition.updatedAt ?: report.reconciledAt,
                ),
            )
        }
        if (latest == null || latest.state == ExecutionLifecycleState.CLOSED) return null
        if (relatedOpenOrder != null && latest.state == ExecutionLifecycleState.EXIT_SUBMITTED) {
            return null
        }
        val relatedFills = report.executions.filter { fill -> fill.matches(latest) }
        if (
            relatedFills.isNotEmpty() &&
            latest.state in setOf(ExecutionLifecycleState.ENTRY_SUBMITTED, ExecutionLifecycleState.PARTIALLY_FILLED)
        ) {
            val filledQuantity = relatedFills.fold(BigDecimal.ZERO) { total, fill -> total + fill.quantity }
            return recordObservedLifecycle(
                latest.copy(
                    id = 0,
                    state = ExecutionLifecycleState.PARTIALLY_FILLED,
                    filledQuantity = filledQuantity,
                    fillVwap = relatedFills.weightedVwap(),
                    reasonCode = "ENTRY_FILL_OBSERVED_WITHOUT_POSITION",
                    occurredAt = relatedFills.maxOf(ExchangeExecutionFill::executedAt),
                ),
            )
        }
        return null
    }

    private suspend fun recordObservedLifecycle(event: ExecutionLifecycleEvent): ExecutionLifecycleEvent? {
        val store = lifecycleStore ?: return null
        val latest = store.latestLifecycleEvent(event.mode, event.symbol)
        if (latest != null && latest.lifecycleId == event.lifecycleId) {
            require(latest.state.canTransitionTo(event.state)) {
                "Invalid execution lifecycle transition from ${latest.state} to ${event.state}."
            }
        }
        val normalizedEvent =
            event.copy(
                occurredAt =
                    latest
                        ?.occurredAt
                        ?.takeIf { event.occurredAt.isBefore(it) }
                        ?: event.occurredAt,
            )
        return store.recordLifecycleEvent(normalizedEvent)?.let { id -> normalizedEvent.copy(id = id) }
    }

    private fun recoveredLifecycleEvent(
        position: ExchangePosition,
        reconciledAt: Instant,
    ): ExecutionLifecycleEvent {
        val openedAt = position.openedAt ?: reconciledAt
        return ExecutionLifecycleEvent(
            mode = runtimeMode,
            lifecycleId = "recovered-${position.symbol.value}-${openedAt.toEpochMilli()}",
            symbol = position.symbol,
            state = ExecutionLifecycleState.OPEN_UNPROTECTED,
            side = position.side,
            requestedQuantity = position.size,
            filledQuantity = position.size,
            fillVwap = position.entryPrice,
            takeProfit = position.takeProfit,
            stopLoss = position.stopLoss,
            exchangeOrderId = null,
            clientOrderId = null,
            reasonCode = "POSITION_RECOVERED_FROM_EXCHANGE",
            occurredAt = position.updatedAt ?: reconciledAt,
        )
    }

    suspend fun pendingClosureAlerts(
        symbol: Symbol,
        limit: Int,
    ): List<PendingExecutionClosureAlert> =
        (projectionStore ?: EmptyExecutionProjectionStore)
            .pendingClosureAlerts(runtimeMode, symbol, limit)

    suspend fun recordClosureAlertAttempt(
        closureId: Long,
        attemptedAt: Instant,
        delivered: Boolean,
    ) {
        (projectionStore ?: EmptyExecutionProjectionStore)
            .recordClosureAlertAttempt(closureId, attemptedAt, delivered)
    }

    private suspend fun fetchReconciliation(symbol: Symbol): ExchangeReconciliationReport {
        logger.info("execution reconcile read requested symbol={}", symbol.value)
        val report =
            ExchangeReconciliationReport(
                symbol = symbol,
                reconciledAt = Instant.now(clock),
                openOrders = gateway.openOrders(symbol),
                positions = gateway.positions(symbol),
                executions = gateway.executions(symbol),
                closedPnls = gateway.closedPnls(symbol),
            )
        logger.info(
            "execution reconcile read completed symbol={} openOrders={} positions={} executions={}",
            symbol.value,
            report.openOrders.size,
            report.positions.size,
            report.executions.size,
        )
        return report
    }

    private suspend fun refreshPerformanceSnapshots() {
        val store = projectionStore ?: return
        val capturedAt = Instant.now(clock)
        LivePerformanceWindow.values().forEach { window ->
            val closures = store.performanceClosures(runtimeMode, window.startAt(capturedAt, sessionStartedAt))
            store.recordLivePerformanceSnapshot(
                closures.toPerformanceSnapshot(
                    mode = runtimeMode,
                    window = window,
                    capturedAt = capturedAt,
                ),
            )
        }
    }

    private suspend fun completedTradeCountForUtcDay(
        symbol: Symbol,
        evaluatedAt: Instant,
    ): Int {
        val dayStart =
            evaluatedAt
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
        val closures =
            projectionStore?.performanceClosures(runtimeMode, dayStart)
                ?: gateway.closedPnls(symbol).map { closedPnl -> closedPnl.toTradeClosure(runtimeMode) }
        return closures.count { closure ->
            closure.symbol == symbol && !closure.openedAt.isBefore(dayStart)
        }
    }

    suspend fun accountBalance(coin: String? = null): ExchangeAccountBalance {
        logger.info("execution account balance requested coin={}", coin ?: "all")
        val balance = gateway.accountBalance(coin)
        logger.info(
            "execution account balance completed accountType={} coins={}",
            balance.accountType,
            balance.coins.joinToString(",") { it.coin },
        )
        return balance
    }

    suspend fun cancelOrder(request: ExchangeCancelRequest): ExchangeCancelResult {
        logger.info("execution cancel requested symbol={}", request.symbol.value)
        val result = gateway.cancelOrder(request)
        logger.info("execution cancel completed symbol={} exchangeOrderId={}", request.symbol.value, result.exchangeOrderId)
        return result
    }

    suspend fun submitSmokeMarketOrder(
        symbol: Symbol,
        side: Side,
        quantity: BigDecimal,
    ): ExchangeSmokeOrderResult {
        require(config.enabled) { "Private execution must be enabled for smoke order." }
        require(quantity >= config.minQuantity) {
            "Smoke order quantity must be greater than or equal to ${config.minQuantity.toPlainString()}."
        }
        config.maxQuantity?.let { maxQuantity ->
            require(quantity <= maxQuantity) {
                "Smoke order quantity must be less than or equal to ${maxQuantity.toPlainString()}."
            }
        }
        val normalizedQuantity = quantity.floorToStep(config.quantityStep)
        require(normalizedQuantity == quantity.stripTrailingZeros()) {
            "Smoke order quantity must align with quantity step ${config.quantityStep.toPlainString()}."
        }

        val now = Instant.now(clock)
        val clientOrderId = smokeClientOrderId(symbol = symbol, side = side, now = now)
        val signalId =
            tradingStore.recordSignal(
                PaperSignalRecord(
                    strategy = "smoke-test",
                    symbol = symbol,
                    side = side,
                    score = 0,
                    grade = "SMOKE",
                    reasonCodes = listOf("TESTNET_MARKET_ORDER_SMOKE"),
                    accepted = true,
                    rejectionReason = null,
                    createdAt = now,
                ),
            )
        logger.warn(
            "execution smoke market order requested symbol={} side={} qty={}",
            symbol.value,
            side.name,
            quantity.toPlainString(),
        )
        syncLeverage(symbol)
        val orderResult =
            gateway.placeOrder(
                ExchangeOrderRequest(
                    symbol = symbol,
                    side = side,
                    orderType = OrderType.MARKET,
                    quantity = quantity,
                    clientOrderId = clientOrderId,
                    takeProfit = null,
                    stopLoss = null,
                ),
            )
        val orderId =
            tradingStore.recordOrder(
                PaperOrderRecord(
                    exchangeOrderId = orderResult.exchangeOrderId,
                    clientOrderId = clientOrderId,
                    signalId = signalId,
                    side = side,
                    orderType = OrderType.MARKET,
                    orderStatus = orderResult.status,
                    intendedRisk = BigDecimal.ZERO,
                    createdAt = now,
                ),
            )
        recordSubmissionLifecycle(
            state = ExecutionLifecycleState.ENTRY_SUBMITTED,
            lifecycleId = clientOrderId,
            symbol = symbol,
            side = side,
            requestedQuantity = quantity,
            takeProfit = null,
            stopLoss = null,
            exchangeOrderId = orderResult.exchangeOrderId,
            clientOrderId = clientOrderId,
            reasonCode = "SMOKE_ENTRY_SUBMITTED",
            occurredAt = now,
        )
        logger.warn(
            "execution smoke market order submitted symbol={} side={} signalId={} orderId={} exchangeOrderId={}",
            symbol.value,
            side.name,
            signalId,
            orderId,
            orderResult.exchangeOrderId,
        )
        return ExchangeSmokeOrderResult(
            symbol = symbol,
            side = side,
            quantity = quantity,
            exchangeOrderId = orderResult.exchangeOrderId,
            clientOrderId = clientOrderId,
            orderId = orderId,
            status = orderResult.status.name,
            submittedAt = now,
        )
    }

    suspend fun submitManualMarketOrder(
        symbol: Symbol,
        side: Side,
        quantity: BigDecimal,
    ): ExchangeManualOrderResult =
        submitManualOrder(
            symbol = symbol,
            side = side,
            quantity = quantity,
            reduceOnly = false,
            strategyName = "manual-market-order",
            reasonCode = "MANUAL_MARKET_ORDER",
            clientOrderPrefix = "manual",
        )

    suspend fun submitReduceOnlyCloseOrder(
        symbol: Symbol,
        positionSide: Side,
        quantity: BigDecimal,
    ): ExchangeManualOrderResult =
        submitManualOrder(
            symbol = symbol,
            side = positionSide.opposite(),
            quantity = quantity,
            reduceOnly = true,
            strategyName = "manual-close-position",
            reasonCode = "MANUAL_CLOSE_POSITION",
            clientOrderPrefix = "close",
        )

    private suspend fun submitPolicyTimeExit(
        position: ExchangePosition,
        timeframe: Timeframe,
        mode: BotMode,
        evaluatedAt: Instant,
    ): ExchangeEvaluationResult {
        val exitOrder =
            submitManualOrder(
                symbol = position.symbol,
                side = position.side.opposite(),
                quantity = position.size,
                reduceOnly = true,
                strategyName = "automatic-position-policy",
                reasonCode = "MAX_HOLD_DURATION_REACHED",
                clientOrderPrefix = "time",
            )
        logger.warn(
            "execution time exit submitted symbol={} openedAt={} qty={} exchangeOrderId={}",
            position.symbol.value,
            position.openedAt,
            position.size.toPlainString(),
            exitOrder.exchangeOrderId,
        )
        return ExchangeEvaluationResult(
            symbol = position.symbol,
            timeframe = timeframe,
            mode = mode.name,
            status = ExchangeEvaluationStatus.EXIT_SUBMITTED,
            evaluatedAt = evaluatedAt,
            candleCount = 0,
            reasonCodes = listOf("MAX_HOLD_DURATION_REACHED"),
            signalId = null,
            orderId = exitOrder.orderId,
            exchangeOrderId = exitOrder.exchangeOrderId,
            clientOrderId = exitOrder.clientOrderId,
            entryPrice = position.entryPrice,
            takeProfit = null,
            stopLoss = null,
            quantity = position.size,
            intendedRisk = null,
        )
    }

    private suspend fun submitManualOrder(
        symbol: Symbol,
        side: Side,
        quantity: BigDecimal,
        reduceOnly: Boolean,
        strategyName: String,
        reasonCode: String,
        clientOrderPrefix: String,
    ): ExchangeManualOrderResult {
        require(config.enabled) { "Private execution must be enabled for manual order." }
        require(quantity >= config.minQuantity) {
            "Manual order quantity must be greater than or equal to ${config.minQuantity.toPlainString()}."
        }
        if (!reduceOnly) {
            config.maxQuantity?.let { maxQuantity ->
                require(quantity <= maxQuantity) {
                    "Manual order quantity must be less than or equal to ${maxQuantity.toPlainString()}."
                }
            }
        }
        val normalizedQuantity = quantity.floorToStep(config.quantityStep)
        require(normalizedQuantity == quantity.stripTrailingZeros()) {
            "Manual order quantity must align with quantity step ${config.quantityStep.toPlainString()}."
        }

        val now = Instant.now(clock)
        val clientOrderId = manualClientOrderId(prefix = clientOrderPrefix, symbol = symbol, side = side, now = now)
        val signalId =
            tradingStore.recordSignal(
                PaperSignalRecord(
                    strategy = strategyName,
                    symbol = symbol,
                    side = side,
                    score = 0,
                    grade = "MANUAL",
                    reasonCodes = listOf(reasonCode),
                    accepted = true,
                    rejectionReason = null,
                    createdAt = now,
                ),
            )
        logger.warn(
            "execution manual market order requested symbol={} side={} qty={} reduceOnly={}",
            symbol.value,
            side.name,
            quantity.toPlainString(),
            reduceOnly,
        )
        if (!reduceOnly) {
            syncLeverage(symbol)
        }
        val orderResult =
            gateway.placeOrder(
                ExchangeOrderRequest(
                    symbol = symbol,
                    side = side,
                    orderType = OrderType.MARKET,
                    quantity = quantity,
                    clientOrderId = clientOrderId,
                    takeProfit = null,
                    stopLoss = null,
                    reduceOnly = reduceOnly,
                ),
            )
        val orderId =
            tradingStore.recordOrder(
                PaperOrderRecord(
                    exchangeOrderId = orderResult.exchangeOrderId,
                    clientOrderId = clientOrderId,
                    signalId = signalId,
                    side = side,
                    orderType = OrderType.MARKET,
                    orderStatus = orderResult.status,
                    intendedRisk = BigDecimal.ZERO,
                    createdAt = now,
                ),
            )
        val latestLifecycle =
            lifecycleStore
                ?.latestLifecycleEvent(runtimeMode, symbol)
                ?.takeIf { event -> event.state != ExecutionLifecycleState.CLOSED }
        val lifecycleId =
            if (reduceOnly && latestLifecycle != null) {
                latestLifecycle.lifecycleId
            } else {
                clientOrderId
            }
        recordSubmissionLifecycle(
            state =
                if (reduceOnly) {
                    ExecutionLifecycleState.EXIT_SUBMITTED
                } else {
                    ExecutionLifecycleState.ENTRY_SUBMITTED
                },
            lifecycleId = lifecycleId,
            symbol = symbol,
            side = if (reduceOnly) side.opposite() else side,
            requestedQuantity = latestLifecycle?.requestedQuantity ?: quantity,
            takeProfit = latestLifecycle?.takeProfit,
            stopLoss = latestLifecycle?.stopLoss,
            exchangeOrderId = orderResult.exchangeOrderId,
            clientOrderId = clientOrderId,
            reasonCode = reasonCode,
            occurredAt = now,
            filledQuantity = latestLifecycle?.filledQuantity,
            fillVwap = latestLifecycle?.fillVwap,
        )
        logger.warn(
            "execution manual market order submitted symbol={} side={} signalId={} orderId={} exchangeOrderId={} reduceOnly={}",
            symbol.value,
            side.name,
            signalId,
            orderId,
            orderResult.exchangeOrderId,
            reduceOnly,
        )
        return ExchangeManualOrderResult(
            symbol = symbol,
            side = side,
            quantity = quantity,
            reduceOnly = reduceOnly,
            exchangeOrderId = orderResult.exchangeOrderId,
            clientOrderId = clientOrderId,
            orderId = orderId,
            status = orderResult.status.name,
            submittedAt = now,
        )
    }

    private suspend fun recordSubmissionLifecycle(
        state: ExecutionLifecycleState,
        lifecycleId: String,
        symbol: Symbol,
        side: Side,
        requestedQuantity: BigDecimal,
        takeProfit: BigDecimal?,
        stopLoss: BigDecimal?,
        exchangeOrderId: String?,
        clientOrderId: String,
        reasonCode: String,
        occurredAt: Instant,
        filledQuantity: BigDecimal? = null,
        fillVwap: BigDecimal? = null,
    ) {
        val store = lifecycleStore ?: return
        val latest = store.latestLifecycleEvent(runtimeMode, symbol)
        if (latest != null && latest.lifecycleId == lifecycleId) {
            require(latest.state.canTransitionTo(state)) {
                "Invalid execution lifecycle transition from ${latest.state} to $state."
            }
        }
        store.recordLifecycleEvent(
            ExecutionLifecycleEvent(
                mode = runtimeMode,
                lifecycleId = lifecycleId,
                symbol = symbol,
                state = state,
                side = side,
                requestedQuantity = requestedQuantity,
                filledQuantity = filledQuantity,
                fillVwap = fillVwap,
                takeProfit = takeProfit,
                stopLoss = stopLoss,
                exchangeOrderId = exchangeOrderId,
                clientOrderId = clientOrderId,
                reasonCode = reasonCode,
                occurredAt = occurredAt,
            ),
        )
    }

    private suspend fun syncLeverage(symbol: Symbol) {
        val leverage = config.leverage ?: return
        logger.info(
            "execution leverage sync requested symbol={} leverage={}",
            symbol.value,
            leverage.toPlainString(),
        )
        gateway.setLeverage(symbol, leverage)
        logger.info(
            "execution leverage sync completed symbol={} leverage={}",
            symbol.value,
            leverage.toPlainString(),
        )
    }

    private suspend fun SignalIntent.isDuplicate(): Boolean {
        val signalKey = score.reasonCodes.firstOrNull { it.startsWith(SIGNAL_KEY_PREFIX) } ?: return false
        return tradingStore
            .recentSignals(config.duplicateSignalLookback)
            .any { recentSignal ->
                recentSignal.accepted &&
                    recentSignal.strategy == strategy &&
                    recentSignal.symbol == symbol &&
                    recentSignal.side == side &&
                    signalKey in recentSignal.reasonCodes
            }
    }

    private suspend fun executionAccountEquity(): BigDecimal {
        if (!config.useLiveAccountEquity) return config.accountEquity
        val balance = gateway.accountBalance("USDT")
        val liveEquity =
            balance.totalEquity
                ?: balance.totalWalletBalance
                ?: balance.coins.firstOrNull { it.coin.equals("USDT", ignoreCase = true) }?.equity
        if (liveEquity != null && liveEquity > BigDecimal.ZERO) return liveEquity
        logger.warn(
            "execution live account equity unavailable accountType={} fallbackEquity={}",
            balance.accountType,
            config.accountEquity.toPlainString(),
        )
        return config.accountEquity
    }

    suspend fun closedTrades(
        symbol: Symbol?,
        mode: ExecutionRuntimeMode?,
        limit: Int,
        cursor: Long?,
    ): List<ExecutionTradeClosure> =
        (projectionStore ?: EmptyExecutionProjectionStore)
            .closedTrades(symbol = symbol, mode = mode, limit = limit, cursor = cursor)

    suspend fun lifecycleEvents(
        symbol: Symbol?,
        mode: ExecutionRuntimeMode?,
        limit: Int,
    ): List<ExecutionLifecycleEvent> = lifecycleStore?.lifecycleEvents(mode = mode, symbol = symbol, limit = limit).orEmpty()

    suspend fun livePerformanceSummary(
        mode: ExecutionRuntimeMode?,
        window: LivePerformanceWindow,
    ): LivePerformanceSnapshot? {
        val store = projectionStore ?: return null
        val capturedAt = Instant.now(clock)
        val effectiveMode = mode ?: runtimeMode
        return store
            .performanceClosures(effectiveMode, window.startAt(capturedAt, sessionStartedAt))
            .toPerformanceSnapshot(
                mode = effectiveMode,
                window = window,
                capturedAt = capturedAt,
            )
    }
}

data class ExchangeTradingLoopConfig(
    val symbol: Symbol,
    val timeframe: Timeframe,
    val candleLimit: Int = 18_000,
    val syncLimit: Int = 1000,
    val intervalSeconds: Long = 300,
) {
    init {
        require(candleLimit in 20..ResearchCandleLimits.MAX_M5_REPLAY_CANDLES) {
            "Execution loop candle limit must be between 20 and ${ResearchCandleLimits.MAX_M5_REPLAY_CANDLES}."
        }
        require(syncLimit in 1..1000) { "Execution loop sync limit must be between 1 and 1000." }
        require(intervalSeconds in 10..86_400) { "Execution loop interval seconds must be between 10 and 86400." }
    }
}

private fun BotMode.blocksNewEntries(): Boolean = this != BotMode.RUNNING

private fun BotMode.allowsPositionManagement(): Boolean = this == BotMode.RUNNING || this == BotMode.PAUSE_NEW_ENTRIES

private fun OrderStatus.isActive(): Boolean = this == OrderStatus.SUBMITTED || this == OrderStatus.PARTIALLY_FILLED

private fun ExchangeOpenOrder.matches(event: ExecutionLifecycleEvent): Boolean =
    (!exchangeOrderId.isNullOrBlank() && exchangeOrderId == event.exchangeOrderId) ||
        (!clientOrderId.isNullOrBlank() && clientOrderId == event.clientOrderId)

private fun ExchangeExecutionFill.matches(event: ExecutionLifecycleEvent): Boolean =
    (!exchangeOrderId.isNullOrBlank() && exchangeOrderId == event.exchangeOrderId) ||
        (!clientOrderId.isNullOrBlank() && clientOrderId == event.clientOrderId)

private fun List<ExchangeExecutionFill>.weightedVwap(): BigDecimal {
    val totalQuantity = fold(BigDecimal.ZERO) { total, fill -> total + fill.quantity }
    require(totalQuantity > BigDecimal.ZERO) { "Execution fill quantity must be positive for VWAP." }
    val notional = fold(BigDecimal.ZERO) { total, fill -> total + fill.price.multiply(fill.quantity) }
    return notional.divide(totalQuantity, MathContext.DECIMAL128)
}

private fun closedCandleBoundary(
    instant: Instant,
    timeframe: Timeframe,
): Instant {
    val timeframeMillis =
        when (timeframe) {
            Timeframe.M1 -> 60_000L
            Timeframe.M5 -> 300_000L
            Timeframe.M15 -> 900_000L
            Timeframe.H1 -> 3_600_000L
        }
    return Instant.ofEpochMilli((instant.toEpochMilli() / timeframeMillis) * timeframeMillis)
}

private fun LivePerformanceWindow.startAt(
    capturedAt: Instant,
    sessionStartedAt: Instant,
): Instant? =
    when (this) {
        LivePerformanceWindow.SESSION -> sessionStartedAt
        LivePerformanceWindow.SEVEN_DAYS -> capturedAt.minus(Duration.ofDays(7))
        LivePerformanceWindow.THIRTY_DAYS -> capturedAt.minus(Duration.ofDays(30))
        LivePerformanceWindow.ALL -> null
    }

private fun Side.opposite(): Side =
    when (this) {
        Side.BUY -> Side.SELL
        Side.SELL -> Side.BUY
    }

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

private fun clientOrderId(
    symbol: Symbol,
    side: Side,
    now: Instant,
    signalId: Long,
): String {
    val sideCode =
        when (side) {
            Side.BUY -> "B"
            Side.SELL -> "S"
        }
    return "bt-${symbol.value}-${now.toEpochMilli()}-$signalId-$sideCode".take(36)
}

private fun smokeClientOrderId(
    symbol: Symbol,
    side: Side,
    now: Instant,
): String {
    val sideCode =
        when (side) {
            Side.BUY -> "B"
            Side.SELL -> "S"
        }
    return "smoke-${symbol.value}-${now.toEpochMilli()}-$sideCode".take(36)
}

private fun manualClientOrderId(
    prefix: String,
    symbol: Symbol,
    side: Side,
    now: Instant,
): String {
    val sideCode =
        when (side) {
            Side.BUY -> "B"
            Side.SELL -> "S"
        }
    return "$prefix-${symbol.value}-${now.toEpochMilli()}-$sideCode".take(36)
}

private fun ExchangeClosedPnl.toTradeClosure(mode: ExecutionRuntimeMode): ExecutionTradeClosure =
    ExecutionTradeClosure(
        mode = mode,
        symbol = symbol,
        side = side,
        openedAt = openedAt ?: closedAt,
        closedAt = closedAt,
        entryPrice = entryPrice,
        exitPrice = exitPrice,
        quantity = quantity,
        grossPnl = grossPnl,
        fees = fees,
        netPnl = netPnl,
        exitReason = exitReason ?: "CLOSED_PNL",
        exchangeOrderId = exchangeOrderId,
        clientOrderId = clientOrderId,
    )

private fun List<ExecutionTradeClosure>.toPerformanceSnapshot(
    mode: ExecutionRuntimeMode,
    window: LivePerformanceWindow,
    capturedAt: Instant,
): LivePerformanceSnapshot {
    val sorted = sortedBy { it.closedAt }
    val grossProfit = sorted.filter { it.netPnl > BigDecimal.ZERO }.fold(BigDecimal.ZERO) { acc, trade -> acc + trade.netPnl }
    val grossLoss = sorted.filter { it.netPnl < BigDecimal.ZERO }.fold(BigDecimal.ZERO) { acc, trade -> acc + trade.netPnl.abs() }
    val fees = sorted.fold(BigDecimal.ZERO) { acc, trade -> acc + trade.fees }
    val netPnl = sorted.fold(BigDecimal.ZERO) { acc, trade -> acc + trade.netPnl }
    val tradeCount = sorted.size
    val wins = sorted.count { it.netPnl > BigDecimal.ZERO }
    val winRate =
        if (tradeCount == 0) {
            BigDecimal.ZERO
        } else {
            BigDecimal(wins)
                .multiply(BigDecimal("100"))
                .divide(BigDecimal(tradeCount), 8, RoundingMode.HALF_UP)
        }
    val profitFactor = if (grossLoss > BigDecimal.ZERO) grossProfit.divide(grossLoss, 8, RoundingMode.HALF_UP) else null
    val expectancy = if (tradeCount == 0) null else netPnl.divide(BigDecimal(tradeCount), 8, RoundingMode.HALF_UP)
    var equity = BigDecimal.ZERO
    var peak = BigDecimal.ZERO
    var maxDrawdownPct = BigDecimal.ZERO
    sorted.forEach { trade ->
        equity += trade.netPnl
        if (equity > peak) peak = equity
        if (peak > BigDecimal.ZERO) {
            val drawdown = peak.subtract(equity).divide(peak, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
            if (drawdown > maxDrawdownPct) maxDrawdownPct = drawdown
        }
    }
    return LivePerformanceSnapshot(
        mode = mode,
        window = window,
        tradeCount = tradeCount,
        winRatePct = winRate,
        grossProfit = grossProfit,
        grossLoss = grossLoss,
        fees = fees,
        netPnl = netPnl,
        profitFactor = profitFactor,
        expectancy = expectancy,
        maxClosedTradeDrawdownPct = maxDrawdownPct,
        lastClosedAt = sorted.lastOrNull()?.closedAt,
        capturedAt = capturedAt,
    )
}

private object EmptyExecutionProjectionStore : ExecutionProjectionStore {
    override suspend fun recordTradeClosure(
        closure: ExecutionTradeClosure,
        suppressedAt: Instant?,
    ): Long? = null

    override suspend fun closedTrades(
        symbol: Symbol?,
        mode: ExecutionRuntimeMode?,
        limit: Int,
        cursor: Long?,
    ): List<ExecutionTradeClosure> = emptyList()

    override suspend fun latestClosedTrade(symbol: Symbol): ExecutionTradeClosure? = null

    override suspend fun performanceClosures(
        mode: ExecutionRuntimeMode,
        closedAtOrAfter: Instant?,
    ): List<ExecutionTradeClosure> = emptyList()

    override suspend fun hasClosureHistory(
        mode: ExecutionRuntimeMode,
        symbol: Symbol,
    ): Boolean = false

    override suspend fun pendingClosureAlerts(
        mode: ExecutionRuntimeMode,
        symbol: Symbol,
        limit: Int,
    ): List<PendingExecutionClosureAlert> = emptyList()

    override suspend fun recordClosureAlertAttempt(
        closureId: Long,
        attemptedAt: Instant,
        delivered: Boolean,
    ) = Unit

    override suspend fun recordLivePerformanceSnapshot(snapshot: LivePerformanceSnapshot): Long = 0

    override suspend fun latestLivePerformanceSummary(
        mode: ExecutionRuntimeMode?,
        window: LivePerformanceWindow,
    ): LivePerformanceSnapshot? = null
}

private const val SIGNAL_KEY_PREFIX = "SIGNAL_AT_"
