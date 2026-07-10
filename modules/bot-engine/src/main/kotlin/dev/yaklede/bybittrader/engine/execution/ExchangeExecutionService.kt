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

class ExchangeExecutionService(
    private val stateStore: BotStateStore,
    private val candleStore: MarketCandleStore,
    private val tradingStore: PaperTradingStore,
    private val strategy: TradingStrategy,
    private val gateway: ExchangeExecutionGateway,
    private val config: ExchangeExecutionConfig = ExchangeExecutionConfig(),
    private val projectionStore: ExecutionProjectionStore? = tradingStore as? ExecutionProjectionStore,
    private val runtimeMode: ExecutionRuntimeMode = ExecutionRuntimeMode.TESTNET,
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
        val sizing = calculateSizing(entryPrice, riskPerUnit, intendedRisk, accountEquity)
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

        val takeProfit = calculateTakeProfit(signal, entryPrice, riskPerUnit)
        val targetStopRejection = targetStopRejection(signal.side, entryPrice, takeProfit, signal.invalidationPrice.value)
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
        val hasOpenPosition = gateway.positions(symbol).any { position -> position.size > BigDecimal.ZERO }
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
        val store = projectionStore ?: return emptyList()
        val firstBootstrap = !store.hasClosureHistory(runtimeMode, symbol)
        val persistedClosures =
            gateway.closedPnls(symbol).mapNotNull { closedPnl ->
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
        config.maxQuantity?.let { maxQuantity ->
            require(quantity <= maxQuantity) {
                "Manual order quantity must be less than or equal to ${maxQuantity.toPlainString()}."
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

    private fun calculateSizing(
        entryPrice: BigDecimal,
        riskPerUnit: BigDecimal,
        intendedRisk: BigDecimal,
        accountEquity: BigDecimal,
    ): Sizing? {
        if (riskPerUnit <= BigDecimal.ZERO) return null
        var quantity = intendedRisk.divide(riskPerUnit, MathContext.DECIMAL64).floorToStep(config.quantityStep)
        config.maxQuantity?.let { maxQuantity ->
            if (quantity > maxQuantity) quantity = maxQuantity.floorToStep(config.quantityStep)
        }
        config.leverage?.let { leverage ->
            val maxNotionalByLeverage = accountEquity.multiply(leverage, MathContext.DECIMAL64)
            val maxQuantityByLeverage = maxNotionalByLeverage.divide(entryPrice, MathContext.DECIMAL64).floorToStep(config.quantityStep)
            if (quantity > maxQuantityByLeverage) quantity = maxQuantityByLeverage
        }
        config.maxNotional?.let { maxNotional ->
            val maxQuantityByNotional = maxNotional.divide(entryPrice, MathContext.DECIMAL64).floorToStep(config.quantityStep)
            if (quantity > maxQuantityByNotional) quantity = maxQuantityByNotional
        }
        return if (quantity >= config.minQuantity) Sizing(quantity = quantity) else null
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

    private fun calculateTakeProfit(
        signal: SignalIntent,
        entryPrice: BigDecimal,
        riskPerUnit: BigDecimal,
    ): BigDecimal =
        when (signal.side) {
            Side.BUY -> entryPrice.add(riskPerUnit.multiply(signal.expectedR, MathContext.DECIMAL64))
            Side.SELL -> entryPrice.subtract(riskPerUnit.multiply(signal.expectedR, MathContext.DECIMAL64))
        }

    private fun targetStopRejection(
        side: Side,
        entryPrice: BigDecimal,
        takeProfit: BigDecimal,
        stopLoss: BigDecimal,
    ): String? {
        val grossTargetMove =
            when (side) {
                Side.BUY -> takeProfit.subtract(entryPrice)
                Side.SELL -> entryPrice.subtract(takeProfit)
            }
        val stopMove =
            when (side) {
                Side.BUY -> entryPrice.subtract(stopLoss)
                Side.SELL -> stopLoss.subtract(entryPrice)
            }
        if (grossTargetMove <= BigDecimal.ZERO || stopMove <= BigDecimal.ZERO) return "INVALID_TARGET_STOP_GEOMETRY"
        val roundTripCostRate = config.feeRate.multiply(BigDecimal("2")).add(config.slippageBufferRate)
        val roundTripCostMove = entryPrice.multiply(roundTripCostRate, MathContext.DECIMAL64)
        return if (grossTargetMove <= roundTripCostMove) "TARGET_DOES_NOT_COVER_ROUND_TRIP_FEES" else null
    }

    suspend fun closedTrades(
        symbol: Symbol?,
        mode: ExecutionRuntimeMode?,
        limit: Int,
        cursor: Long?,
    ): List<ExecutionTradeClosure> =
        (projectionStore ?: EmptyExecutionProjectionStore)
            .closedTrades(symbol = symbol, mode = mode, limit = limit, cursor = cursor)

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
    val alertBatchLimit: Int = 100,
    val intervalSeconds: Long = 300,
) {
    init {
        require(candleLimit in 20..ResearchCandleLimits.MAX_M5_REPLAY_CANDLES) {
            "Execution loop candle limit must be between 20 and ${ResearchCandleLimits.MAX_M5_REPLAY_CANDLES}."
        }
        require(syncLimit in 1..1000) { "Execution loop sync limit must be between 1 and 1000." }
        require(alertBatchLimit in 1..1000) { "Execution alert batch limit must be between 1 and 1000." }
        require(intervalSeconds in 10..86_400) { "Execution loop interval seconds must be between 10 and 86400." }
    }
}

private data class Sizing(
    val quantity: BigDecimal,
)

private fun BotMode.blocksNewEntries(): Boolean = this != BotMode.RUNNING

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

private fun BigDecimal.floorToStep(step: BigDecimal): BigDecimal {
    val units = divide(step, 0, RoundingMode.DOWN)
    return units.multiply(step).stripTrailingZeros()
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
