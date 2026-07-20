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
import dev.yaklede.bybittrader.engine.market.MarketDataException
import dev.yaklede.bybittrader.engine.market.MarketDataFeed
import dev.yaklede.bybittrader.engine.market.MarketDataSyncService
import dev.yaklede.bybittrader.engine.paper.PaperFillRecord
import dev.yaklede.bybittrader.engine.paper.PaperOrderRecord
import dev.yaklede.bybittrader.engine.paper.PaperPerformanceSnapshot
import dev.yaklede.bybittrader.engine.paper.PaperPositionRecord
import dev.yaklede.bybittrader.engine.paper.PaperSignalRecord
import dev.yaklede.bybittrader.engine.paper.PaperTradeRecord
import dev.yaklede.bybittrader.engine.paper.PaperTradingStore
import dev.yaklede.bybittrader.strategy.StrategyDecision
import dev.yaklede.bybittrader.strategy.TradingStrategy
import io.kotest.assertions.throwables.shouldThrow
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

        "rejects a live order whose stop reaches the estimated liquidation boundary" {
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
                            riskFraction = BigDecimal("0.01"),
                            leverage = BigDecimal("25"),
                        ),
                )

            val result =
                service.evaluateAndSubmit(
                    symbol = Symbol("BTCUSDT"),
                    timeframe = Timeframe.M5,
                    candleLimit = 30,
                )

            result.status shouldBe ExchangeEvaluationStatus.REJECTED
            result.reasonCodes shouldBe listOf("STOP_REACHES_ESTIMATED_LIQUIDATION")
            gateway.placedOrders shouldBe emptyList()
            store.signals.single().rejectionReason shouldBe "STOP_REACHES_ESTIMATED_LIQUIDATION"
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

        "evaluation ignores the current open candle and reports closed warmup shortage" {
            val symbol = Symbol("BTCUSDT")
            val service =
                testService(
                    candleStore =
                        ListCandleStore(
                            listOf(
                                executionCandle(symbol, Instant.parse("2024-06-29T23:55:00Z")),
                                executionCandle(symbol, Instant.parse("2024-06-30T00:00:00Z")),
                            ),
                        ),
                    config = ExchangeExecutionConfig(enabled = true),
                )

            val result = service.evaluateAndSubmit(symbol, Timeframe.M5, 2)

            result.status shouldBe ExchangeEvaluationStatus.NO_TRADE
            result.candleCount shouldBe 1
            result.reasonCodes shouldContainExactly listOf("INSUFFICIENT_CLOSED_CANDLE_HISTORY")
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

        "read reconcile does not persist and runtime reconcile persists closed pnl once" {
            val store = InMemoryTradingStore()
            val gateway =
                RecordingExecutionGateway(
                    closedPnls =
                        listOf(
                            ExchangeClosedPnl(
                                exchangeOrderId = "exit-1",
                                clientOrderId = "client-1",
                                symbol = Symbol("BTCUSDT"),
                                side = Side.BUY,
                                openedAt = Instant.parse("2024-06-30T00:00:00Z"),
                                closedAt = Instant.parse("2024-06-30T00:10:00Z"),
                                entryPrice = BigDecimal("100"),
                                exitPrice = BigDecimal("106"),
                                quantity = BigDecimal("1"),
                                grossPnl = BigDecimal("6.12"),
                                fees = BigDecimal("0.12"),
                                netPnl = BigDecimal("6"),
                                exitReason = "TAKE_PROFIT",
                            ),
                        ),
                )
            val service = testService(store = store, gateway = gateway, config = ExchangeExecutionConfig(enabled = true))

            service.reconcile(Symbol("BTCUSDT")).persistedClosures shouldBe emptyList()
            store.closedTrades(null, null, 10, null) shouldBe emptyList()
            service.persistDiscoveredClosures(Symbol("BTCUSDT")).size shouldBe 1
            service.persistDiscoveredClosures(Symbol("BTCUSDT")) shouldBe emptyList()
            store.closedTrades(null, null, 10, null).single().netPnl shouldBe BigDecimal("6")
            store.latestLivePerformanceSummary(ExecutionRuntimeMode.TESTNET, LivePerformanceWindow.ALL)?.tradeCount shouldBe 1
        }

        "runtime reconcile reaches closure alerts before a market sync failure" {
            val store = InMemoryTradingStore()
            val gateway =
                RecordingExecutionGateway(
                    closedPnls =
                        listOf(
                            testClosedPnl(
                                exchangeOrderId = "close-before-sync",
                                closedAt = Instant.parse("2024-06-30T00:10:00Z"),
                            ),
                        ),
                )
            val executionService =
                testService(
                    store = store,
                    gateway = gateway,
                    config = ExchangeExecutionConfig(enabled = true),
                )
            val marketSyncService =
                MarketDataSyncService(
                    marketDataFeed = FailingSyncMarketDataFeed(),
                    candleStore = InMemoryCandleStore(),
                    clock = Clock.fixed(Instant.parse("2024-06-30T00:00:00Z"), ZoneOffset.UTC),
                )
            val alerted = mutableListOf<ExecutionTradeClosure>()
            val loop =
                ExchangeTradingLoop(
                    marketDataSyncService = marketSyncService,
                    executionService = executionService,
                    config = ExchangeTradingLoopConfig(Symbol("BTCUSDT"), Timeframe.M5, candleLimit = 20),
                    onClosure = { closure ->
                        alerted += closure
                        true
                    },
                )

            shouldThrow<MarketDataException> { loop.runOnce() }

            alerted.map { it.exchangeOrderId } shouldContainExactly listOf("close-before-sync")
            store.closures.size shouldBe 1
        }

        "failed closure delivery stays pending retries and stops after success" {
            val symbol = Symbol("BTCUSDT")
            val store = InMemoryTradingStore()
            val gateway =
                RecordingExecutionGateway(
                    closedPnls =
                        listOf(
                            testClosedPnl(
                                exchangeOrderId = "retry-close",
                                closedAt = Instant.parse("2024-06-30T00:10:00Z"),
                            ),
                        ),
                )
            val service = testService(store = store, gateway = gateway, config = ExchangeExecutionConfig(enabled = false))
            val marketSyncService =
                MarketDataSyncService(
                    marketDataFeed = PassingSyncMarketDataFeed(),
                    candleStore = InMemoryCandleStore(),
                    clock = Clock.fixed(Instant.parse("2024-06-30T00:00:00Z"), ZoneOffset.UTC),
                )
            val deliveryResults = ArrayDeque(listOf(false, true))
            val deliveredIds = mutableListOf<Long>()
            var evaluationCount = 0
            val loop =
                ExchangeTradingLoop(
                    marketDataSyncService = marketSyncService,
                    executionService = service,
                    config = ExchangeTradingLoopConfig(symbol, Timeframe.M5, candleLimit = 20),
                    clock = Clock.fixed(Instant.parse("2024-06-30T00:00:00Z"), ZoneOffset.UTC),
                    onClosure = { closure ->
                        deliveredIds += closure.id
                        deliveryResults.removeFirst()
                    },
                    onResult = { evaluationCount += 1 },
                )

            loop.runOnce().status shouldBe ExchangeEvaluationStatus.DISABLED
            store.pendingClosureAlerts(ExecutionRuntimeMode.TESTNET, symbol, 10).single().attemptCount shouldBe 1
            loop.runOnce().status shouldBe ExchangeEvaluationStatus.DISABLED
            store.pendingClosureAlerts(ExecutionRuntimeMode.TESTNET, symbol, 10) shouldBe emptyList()
            loop.runOnce().status shouldBe ExchangeEvaluationStatus.DISABLED

            deliveredIds.size shouldBe 2
            store.alertAttempts.values.single() shouldBe 2
            store.deliveredAt.size shouldBe 1
            evaluationCount shouldBe 3
        }

        "one failed closure alert does not block later pending alerts or evaluation" {
            val symbol = Symbol("BTCUSDT")
            val store = InMemoryTradingStore()
            val service =
                testService(
                    store = store,
                    gateway =
                        RecordingExecutionGateway(
                            closedPnls =
                                listOf(
                                    testClosedPnl("first-fails", Instant.parse("2024-06-30T00:10:00Z")),
                                    testClosedPnl("second-succeeds", Instant.parse("2024-06-30T00:11:00Z")),
                                ),
                        ),
                    config = ExchangeExecutionConfig(enabled = false),
                )
            val callbackIds = mutableListOf<String?>()
            var evaluationCount = 0
            val loop =
                ExchangeTradingLoop(
                    marketDataSyncService =
                        MarketDataSyncService(
                            marketDataFeed = PassingSyncMarketDataFeed(),
                            candleStore = InMemoryCandleStore(),
                            clock = Clock.fixed(Instant.parse("2024-06-30T00:00:00Z"), ZoneOffset.UTC),
                        ),
                    executionService = service,
                    config = ExchangeTradingLoopConfig(symbol, Timeframe.M5, candleLimit = 20),
                    onClosure = { closure ->
                        callbackIds += closure.exchangeOrderId
                        closure.exchangeOrderId == "second-succeeds"
                    },
                    onResult = { evaluationCount += 1 },
                )

            loop.runOnce().status shouldBe ExchangeEvaluationStatus.DISABLED

            callbackIds shouldContainExactly listOf("first-fails", "second-succeeds")
            store
                .pendingClosureAlerts(ExecutionRuntimeMode.TESTNET, symbol, 10)
                .single()
                .closure.exchangeOrderId shouldBe "first-fails"
            store.deliveredAt.size shouldBe 1
            evaluationCount shouldBe 1
        }

        "first bootstrap suppresses history but keeps post start closure pending" {
            val symbol = Symbol("BTCUSDT")
            val store = InMemoryTradingStore()
            val service =
                testService(
                    store = store,
                    gateway =
                        RecordingExecutionGateway(
                            closedPnls =
                                listOf(
                                    testClosedPnl("historical", Instant.parse("2024-06-29T23:30:00Z")),
                                    testClosedPnl("post-start", Instant.parse("2024-06-30T00:10:00Z")),
                                ),
                        ),
                    config = ExchangeExecutionConfig(enabled = false),
                )

            service.persistDiscoveredClosures(symbol).size shouldBe 2

            store.suppressedAt.keys.size shouldBe 1
            store
                .pendingClosureAlerts(ExecutionRuntimeMode.TESTNET, symbol, 10)
                .single()
                .closure.exchangeOrderId shouldBe "post-start"
        }

        "existing closure history captures a restart downtime closure as pending" {
            val symbol = Symbol("BTCUSDT")
            val store = InMemoryTradingStore()
            val historical = testClosedPnl("historical", Instant.parse("2024-06-29T23:30:00Z"))
            testService(
                store = store,
                gateway = RecordingExecutionGateway(closedPnls = listOf(historical)),
                config = ExchangeExecutionConfig(enabled = false),
            ).persistDiscoveredClosures(symbol)
            val downtime = testClosedPnl("downtime", Instant.parse("2024-06-30T01:00:00Z"))
            val restartedService =
                testService(
                    store = store,
                    gateway = RecordingExecutionGateway(closedPnls = listOf(historical, downtime)),
                    config = ExchangeExecutionConfig(enabled = false),
                    clock = Clock.fixed(Instant.parse("2024-06-30T02:00:00Z"), ZoneOffset.UTC),
                )

            restartedService.persistDiscoveredClosures(symbol).size shouldBe 1

            store
                .pendingClosureAlerts(ExecutionRuntimeMode.TESTNET, symbol, 10)
                .single()
                .closure.exchangeOrderId shouldBe "downtime"
        }

        "execution rejects target stop geometry that cannot cover round trip fees" {
            val gateway = RecordingExecutionGateway()
            val store = InMemoryTradingStore()
            val service =
                testService(
                    store = store,
                    gateway = gateway,
                    config =
                        ExchangeExecutionConfig(
                            enabled = true,
                            feeRate = BigDecimal("0.01"),
                            accountEquity = BigDecimal("1000"),
                            riskFraction = BigDecimal("0.01"),
                            quantityStep = BigDecimal("0.001"),
                            minQuantity = BigDecimal("0.001"),
                        ),
                    strategy = TinyTargetStrategy(),
                )

            val result = service.evaluateAndSubmit(Symbol("BTCUSDT"), Timeframe.M5, 30)

            result.status shouldBe ExchangeEvaluationStatus.REJECTED
            result.reasonCodes shouldContainExactly listOf("TARGET_DOES_NOT_COVER_ROUND_TRIP_FEES")
            gateway.placedOrders shouldBe emptyList()
        }

        "automatic entry rejects active orders and open positions" {
            val symbol = Symbol("BTCUSDT")
            val activeOrder =
                ExchangeOpenOrder(
                    exchangeOrderId = "active-1",
                    clientOrderId = "client-1",
                    symbol = symbol,
                    side = Side.BUY,
                    orderType = OrderType.MARKET,
                    status = OrderStatus.SUBMITTED,
                    quantity = BigDecimal("1"),
                    createdAt = Instant.parse("2024-06-29T23:59:00Z"),
                )
            val position =
                ExchangePosition(
                    symbol = symbol,
                    side = Side.BUY,
                    size = BigDecimal("1"),
                    openedAt = Instant.parse("2024-06-29T23:59:00Z"),
                    entryPrice = BigDecimal("100"),
                    markPrice = BigDecimal("105"),
                    unrealizedPnl = BigDecimal("5"),
                    updatedAt = Instant.parse("2024-06-29T23:59:00Z"),
                )

            listOf(
                RecordingExecutionGateway(openOrders = listOf(activeOrder)),
                RecordingExecutionGateway(positions = listOf(position)),
            ).forEach { gateway ->
                val result =
                    testService(gateway = gateway, config = ExchangeExecutionConfig(enabled = true))
                        .evaluateAndSubmit(symbol, Timeframe.M5, 30)

                result.status shouldBe ExchangeEvaluationStatus.REJECTED
                result.reasonCodes shouldContainExactly listOf("EXISTING_EXCHANGE_EXPOSURE")
                gateway.placedOrders shouldBe emptyList()
            }
        }

        "position policy submits a reduce-only exit after the maximum hold duration" {
            val symbol = Symbol("BTCUSDT")
            val gateway =
                RecordingExecutionGateway(
                    positions =
                        listOf(
                            ExchangePosition(
                                symbol = symbol,
                                side = Side.BUY,
                                size = BigDecimal("1"),
                                openedAt = Instant.parse("2024-06-29T20:00:00Z"),
                                entryPrice = BigDecimal("100"),
                                markPrice = BigDecimal("105"),
                                unrealizedPnl = BigDecimal("5"),
                                updatedAt = Instant.parse("2024-06-30T00:00:00Z"),
                            ),
                        ),
                )
            val result =
                testService(
                    stateStore = InMemoryStateStore(BotMode.PAUSE_NEW_ENTRIES),
                    gateway = gateway,
                    config =
                        ExchangeExecutionConfig(
                            enabled = true,
                            maxQuantity = BigDecimal("0.1"),
                        ),
                    positionPolicy = AutomaticPositionPolicy(Timeframe.M5, maxHoldCandles = 36, maxTradesPerUtcDay = 5),
                ).evaluateAndSubmit(symbol, Timeframe.M5, 30)

            result.status shouldBe ExchangeEvaluationStatus.EXIT_SUBMITTED
            result.reasonCodes shouldContainExactly listOf("MAX_HOLD_DURATION_REACHED")
            gateway.placedOrders.single().reduceOnly shouldBe true
            gateway.placedOrders.single().side shouldBe Side.SELL
            gateway.placedOrders.single().quantity shouldBe BigDecimal("1")
        }

        "position policy does not duplicate a pending time exit" {
            val symbol = Symbol("BTCUSDT")
            val position =
                ExchangePosition(
                    symbol = symbol,
                    side = Side.BUY,
                    size = BigDecimal("1"),
                    openedAt = Instant.parse("2024-06-29T20:00:00Z"),
                    entryPrice = BigDecimal("100"),
                    markPrice = BigDecimal("105"),
                    unrealizedPnl = BigDecimal("5"),
                    updatedAt = Instant.parse("2024-06-30T00:00:00Z"),
                )
            val pendingExit =
                ExchangeOpenOrder(
                    exchangeOrderId = "time-exchange-1",
                    clientOrderId = "time-BTCUSDT-1719705600000-S",
                    symbol = symbol,
                    side = Side.SELL,
                    orderType = OrderType.MARKET,
                    status = OrderStatus.SUBMITTED,
                    quantity = BigDecimal("1"),
                    createdAt = Instant.parse("2024-06-30T00:00:00Z"),
                )
            val gateway =
                RecordingExecutionGateway(
                    openOrders = listOf(pendingExit),
                    positions = listOf(position),
                )

            val result =
                testService(
                    gateway = gateway,
                    config = ExchangeExecutionConfig(enabled = true),
                    positionPolicy = AutomaticPositionPolicy(Timeframe.M5, maxHoldCandles = 36, maxTradesPerUtcDay = 5),
                ).evaluateAndSubmit(symbol, Timeframe.M5, 30)

            result.status shouldBe ExchangeEvaluationStatus.NO_TRADE
            result.reasonCodes shouldContainExactly listOf("MAX_HOLD_EXIT_PENDING")
            result.exchangeOrderId shouldBe "time-exchange-1"
            gateway.placedOrders shouldBe emptyList()
        }

        "position policy blocks entries after the UTC daily trade limit" {
            val symbol = Symbol("BTCUSDT")
            val store = InMemoryTradingStore()
            repeat(5) { index ->
                store.recordTradeClosure(
                    testClosure(
                        exchangeOrderId = "today-$index",
                        closedAt = Instant.parse("2024-06-30T00:10:00Z").plusSeconds(index * 60L),
                    ),
                )
            }
            val gateway = RecordingExecutionGateway()

            val result =
                testService(
                    store = store,
                    gateway = gateway,
                    config = ExchangeExecutionConfig(enabled = true),
                    positionPolicy = AutomaticPositionPolicy(Timeframe.M5, maxHoldCandles = 36, maxTradesPerUtcDay = 5),
                    clock = Clock.fixed(Instant.parse("2024-06-30T01:00:00Z"), ZoneOffset.UTC),
                ).evaluateAndSubmit(symbol, Timeframe.M5, 30)

            result.status shouldBe ExchangeEvaluationStatus.NO_TRADE
            result.reasonCodes shouldContainExactly listOf("DAILY_TRADE_LIMIT_REACHED")
            gateway.placedOrders shouldBe emptyList()
        }

        "live performance aggregates all stored closures across contract windows" {
            val now = Instant.parse("2024-06-30T00:00:00Z")
            val store = InMemoryTradingStore()
            val service = testService(store = store, config = ExchangeExecutionConfig(enabled = true))
            repeat(116) { index ->
                store.recordTradeClosure(testClosure("old-$index", now.minusSeconds(60L * 86_400 + index)))
            }
            store.recordTradeClosure(testClosure("outside-30d", now.minusSeconds(31L * 86_400)))
            store.recordTradeClosure(testClosure("inside-30d", now.minusSeconds(8L * 86_400)))
            store.recordTradeClosure(testClosure("inside-7d", now.minusSeconds(6L * 86_400)))
            store.recordTradeClosure(testClosure("session", now))

            service.livePerformanceSummary(null, LivePerformanceWindow.ALL)?.tradeCount shouldBe 120
            service.livePerformanceSummary(null, LivePerformanceWindow.THIRTY_DAYS)?.tradeCount shouldBe 3
            service.livePerformanceSummary(null, LivePerformanceWindow.SEVEN_DAYS)?.tradeCount shouldBe 2
            service.livePerformanceSummary(null, LivePerformanceWindow.SESSION)?.tradeCount shouldBe 1
        }
    })

private fun testService(
    stateStore: InMemoryStateStore = InMemoryStateStore(BotMode.RUNNING),
    candleStore: MarketCandleStore = InMemoryCandleStore(),
    store: InMemoryTradingStore = InMemoryTradingStore(),
    gateway: RecordingExecutionGateway = RecordingExecutionGateway(),
    config: ExchangeExecutionConfig,
    strategy: TradingStrategy = AlwaysBuyExecutionStrategy(),
    positionPolicy: AutomaticPositionPolicy? = null,
    clock: Clock = Clock.fixed(Instant.parse("2024-06-30T00:00:00Z"), ZoneOffset.UTC),
): ExchangeExecutionService =
    ExchangeExecutionService(
        stateStore = stateStore,
        candleStore = candleStore,
        tradingStore = store,
        strategy = strategy,
        gateway = gateway,
        config = config,
        positionPolicy = positionPolicy,
        clock = clock,
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
                    openedAt = Instant.parse("2024-06-29T20:00:00Z").plusSeconds(index * 300L),
                    open = close,
                    high = close + BigDecimal("2"),
                    low = close - BigDecimal("2"),
                    close = close,
                    volume = BigDecimal("100"),
                )
            }.takeLast(limit)
}

private class ListCandleStore(
    private val candles: List<Candle>,
) : MarketCandleStore {
    override suspend fun upsert(candles: List<Candle>) = Unit

    override suspend fun recentCandles(
        symbol: Symbol,
        timeframe: Timeframe,
        limit: Int,
    ): List<Candle> =
        candles
            .filter { candle -> candle.symbol == symbol && candle.timeframe == timeframe }
            .sortedByDescending(Candle::openedAt)
            .take(limit)
}

private class FailingSyncMarketDataFeed : MarketDataFeed {
    override suspend fun fetchRecentCandles(
        symbol: Symbol,
        timeframe: Timeframe,
        limit: Int,
    ): List<Candle> = throw MarketDataException("market sync failed")
}

private class PassingSyncMarketDataFeed : MarketDataFeed {
    override suspend fun fetchRecentCandles(
        symbol: Symbol,
        timeframe: Timeframe,
        limit: Int,
    ): List<Candle> = emptyList()
}

private class InMemoryTradingStore :
    PaperTradingStore,
    ExecutionProjectionStore {
    val signals = mutableListOf<PaperSignalRecord>()
    val orders = mutableListOf<PaperOrderRecord>()
    val closures = mutableListOf<ExecutionTradeClosure>()
    val performance = mutableListOf<LivePerformanceSnapshot>()
    val suppressedAt = mutableMapOf<Long, Instant>()
    val deliveredAt = mutableMapOf<Long, Instant>()
    val alertAttempts = mutableMapOf<Long, Int>()
    val lastAlertAttemptAt = mutableMapOf<Long, Instant>()

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

    override suspend fun recordTradeClosure(
        closure: ExecutionTradeClosure,
        suppressedAt: Instant?,
    ): Long? {
        if (closures.any {
                it.mode == closure.mode &&
                    it.symbol == closure.symbol &&
                    it.exchangeOrderId == closure.exchangeOrderId &&
                    it.clientOrderId == closure.clientOrderId &&
                    it.closedAt == closure.closedAt
            }
        ) {
            return null
        }
        val id = closures.size + 1L
        closures += closure.copy(id = id)
        suppressedAt?.let { this.suppressedAt[id] = it }
        alertAttempts[id] = 0
        return id
    }

    override suspend fun closedTrades(
        symbol: Symbol?,
        mode: ExecutionRuntimeMode?,
        limit: Int,
        cursor: Long?,
    ): List<ExecutionTradeClosure> =
        closures
            .filter { (symbol == null || it.symbol == symbol) && (mode == null || it.mode == mode) && (cursor == null || it.id < cursor) }
            .sortedByDescending { it.id }
            .take(limit)

    override suspend fun latestClosedTrade(symbol: Symbol): ExecutionTradeClosure? =
        closures.filter { it.symbol == symbol }.maxByOrNull { it.id }

    override suspend fun performanceClosures(
        mode: ExecutionRuntimeMode,
        closedAtOrAfter: Instant?,
    ): List<ExecutionTradeClosure> =
        closures
            .filter { closure ->
                closure.mode == mode && (closedAtOrAfter == null || !closure.closedAt.isBefore(closedAtOrAfter))
            }.sortedWith(compareBy(ExecutionTradeClosure::closedAt, ExecutionTradeClosure::id))

    override suspend fun hasClosureHistory(
        mode: ExecutionRuntimeMode,
        symbol: Symbol,
    ): Boolean = closures.any { closure -> closure.mode == mode && closure.symbol == symbol }

    override suspend fun pendingClosureAlerts(
        mode: ExecutionRuntimeMode,
        symbol: Symbol,
        limit: Int,
    ): List<PendingExecutionClosureAlert> =
        closures
            .filter { closure ->
                closure.mode == mode &&
                    closure.symbol == symbol &&
                    closure.id !in deliveredAt &&
                    closure.id !in suppressedAt
            }.sortedWith(compareBy(ExecutionTradeClosure::closedAt, ExecutionTradeClosure::id))
            .take(limit)
            .map { closure ->
                PendingExecutionClosureAlert(
                    closure = closure,
                    attemptCount = alertAttempts[closure.id] ?: 0,
                    lastAttemptAt = lastAlertAttemptAt[closure.id],
                )
            }

    override suspend fun recordClosureAlertAttempt(
        closureId: Long,
        attemptedAt: Instant,
        delivered: Boolean,
    ) {
        alertAttempts[closureId] = (alertAttempts[closureId] ?: 0) + 1
        lastAlertAttemptAt[closureId] = attemptedAt
        if (delivered) deliveredAt[closureId] = attemptedAt
    }

    override suspend fun recordLivePerformanceSnapshot(snapshot: LivePerformanceSnapshot): Long {
        val id = performance.size + 1L
        performance += snapshot.copy(id = id)
        return id
    }

    override suspend fun latestLivePerformanceSummary(
        mode: ExecutionRuntimeMode?,
        window: LivePerformanceWindow,
    ): LivePerformanceSnapshot? = performance.lastOrNull { (mode == null || it.mode == mode) && it.window == window }
}

private class RecordingExecutionGateway(
    private val openOrders: List<ExchangeOpenOrder> = emptyList(),
    private val positions: List<ExchangePosition> = emptyList(),
    private val closedPnls: List<ExchangeClosedPnl> = emptyList(),
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

    override suspend fun positions(symbol: Symbol): List<ExchangePosition> = positions

    override suspend fun executions(symbol: Symbol): List<ExchangeExecutionFill> = emptyList()

    override suspend fun closedPnls(symbol: Symbol): List<ExchangeClosedPnl> = closedPnls

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

private class TinyTargetStrategy : TradingStrategy {
    override val name: String = "tiny-target-test"
    override val warmupCandles: Int = 2

    override fun evaluate(candles: List<Candle>): StrategyDecision {
        val latest = candles.last()
        return StrategyDecision(
            intent =
                SignalIntent(
                    symbol = latest.symbol,
                    side = Side.BUY,
                    strategy = name,
                    score = SignalScore(80, listOf("TINY_TARGET")),
                    invalidationPrice = Price(BigDecimal("104.90")),
                    expectedR = BigDecimal("1"),
                ),
            reasonCodes = listOf("TINY_TARGET"),
        )
    }
}

private fun executionCandle(
    symbol: Symbol,
    openedAt: Instant,
): Candle =
    Candle(
        symbol = symbol,
        timeframe = Timeframe.M5,
        openedAt = openedAt,
        open = BigDecimal("105"),
        high = BigDecimal("107"),
        low = BigDecimal("103"),
        close = BigDecimal("105"),
        volume = BigDecimal("100"),
    )

private fun testClosedPnl(
    exchangeOrderId: String,
    closedAt: Instant = Instant.parse("2024-06-29T23:30:00Z"),
): ExchangeClosedPnl =
    ExchangeClosedPnl(
        exchangeOrderId = exchangeOrderId,
        clientOrderId = null,
        symbol = Symbol("BTCUSDT"),
        side = Side.BUY,
        openedAt = Instant.parse("2024-06-29T23:00:00Z"),
        closedAt = closedAt,
        entryPrice = BigDecimal("100"),
        exitPrice = BigDecimal("105"),
        quantity = BigDecimal("1"),
        grossPnl = BigDecimal("5.12"),
        fees = BigDecimal("0.12"),
        netPnl = BigDecimal("5"),
        exitReason = "TAKE_PROFIT",
    )

private fun testClosure(
    exchangeOrderId: String,
    closedAt: Instant,
): ExecutionTradeClosure =
    ExecutionTradeClosure(
        mode = ExecutionRuntimeMode.TESTNET,
        symbol = Symbol("BTCUSDT"),
        side = Side.BUY,
        openedAt = closedAt.minusSeconds(300),
        closedAt = closedAt,
        entryPrice = BigDecimal("100"),
        exitPrice = BigDecimal("101"),
        quantity = BigDecimal("1"),
        grossPnl = BigDecimal("1.12"),
        fees = BigDecimal("0.12"),
        netPnl = BigDecimal("1"),
        exitReason = "TAKE_PROFIT",
        exchangeOrderId = exchangeOrderId,
        clientOrderId = null,
    )
