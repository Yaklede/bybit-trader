package dev.yaklede.bybittrader.engine.execution

import dev.yaklede.bybittrader.domain.BotMode
import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.OrderStatus
import dev.yaklede.bybittrader.domain.OrderType
import dev.yaklede.bybittrader.domain.Price
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.SignalIntent
import dev.yaklede.bybittrader.domain.SignalScore
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.control.BotRuntimeStatus
import dev.yaklede.bybittrader.engine.control.BotStateStore
import dev.yaklede.bybittrader.engine.market.MarketCandleStore
import dev.yaklede.bybittrader.engine.paper.PaperFillRecord
import dev.yaklede.bybittrader.engine.paper.PaperOrderRecord
import dev.yaklede.bybittrader.engine.paper.PaperPerformanceSnapshot
import dev.yaklede.bybittrader.engine.paper.PaperPositionRecord
import dev.yaklede.bybittrader.engine.paper.PaperSignalRecord
import dev.yaklede.bybittrader.engine.paper.PaperTradeRecord
import dev.yaklede.bybittrader.engine.paper.PaperTradingStore
import dev.yaklede.bybittrader.strategy.StrategyDecision
import dev.yaklede.bybittrader.strategy.TradingStrategy
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ExchangeExecutionServiceTest :
    StringSpec({
        "disabled execution returns disabled without evaluating or submitting" {
            val gateway = RecordingExecutionGateway()
            val service =
                testService(
                    gateway = gateway,
                    config = ExchangeExecutionConfig(enabled = false),
                )

            val result =
                service.evaluateAndSubmit(
                    symbol = Symbol("BTCUSDT"),
                    timeframe = Timeframe.M5,
                    candleLimit = 30,
                )

            result.status shouldBe ExchangeEvaluationStatus.DISABLED
            gateway.placedOrders shouldBe emptyList()
        }

        "running execution sizes and submits a market order" {
            val gateway = RecordingExecutionGateway()
            val store = InMemoryTradingStore()
            val service =
                testService(
                    store = store,
                    gateway = gateway,
                    config =
                        ExchangeExecutionConfig(
                            enabled = true,
                            accountEquity = BigDecimal("1000000"),
                            riskFraction = BigDecimal("0.055"),
                            quantityStep = BigDecimal("0.001"),
                            minQuantity = BigDecimal("0.001"),
                            maxNotional = BigDecimal("1000000"),
                        ),
                )

            val result =
                service.evaluateAndSubmit(
                    symbol = Symbol("BTCUSDT"),
                    timeframe = Timeframe.M5,
                    candleLimit = 30,
                )

            result.status shouldBe ExchangeEvaluationStatus.SUBMITTED
            result.quantity shouldBe BigDecimal("9523.809")
            result.takeProfit shouldBe BigDecimal("112.5")
            result.stopLoss shouldBe BigDecimal("100")
            store.signals.single().accepted shouldBe true
            store.orders.single().orderStatus shouldBe OrderStatus.SUBMITTED
            store.orders.single().exchangeOrderId shouldBe "exchange-1"
            gateway.placedOrders.map { it.clientOrderId }.shouldContainExactly(
                listOf("bt-BTCUSDT-1719705600000-1-B"),
            )
        }

        "live account equity and leverage cap execution quantity" {
            val gateway =
                RecordingExecutionGateway(
                    accountBalance =
                        ExchangeAccountBalance(
                            accountType = "UNIFIED",
                            totalEquity = BigDecimal("200"),
                            totalWalletBalance = BigDecimal("200"),
                            totalMarginBalance = BigDecimal("200"),
                            totalAvailableBalance = BigDecimal("200"),
                            totalPerpUnrealizedPnl = BigDecimal.ZERO,
                            totalInitialMargin = BigDecimal.ZERO,
                            totalMaintenanceMargin = BigDecimal.ZERO,
                            coins = emptyList(),
                            capturedAt = Instant.parse("2024-06-30T00:00:00Z"),
                        ),
                )
            val service =
                testService(
                    gateway = gateway,
                    config =
                        ExchangeExecutionConfig(
                            enabled = true,
                            accountEquity = BigDecimal("1000000"),
                            useLiveAccountEquity = true,
                            leverage = BigDecimal("1.1"),
                            riskFraction = BigDecimal("0.055"),
                            quantityStep = BigDecimal("0.001"),
                            minQuantity = BigDecimal("0.001"),
                        ),
                )

            val result =
                service.evaluateAndSubmit(
                    symbol = Symbol("BTCUSDT"),
                    timeframe = Timeframe.M5,
                    candleLimit = 30,
                )

            result.status shouldBe ExchangeEvaluationStatus.SUBMITTED
            result.intendedRisk shouldBe BigDecimal("11.000")
            result.quantity shouldBe BigDecimal("2.095")
            gateway.leverageRequests.shouldContainExactly(listOf(Symbol("BTCUSDT") to BigDecimal("1.1")))
            gateway.placedOrders.single().quantity shouldBe BigDecimal("2.095")
        }

        "paused mode skips new entries before submitting" {
            val gateway = RecordingExecutionGateway()
            val service =
                testService(
                    stateStore = InMemoryStateStore(BotMode.PAUSE_NEW_ENTRIES),
                    gateway = gateway,
                    config = ExchangeExecutionConfig(enabled = true),
                )

            val result =
                service.evaluateAndSubmit(
                    symbol = Symbol("BTCUSDT"),
                    timeframe = Timeframe.M5,
                    candleLimit = 30,
                )

            result.status shouldBe ExchangeEvaluationStatus.SKIPPED_BY_MODE
            gateway.placedOrders shouldBe emptyList()
        }

        "reconcile delegates to gateway snapshots" {
            val gateway =
                RecordingExecutionGateway(
                    openOrders =
                        listOf(
                            ExchangeOpenOrder(
                                exchangeOrderId = "exchange-1",
                                clientOrderId = "client-1",
                                symbol = Symbol("BTCUSDT"),
                                side = Side.BUY,
                                orderType = OrderType.MARKET,
                                status = OrderStatus.SUBMITTED,
                                quantity = BigDecimal("1"),
                                createdAt = Instant.parse("2024-06-30T00:00:00Z"),
                            ),
                        ),
                )
            val service = testService(gateway = gateway, config = ExchangeExecutionConfig(enabled = true))

            val report = service.reconcile(Symbol("BTCUSDT"))

            report.openOrders.single().exchangeOrderId shouldBe "exchange-1"
            report.reconciledAt shouldBe Instant.parse("2024-06-30T00:00:00Z")
        }

        "account balance delegates to gateway" {
            val gateway =
                RecordingExecutionGateway(
                    accountBalance =
                        ExchangeAccountBalance(
                            accountType = "UNIFIED",
                            totalEquity = BigDecimal("1200.5"),
                            totalWalletBalance = BigDecimal("1000"),
                            totalMarginBalance = BigDecimal("1100"),
                            totalAvailableBalance = BigDecimal("900"),
                            totalPerpUnrealizedPnl = BigDecimal("100.5"),
                            totalInitialMargin = BigDecimal("50"),
                            totalMaintenanceMargin = BigDecimal("20"),
                            coins =
                                listOf(
                                    ExchangeCoinBalance(
                                        coin = "USDT",
                                        equity = BigDecimal("1200.5"),
                                        usdValue = BigDecimal("1200.5"),
                                        walletBalance = BigDecimal("1000"),
                                        locked = BigDecimal.ZERO,
                                        unrealizedPnl = BigDecimal("100.5"),
                                    ),
                                ),
                            capturedAt = Instant.parse("2024-06-30T00:00:00Z"),
                        ),
                )
            val service = testService(gateway = gateway, config = ExchangeExecutionConfig(enabled = true))

            val balance = service.accountBalance("USDT")

            balance.accountType shouldBe "UNIFIED"
            balance.totalEquity shouldBe BigDecimal("1200.5")
            balance.coins.single().coin shouldBe "USDT"
        }
    })

private fun testService(
    stateStore: InMemoryStateStore = InMemoryStateStore(BotMode.RUNNING),
    candleStore: InMemoryCandleStore = InMemoryCandleStore(),
    store: InMemoryTradingStore = InMemoryTradingStore(),
    gateway: RecordingExecutionGateway = RecordingExecutionGateway(),
    config: ExchangeExecutionConfig,
): ExchangeExecutionService =
    ExchangeExecutionService(
        stateStore = stateStore,
        candleStore = candleStore,
        tradingStore = store,
        strategy = AlwaysBuyExecutionStrategy(),
        gateway = gateway,
        config = config,
        clock = Clock.fixed(Instant.parse("2024-06-30T00:00:00Z"), ZoneOffset.UTC),
    )

private class InMemoryStateStore(
    mode: BotMode,
) : BotStateStore {
    private var status =
        BotRuntimeStatus(
            mode = mode,
            updatedAt = Instant.parse("2024-06-30T00:00:00Z"),
            heartbeatAt = null,
        )

    override suspend fun current(): BotRuntimeStatus = status

    override suspend fun update(status: BotRuntimeStatus) {
        this.status = status
    }
}

private class InMemoryCandleStore : MarketCandleStore {
    override suspend fun upsert(candles: List<Candle>) = Unit

    override suspend fun recentCandles(
        symbol: Symbol,
        timeframe: Timeframe,
        limit: Int,
    ): List<Candle> =
        (0 until 40)
            .map { index ->
                val close = BigDecimal("105")
                Candle(
                    symbol = symbol,
                    timeframe = timeframe,
                    openedAt = Instant.parse("2024-06-30T00:00:00Z").plusSeconds(index * 300L),
                    open = close,
                    high = close + BigDecimal("2"),
                    low = close - BigDecimal("2"),
                    close = close,
                    volume = BigDecimal("100"),
                )
            }.takeLast(limit)
}

private class InMemoryTradingStore : PaperTradingStore {
    val signals = mutableListOf<PaperSignalRecord>()
    val orders = mutableListOf<PaperOrderRecord>()

    override suspend fun recordSignal(signal: PaperSignalRecord): Long {
        val id = signals.size + 1L
        signals += signal.copy(id = id)
        return id
    }

    override suspend fun recordOrder(order: PaperOrderRecord): Long {
        val id = orders.size + 1L
        orders += order.copy(id = id)
        return id
    }

    override suspend fun recordFill(fill: PaperFillRecord): Long = 0

    override suspend fun recordPosition(position: PaperPositionRecord): Long = 0

    override suspend fun recordPerformanceSnapshot(snapshot: PaperPerformanceSnapshot): Long = 0

    override suspend fun latestPerformanceSummary(): PaperPerformanceSnapshot? = null

    override suspend fun recentSignals(limit: Int): List<PaperSignalRecord> = signals.asReversed().take(limit)

    override suspend fun recentTrades(limit: Int): List<PaperTradeRecord> = emptyList()
}

private class RecordingExecutionGateway(
    private val openOrders: List<ExchangeOpenOrder> = emptyList(),
    private val accountBalance: ExchangeAccountBalance =
        ExchangeAccountBalance(
            accountType = "UNIFIED",
            totalEquity = BigDecimal("1000"),
            totalWalletBalance = BigDecimal("1000"),
            totalMarginBalance = BigDecimal("1000"),
            totalAvailableBalance = BigDecimal("1000"),
            totalPerpUnrealizedPnl = BigDecimal.ZERO,
            totalInitialMargin = BigDecimal.ZERO,
            totalMaintenanceMargin = BigDecimal.ZERO,
            coins = emptyList(),
            capturedAt = Instant.parse("2024-06-30T00:00:00Z"),
        ),
) : ExchangeExecutionGateway {
    val leverageRequests = mutableListOf<Pair<Symbol, BigDecimal>>()
    val placedOrders = mutableListOf<ExchangeOrderRequest>()

    override suspend fun setLeverage(
        symbol: Symbol,
        leverage: BigDecimal,
    ) {
        leverageRequests += symbol to leverage
    }

    override suspend fun placeOrder(request: ExchangeOrderRequest): ExchangeOrderResult {
        placedOrders += request
        return ExchangeOrderResult(
            exchangeOrderId = "exchange-1",
            clientOrderId = request.clientOrderId,
            status = OrderStatus.SUBMITTED,
        )
    }

    override suspend fun cancelOrder(request: ExchangeCancelRequest): ExchangeCancelResult =
        ExchangeCancelResult(
            exchangeOrderId = request.exchangeOrderId,
            clientOrderId = request.clientOrderId,
        )

    override suspend fun openOrders(symbol: Symbol): List<ExchangeOpenOrder> = openOrders

    override suspend fun positions(symbol: Symbol): List<ExchangePosition> = emptyList()

    override suspend fun executions(symbol: Symbol): List<ExchangeExecutionFill> = emptyList()

    override suspend fun accountBalance(coin: String?): ExchangeAccountBalance = accountBalance
}

private class AlwaysBuyExecutionStrategy : TradingStrategy {
    override val name: String = "always-buy-execution-test"
    override val warmupCandles: Int = 2

    override fun evaluate(candles: List<Candle>): StrategyDecision {
        val latest = candles.last()
        return StrategyDecision(
            intent =
                SignalIntent(
                    symbol = latest.symbol,
                    side = Side.BUY,
                    strategy = name,
                    score = SignalScore(80, listOf("TEST_ENTRY")),
                    invalidationPrice = Price(BigDecimal("100")),
                    expectedR = BigDecimal("1.5"),
                ),
            reasonCodes = listOf("TEST_ENTRY"),
        )
    }
}
