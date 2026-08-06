package dev.yaklede.bybittrader.engine.execution

import dev.yaklede.bybittrader.domain.BotMode
import dev.yaklede.bybittrader.domain.Candle
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.cancellation.CancellationException

class ExchangeExecutionService(
    private val stateStore: BotStateStore,
    private val candleStore: MarketCandleStore,
    private val tradingStore: PaperTradingStore,
    private val strategy: TradingStrategy,
    private val gateway: ExchangeExecutionGateway,
    private val config: ExchangeExecutionConfig = ExchangeExecutionConfig(),
    private val projectionStore: ExecutionProjectionStore? = tradingStore as? ExecutionProjectionStore,
    private val lifecycleStore: ExecutionLifecycleStore? = tradingStore as? ExecutionLifecycleStore,
    private val positionRuntimeStore: ExecutionPositionRuntimeStateStore? = tradingStore as? ExecutionPositionRuntimeStateStore,
    private val runtimeMode: ExecutionRuntimeMode = ExecutionRuntimeMode.TESTNET,
    private val positionPolicy: AutomaticPositionPolicy? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(ExchangeExecutionService::class.java)
    private val sessionStartedAt = Instant.now(clock)
    private val evaluationMutex = Mutex()
    private val lifecycleMutex = Mutex()
    private val automaticPositionPolicyEngine =
        positionPolicy?.let { policy -> AutomaticPositionPolicyEngine(policy, config.feeRate, config.priceTick) }
    private val safetyCoordinator by lazy {
        ExchangeSafetyCoordinator(
            gateway = gateway,
            lifecycleStore = lifecycleStore,
            runtimeMode = runtimeMode,
            config = config,
            submitClose = { position, reasonCode ->
                submitManualOrder(
                    symbol = position.symbol,
                    side = position.side.opposite(),
                    quantity = position.size,
                    reduceOnly = true,
                    strategyName = "exchange-safety",
                    reasonCode = reasonCode,
                    clientOrderPrefix = if (reasonCode == "EMERGENCY_FLATTEN") "flatten" else "safe",
                )
            },
            clock = clock,
        )
    }

    suspend fun evaluateAndSubmit(
        symbol: Symbol,
        timeframe: Timeframe,
        candleLimit: Int,
    ): ExchangeEvaluationResult =
        evaluationMutex.withLock {
            evaluateAndSubmitLocked(symbol, timeframe, candleLimit)
        }

    private suspend fun evaluateAndSubmitLocked(
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
        val activeManagedPosition = managedPositions?.firstOrNull { position -> position.size > BigDecimal.ZERO }
        if (activeManagedPosition != null && activePositionPolicy != null) {
            manageAutomaticPosition(
                position = activeManagedPosition,
                timeframe = timeframe,
                candleLimit = candleLimit,
                mode = mode,
                evaluatedAt = now,
            )?.let { return it }
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
            val submittedEntriesToday = automaticEntryCountForUtcDay(symbol, now)
            if (submittedEntriesToday >= activePositionPolicy.maxTradesPerUtcDay) {
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
        if (config.circuitBreakerEnabled) {
            val riskDecision = currentEntryRiskDecision(now)
            if (!riskDecision.allowsEntry) {
                logger.warn(
                    "execution entry blocked by account risk circuit breaker symbol={} mode={} reasons={}",
                    symbol.value,
                    mode.name,
                    riskDecision.reasonCodes,
                )
                return entryBlockedResult(
                    symbol = symbol,
                    timeframe = timeframe,
                    mode = mode,
                    evaluatedAt = now,
                    reasonCodes = riskDecision.reasonCodes,
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

        val latestClosedCandle = candles.last()
        val expectedLatestOpenedAt = closedBefore.minusMillis(timeframe.executionDurationMillis())
        if (latestClosedCandle.openedAt != expectedLatestOpenedAt) {
            return ExchangeEvaluationResult(
                symbol = symbol,
                timeframe = timeframe,
                mode = mode.name,
                status = ExchangeEvaluationStatus.NO_TRADE,
                evaluatedAt = now,
                candleCount = candles.size,
                reasonCodes = listOf("LATEST_CLOSED_CANDLE_MISSING"),
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
        if (Duration.between(closedBefore, now) > config.maximumEntryDelay) {
            return ExchangeEvaluationResult(
                symbol = symbol,
                timeframe = timeframe,
                mode = mode.name,
                status = ExchangeEvaluationStatus.NO_TRADE,
                evaluatedAt = now,
                candleCount = candles.size,
                reasonCodes = listOf("ENTRY_WINDOW_EXPIRED"),
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
        val signalKey = "$SIGNAL_KEY_PREFIX${latestClosedCandle.openedAt}"
        if (signal.isDuplicate(signalKey)) {
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

        val entryPrice =
            automaticEntryLimitPrice(
                side = signal.side,
                referencePrice = latestClosedCandle.close,
                slippageRate = config.slippageBufferRate,
                priceTick = config.priceTick,
            )
        val protectionPlan =
            ExecutionTradePlanCalculator.calculateProtection(
                side = signal.side,
                entryPrice = entryPrice,
                structuralStopPrice = signal.invalidationPrice.value,
                entryAnchoredStopDistance = signal.entryAnchoredStopDistance,
                expectedR = signal.expectedR,
                priceTick = config.priceTick,
                fixedTargetEnabled = activePositionPolicy?.fixedTargetEnabled ?: true,
            )
        if (protectionPlan == null) {
            val rejectionReason = "INVALID_TARGET_STOP_GEOMETRY"
            val rejectedSignalId =
                tradingStore.recordSignal(
                    signal.toRecord(
                        accepted = false,
                        rejectionReason = rejectionReason,
                        createdAt = now,
                        signalKey = signalKey,
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
                takeProfit = null,
                stopLoss = signal.invalidationPrice.value,
                quantity = null,
                intendedRisk = null,
            )
        }
        val riskPerUnit = protectionPlan.riskPerUnit
        val accountEquity = executionAccountEquity()
        val intendedRisk = accountEquity.multiply(config.riskFraction, MathContext.DECIMAL64)
        val costAdjustedRiskPerUnit =
            ExecutionTradePlanCalculator.costAdjustedRiskPerUnit(
                entryPrice = entryPrice,
                riskPerUnit = riskPerUnit,
                feeRate = config.feeRate,
                slippageBufferRate = config.slippageBufferRate,
                exitSlippageRate = config.slippageBufferRate,
            )
        val sizing =
            ExecutionTradePlanCalculator.calculateSizing(
                entryPrice = entryPrice,
                riskPerUnit = costAdjustedRiskPerUnit,
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
                        signalKey = signalKey,
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
                    stopLoss = protectionPlan.stopLoss,
                    quantity = null,
                    intendedRisk = intendedRisk,
                )
            logger.info("execution evaluate rejected symbol={} signalId={} reason=INVALID_EXECUTION_SIZE", symbol.value, rejectedSignalId)
            return result
        }

        val takeProfit = protectionPlan.takeProfit
        val stopLoss = protectionPlan.stopLoss
        val targetStopRejection =
            takeProfit?.let { targetPrice ->
                ExecutionTradePlanCalculator.targetStopRejection(
                    side = signal.side,
                    entryPrice = entryPrice,
                    takeProfit = targetPrice,
                    stopLoss = stopLoss,
                    feeRate = config.feeRate,
                    slippageBufferRate = config.slippageBufferRate,
                    minimumNetRiskReward = config.minimumNetRiskReward,
                    exitSlippageRate = config.slippageBufferRate,
                )
            } ?: ExecutionTradePlanCalculator.leverageStopRejection(
                side = signal.side,
                entryPrice = entryPrice,
                stopLoss = stopLoss,
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
                        signalKey = signalKey,
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
                stopLoss = stopLoss,
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
                        signalKey = signalKey,
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
                stopLoss = protectionPlan.stopLoss,
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
                    signalKey = signalKey,
                ),
            )
        val clientOrderId = clientOrderId(symbol = symbol, side = signal.side, now = now, signalId = signalId)
        syncLeverage(symbol)
        val orderResult =
            gateway.placeOrder(
                ExchangeOrderRequest(
                    symbol = symbol,
                    side = signal.side,
                    orderType = OrderType.LIMIT,
                    quantity = sizing.quantity,
                    clientOrderId = clientOrderId,
                    takeProfit = takeProfit,
                    stopLoss = stopLoss,
                    price = entryPrice,
                    timeInForce = ExchangeTimeInForce.IOC,
                ),
            )
        val orderId =
            tradingStore.recordOrder(
                PaperOrderRecord(
                    exchangeOrderId = orderResult.exchangeOrderId,
                    clientOrderId = clientOrderId,
                    signalId = signalId,
                    side = signal.side,
                    orderType = OrderType.LIMIT,
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
            stopLoss = stopLoss,
            exchangeOrderId = orderResult.exchangeOrderId,
            clientOrderId = clientOrderId,
            reasonCode = "AUTOMATIC_ENTRY_SUBMITTED",
            occurredAt = now,
            protectionRequired = true,
            plannedEntryPrice = entryPrice,
            structuralStopPrice = signal.invalidationPrice.value,
            entryAnchoredStopDistance = signal.entryAnchoredStopDistance,
            expectedR = signal.expectedR,
            protectionDeadlineAt = now.plus(config.protectionGracePeriod),
            fixedTargetEnabled = activePositionPolicy?.fixedTargetEnabled ?: true,
            intendedRisk = intendedRisk,
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
                stopLoss = stopLoss,
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

    suspend fun riskReadiness(): ExecutionRiskReadiness {
        val now = Instant.now(clock)
        val botMode = stateStore.current().mode
        val store = projectionStore
        val riskState = store?.executionRiskState(runtimeMode)
        val walletState =
            if (config.walletReconciliationEnabled) {
                store?.walletReconciliationState(runtimeMode, ACCOUNT_LEDGER_CURRENCY)
            } else {
                null
            }
        val reasonCodes = mutableListOf<String>()
        if (!config.enabled) reasonCodes += "PRIVATE_EXECUTION_DISABLED"
        if (botMode != BotMode.RUNNING) reasonCodes += "BOT_MODE_NOT_RUNNING"
        if (store == null) {
            reasonCodes += "RISK_STATE_STORE_UNAVAILABLE"
        } else {
            reasonCodes += config.evaluateRiskState(riskState, now).reasonCodes
            if (config.walletReconciliationEnabled) {
                reasonCodes +=
                    ExecutionWalletReconciler
                        .evaluate(
                            state = walletState,
                            now = now,
                            maximumAge = config.walletReconciliationMaximumAge,
                            confirmedMismatchCount = config.walletReconciliationConfirmedMismatchCount,
                        ).reasonCodes
            }
        }
        val useUnitizedNav = config.walletReconciliationEnabled
        val navReady = !useUnitizedNav || riskState?.navStatus == ExecutionRiskNavStatus.READY
        val latest =
            if (useUnitizedNav) {
                riskState?.latestUnitizedNav
            } else {
                riskState?.latestEquity
            }
        val dayStart =
            if (useUnitizedNav) {
                riskState?.dayStartUnitizedNav
            } else {
                riskState?.dayStartEquity
            }
        val peak =
            if (useUnitizedNav) {
                riskState?.peakUnitizedNav
            } else {
                riskState?.peakEquity
            }
        return ExecutionRiskReadiness(
            runtimeMode = runtimeMode,
            botMode = botMode.name,
            executionEnabled = config.enabled,
            evaluatedAt = now,
            allowsEntry = reasonCodes.isEmpty(),
            reasonCodes = reasonCodes.distinct(),
            riskState = riskState,
            walletReconciliationEnabled = config.walletReconciliationEnabled,
            walletReconciliationState = walletState,
            currentDailyLossFraction = if (navReady) readinessLossFraction(dayStart, latest) else null,
            currentAccountDrawdownFraction = if (navReady) readinessLossFraction(peak, latest) else null,
            maximumDailyLossFraction = config.maximumDailyLossFraction,
            maximumAccountDrawdownFraction = config.maximumAccountDrawdownFraction,
            maximumConsecutiveLosses = config.maximumConsecutiveLosses,
        )
    }

    suspend fun persistDiscoveredClosures(symbol: Symbol): List<ExecutionTradeClosure> {
        logger.info("execution closure discovery requested symbol={}", symbol.value)
        val executions = gateway.executions(symbol)
        return persistDiscoveredClosures(symbol, gateway.closedPnls(symbol), executions)
    }

    suspend fun persistExchangeState(symbol: Symbol): ExchangeReconciliationReport =
        lifecycleMutex.withLock {
            persistExchangeStateLocked(symbol)
        }

    private suspend fun persistExchangeStateLocked(symbol: Symbol): ExchangeReconciliationReport {
        val report = fetchReconciliation(symbol)
        val accountSnapshot = persistAccountSnapshot()
        val transactionSync =
            accountSnapshot
                ?.let { snapshot -> persistAccountTransactions(snapshot.capturedAt) }
                ?: AccountTransactionSyncResult(succeeded = false)
        persistExecutionFills(report.executions)
        val persistedClosures = persistDiscoveredClosures(symbol, report.closedPnls, report.executions)
        if (accountSnapshot != null && (!config.walletReconciliationEnabled || transactionSync.succeeded)) {
            persistRiskState(accountSnapshot, persistedClosures)
        }
        if (accountSnapshot != null && config.walletReconciliationEnabled) {
            persistWalletReconciliation(accountSnapshot, transactionSync.succeeded)
        }
        if (accountSnapshot != null && persistedClosures.isEmpty()) {
            refreshPerformanceSnapshots()
        }
        val lifecycleEvent = persistLifecycleObservation(report)
        enforcePersistedSafetyMode(report)
        return report.copy(
            persistedClosures = persistedClosures,
            lifecycleEvent = lifecycleEvent,
        )
    }

    suspend fun enforceCurrentSafetyMode(symbol: Symbol): ExchangeSafetyResult =
        lifecycleMutex.withLock {
            val mode = stateStore.current().mode
            require(mode == BotMode.PAUSE_ALL || mode == BotMode.EMERGENCY_STOP) {
                "Bot mode ${mode.name} does not require an exchange safety action."
            }
            enforceSafetyModeLocked(mode, symbol)
        }

    suspend fun verifyCurrentSafetyMode(symbol: Symbol): ExchangeSafetyResult? =
        lifecycleMutex.withLock {
            val mode = stateStore.current().mode
            if (mode != BotMode.PAUSE_ALL && mode != BotMode.EMERGENCY_STOP) {
                null
            } else {
                enforceSafetyModeLocked(mode, symbol)
            }
        }

    private suspend fun enforceSafetyModeLocked(
        mode: BotMode,
        symbol: Symbol,
    ): ExchangeSafetyResult {
        val result = safetyCoordinator.enforce(mode, symbol)
        logger.warn(
            "execution safety action completed action={} status={} symbol={} cancelledEntries={} submittedCloses={} remainingOrders={} remainingPositions={} issues={}",
            result.action.name,
            result.status.name,
            symbol.value,
            result.cancelledEntryOrderCount,
            result.submittedCloseOrderCount,
            result.remainingOpenOrderCount,
            result.remainingPositionCount,
            result.issueCodes,
        )
        return result
    }

    private suspend fun enforcePersistedSafetyMode(report: ExchangeReconciliationReport) {
        val mode = stateStore.current().mode
        if (mode != BotMode.PAUSE_ALL && mode != BotMode.EMERGENCY_STOP) return
        val attempt =
            safetyCoordinator.enforceOnce(
                mode = mode,
                openOrders = report.openOrders,
                positions = report.positions,
                observedAt = report.reconciledAt,
            )
        if (attempt.cancelledEntryOrderCount > 0 || attempt.submittedCloseOrderCount > 0 || attempt.issueCodes.isNotEmpty()) {
            logger.warn(
                "execution persisted safety action applied mode={} symbol={} cancelledEntries={} submittedCloses={} issues={}",
                mode.name,
                report.symbol.value,
                attempt.cancelledEntryOrderCount,
                attempt.submittedCloseOrderCount,
                attempt.issueCodes,
            )
        }
    }

    suspend fun observeOrderUpdate(update: ExchangeOrderUpdate): ExecutionLifecycleEvent? =
        lifecycleMutex.withLock {
            observeOrderUpdateLocked(update)
        }

    private suspend fun observeOrderUpdateLocked(update: ExchangeOrderUpdate): ExecutionLifecycleEvent? {
        val store = lifecycleStore ?: return null
        val latest = store.latestLifecycleEvent(runtimeMode, update.symbol) ?: return null
        if (latest.state == ExecutionLifecycleState.CLOSED || !update.matches(latest)) return null
        if (update.updatedAt.isBefore(latest.occurredAt)) return null
        if (
            latest.state in setOf(ExecutionLifecycleState.OPEN_UNPROTECTED, ExecutionLifecycleState.OPEN_PROTECTED) &&
            !update.reduceOnly
        ) {
            return null
        }
        val hasFill = update.cumulativeFilledQuantity > BigDecimal.ZERO
        val isExit = update.reduceOnly || latest.state == ExecutionLifecycleState.EXIT_SUBMITTED
        val nextState =
            when (update.status) {
                OrderStatus.FILLED ->
                    when {
                        !hasFill -> ExecutionLifecycleState.ERROR
                        isExit -> ExecutionLifecycleState.EXIT_SUBMITTED
                        else -> ExecutionLifecycleState.ENTRY_FILLED
                    }

                OrderStatus.PARTIALLY_FILLED ->
                    if (hasFill) ExecutionLifecycleState.PARTIALLY_FILLED else ExecutionLifecycleState.ERROR

                OrderStatus.CANCELLED ->
                    when {
                        isExit -> ExecutionLifecycleState.ERROR
                        hasFill -> ExecutionLifecycleState.PARTIALLY_FILLED
                        else -> ExecutionLifecycleState.ENTRY_CANCELLED
                    }

                OrderStatus.REJECTED ->
                    when {
                        isExit || hasFill -> ExecutionLifecycleState.ERROR
                        else -> ExecutionLifecycleState.ENTRY_REJECTED
                    }

                OrderStatus.CREATED,
                OrderStatus.SUBMITTED,
                -> return null
            }
        if (!latest.state.canTransitionTo(nextState)) return null
        val reasonCode =
            when {
                update.status == OrderStatus.FILLED && !hasFill -> "ORDER_FILLED_WITHOUT_QUANTITY"
                update.status == OrderStatus.FILLED && isExit -> "EXIT_FILL_CONFIRMED_PENDING_RECONCILIATION"
                update.status == OrderStatus.FILLED -> "ENTRY_FILL_CONFIRMED_PENDING_POSITION"
                update.status == OrderStatus.PARTIALLY_FILLED && hasFill -> "ENTRY_PARTIALLY_FILLED"
                update.status == OrderStatus.PARTIALLY_FILLED -> "PARTIAL_STATUS_WITHOUT_QUANTITY"
                update.status == OrderStatus.CANCELLED && isExit -> "EXIT_ORDER_CANCELLED"
                update.status == OrderStatus.CANCELLED && hasFill -> "ENTRY_PARTIAL_FILL_REMAINDER_CANCELLED"
                update.status == OrderStatus.CANCELLED -> "ENTRY_ORDER_CANCELLED_UNFILLED"
                update.status == OrderStatus.REJECTED && isExit -> "EXIT_ORDER_REJECTED"
                update.status == OrderStatus.REJECTED && hasFill -> "REJECTED_ORDER_REPORTED_PARTIAL_FILL"
                else -> "ENTRY_ORDER_REJECTED"
            }
        return recordObservedLifecycle(
            latest.copy(
                id = 0,
                state = nextState,
                filledQuantity = update.cumulativeFilledQuantity.takeIf { it > BigDecimal.ZERO },
                fillVwap = update.averageFillPrice ?: latest.fillVwap,
                exchangeOrderId = update.exchangeOrderId ?: latest.exchangeOrderId,
                clientOrderId = update.clientOrderId ?: latest.clientOrderId,
                reasonCode =
                    listOfNotNull(
                        reasonCode,
                        update.rejectReason?.takeUnless { it == "EC_NoError" },
                        update.cancelType?.takeUnless { it == "UNKNOWN" },
                    ).joinToString("|"),
                occurredAt = update.updatedAt,
            ),
        )
    }

    suspend fun persistExecutionFill(fill: ExchangeExecutionFill): ExecutionFillEvent? = persistExecutionFill(fill, Instant.now(clock))

    private suspend fun persistExecutionFill(
        fill: ExchangeExecutionFill,
        receivedAt: Instant,
    ): ExecutionFillEvent? {
        val store = projectionStore ?: return null
        val event =
            ExecutionFillEvent(
                mode = runtimeMode,
                fill = fill,
                receivedAt = receivedAt,
            )
        return store.recordExecutionFill(event)?.let { id -> event.copy(id = id) }
    }

    private suspend fun persistExecutionFills(fills: List<ExchangeExecutionFill>): List<ExecutionFillEvent> {
        val receivedAt = Instant.now(clock)
        return fills.mapNotNull { fill -> persistExecutionFill(fill, receivedAt) }
    }

    private suspend fun persistDiscoveredClosures(
        symbol: Symbol,
        closedPnls: List<ExchangeClosedPnl>,
        executions: List<ExchangeExecutionFill> = emptyList(),
    ): List<ExecutionTradeClosure> {
        val store = projectionStore ?: return emptyList()
        val firstBootstrap = !store.hasClosureHistory(runtimeMode, symbol)
        val persistedClosures =
            closedPnls.mapNotNull { closedPnl ->
                val resolvedClosedPnl = closedPnl.resolveExitReason(executions)
                val closure = resolvedClosedPnl.toTradeClosure(runtimeMode)
                val suppressedAt = sessionStartedAt.takeIf { firstBootstrap && closure.closedAt.isBefore(it) }
                store
                    .recordTradeClosure(closure, suppressedAt = suppressedAt)
                    ?.let { id -> closure.copy(id = id) }
            }
        if (persistedClosures.isNotEmpty()) {
            refreshPerformanceSnapshots()
        }
        logger.info(
            "execution closure discovery completed symbol={} observedClosures={} newClosures={} reasons={}",
            symbol.value,
            closedPnls.size,
            persistedClosures.size,
            persistedClosures.groupingBy(ExecutionTradeClosure::exitReason).eachCount(),
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
            val recorded =
                recordObservedLifecycle(
                    latest.copy(
                        id = 0,
                        state = ExecutionLifecycleState.CLOSED,
                        filledQuantity = observedClosure.quantity,
                        fillVwap = observedClosure.exitPrice,
                        exchangeOrderId = observedClosure.exchangeOrderId,
                        clientOrderId = observedClosure.clientOrderId,
                        reasonCode = observedClosure.exitReason ?: "UNKNOWN",
                        occurredAt = observedClosure.closedAt,
                    ),
                )
            positionRuntimeStore?.deleteExecutionPositionRuntimeState(runtimeMode, report.symbol)
            return recorded
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
            return observeActivePositionProtection(report, activePosition, base)
        }
        if (latest == null) return null
        if (latest.state == ExecutionLifecycleState.CLOSED) {
            positionRuntimeStore?.deleteExecutionPositionRuntimeState(runtimeMode, report.symbol)
            return null
        }
        if (latest.state.isUnfilledTerminalEntry()) {
            return null
        }
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
        val deadline = latest.protectionDeadlineAt
        if (
            relatedOpenOrder == null &&
            deadline != null &&
            !report.reconciledAt.isBefore(deadline) &&
            (
                latest.state == ExecutionLifecycleState.ENTRY_FILLED ||
                    (latest.state == ExecutionLifecycleState.ENTRY_SUBMITTED && relatedFills.isEmpty())
            )
        ) {
            return recordObservedLifecycle(
                latest.copy(
                    id = 0,
                    state = ExecutionLifecycleState.ERROR,
                    reasonCode =
                        if (latest.state == ExecutionLifecycleState.ENTRY_FILLED) {
                            "ENTRY_FILL_POSITION_MISSING"
                        } else {
                            "ENTRY_ORDER_FINAL_STATE_UNKNOWN"
                        },
                    occurredAt = report.reconciledAt,
                ),
            )
        }
        return null
    }

    private suspend fun observeActivePositionProtection(
        report: ExchangeReconciliationReport,
        activePosition: ExchangePosition,
        base: ExecutionLifecycleEvent,
    ): ExecutionLifecycleEvent? {
        if (base.state == ExecutionLifecycleState.EXIT_SUBMITTED) {
            if (report.reconciledAt.isBefore(base.occurredAt.plus(config.protectionGracePeriod))) return null
            return recordObservedLifecycle(
                base.copy(
                    id = 0,
                    state = ExecutionLifecycleState.ERROR,
                    filledQuantity = activePosition.size,
                    fillVwap = activePosition.entryPrice,
                    takeProfit = activePosition.takeProfit,
                    stopLoss = activePosition.stopLoss,
                    reasonCode = "EXIT_SUBMITTED_POSITION_STILL_OPEN",
                    occurredAt = report.reconciledAt,
                ),
            )
        }

        val managedRuntime =
            positionRuntimeStore
                ?.executionPositionRuntimeState(runtimeMode, activePosition.symbol)
                ?.takeIf { runtime ->
                    runtime.lifecycleId == base.lifecycleId && runtime.policyState.side == activePosition.side
                }
        val desiredProtection = managedRuntime?.toProtectionPlan() ?: base.desiredProtection(activePosition)
        val actualRisk =
            if (managedRuntime == null) {
                desiredProtection?.let { plan -> actualPositionRisk(activePosition, plan) }
            } else {
                null
            }
        val maximumActualRisk =
            base.intendedRisk?.multiply(
                BigDecimal.ONE.add(config.maximumActualRiskOverrunFraction),
                MathContext.DECIMAL64,
            )
        if (actualRisk != null && maximumActualRisk != null && actualRisk > maximumActualRisk) {
            return failClosedActualRiskOverrunPosition(
                report = report,
                position = activePosition,
                lifecycle = base,
                actualRisk = actualRisk,
                maximumActualRisk = maximumActualRisk,
            )
        }
        var observedPosition = activePosition
        var protectionUpdateFailed = false
        if (
            base.protectionRequired &&
            desiredProtection != null &&
            !activePosition.matches(desiredProtection, config.priceTick)
        ) {
            try {
                gateway.setPositionProtection(
                    ExchangePositionProtectionRequest(
                        symbol = activePosition.symbol,
                        takeProfit = desiredProtection.takeProfit,
                        stopLoss = desiredProtection.stopLoss,
                    ),
                )
                observedPosition =
                    gateway
                        .positions(activePosition.symbol)
                        .firstOrNull { position -> position.size > BigDecimal.ZERO && position.side == activePosition.side }
                        ?: return null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                protectionUpdateFailed = true
                logger.error(
                    "execution position protection update failed symbol={} lifecycleId={} errorType={} message={}",
                    activePosition.symbol.value,
                    base.lifecycleId,
                    error::class.simpleName,
                    error.message,
                    error,
                )
            }
        }

        val protected =
            if (base.protectionRequired) {
                desiredProtection != null && observedPosition.matches(desiredProtection, config.priceTick)
            } else {
                observedPosition.takeProfit != null && observedPosition.stopLoss != null
            }
        val observation =
            base.copy(
                id = 0,
                state =
                    if (protected) {
                        ExecutionLifecycleState.OPEN_PROTECTED
                    } else {
                        ExecutionLifecycleState.OPEN_UNPROTECTED
                    },
                side = observedPosition.side,
                filledQuantity = observedPosition.size,
                fillVwap = observedPosition.entryPrice,
                takeProfit = observedPosition.takeProfit,
                stopLoss = observedPosition.stopLoss,
                reasonCode =
                    when {
                        protected && base.protectionRequired -> "ACTUAL_FILL_PROTECTION_VERIFIED"
                        protected -> "PROTECTED_POSITION_OBSERVED"
                        protectionUpdateFailed -> "POSITION_PROTECTION_UPDATE_FAILED"
                        base.protectionRequired && desiredProtection == null -> "POSITION_PROTECTION_PLAN_UNAVAILABLE"
                        else -> "UNPROTECTED_POSITION_OBSERVED"
                    },
                occurredAt = observedPosition.updatedAt ?: report.reconciledAt,
            )
        val recordedObservation = recordObservedLifecycle(observation)
        if (protected && base.protectionRequired) {
            when (initializeAutomaticPositionRuntime(report, observedPosition, base, requireNotNull(desiredProtection))) {
                PositionRuntimeInitialization.FAILED ->
                    return failClosedPositionPolicyState(report, observedPosition, observation) ?: recordedObservation

                PositionRuntimeInitialization.PENDING,
                PositionRuntimeInitialization.READY,
                PositionRuntimeInitialization.NOT_REQUIRED,
                -> Unit
            }
        }
        val deadline = base.protectionDeadlineAt
        if (
            protected ||
            !base.protectionRequired ||
            deadline == null ||
            report.reconciledAt.isBefore(deadline)
        ) {
            return recordedObservation
        }

        return failClosedUnprotectedPosition(report, observedPosition, observation) ?: recordedObservation
    }

    private suspend fun initializeAutomaticPositionRuntime(
        report: ExchangeReconciliationReport,
        position: ExchangePosition,
        lifecycle: ExecutionLifecycleEvent,
        protection: ExecutionProtectionPlan,
    ): PositionRuntimeInitialization {
        val engine = automaticPositionPolicyEngine ?: return PositionRuntimeInitialization.NOT_REQUIRED
        val store = positionRuntimeStore ?: return PositionRuntimeInitialization.FAILED
        val existing = store.executionPositionRuntimeState(runtimeMode, position.symbol)
        if (existing?.lifecycleId == lifecycle.lifecycleId) return PositionRuntimeInitialization.READY
        if (report.openOrders.any { order -> order.status.isActive() && order.matches(lifecycle) }) {
            return PositionRuntimeInitialization.PENDING
        }
        val entryAt =
            report.executions
                .filter { fill -> fill.matches(lifecycle) && fill.side == position.side }
                .minOfOrNull(ExchangeExecutionFill::executedAt)
                ?: position.openedAt
                ?: return PositionRuntimeInitialization.FAILED
        return try {
            val runtime =
                engine.open(
                    lifecycle = lifecycle,
                    position = position,
                    entryAt = entryAt,
                    protection = protection,
                    updatedAt = report.reconciledAt,
                )
            store.upsertExecutionPositionRuntimeState(runtime)
            logger.info(
                "execution causal position state initialized symbol={} lifecycleId={} entryAt={} entryPrice={} qty={}",
                position.symbol.value,
                lifecycle.lifecycleId,
                entryAt,
                position.entryPrice,
                position.size.toPlainString(),
            )
            PositionRuntimeInitialization.READY
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger.error(
                "execution causal position state initialization failed symbol={} lifecycleId={} message={}",
                position.symbol.value,
                lifecycle.lifecycleId,
                error.message,
                error,
            )
            PositionRuntimeInitialization.FAILED
        }
    }

    private suspend fun failClosedPositionPolicyState(
        report: ExchangeReconciliationReport,
        position: ExchangePosition,
        lifecycle: ExecutionLifecycleEvent,
    ): ExecutionLifecycleEvent? =
        try {
            submitManualOrder(
                symbol = position.symbol,
                side = position.side.opposite(),
                quantity = position.size,
                reduceOnly = true,
                strategyName = "automatic-position-policy-fail-closed",
                reasonCode = "POSITION_POLICY_STATE_UNAVAILABLE",
                clientOrderPrefix = "policy",
            )
            lifecycleStore?.latestLifecycleEvent(runtimeMode, position.symbol)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger.error(
                "execution causal position state fail-closed exit failed symbol={} lifecycleId={}",
                position.symbol.value,
                lifecycle.lifecycleId,
                error,
            )
            recordObservedLifecycle(
                lifecycle.copy(
                    id = 0,
                    state = ExecutionLifecycleState.ERROR,
                    reasonCode = "POSITION_POLICY_STATE_EXIT_FAILED",
                    occurredAt = report.reconciledAt,
                ),
            )
        }

    private fun actualPositionRisk(
        position: ExchangePosition,
        protection: ExecutionProtectionPlan,
    ): BigDecimal {
        val entryPrice = requireNotNull(position.entryPrice)
        val costAdjustedRiskPerUnit =
            ExecutionTradePlanCalculator.costAdjustedRiskPerUnit(
                entryPrice = entryPrice,
                riskPerUnit = protection.riskPerUnit,
                feeRate = config.feeRate,
                slippageBufferRate = config.slippageBufferRate,
                exitSlippageRate = config.slippageBufferRate,
            )
        return costAdjustedRiskPerUnit.multiply(position.size, MathContext.DECIMAL64)
    }

    private suspend fun failClosedActualRiskOverrunPosition(
        report: ExchangeReconciliationReport,
        position: ExchangePosition,
        lifecycle: ExecutionLifecycleEvent,
        actualRisk: BigDecimal,
        maximumActualRisk: BigDecimal,
    ): ExecutionLifecycleEvent? {
        logger.error(
            "execution actual-fill risk exceeded symbol={} lifecycleId={} actualRisk={} maximumRisk={}",
            position.symbol.value,
            lifecycle.lifecycleId,
            actualRisk.toPlainString(),
            maximumActualRisk.toPlainString(),
        )
        return try {
            submitManualOrder(
                symbol = position.symbol,
                side = position.side.opposite(),
                quantity = position.size,
                reduceOnly = true,
                strategyName = "automatic-risk-fail-closed",
                reasonCode = "ACTUAL_FILL_RISK_LIMIT_EXCEEDED",
                clientOrderPrefix = "risk",
            )
            lifecycleStore?.latestLifecycleEvent(runtimeMode, position.symbol)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger.error(
                "execution actual-fill risk fail-closed exit failed symbol={} lifecycleId={}",
                position.symbol.value,
                lifecycle.lifecycleId,
                error,
            )
            recordObservedLifecycle(
                lifecycle.copy(
                    id = 0,
                    state = ExecutionLifecycleState.ERROR,
                    filledQuantity = position.size,
                    fillVwap = position.entryPrice,
                    takeProfit = position.takeProfit,
                    stopLoss = position.stopLoss,
                    reasonCode = "ACTUAL_FILL_RISK_OVERRUN_EXIT_FAILED",
                    occurredAt = report.reconciledAt,
                ),
            )
        }
    }

    private suspend fun failClosedUnprotectedPosition(
        report: ExchangeReconciliationReport,
        position: ExchangePosition,
        lifecycle: ExecutionLifecycleEvent,
    ): ExecutionLifecycleEvent? {
        report.openOrders
            .filter { order -> order.status.isActive() && order.matches(lifecycle) }
            .forEach { order ->
                try {
                    gateway.cancelOrder(
                        ExchangeCancelRequest(
                            symbol = order.symbol,
                            exchangeOrderId = order.exchangeOrderId,
                            clientOrderId = order.clientOrderId,
                        ),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logger.error(
                        "execution unprotected entry cancellation failed symbol={} lifecycleId={} exchangeOrderId={}",
                        position.symbol.value,
                        lifecycle.lifecycleId,
                        order.exchangeOrderId,
                        error,
                    )
                }
            }
        return try {
            submitManualOrder(
                symbol = position.symbol,
                side = position.side.opposite(),
                quantity = position.size,
                reduceOnly = true,
                strategyName = "automatic-protection-fail-closed",
                reasonCode = "UNPROTECTED_POSITION_TIMEOUT",
                clientOrderPrefix = "protect",
            )
            lifecycle
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger.error(
                "execution unprotected position fail-closed exit failed symbol={} lifecycleId={}",
                position.symbol.value,
                lifecycle.lifecycleId,
                error,
            )
            recordObservedLifecycle(
                lifecycle.copy(
                    id = 0,
                    state = ExecutionLifecycleState.ERROR,
                    reasonCode = "UNPROTECTED_POSITION_EXIT_FAILED",
                    occurredAt = report.reconciledAt,
                ),
            )
        }
    }

    private fun ExecutionLifecycleEvent.desiredProtection(position: ExchangePosition): ExecutionProtectionPlan? {
        if (!protectionRequired) return null
        val entryPrice = position.entryPrice ?: return null
        val structuralStop = structuralStopPrice ?: return null
        val targetR = expectedR ?: return null
        return ExecutionTradePlanCalculator.calculateProtection(
            side = position.side,
            entryPrice = entryPrice,
            structuralStopPrice = structuralStop,
            entryAnchoredStopDistance = entryAnchoredStopDistance,
            expectedR = targetR,
            priceTick = config.priceTick,
            fixedTargetEnabled = fixedTargetEnabled,
        )
    }

    private fun ExecutionPositionRuntimeState.toProtectionPlan(): ExecutionProtectionPlan =
        ExecutionProtectionPlan(
            takeProfit = policyState.fullTargetPrice?.let(BigDecimal::valueOf),
            stopLoss = BigDecimal.valueOf(policyState.currentStopPrice),
            riskPerUnit = BigDecimal.valueOf(policyState.riskPerUnit),
        )

    private fun ExchangePosition.matches(
        desired: ExecutionProtectionPlan,
        tolerance: BigDecimal,
    ): Boolean =
        takeProfit.matchesOptional(desired.takeProfit, tolerance) &&
            stopLoss.isNear(desired.stopLoss, tolerance)

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
        val resolvedClosedPnls = report.closedPnls.map { closedPnl -> closedPnl.resolveExitReason(report.executions) }
        val resolvedReport = report.copy(closedPnls = resolvedClosedPnls)
        logger.info(
            "execution reconcile read completed symbol={} openOrders={} positions={} executions={} closedPnls={} exitReasons={}",
            symbol.value,
            report.openOrders.size,
            report.positions.size,
            report.executions.size,
            resolvedReport.closedPnls.size,
            resolvedReport.closedPnls.groupingBy { it.exitReason ?: "UNKNOWN" }.eachCount(),
        )
        return resolvedReport
    }

    private suspend fun refreshPerformanceSnapshots() {
        val store = projectionStore ?: return
        val capturedAt = Instant.now(clock)
        LivePerformanceWindow.values().forEach { window ->
            val startAt = window.startAt(capturedAt, sessionStartedAt)
            val closures = store.performanceClosures(runtimeMode, startAt)
            val accountSnapshots = store.accountSnapshots(runtimeMode, startAt)
            val accountBaseline = startAt?.let { store.latestAccountSnapshot(runtimeMode, it) }
            store.recordLivePerformanceSnapshot(
                closures.toPerformanceSnapshot(
                    mode = runtimeMode,
                    window = window,
                    capturedAt = capturedAt,
                    accountSnapshots = accountSnapshots,
                    accountBaseline = accountBaseline,
                ),
            )
        }
    }

    private suspend fun persistAccountSnapshot(): ExecutionAccountSnapshot? {
        val store = projectionStore ?: return null
        return try {
            val snapshot =
                gateway
                    .accountBalance("USDT")
                    .toExecutionAccountSnapshot(runtimeMode)
            val id = store.recordAccountSnapshot(snapshot)
            snapshot.copy(id = id)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger.warn(
                "execution account snapshot unavailable mode={} errorType={} message={}",
                runtimeMode.name,
                error::class.simpleName,
                error.message,
            )
            null
        }
    }

    private suspend fun persistAccountTransactions(endAt: Instant): AccountTransactionSyncResult {
        val store = projectionStore ?: return AccountTransactionSyncResult(succeeded = false)
        return try {
            val latest = store.latestAccountTransaction(runtimeMode, ACCOUNT_LEDGER_CURRENCY)
            val bootstrapStart = endAt.minus(ACCOUNT_TRANSACTION_BOOTSTRAP_RANGE)
            val overlapStart = latest?.transaction?.transactionAt?.minus(ACCOUNT_TRANSACTION_OVERLAP)
            val unresolvedBaseline =
                store
                    .walletReconciliationState(runtimeMode, ACCOUNT_LEDGER_CURRENCY)
                    ?.baselineCapturedAt
                    ?.minusSeconds(1)
            val incrementalStart = overlapStart ?: bootstrapStart
            val requestedStart =
                unresolvedBaseline
                    ?.let { baseline -> minOf(baseline, incrementalStart) }
                    ?: incrementalStart
            val maximumRangeStart = endAt.minus(ACCOUNT_TRANSACTION_MAXIMUM_RANGE)
            val startAt = requestedStart.takeIf { it.isAfter(maximumRangeStart) } ?: maximumRangeStart
            val receivedAt = Instant.now(clock)
            val persisted =
                gateway
                    .accountTransactions(
                        currency = ACCOUNT_LEDGER_CURRENCY,
                        startAt = startAt,
                        endAt = endAt,
                    ).mapNotNull { transaction ->
                        val event =
                            ExecutionAccountTransactionEvent(
                                mode = runtimeMode,
                                transaction = transaction,
                                receivedAt = receivedAt,
                            )
                        store.recordAccountTransaction(event)?.let { id -> event.copy(id = id) }
                    }
            logger.info(
                "execution account transactions persisted mode={} currency={} startAt={} endAt={} newTransactions={}",
                runtimeMode.name,
                ACCOUNT_LEDGER_CURRENCY,
                startAt,
                endAt,
                persisted.size,
            )
            AccountTransactionSyncResult(succeeded = true, persisted = persisted)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger.warn(
                "execution account transaction sync unavailable mode={} currency={} errorType={} message={}",
                runtimeMode.name,
                ACCOUNT_LEDGER_CURRENCY,
                error::class.simpleName,
                error.message,
            )
            AccountTransactionSyncResult(succeeded = false)
        }
    }

    private suspend fun persistWalletReconciliation(
        current: ExecutionAccountSnapshot,
        transactionSyncSucceeded: Boolean,
    ): ExecutionWalletReconciliationState? {
        val store = projectionStore ?: return null
        val previous = store.walletReconciliationState(runtimeMode, ACCOUNT_LEDGER_CURRENCY)
        val transactions =
            store.accountTransactions(
                mode = runtimeMode,
                currency = ACCOUNT_LEDGER_CURRENCY,
                transactionAtOrAfter = previous?.baselineCapturedAt,
                transactionAtOrBefore = current.capturedAt,
            )
        val state =
            ExecutionWalletReconciler.update(
                previous = previous,
                current = current,
                transactions = transactions,
                currency = ACCOUNT_LEDGER_CURRENCY,
                tolerance = config.walletReconciliationTolerance,
                transactionSyncSucceeded = transactionSyncSucceeded,
                reconciledAt = Instant.now(clock),
            )
        store.upsertWalletReconciliationState(state)
        logger.info(
            "execution wallet reconciliation completed mode={} currency={} status={} observedChange={} ledgerChange={} difference={} consecutiveMismatches={}",
            runtimeMode.name,
            ACCOUNT_LEDGER_CURRENCY,
            state.status.name,
            state.observedWalletChange?.toPlainString(),
            state.ledgerChange?.toPlainString(),
            state.difference?.toPlainString(),
            state.consecutiveMismatches,
        )
        return state
    }

    private suspend fun currentEntryRiskDecision(now: Instant): ExecutionRiskDecision {
        val store = projectionStore ?: return ExecutionRiskDecision(listOf("RISK_STATE_STORE_UNAVAILABLE"))
        var state = store.executionRiskState(runtimeMode)
        var decision = config.evaluateRiskState(state, now)
        if (!config.walletReconciliationEnabled && decision.reasonCodes.any(RISK_STATE_REFRESH_REASON_CODES::contains)) {
            val snapshot = persistAccountSnapshot()
            if (snapshot != null) {
                state = persistRiskState(snapshot, recentClosuresAfter(state?.lastClosureId))
                decision = config.evaluateRiskState(state, now)
            }
        }
        val reasonCodes = decision.reasonCodes.toMutableList()
        if (config.walletReconciliationEnabled) {
            reasonCodes +=
                ExecutionWalletReconciler
                    .evaluate(
                        state = store.walletReconciliationState(runtimeMode, ACCOUNT_LEDGER_CURRENCY),
                        now = now,
                        maximumAge = config.walletReconciliationMaximumAge,
                        confirmedMismatchCount = config.walletReconciliationConfirmedMismatchCount,
                    ).reasonCodes
        }
        return ExecutionRiskDecision(reasonCodes.distinct())
    }

    private suspend fun persistRiskState(
        snapshot: ExecutionAccountSnapshot,
        newClosures: List<ExecutionTradeClosure>,
    ): ExecutionRiskState? {
        val store = projectionStore ?: return null
        val previous = store.executionRiskState(runtimeMode)
        val pendingClosures =
            (newClosures + recentClosuresAfter(previous?.lastClosureId))
                .distinctBy(ExecutionTradeClosure::id)
                .sortedBy(ExecutionTradeClosure::id)
        val accountTransactions =
            store.accountTransactionsAfterId(
                mode = runtimeMode,
                currency = ACCOUNT_LEDGER_CURRENCY,
                afterId = previous?.lastAccountTransactionId,
                transactionAtOrBefore = snapshot.capturedAt,
            )
        val state =
            ExecutionRiskCircuitBreaker.update(
                previous = previous,
                snapshot = snapshot,
                newClosures = pendingClosures,
                accountTransactions = accountTransactions,
            ) ?: return null
        store.upsertExecutionRiskState(state)
        return state
    }

    private suspend fun recentClosuresAfter(lastClosureId: Long?): List<ExecutionTradeClosure> {
        val store = projectionStore ?: return emptyList()
        return store
            .closedTrades(
                symbol = null,
                mode = runtimeMode,
                limit = RISK_CLOSURE_QUERY_LIMIT,
                cursor = null,
            ).filter { closure -> lastClosureId == null || closure.id > lastClosureId }
    }

    private suspend fun automaticEntryCountForUtcDay(
        symbol: Symbol,
        evaluatedAt: Instant,
    ): Int {
        val dayStart =
            evaluatedAt
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
        val store = lifecycleStore
        if (store != null) {
            return store
                .lifecycleEvents(runtimeMode, symbol, DAILY_ENTRY_EVENT_QUERY_LIMIT)
                .filter { event ->
                    event.state == ExecutionLifecycleState.ENTRY_SUBMITTED &&
                        event.reasonCode == "AUTOMATIC_ENTRY_SUBMITTED" &&
                        !event.occurredAt.isBefore(dayStart)
                }.distinctBy(ExecutionLifecycleEvent::lifecycleId)
                .size
        }

        val closures = gateway.closedPnls(symbol).map { closedPnl -> closedPnl.toTradeClosure(runtimeMode) }
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

    private suspend fun manageAutomaticPosition(
        position: ExchangePosition,
        timeframe: Timeframe,
        candleLimit: Int,
        mode: BotMode,
        evaluatedAt: Instant,
    ): ExchangeEvaluationResult? {
        val runtimeStore = positionRuntimeStore ?: return null
        val engine = automaticPositionPolicyEngine ?: return null
        val runtime = runtimeStore.executionPositionRuntimeState(runtimeMode, position.symbol) ?: return null
        val lifecycle = lifecycleStore?.latestLifecycleEvent(runtimeMode, position.symbol)
        if (
            lifecycle == null ||
            lifecycle.lifecycleId != runtime.lifecycleId ||
            lifecycle.state !in
            setOf(
                ExecutionLifecycleState.OPEN_UNPROTECTED,
                ExecutionLifecycleState.OPEN_PROTECTED,
                ExecutionLifecycleState.EXIT_SUBMITTED,
            ) ||
            runtime.timeframe != timeframe ||
            runtime.policyState.side != position.side ||
            !position.size.matchesQuantity(runtime.policyState.remainingQuantity, config.quantityStep) ||
            !position.entryPrice.isNear(BigDecimal.valueOf(runtime.policyState.entryPrice), config.priceTick)
        ) {
            return submitPolicyFailureExit(
                position = position,
                timeframe = timeframe,
                mode = mode,
                evaluatedAt = evaluatedAt,
                reasonCode = "POSITION_POLICY_STATE_MISMATCH",
            )
        }
        if (lifecycle.state == ExecutionLifecycleState.EXIT_SUBMITTED) {
            val activeExit =
                gateway
                    .openOrders(position.symbol)
                    .firstOrNull { order ->
                        order.status.isActive() && order.matches(lifecycle)
                    }
            if (activeExit == null && !evaluatedAt.isBefore(lifecycle.occurredAt.plus(config.protectionGracePeriod))) {
                return submitPolicyFailureExit(
                    position,
                    timeframe,
                    mode,
                    evaluatedAt,
                    "POSITION_EXIT_CONFIRMATION_TIMEOUT",
                )
            }
            return positionPolicyResult(
                position = position,
                timeframe = timeframe,
                mode = mode,
                evaluatedAt = evaluatedAt,
                status = ExchangeEvaluationStatus.NO_TRADE,
                reasonCode = "POSITION_EXIT_CONFIRMATION_PENDING",
                exchangeOrderId = activeExit?.exchangeOrderId ?: lifecycle.exchangeOrderId,
                clientOrderId = activeExit?.clientOrderId ?: lifecycle.clientOrderId,
            )
        }

        val closedBefore = closedCandleBoundary(evaluatedAt, timeframe)
        val candles =
            candleStore
                .recentCandles(position.symbol, timeframe, candleLimit)
                .filter { candle -> candle.openedAt.isBefore(closedBefore) }
                .sortedBy(Candle::openedAt)
        return when (val decision = engine.advance(runtime, candles, closedBefore)) {
            is AutomaticPositionPolicyDecision.Waiting ->
                positionPolicyResult(position, timeframe, mode, evaluatedAt, ExchangeEvaluationStatus.NO_TRADE, decision.reasonCode)

            is AutomaticPositionPolicyDecision.Failure ->
                submitPolicyFailureExit(position, timeframe, mode, evaluatedAt, decision.reasonCode)

            is AutomaticPositionPolicyDecision.Exit -> {
                runtimeStore.upsertExecutionPositionRuntimeState(decision.state)
                submitPolicyExit(
                    position = position,
                    timeframe = timeframe,
                    mode = mode,
                    evaluatedAt = evaluatedAt,
                    reasonCode = "POSITION_POLICY_${decision.reason.name}",
                )
            }

            is AutomaticPositionPolicyDecision.Update ->
                applyAutomaticPositionUpdate(
                    position = position,
                    lifecycle = lifecycle,
                    decision = decision,
                    timeframe = timeframe,
                    mode = mode,
                    evaluatedAt = evaluatedAt,
                )
        }
    }

    private suspend fun applyAutomaticPositionUpdate(
        position: ExchangePosition,
        lifecycle: ExecutionLifecycleEvent,
        decision: AutomaticPositionPolicyDecision.Update,
        timeframe: Timeframe,
        mode: BotMode,
        evaluatedAt: Instant,
    ): ExchangeEvaluationResult {
        val desired =
            ExecutionProtectionPlan(
                takeProfit = decision.takeProfit,
                stopLoss = decision.stopLoss,
                riskPerUnit = BigDecimal.valueOf(decision.state.policyState.riskPerUnit),
            )
        var observed = position
        if (!observed.matches(desired, config.priceTick)) {
            try {
                gateway.setPositionProtection(
                    ExchangePositionProtectionRequest(
                        symbol = position.symbol,
                        takeProfit = desired.takeProfit,
                        stopLoss = desired.stopLoss,
                    ),
                )
                observed =
                    gateway
                        .positions(position.symbol)
                        .firstOrNull { candidate -> candidate.size > BigDecimal.ZERO && candidate.side == position.side }
                        ?: return submitPolicyFailureExit(
                            position,
                            timeframe,
                            mode,
                            evaluatedAt,
                            "POSITION_POLICY_POSITION_MISSING_AFTER_UPDATE",
                        )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logger.error(
                    "execution causal position protection update failed symbol={} lifecycleId={} message={}",
                    position.symbol.value,
                    lifecycle.lifecycleId,
                    error.message,
                    error,
                )
                return submitPolicyFailureExit(
                    position,
                    timeframe,
                    mode,
                    evaluatedAt,
                    "POSITION_POLICY_PROTECTION_UPDATE_FAILED",
                )
            }
        }
        if (!observed.matches(desired, config.priceTick)) {
            return submitPolicyFailureExit(
                observed,
                timeframe,
                mode,
                evaluatedAt,
                "POSITION_POLICY_PROTECTION_VERIFICATION_FAILED",
            )
        }
        requireNotNull(positionRuntimeStore).upsertExecutionPositionRuntimeState(decision.state)
        recordObservedLifecycle(
            lifecycle.copy(
                id = 0,
                state = ExecutionLifecycleState.OPEN_PROTECTED,
                filledQuantity = observed.size,
                fillVwap = observed.entryPrice,
                takeProfit = observed.takeProfit,
                stopLoss = observed.stopLoss,
                reasonCode = "POSITION_POLICY_CLOSED_CANDLE_APPLIED",
                occurredAt = evaluatedAt,
            ),
        )
        return positionPolicyResult(
            observed,
            timeframe,
            mode,
            evaluatedAt,
            ExchangeEvaluationStatus.NO_TRADE,
            "POSITION_POLICY_CLOSED_CANDLE_APPLIED",
        )
    }

    private suspend fun submitPolicyFailureExit(
        position: ExchangePosition,
        timeframe: Timeframe,
        mode: BotMode,
        evaluatedAt: Instant,
        reasonCode: String,
    ): ExchangeEvaluationResult {
        logger.error(
            "execution causal position policy failed closed symbol={} reasonCode={}",
            position.symbol.value,
            reasonCode,
        )
        return submitPolicyExit(
            position = position,
            timeframe = timeframe,
            mode = mode,
            evaluatedAt = evaluatedAt,
            reasonCode = reasonCode,
        )
    }

    private suspend fun submitPolicyExit(
        position: ExchangePosition,
        timeframe: Timeframe,
        mode: BotMode,
        evaluatedAt: Instant,
        reasonCode: String,
    ): ExchangeEvaluationResult {
        val exitOrder =
            submitManualOrder(
                symbol = position.symbol,
                side = position.side.opposite(),
                quantity = position.size,
                reduceOnly = true,
                strategyName = "automatic-causal-position-policy",
                reasonCode = reasonCode,
                clientOrderPrefix = "policy",
            )
        return positionPolicyResult(
            position = position,
            timeframe = timeframe,
            mode = mode,
            evaluatedAt = evaluatedAt,
            status = ExchangeEvaluationStatus.EXIT_SUBMITTED,
            reasonCode = reasonCode,
            orderId = exitOrder.orderId,
            exchangeOrderId = exitOrder.exchangeOrderId,
            clientOrderId = exitOrder.clientOrderId,
        )
    }

    private fun positionPolicyResult(
        position: ExchangePosition,
        timeframe: Timeframe,
        mode: BotMode,
        evaluatedAt: Instant,
        status: ExchangeEvaluationStatus,
        reasonCode: String,
        orderId: Long? = null,
        exchangeOrderId: String? = null,
        clientOrderId: String? = null,
    ): ExchangeEvaluationResult =
        ExchangeEvaluationResult(
            symbol = position.symbol,
            timeframe = timeframe,
            mode = mode.name,
            status = status,
            evaluatedAt = evaluatedAt,
            candleCount = 0,
            reasonCodes = listOf(reasonCode),
            signalId = null,
            orderId = orderId,
            exchangeOrderId = exchangeOrderId,
            clientOrderId = clientOrderId,
            entryPrice = position.entryPrice,
            takeProfit = position.takeProfit,
            stopLoss = position.stopLoss,
            quantity = position.size,
            intendedRisk = null,
        )

    private fun entryBlockedResult(
        symbol: Symbol,
        timeframe: Timeframe,
        mode: BotMode,
        evaluatedAt: Instant,
        reasonCodes: List<String>,
    ): ExchangeEvaluationResult =
        ExchangeEvaluationResult(
            symbol = symbol,
            timeframe = timeframe,
            mode = mode.name,
            status = ExchangeEvaluationStatus.NO_TRADE,
            evaluatedAt = evaluatedAt,
            candleCount = 0,
            reasonCodes = reasonCodes,
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
            protectionRequired = latestLifecycle?.protectionRequired ?: false,
            plannedEntryPrice = latestLifecycle?.plannedEntryPrice,
            structuralStopPrice = latestLifecycle?.structuralStopPrice,
            entryAnchoredStopDistance = latestLifecycle?.entryAnchoredStopDistance,
            expectedR = latestLifecycle?.expectedR,
            protectionDeadlineAt = latestLifecycle?.protectionDeadlineAt,
            fixedTargetEnabled = latestLifecycle?.fixedTargetEnabled ?: true,
            intendedRisk = latestLifecycle?.intendedRisk,
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
        protectionRequired: Boolean = false,
        plannedEntryPrice: BigDecimal? = null,
        structuralStopPrice: BigDecimal? = null,
        entryAnchoredStopDistance: BigDecimal? = null,
        expectedR: BigDecimal? = null,
        protectionDeadlineAt: Instant? = null,
        fixedTargetEnabled: Boolean = true,
        intendedRisk: BigDecimal? = null,
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
                protectionRequired = protectionRequired,
                plannedEntryPrice = plannedEntryPrice,
                structuralStopPrice = structuralStopPrice,
                entryAnchoredStopDistance = entryAnchoredStopDistance,
                expectedR = expectedR,
                protectionDeadlineAt = protectionDeadlineAt,
                fixedTargetEnabled = fixedTargetEnabled,
                intendedRisk = intendedRisk,
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

    private suspend fun SignalIntent.isDuplicate(signalKey: String): Boolean =
        tradingStore
            .recentSignals(config.duplicateSignalLookback)
            .any { recentSignal ->
                recentSignal.accepted &&
                    recentSignal.strategy == strategy &&
                    recentSignal.symbol == symbol &&
                    signalKey in recentSignal.reasonCodes
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
                accountSnapshots =
                    store.accountSnapshots(effectiveMode, window.startAt(capturedAt, sessionStartedAt)),
                accountBaseline =
                    window
                        .startAt(capturedAt, sessionStartedAt)
                        ?.let { startAt -> store.latestAccountSnapshot(effectiveMode, startAt) },
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

private fun BotMode.allowsPositionManagement(): Boolean = this != BotMode.EMERGENCY_STOP

private fun OrderStatus.isActive(): Boolean = this == OrderStatus.SUBMITTED || this == OrderStatus.PARTIALLY_FILLED

private fun ExecutionLifecycleState.isUnfilledTerminalEntry(): Boolean =
    this == ExecutionLifecycleState.ENTRY_CANCELLED || this == ExecutionLifecycleState.ENTRY_REJECTED

private enum class PositionRuntimeInitialization {
    NOT_REQUIRED,
    PENDING,
    READY,
    FAILED,
}

private fun ExchangeOpenOrder.matches(event: ExecutionLifecycleEvent): Boolean =
    (!exchangeOrderId.isNullOrBlank() && exchangeOrderId == event.exchangeOrderId) ||
        (!clientOrderId.isNullOrBlank() && clientOrderId == event.clientOrderId)

private fun ExchangeExecutionFill.matches(event: ExecutionLifecycleEvent): Boolean =
    (!exchangeOrderId.isNullOrBlank() && exchangeOrderId == event.exchangeOrderId) ||
        (!clientOrderId.isNullOrBlank() && clientOrderId == event.clientOrderId)

private fun ExchangeOrderUpdate.matches(event: ExecutionLifecycleEvent): Boolean =
    (!exchangeOrderId.isNullOrBlank() && exchangeOrderId == event.exchangeOrderId) ||
        (!clientOrderId.isNullOrBlank() && clientOrderId == event.clientOrderId)

private fun List<ExchangeExecutionFill>.weightedVwap(): BigDecimal {
    val totalQuantity = fold(BigDecimal.ZERO) { total, fill -> total + fill.quantity }
    require(totalQuantity > BigDecimal.ZERO) { "Execution fill quantity must be positive for VWAP." }
    val notional = fold(BigDecimal.ZERO) { total, fill -> total + fill.price.multiply(fill.quantity) }
    return notional.divide(totalQuantity, MathContext.DECIMAL128)
}

private fun BigDecimal?.isNear(
    expected: BigDecimal,
    tolerance: BigDecimal,
): Boolean = this != null && subtract(expected).abs() <= tolerance

private fun BigDecimal?.matchesOptional(
    expected: BigDecimal?,
    tolerance: BigDecimal,
): Boolean = if (expected == null) this == null || compareTo(BigDecimal.ZERO) == 0 else isNear(expected, tolerance)

private fun BigDecimal.matchesQuantity(
    expected: Double,
    quantityStep: BigDecimal,
): Boolean = subtract(BigDecimal.valueOf(expected)).abs() <= quantityStep.divide(BigDecimal("2"), MathContext.DECIMAL64)

private fun automaticEntryLimitPrice(
    side: Side,
    referencePrice: BigDecimal,
    slippageRate: BigDecimal,
    priceTick: BigDecimal,
): BigDecimal {
    val rawLimit =
        when (side) {
            Side.BUY -> referencePrice.multiply(BigDecimal.ONE.add(slippageRate), MathContext.DECIMAL64)
            Side.SELL -> referencePrice.multiply(BigDecimal.ONE.subtract(slippageRate), MathContext.DECIMAL64)
        }
    return when (side) {
        Side.BUY -> rawLimit.floorToStep(priceTick)
        Side.SELL -> rawLimit.ceilToStep(priceTick)
    }
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

private fun Timeframe.executionDurationMillis(): Long =
    when (this) {
        Timeframe.M1 -> 60_000L
        Timeframe.M5 -> 300_000L
        Timeframe.M15 -> 900_000L
        Timeframe.H1 -> 3_600_000L
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
    signalKey: String,
): PaperSignalRecord =
    PaperSignalRecord(
        strategy = strategy,
        symbol = symbol,
        side = side,
        score = score.total,
        grade = score.total.toGrade(),
        reasonCodes = (score.reasonCodes + signalKey).distinct(),
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

private fun readinessLossFraction(
    baseline: BigDecimal?,
    current: BigDecimal?,
): BigDecimal? {
    if (baseline == null || current == null || baseline <= BigDecimal.ZERO) return null
    if (current >= baseline) return BigDecimal.ZERO
    return baseline.subtract(current).divide(baseline, MathContext.DECIMAL64)
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
        exitReason = exitReason ?: "UNKNOWN",
        exchangeOrderId = exchangeOrderId,
        clientOrderId = clientOrderId,
    )

private fun ExchangeClosedPnl.resolveExitReason(executions: List<ExchangeExecutionFill>): ExchangeClosedPnl {
    val existingReason = exitReason?.trim()?.uppercase()
    if (existingReason != null && existingReason !in GENERIC_EXIT_REASONS) return this

    val matchingExecutions =
        executions.filter { execution ->
            (!exchangeOrderId.isNullOrBlank() && execution.exchangeOrderId == exchangeOrderId) ||
                (!clientOrderId.isNullOrBlank() && execution.clientOrderId == clientOrderId)
        }
    val observedReason = matchingExecutions.asSequence().mapNotNull(ExchangeExecutionFill::exitReason).firstOrNull()
    val clientReason = clientOrderId?.let(::clientOrderExitReason)
    return copy(exitReason = observedReason ?: clientReason ?: existingReason ?: "UNKNOWN")
}

private fun ExchangeExecutionFill.exitReason(): String? =
    listOf(createType, stopOrderType, executionType)
        .asSequence()
        .mapNotNull(::normalizeExchangeExitReason)
        .firstOrNull()

private fun normalizeExchangeExitReason(value: String?): String? {
    val normalized = value?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: return null
    return when {
        normalized.contains("ADL") -> "ADL"
        normalized.contains("LIQ") || normalized.contains("TAKEOVER") -> "LIQUIDATION"
        normalized.contains("TRAILING") -> "TRAILING_STOP"
        normalized.contains("TAKE_PROFIT") || normalized.contains("TAKEPROFIT") -> "TAKE_PROFIT"
        normalized.contains("STOP_LOSS") || normalized.contains("STOPLOSS") -> "STOP_LOSS"
        else -> null
    }
}

private fun clientOrderExitReason(clientOrderId: String): String? {
    val prefix = clientOrderId.substringBefore('-', missingDelimiterValue = clientOrderId).uppercase()
    return when (prefix) {
        "TIME" -> "TIME_EXIT"
        "CLOSE" -> "MANUAL_EXIT"
        "MANUAL" -> "MANUAL_EXIT"
        else -> null
    }
}

private val GENERIC_EXIT_REASONS = setOf("", "CLOSED_PNL", "CLOSED_PNL_OBSERVED", "UNKNOWN")

private fun ExchangeAccountBalance.toExecutionAccountSnapshot(mode: ExecutionRuntimeMode): ExecutionAccountSnapshot {
    val trackedCoin = coins.firstOrNull { coin -> coin.coin.equals(ACCOUNT_LEDGER_CURRENCY, ignoreCase = true) }
    return ExecutionAccountSnapshot(
        mode = mode,
        accountType = accountType,
        totalEquity = totalEquity,
        totalWalletBalance = totalWalletBalance,
        totalMarginBalance = totalMarginBalance,
        totalAvailableBalance = totalAvailableBalance,
        totalPerpUnrealizedPnl = totalPerpUnrealizedPnl,
        capturedAt = capturedAt,
        totalInitialMargin = totalInitialMargin,
        totalMaintenanceMargin = totalMaintenanceMargin,
        trackedCoin = trackedCoin?.coin,
        trackedCoinEquity = trackedCoin?.equity,
        trackedCoinWalletBalance = trackedCoin?.walletBalance,
        trackedCoinUnrealizedPnl = trackedCoin?.unrealizedPnl,
        trackedCoinCumulativeRealizedPnl = trackedCoin?.cumulativeRealizedPnl,
    )
}

private fun List<ExecutionTradeClosure>.toPerformanceSnapshot(
    mode: ExecutionRuntimeMode,
    window: LivePerformanceWindow,
    capturedAt: Instant,
    accountSnapshots: List<ExecutionAccountSnapshot> = emptyList(),
    accountBaseline: ExecutionAccountSnapshot? = null,
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
    var closedTradeEquity = BigDecimal.ZERO
    var closedTradePeak = BigDecimal.ZERO
    var maxClosedTradeDrawdownPct = BigDecimal.ZERO
    sorted.forEach { trade ->
        closedTradeEquity += trade.netPnl
        if (closedTradeEquity > closedTradePeak) closedTradePeak = closedTradeEquity
        if (closedTradePeak > BigDecimal.ZERO) {
            val drawdown =
                closedTradePeak
                    .subtract(closedTradeEquity)
                    .divide(closedTradePeak, 8, RoundingMode.HALF_UP)
                    .multiply(BigDecimal("100"))
            if (drawdown > maxClosedTradeDrawdownPct) maxClosedTradeDrawdownPct = drawdown
        }
    }
    val accountPoints =
        buildList {
            accountBaseline?.let(::add)
            addAll(accountSnapshots)
        }.sortedWith(compareBy(ExecutionAccountSnapshot::capturedAt, ExecutionAccountSnapshot::id))
    var accountPeak: BigDecimal? = null
    var maxAccountDrawdownPct: BigDecimal? = null
    accountPoints.forEach { snapshot ->
        val equity = snapshot.totalEquity ?: return@forEach
        val previousPeak = accountPeak
        val peak = if (previousPeak == null || equity > previousPeak) equity else previousPeak
        accountPeak = peak
        if (peak > BigDecimal.ZERO) {
            val drawdown =
                peak
                    .subtract(equity)
                    .divide(peak, 8, RoundingMode.HALF_UP)
                    .multiply(BigDecimal("100"))
            if (maxAccountDrawdownPct == null || drawdown > maxAccountDrawdownPct) {
                maxAccountDrawdownPct = drawdown
            }
        }
    }
    val latestAccountSnapshot = accountPoints.lastOrNull { it.totalEquity != null }
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
        maxClosedTradeDrawdownPct = maxClosedTradeDrawdownPct,
        lastClosedAt = sorted.lastOrNull()?.closedAt,
        capturedAt = capturedAt,
        accountEquity = latestAccountSnapshot?.totalEquity,
        accountPeakEquity = accountPeak,
        maxAccountDrawdownPct = maxAccountDrawdownPct,
        accountEquityCapturedAt = latestAccountSnapshot?.capturedAt,
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
private const val DAILY_ENTRY_EVENT_QUERY_LIMIT = 1000
private const val RISK_CLOSURE_QUERY_LIMIT = 1000
private const val ACCOUNT_LEDGER_CURRENCY = "USDT"
private val ACCOUNT_TRANSACTION_BOOTSTRAP_RANGE: Duration = Duration.ofHours(24)
private val ACCOUNT_TRANSACTION_OVERLAP: Duration = Duration.ofMinutes(5)
private val ACCOUNT_TRANSACTION_MAXIMUM_RANGE: Duration = Duration.ofDays(7)
private val RISK_STATE_REFRESH_REASON_CODES =
    setOf(
        "RISK_STATE_UNAVAILABLE",
        "RISK_STATE_STALE",
        "RISK_STATE_CLOCK_SKEW",
    )

private fun ExchangeExecutionConfig.evaluateRiskState(
    state: ExecutionRiskState?,
    now: Instant,
): ExecutionRiskDecision =
    ExecutionRiskCircuitBreaker.evaluate(
        state = state,
        now = now,
        maximumAge = riskStateMaximumAge,
        maximumDailyLossFraction = maximumDailyLossFraction,
        maximumAccountDrawdownFraction = maximumAccountDrawdownFraction,
        maximumConsecutiveLosses = maximumConsecutiveLosses,
        useUnitizedNav = walletReconciliationEnabled,
    )

private data class AccountTransactionSyncResult(
    val succeeded: Boolean,
    val persisted: List<ExecutionAccountTransactionEvent> = emptyList(),
)
