package dev.yaklede.bybittrader.engine.execution

import dev.yaklede.bybittrader.domain.BotMode
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
import java.time.Instant

class ExchangeExecutionService(
    private val stateStore: BotStateStore,
    private val candleStore: MarketCandleStore,
    private val tradingStore: PaperTradingStore,
    private val strategy: TradingStrategy,
    private val gateway: ExchangeExecutionGateway,
    private val config: ExchangeExecutionConfig = ExchangeExecutionConfig(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(ExchangeExecutionService::class.java)

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

        val candles = candleStore.recentCandles(symbol, timeframe, candleLimit).sortedBy { it.openedAt }
        require(candles.size >= strategy.warmupCandles) {
            "At least ${strategy.warmupCandles} candles are required for ${strategy.name}."
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
        val intendedRisk = config.accountEquity.multiply(config.riskFraction, MathContext.DECIMAL64)
        val sizing = calculateSizing(entryPrice, riskPerUnit, intendedRisk)
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
        val signalId =
            tradingStore.recordSignal(
                signal.toRecord(
                    accepted = true,
                    rejectionReason = null,
                    createdAt = now,
                ),
            )
        val clientOrderId = clientOrderId(symbol = symbol, side = signal.side, now = now, signalId = signalId)
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

    suspend fun reconcile(symbol: Symbol): ExchangeReconciliationReport {
        logger.info("execution reconcile requested symbol={}", symbol.value)
        val report =
            ExchangeReconciliationReport(
                symbol = symbol,
                reconciledAt = Instant.now(clock),
                openOrders = gateway.openOrders(symbol),
                positions = gateway.positions(symbol),
                executions = gateway.executions(symbol),
            )
        logger.info(
            "execution reconcile completed symbol={} openOrders={} positions={} executions={}",
            symbol.value,
            report.openOrders.size,
            report.positions.size,
            report.executions.size,
        )
        return report
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
    ): Sizing? {
        if (riskPerUnit <= BigDecimal.ZERO) return null
        var quantity = intendedRisk.divide(riskPerUnit, MathContext.DECIMAL64).floorToStep(config.quantityStep)
        config.maxQuantity?.let { maxQuantity ->
            if (quantity > maxQuantity) quantity = maxQuantity.floorToStep(config.quantityStep)
        }
        config.maxNotional?.let { maxNotional ->
            val maxQuantityByNotional = maxNotional.divide(entryPrice, MathContext.DECIMAL64).floorToStep(config.quantityStep)
            if (quantity > maxQuantityByNotional) quantity = maxQuantityByNotional
        }
        return if (quantity >= config.minQuantity) Sizing(quantity = quantity) else null
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

private data class Sizing(
    val quantity: BigDecimal,
)

private fun BotMode.blocksNewEntries(): Boolean = this != BotMode.RUNNING

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

private const val SIGNAL_KEY_PREFIX = "ENTRY_AT_"
