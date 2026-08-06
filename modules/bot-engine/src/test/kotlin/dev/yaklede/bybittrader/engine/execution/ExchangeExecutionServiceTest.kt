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
import dev.yaklede.bybittrader.engine.position.CausalPositionState
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

        "running execution sizes and submits a bounded IOC entry" {
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
            store.orders.single().orderType shouldBe OrderType.LIMIT
            store.orders.single().exchangeOrderId shouldBe "exchange-1"
            store.lifecycleRecords.single().state shouldBe ExecutionLifecycleState.ENTRY_SUBMITTED
            store.lifecycleRecords.single().takeProfit shouldBe BigDecimal("112.5")
            store.lifecycleRecords.single().stopLoss shouldBe BigDecimal("100")
            gateway.placedOrders.map { it.clientOrderId }.shouldContainExactly(
                listOf("bt-BTCUSDT-1719705600000-1-B"),
            )
            gateway.placedOrders.single().orderType shouldBe OrderType.LIMIT
            gateway.placedOrders.single().timeInForce shouldBe ExchangeTimeInForce.IOC
            gateway.placedOrders.single().price shouldBe BigDecimal("105")
        }

        "stop-only policy submits and persists no fixed take profit" {
            val gateway = RecordingExecutionGateway()
            val store = InMemoryTradingStore()
            val service =
                testService(
                    store = store,
                    gateway = gateway,
                    config = ExchangeExecutionConfig(enabled = true),
                    positionPolicy =
                        AutomaticPositionPolicy(
                            timeframe = Timeframe.M5,
                            maxHoldCandles = 36,
                            maxTradesPerUtcDay = 1,
                            fixedTargetEnabled = false,
                        ),
                )

            val result = service.evaluateAndSubmit(Symbol("BTCUSDT"), Timeframe.M5, 30)

            result.status shouldBe ExchangeEvaluationStatus.SUBMITTED
            result.takeProfit shouldBe null
            gateway.placedOrders.single().takeProfit shouldBe null
            gateway.placedOrders.single().stopLoss shouldBe BigDecimal("100")
            store.lifecycleRecords.single().fixedTargetEnabled shouldBe false
            store.lifecycleRecords.single().takeProfit shouldBe null
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

        "account risk circuit breaker blocks a new entry from persisted equity state" {
            val now = Instant.parse("2024-06-30T00:00:00Z")
            val store = InMemoryTradingStore()
            store.riskStates[ExecutionRuntimeMode.TESTNET] =
                ExecutionRiskState(
                    mode = ExecutionRuntimeMode.TESTNET,
                    peakEquity = BigDecimal("100"),
                    utcDayStartedAt = now,
                    dayStartEquity = BigDecimal("100"),
                    latestEquity = BigDecimal("96"),
                    consecutiveLosses = 3,
                    lastClosureId = 3,
                    updatedAt = now,
                )
            val gateway = RecordingExecutionGateway()
            val service = testService(store = store, gateway = gateway, config = ExchangeExecutionConfig(enabled = true))

            val result = service.evaluateAndSubmit(Symbol("BTCUSDT"), Timeframe.M5, 30)

            result.status shouldBe ExchangeEvaluationStatus.NO_TRADE
            result.reasonCodes shouldBe
                listOf(
                    "DAILY_EQUITY_LOSS_LIMIT_REACHED",
                    "CONSECUTIVE_LOSS_LIMIT_REACHED",
                )
            gateway.placedOrders shouldBe emptyList()
        }

        "wallet reconciliation mismatch blocks a new entry" {
            val now = Instant.parse("2024-06-30T00:00:00Z")
            val store = InMemoryTradingStore()
            store.riskStates[ExecutionRuntimeMode.TESTNET] =
                ExecutionRiskState(
                    mode = ExecutionRuntimeMode.TESTNET,
                    peakEquity = BigDecimal("100"),
                    utcDayStartedAt = now,
                    dayStartEquity = BigDecimal("100"),
                    latestEquity = BigDecimal("100"),
                    consecutiveLosses = 0,
                    lastClosureId = null,
                    updatedAt = now,
                )
            store.walletReconciliationStates[ExecutionRuntimeMode.TESTNET to "USDT"] =
                ExecutionWalletReconciliationState(
                    mode = ExecutionRuntimeMode.TESTNET,
                    currency = "USDT",
                    status = ExecutionWalletReconciliationStatus.MISMATCH,
                    baselineSnapshotId = 1,
                    baselineCapturedAt = now.minusSeconds(60),
                    baselineWalletBalance = BigDecimal("100"),
                    currentSnapshotId = 2,
                    currentCapturedAt = now,
                    currentWalletBalance = BigDecimal("99"),
                    observedWalletChange = BigDecimal("-1"),
                    ledgerChange = BigDecimal.ZERO,
                    difference = BigDecimal("-1"),
                    tolerance = BigDecimal("0.01"),
                    consecutiveMismatches = 3,
                    lastMatchedAt = null,
                    reconciledAt = now,
                )
            val gateway = RecordingExecutionGateway()
            val service =
                testService(
                    store = store,
                    gateway = gateway,
                    config =
                        ExchangeExecutionConfig(
                            enabled = true,
                            walletReconciliationEnabled = true,
                            walletReconciliationConfirmedMismatchCount = 3,
                        ),
                )

            val result = service.evaluateAndSubmit(Symbol("BTCUSDT"), Timeframe.M5, 30)

            result.status shouldBe ExchangeEvaluationStatus.NO_TRADE
            result.reasonCodes shouldBe listOf("ACCOUNT_LEDGER_MISMATCH_CONFIRMED")
            gateway.placedOrders shouldBe emptyList()
        }

        "account risk circuit breaker does not prevent an expired position exit" {
            val now = Instant.parse("2024-06-30T00:00:00Z")
            val store = InMemoryTradingStore()
            store.riskStates[ExecutionRuntimeMode.TESTNET] =
                ExecutionRiskState(
                    mode = ExecutionRuntimeMode.TESTNET,
                    peakEquity = BigDecimal("100"),
                    utcDayStartedAt = now,
                    dayStartEquity = BigDecimal("100"),
                    latestEquity = BigDecimal("70"),
                    consecutiveLosses = 5,
                    lastClosureId = 5,
                    updatedAt = now,
                )
            val gateway =
                RecordingExecutionGateway(
                    positions =
                        listOf(
                            ExchangePosition(
                                symbol = Symbol("BTCUSDT"),
                                side = Side.BUY,
                                size = BigDecimal.ONE,
                                openedAt = Instant.parse("2024-06-29T20:00:00Z"),
                                entryPrice = BigDecimal("105"),
                                markPrice = BigDecimal("104"),
                                unrealizedPnl = BigDecimal("-1"),
                                updatedAt = now,
                                takeProfit = BigDecimal("112.5"),
                                stopLoss = BigDecimal("100"),
                            ),
                        ),
                )
            val service =
                testService(
                    store = store,
                    gateway = gateway,
                    config = ExchangeExecutionConfig(enabled = true),
                    positionPolicy =
                        AutomaticPositionPolicy(
                            timeframe = Timeframe.M5,
                            maxHoldCandles = 36,
                            maxTradesPerUtcDay = 5,
                            fixedTargetEnabled = true,
                        ),
                )

            val result = service.evaluateAndSubmit(Symbol("BTCUSDT"), Timeframe.M5, 30)

            result.status shouldBe ExchangeEvaluationStatus.EXIT_SUBMITTED
            result.reasonCodes shouldBe listOf("MAX_HOLD_DURATION_REACHED")
            gateway.placedOrders.single().reduceOnly shouldBe true
        }

        "safe stop cancels entry orders and keeps a protected position" {
            val symbol = Symbol("BTCUSDT")
            val entryOrder =
                ExchangeOpenOrder(
                    exchangeOrderId = "entry-order-1",
                    clientOrderId = "entry-client-1",
                    symbol = symbol,
                    side = Side.BUY,
                    orderType = OrderType.LIMIT,
                    status = OrderStatus.SUBMITTED,
                    quantity = BigDecimal.ONE,
                    createdAt = Instant.parse("2024-06-29T23:59:00Z"),
                    reduceOnly = false,
                )
            val gateway =
                RecordingExecutionGateway(
                    openOrders = listOf(entryOrder),
                    positions = listOf(testManagedPosition(symbol)),
                )
            val service =
                testService(
                    stateStore = InMemoryStateStore(BotMode.PAUSE_ALL),
                    gateway = gateway,
                    config = ExchangeExecutionConfig(enabled = true, safetyVerificationAttempts = 1),
                )

            val result = service.enforceCurrentSafetyMode(symbol)

            result.action shouldBe ExchangeSafetyAction.SAFE_STOP
            result.status shouldBe ExchangeSafetyStatus.CONFIRMED
            result.cancelledEntryOrderCount shouldBe 1
            result.submittedCloseOrderCount shouldBe 0
            result.protectedPositionCount shouldBe 1
            result.remainingOpenOrderCount shouldBe 0
            result.remainingPositionCount shouldBe 1
        }

        "emergency stop cancels entries and confirms a reduce-only flatten" {
            val symbol = Symbol("BTCUSDT")
            val gateway =
                RecordingExecutionGateway(
                    openOrders =
                        listOf(
                            ExchangeOpenOrder(
                                exchangeOrderId = "entry-order-1",
                                clientOrderId = "entry-client-1",
                                symbol = symbol,
                                side = Side.BUY,
                                orderType = OrderType.LIMIT,
                                status = OrderStatus.SUBMITTED,
                                quantity = BigDecimal.ONE,
                                createdAt = Instant.parse("2024-06-29T23:59:00Z"),
                            ),
                        ),
                    positions = listOf(testManagedPosition(symbol)),
                    closeImmediatelyOnReduceOnly = true,
                )
            val service =
                testService(
                    stateStore = InMemoryStateStore(BotMode.EMERGENCY_STOP),
                    gateway = gateway,
                    config = ExchangeExecutionConfig(enabled = true, safetyVerificationAttempts = 1),
                )

            val result = service.enforceCurrentSafetyMode(symbol)

            result.action shouldBe ExchangeSafetyAction.FLATTEN
            result.status shouldBe ExchangeSafetyStatus.CONFIRMED
            result.cancelledEntryOrderCount shouldBe 1
            result.submittedCloseOrderCount shouldBe 1
            result.remainingOpenOrderCount shouldBe 0
            result.remainingPositionCount shouldBe 0
            gateway.placedOrders.single().reduceOnly shouldBe true
            gateway.placedOrders.single().side shouldBe Side.SELL
        }

        "reconciliation retries emergency flatten while a position remains open" {
            val symbol = Symbol("BTCUSDT")
            val gateway = RecordingExecutionGateway(positions = listOf(testManagedPosition(symbol)))
            val service =
                testService(
                    stateStore = InMemoryStateStore(BotMode.EMERGENCY_STOP),
                    gateway = gateway,
                    config = ExchangeExecutionConfig(enabled = true, safetyVerificationAttempts = 1),
                )

            service.persistExchangeState(symbol)

            gateway.placedOrders.single().reduceOnly shouldBe true
            gateway.placedOrders.single().side shouldBe Side.SELL
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

        "evaluation rejects a missing latest closed candle" {
            val symbol = Symbol("BTCUSDT")
            val service =
                testService(
                    candleStore =
                        ListCandleStore(
                            listOf(
                                executionCandle(symbol, Instant.parse("2024-06-29T23:45:00Z")),
                                executionCandle(symbol, Instant.parse("2024-06-29T23:50:00Z")),
                            ),
                        ),
                    config = ExchangeExecutionConfig(enabled = true),
                )

            val result = service.evaluateAndSubmit(symbol, Timeframe.M5, 2)

            result.status shouldBe ExchangeEvaluationStatus.NO_TRADE
            result.reasonCodes shouldContainExactly listOf("LATEST_CLOSED_CANDLE_MISSING")
        }

        "evaluation rejects a signal outside the entry window" {
            val symbol = Symbol("BTCUSDT")
            val service =
                testService(
                    candleStore =
                        ListCandleStore(
                            listOf(
                                executionCandle(symbol, Instant.parse("2024-06-29T23:50:00Z")),
                                executionCandle(symbol, Instant.parse("2024-06-29T23:55:00Z")),
                            ),
                        ),
                    config =
                        ExchangeExecutionConfig(
                            enabled = true,
                            maximumEntryDelay = java.time.Duration.ofSeconds(30),
                        ),
                    clock = Clock.fixed(Instant.parse("2024-06-30T00:00:31Z"), ZoneOffset.UTC),
                )

            val result = service.evaluateAndSubmit(symbol, Timeframe.M5, 2)

            result.status shouldBe ExchangeEvaluationStatus.NO_TRADE
            result.reasonCodes shouldContainExactly listOf("ENTRY_WINDOW_EXPIRED")
        }

        "execution derives a decision key and blocks duplicate submission for the same candle" {
            val gateway = RecordingExecutionGateway()
            val store = InMemoryTradingStore()
            val service =
                testService(
                    store = store,
                    gateway = gateway,
                    config = ExchangeExecutionConfig(enabled = true),
                )

            val first = service.evaluateAndSubmit(Symbol("BTCUSDT"), Timeframe.M5, 30)
            val second = service.evaluateAndSubmit(Symbol("BTCUSDT"), Timeframe.M5, 30)

            first.status shouldBe ExchangeEvaluationStatus.SUBMITTED
            second.status shouldBe ExchangeEvaluationStatus.NO_TRADE
            second.reasonCodes shouldContainExactly
                listOf("DUPLICATE_SIGNAL", "SIGNAL_AT_2024-06-29T23:55:00Z")
            store.signals.single().reasonCodes shouldContainExactly
                listOf("TEST_ENTRY", "SIGNAL_AT_2024-06-29T23:55:00Z")
            gateway.placedOrders.size shouldBe 1
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
            val service =
                testService(
                    store = store,
                    gateway = gateway,
                    config = ExchangeExecutionConfig(enabled = true),
                    positionPolicy =
                        AutomaticPositionPolicy(
                            timeframe = Timeframe.M5,
                            maxHoldCandles = 36,
                            maxTradesPerUtcDay = 1,
                        ),
                )

            service.reconcile(Symbol("BTCUSDT")).persistedClosures shouldBe emptyList()
            store.closedTrades(null, null, 10, null) shouldBe emptyList()
            service.persistDiscoveredClosures(Symbol("BTCUSDT")).size shouldBe 1
            service.persistDiscoveredClosures(Symbol("BTCUSDT")) shouldBe emptyList()
            store.closedTrades(null, null, 10, null).single().netPnl shouldBe BigDecimal("6")
            store.latestLivePerformanceSummary(ExecutionRuntimeMode.TESTNET, LivePerformanceWindow.ALL)?.tradeCount shouldBe 1
        }

        "reconciliation loop persists and alerts a closure without automatic trading" {
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
            val alerted = mutableListOf<ExecutionTradeClosure>()
            val loop =
                ExchangeReconciliationLoop(
                    executionService = executionService,
                    config = ExchangeReconciliationLoopConfig(Symbol("BTCUSDT")),
                    onClosure = { closure ->
                        alerted += closure
                        true
                    },
                )

            loop.runOnce().persistedClosures.size shouldBe 1

            alerted.map { it.exchangeOrderId } shouldContainExactly listOf("close-before-sync")
            store.closures.size shouldBe 1
        }

        "reconciliation loop advances lifecycle while automatic execution is disabled" {
            val symbol = Symbol("BTCUSDT")
            val store = InMemoryTradingStore()
            store.recordLifecycleEvent(testLifecycleEvent())
            val service =
                testService(
                    store = store,
                    gateway =
                        RecordingExecutionGateway(
                            positions =
                                listOf(
                                    ExchangePosition(
                                        symbol = symbol,
                                        side = Side.BUY,
                                        size = BigDecimal("1"),
                                        openedAt = Instant.parse("2024-06-29T23:10:00Z"),
                                        entryPrice = BigDecimal("105"),
                                        markPrice = BigDecimal("106"),
                                        unrealizedPnl = BigDecimal("1"),
                                        takeProfit = BigDecimal("112.5"),
                                        stopLoss = BigDecimal("100"),
                                        updatedAt = Instant.parse("2024-06-30T00:00:00Z"),
                                    ),
                                ),
                        ),
                    config = ExchangeExecutionConfig(enabled = false),
                )
            val observed = mutableListOf<ExecutionLifecycleEvent>()

            ExchangeReconciliationLoop(
                executionService = service,
                config = ExchangeReconciliationLoopConfig(symbol),
                onLifecycleEvent = { event -> observed += event },
            ).runOnce()

            observed.single().state shouldBe ExecutionLifecycleState.OPEN_PROTECTED
            store.lifecycleRecords.last().state shouldBe ExecutionLifecycleState.OPEN_PROTECTED
        }

        "automatic trading loop does not reconcile exchange state" {
            val gateway = RecordingExecutionGateway()
            val loop =
                ExchangeTradingLoop(
                    marketDataSyncService =
                        MarketDataSyncService(
                            marketDataFeed = FailingSyncMarketDataFeed(),
                            candleStore = InMemoryCandleStore(),
                            clock = Clock.fixed(Instant.parse("2024-06-30T00:00:00Z"), ZoneOffset.UTC),
                        ),
                    executionService =
                        testService(
                            gateway = gateway,
                            config = ExchangeExecutionConfig(enabled = true),
                        ),
                    config = ExchangeTradingLoopConfig(Symbol("BTCUSDT"), Timeframe.M5, candleLimit = 20),
                )

            shouldThrow<MarketDataException> { loop.runOnce() }

            gateway.openOrderRequests shouldBe 0
            gateway.positionRequests shouldBe 0
            gateway.executionRequests shouldBe 0
            gateway.closedPnlRequests shouldBe 0
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
            val deliveryResults = ArrayDeque(listOf(false, true))
            val deliveredIds = mutableListOf<Long>()
            val loop =
                ExchangeReconciliationLoop(
                    executionService = service,
                    config = ExchangeReconciliationLoopConfig(symbol),
                    clock = Clock.fixed(Instant.parse("2024-06-30T00:00:00Z"), ZoneOffset.UTC),
                    onClosure = { closure ->
                        deliveredIds += closure.id
                        deliveryResults.removeFirst()
                    },
                )

            loop.runOnce()
            store.pendingClosureAlerts(ExecutionRuntimeMode.TESTNET, symbol, 10).single().attemptCount shouldBe 1
            loop.runOnce()
            store.pendingClosureAlerts(ExecutionRuntimeMode.TESTNET, symbol, 10) shouldBe emptyList()
            loop.runOnce()

            deliveredIds.size shouldBe 2
            store.alertAttempts.values.single() shouldBe 2
            store.deliveredAt.size shouldBe 1
        }

        "one failed closure alert does not block later pending alerts" {
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
            val loop =
                ExchangeReconciliationLoop(
                    executionService = service,
                    config = ExchangeReconciliationLoopConfig(symbol),
                    onClosure = { closure ->
                        callbackIds += closure.exchangeOrderId
                        closure.exchangeOrderId == "second-succeeds"
                    },
                )

            loop.runOnce()

            callbackIds shouldContainExactly listOf("first-fails", "second-succeeds")
            store
                .pendingClosureAlerts(ExecutionRuntimeMode.TESTNET, symbol, 10)
                .single()
                .closure.exchangeOrderId shouldBe "first-fails"
            store.deliveredAt.size shouldBe 1
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

        "execution rejects a positive target whose net risk reward is below the minimum" {
            val gateway = RecordingExecutionGateway()
            val store = InMemoryTradingStore()
            val service =
                testService(
                    store = store,
                    gateway = gateway,
                    config =
                        ExchangeExecutionConfig(
                            enabled = true,
                            accountEquity = BigDecimal("1000"),
                            riskFraction = BigDecimal("0.01"),
                            quantityStep = BigDecimal("0.001"),
                            minQuantity = BigDecimal("0.001"),
                        ),
                    strategy = CostDistortedTargetStrategy(),
                )

            val result = service.evaluateAndSubmit(Symbol("BTCUSDT"), Timeframe.M5, 30)

            result.status shouldBe ExchangeEvaluationStatus.REJECTED
            result.reasonCodes shouldContainExactly listOf("NET_RISK_REWARD_BELOW_MINIMUM")
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
                store.recordLifecycleEvent(
                    testLifecycleEvent(
                        occurredAt = Instant.parse("2024-06-30T00:10:00Z").plusSeconds(index * 60L),
                    ).copy(
                        lifecycleId = "automatic-entry-$index",
                        exchangeOrderId = "today-$index",
                        clientOrderId = "automatic-entry-$index",
                        reasonCode = "AUTOMATIC_ENTRY_SUBMITTED",
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

        "daily trade limit counts submitted entries before they close" {
            val symbol = Symbol("BTCUSDT")
            val store = InMemoryTradingStore()
            store.recordLifecycleEvent(
                testLifecycleEvent(occurredAt = Instant.parse("2024-06-30T00:10:00Z")).copy(
                    lifecycleId = "still-open-entry",
                    clientOrderId = "still-open-entry",
                    reasonCode = "AUTOMATIC_ENTRY_SUBMITTED",
                ),
            )
            val result =
                testService(
                    store = store,
                    gateway = RecordingExecutionGateway(),
                    config = ExchangeExecutionConfig(enabled = true),
                    positionPolicy = AutomaticPositionPolicy(Timeframe.M5, maxHoldCandles = 36, maxTradesPerUtcDay = 1),
                    clock = Clock.fixed(Instant.parse("2024-06-30T01:00:00Z"), ZoneOffset.UTC),
                ).evaluateAndSubmit(symbol, Timeframe.M5, 30)

            result.status shouldBe ExchangeEvaluationStatus.NO_TRADE
            result.reasonCodes shouldContainExactly listOf("DAILY_TRADE_LIMIT_REACHED")
        }

        "exchange reconciliation advances an entry to a protected open position" {
            val symbol = Symbol("BTCUSDT")
            val store = InMemoryTradingStore()
            store.recordLifecycleEvent(testLifecycleEvent())
            val gateway =
                RecordingExecutionGateway(
                    positions =
                        listOf(
                            ExchangePosition(
                                symbol = symbol,
                                side = Side.BUY,
                                size = BigDecimal("1"),
                                openedAt = Instant.parse("2024-06-29T23:00:00Z"),
                                entryPrice = BigDecimal("100"),
                                markPrice = BigDecimal("105"),
                                unrealizedPnl = BigDecimal("5"),
                                updatedAt = Instant.parse("2024-06-29T23:20:00Z"),
                                takeProfit = BigDecimal("112.5"),
                                stopLoss = BigDecimal("100"),
                            ),
                        ),
                )
            val service =
                testService(
                    store = store,
                    gateway = gateway,
                    config = ExchangeExecutionConfig(enabled = true),
                )

            service.persistExchangeState(symbol)

            val latest = store.latestLifecycleEvent(ExecutionRuntimeMode.TESTNET, symbol)
            latest?.state shouldBe ExecutionLifecycleState.OPEN_PROTECTED
            latest?.filledQuantity shouldBe BigDecimal("1")
            latest?.fillVwap shouldBe BigDecimal("100")
            latest?.reasonCode shouldBe "PROTECTED_POSITION_OBSERVED"
        }

        "exchange reconciliation recalculates and verifies protection from actual fill price" {
            val symbol = Symbol("BTCUSDT")
            val store = InMemoryTradingStore()
            store.recordLifecycleEvent(
                testLifecycleEvent().copy(
                    protectionRequired = true,
                    plannedEntryPrice = BigDecimal("105"),
                    structuralStopPrice = BigDecimal("100"),
                    expectedR = BigDecimal("1.5"),
                    protectionDeadlineAt = Instant.parse("2024-06-30T00:02:00Z"),
                ),
            )
            val gateway =
                RecordingExecutionGateway(
                    positions =
                        listOf(
                            ExchangePosition(
                                symbol = symbol,
                                side = Side.BUY,
                                size = BigDecimal("1"),
                                openedAt = Instant.parse("2024-06-29T23:00:00Z"),
                                entryPrice = BigDecimal("106"),
                                markPrice = BigDecimal("106"),
                                unrealizedPnl = BigDecimal.ZERO,
                                updatedAt = Instant.parse("2024-06-30T00:00:00Z"),
                                takeProfit = BigDecimal("112.5"),
                                stopLoss = BigDecimal("100"),
                            ),
                        ),
                )
            val service =
                testService(
                    store = store,
                    gateway = gateway,
                    config = ExchangeExecutionConfig(enabled = true),
                    positionPolicy =
                        AutomaticPositionPolicy(
                            timeframe = Timeframe.M5,
                            maxHoldCandles = 36,
                            maxTradesPerUtcDay = 1,
                        ),
                )

            val report = service.persistExchangeState(symbol)

            gateway.protectionRequests.single().takeProfit shouldBe BigDecimal("115.0")
            gateway.protectionRequests.single().stopLoss shouldBe BigDecimal("100")
            report.lifecycleEvent?.state shouldBe ExecutionLifecycleState.OPEN_PROTECTED
            report.lifecycleEvent?.fillVwap shouldBe BigDecimal("106")
            report.lifecycleEvent?.reasonCode shouldBe "ACTUAL_FILL_PROTECTION_VERIFIED"
            val runtime = store.executionPositionRuntimeState(ExecutionRuntimeMode.TESTNET, symbol)
            runtime?.lifecycleId shouldBe "client-entry-1"
            runtime?.policyState?.entryPrice shouldBe 106.0
            runtime?.policyState?.currentStopPrice shouldBe 100.0
            runtime?.policyState?.fullTargetPrice shouldBe 115.0
        }

        "automatic position applies one newly closed candle and verifies its trailing stop" {
            val symbol = Symbol("BTCUSDT")
            val entryAt = Instant.parse("2024-06-29T23:54:30Z")
            val store = InMemoryTradingStore()
            store.recordLifecycleEvent(
                testLifecycleEvent(occurredAt = entryAt).copy(
                    protectionRequired = true,
                    plannedEntryPrice = BigDecimal("100"),
                    structuralStopPrice = BigDecimal("90"),
                    expectedR = BigDecimal("2"),
                    protectionDeadlineAt = Instant.parse("2024-06-29T23:56:30Z"),
                    fixedTargetEnabled = false,
                ),
            )
            val gateway =
                RecordingExecutionGateway(
                    positions =
                        listOf(
                            ExchangePosition(
                                symbol = symbol,
                                side = Side.BUY,
                                size = BigDecimal.ONE,
                                openedAt = entryAt,
                                entryPrice = BigDecimal("100"),
                                markPrice = BigDecimal("109"),
                                unrealizedPnl = BigDecimal("9"),
                                updatedAt = entryAt,
                                takeProfit = null,
                                stopLoss = BigDecimal("90"),
                            ),
                        ),
                    executions =
                        listOf(
                            ExchangeExecutionFill(
                                exchangeOrderId = "exchange-entry-1",
                                clientOrderId = "client-entry-1",
                                symbol = symbol,
                                side = Side.BUY,
                                price = BigDecimal("100"),
                                quantity = BigDecimal.ONE,
                                fee = BigDecimal("0.06"),
                                executedAt = entryAt,
                                executionId = "entry-fill-1",
                            ),
                        ),
                )
            val service =
                testService(
                    candleStore = ListCandleStore(causalPositionCandles(symbol)),
                    store = store,
                    gateway = gateway,
                    config = ExchangeExecutionConfig(enabled = true),
                    positionPolicy =
                        AutomaticPositionPolicy(
                            timeframe = Timeframe.M5,
                            maxHoldCandles = 36,
                            maxTradesPerUtcDay = 1,
                            atrTrailingPeriod = 2,
                            atrTrailingMultiplier = 1.0,
                            fixedTargetEnabled = false,
                        ),
                )

            service.persistExchangeState(symbol)
            val result = service.evaluateAndSubmit(symbol, Timeframe.M5, 30)

            result.status shouldBe ExchangeEvaluationStatus.NO_TRADE
            result.reasonCodes shouldContainExactly listOf("POSITION_POLICY_CLOSED_CANDLE_APPLIED")
            gateway.protectionRequests.single().stopLoss shouldBe BigDecimal("108.0")
            val runtime = store.executionPositionRuntimeState(ExecutionRuntimeMode.TESTNET, symbol)
            runtime?.lastProcessedCandleAt shouldBe Instant.parse("2024-06-29T23:55:00Z")
            runtime?.policyState?.currentStopPrice shouldBe 108.0
            store.lifecycleRecords.last().stopLoss shouldBe BigDecimal("108.0")

            service.persistExchangeState(symbol)
            gateway.protectionRequests.size shouldBe 1
            store
                .executionPositionRuntimeState(ExecutionRuntimeMode.TESTNET, symbol)
                ?.policyState
                ?.currentStopPrice shouldBe 108.0
        }

        "automatic position fails closed when more than one causal candle was missed" {
            val symbol = Symbol("BTCUSDT")
            val store = InMemoryTradingStore()
            store.recordLifecycleEvent(testLifecycleEvent(state = ExecutionLifecycleState.OPEN_PROTECTED))
            store.upsertExecutionPositionRuntimeState(testExecutionPositionRuntimeState())
            val gateway = RecordingExecutionGateway(positions = listOf(testManagedPosition(symbol)))
            val service =
                testService(
                    store = store,
                    gateway = gateway,
                    config = ExchangeExecutionConfig(enabled = true),
                    positionPolicy = testAutomaticPositionPolicy(),
                )

            val result = service.evaluateAndSubmit(symbol, Timeframe.M5, 30)

            result.status shouldBe ExchangeEvaluationStatus.EXIT_SUBMITTED
            result.reasonCodes shouldContainExactly listOf("POSITION_POLICY_CANDLE_GAP")
            gateway.placedOrders.single().reduceOnly shouldBe true
            gateway.placedOrders.single().side shouldBe Side.SELL
            gateway.placedOrders.single().quantity shouldBe BigDecimal.ONE
        }

        "automatic position retries a reduce-only exit after confirmation timeout" {
            val symbol = Symbol("BTCUSDT")
            val store = InMemoryTradingStore()
            store.recordLifecycleEvent(
                testLifecycleEvent(
                    state = ExecutionLifecycleState.EXIT_SUBMITTED,
                    occurredAt = Instant.parse("2024-06-29T23:55:00Z"),
                ).copy(
                    exchangeOrderId = "exchange-exit-1",
                    clientOrderId = "policy-BTCUSDT-exit-1",
                ),
            )
            store.upsertExecutionPositionRuntimeState(testExecutionPositionRuntimeState())
            val gateway = RecordingExecutionGateway(positions = listOf(testManagedPosition(symbol)))
            val service =
                testService(
                    store = store,
                    gateway = gateway,
                    config = ExchangeExecutionConfig(enabled = true),
                    positionPolicy = testAutomaticPositionPolicy(),
                )

            val result = service.evaluateAndSubmit(symbol, Timeframe.M5, 30)

            result.status shouldBe ExchangeEvaluationStatus.EXIT_SUBMITTED
            result.reasonCodes shouldContainExactly listOf("POSITION_EXIT_CONFIRMATION_TIMEOUT")
            gateway.placedOrders.single().reduceOnly shouldBe true
        }

        "exchange reconciliation closes a position whose actual-fill risk exceeds its budget" {
            val symbol = Symbol("BTCUSDT")
            val store = InMemoryTradingStore()
            store.recordLifecycleEvent(
                testLifecycleEvent().copy(
                    protectionRequired = true,
                    plannedEntryPrice = BigDecimal("105"),
                    structuralStopPrice = BigDecimal("100"),
                    expectedR = BigDecimal("1.5"),
                    protectionDeadlineAt = Instant.parse("2024-06-30T00:02:00Z"),
                    intendedRisk = BigDecimal("10"),
                ),
            )
            val gateway =
                RecordingExecutionGateway(
                    positions =
                        listOf(
                            ExchangePosition(
                                symbol = symbol,
                                side = Side.BUY,
                                size = BigDecimal("1"),
                                openedAt = Instant.parse("2024-06-29T23:00:00Z"),
                                entryPrice = BigDecimal("120"),
                                markPrice = BigDecimal("120"),
                                unrealizedPnl = BigDecimal.ZERO,
                                updatedAt = Instant.parse("2024-06-30T00:00:00Z"),
                                takeProfit = BigDecimal("130"),
                                stopLoss = BigDecimal("100"),
                            ),
                        ),
                )
            val service = testService(store = store, gateway = gateway, config = ExchangeExecutionConfig(enabled = true))

            val report = service.persistExchangeState(symbol)

            gateway.protectionRequests shouldBe emptyList()
            gateway.placedOrders.single().reduceOnly shouldBe true
            gateway.placedOrders.single().side shouldBe Side.SELL
            gateway.placedOrders.single().quantity shouldBe BigDecimal("1")
            report.lifecycleEvent?.state shouldBe ExecutionLifecycleState.EXIT_SUBMITTED
            report.lifecycleEvent?.reasonCode shouldBe "ACTUAL_FILL_RISK_LIMIT_EXCEEDED"
        }

        "exchange reconciliation clears TP and verifies a stop-only actual-fill protection" {
            val symbol = Symbol("BTCUSDT")
            val store = InMemoryTradingStore()
            store.recordLifecycleEvent(
                testLifecycleEvent().copy(
                    protectionRequired = true,
                    plannedEntryPrice = BigDecimal("105"),
                    structuralStopPrice = BigDecimal("100"),
                    expectedR = BigDecimal("1.5"),
                    protectionDeadlineAt = Instant.parse("2024-06-30T00:02:00Z"),
                    fixedTargetEnabled = false,
                ),
            )
            val gateway =
                RecordingExecutionGateway(
                    positions =
                        listOf(
                            ExchangePosition(
                                symbol = symbol,
                                side = Side.BUY,
                                size = BigDecimal("1"),
                                openedAt = Instant.parse("2024-06-29T23:00:00Z"),
                                entryPrice = BigDecimal("106"),
                                markPrice = BigDecimal("106"),
                                unrealizedPnl = BigDecimal.ZERO,
                                updatedAt = Instant.parse("2024-06-30T00:00:00Z"),
                                takeProfit = BigDecimal("112.5"),
                                stopLoss = BigDecimal("100"),
                            ),
                        ),
                )
            val service = testService(store = store, gateway = gateway, config = ExchangeExecutionConfig(enabled = true))

            val report = service.persistExchangeState(symbol)

            gateway.protectionRequests.single().takeProfit shouldBe null
            gateway.protectionRequests.single().stopLoss shouldBe BigDecimal("100")
            report.lifecycleEvent?.state shouldBe ExecutionLifecycleState.OPEN_PROTECTED
            report.lifecycleEvent?.takeProfit shouldBe null
            report.lifecycleEvent?.reasonCode shouldBe "ACTUAL_FILL_PROTECTION_VERIFIED"
        }

        "automatic unprotected position is closed after its protection deadline" {
            val symbol = Symbol("BTCUSDT")
            val store = InMemoryTradingStore()
            store.recordLifecycleEvent(
                testLifecycleEvent().copy(
                    protectionRequired = true,
                    plannedEntryPrice = BigDecimal("105"),
                    structuralStopPrice = BigDecimal("100"),
                    expectedR = BigDecimal("1.5"),
                    protectionDeadlineAt = Instant.parse("2024-06-29T23:11:00Z"),
                ),
            )
            val gateway =
                RecordingExecutionGateway(
                    positions =
                        listOf(
                            ExchangePosition(
                                symbol = symbol,
                                side = Side.BUY,
                                size = BigDecimal("1"),
                                openedAt = Instant.parse("2024-06-29T23:00:00Z"),
                                entryPrice = BigDecimal("106"),
                                markPrice = BigDecimal("106"),
                                unrealizedPnl = BigDecimal.ZERO,
                                updatedAt = Instant.parse("2024-06-30T00:00:00Z"),
                            ),
                        ),
                    protectionFailure = ExchangeExecutionException("protection rejected"),
                )
            val service = testService(store = store, gateway = gateway, config = ExchangeExecutionConfig(enabled = true))

            val report = service.persistExchangeState(symbol)

            report.lifecycleEvent?.state shouldBe ExecutionLifecycleState.OPEN_UNPROTECTED
            gateway.placedOrders.single().reduceOnly shouldBe true
            gateway.placedOrders.single().side shouldBe Side.SELL
            store.lifecycleRecords.last().state shouldBe ExecutionLifecycleState.EXIT_SUBMITTED
            store.lifecycleRecords.last().reasonCode shouldBe "UNPROTECTED_POSITION_TIMEOUT"
        }

        "exchange reconciliation recovers and flags an unprotected position" {
            val symbol = Symbol("BTCUSDT")
            val store = InMemoryTradingStore()
            val gateway =
                RecordingExecutionGateway(
                    positions =
                        listOf(
                            ExchangePosition(
                                symbol = symbol,
                                side = Side.SELL,
                                size = BigDecimal("0.5"),
                                openedAt = Instant.parse("2024-06-29T23:00:00Z"),
                                entryPrice = BigDecimal("100"),
                                markPrice = BigDecimal("98"),
                                unrealizedPnl = BigDecimal("1"),
                                updatedAt = Instant.parse("2024-06-29T23:20:00Z"),
                                takeProfit = BigDecimal("90"),
                                stopLoss = null,
                            ),
                        ),
                )
            val service = testService(store = store, gateway = gateway, config = ExchangeExecutionConfig(enabled = true))

            val report = service.persistExchangeState(symbol)

            report.lifecycleEvent?.state shouldBe ExecutionLifecycleState.OPEN_UNPROTECTED
            report.lifecycleEvent?.lifecycleId shouldBe "recovered-BTCUSDT-1719702000000"
            report.lifecycleEvent?.reasonCode shouldBe "UNPROTECTED_POSITION_OBSERVED"
        }

        "exchange reconciliation keeps a pending exit state while the reduce-only order is open" {
            val symbol = Symbol("BTCUSDT")
            val store = InMemoryTradingStore()
            store.recordLifecycleEvent(
                testLifecycleEvent(
                    state = ExecutionLifecycleState.EXIT_SUBMITTED,
                    occurredAt = Instant.parse("2024-06-29T23:20:00Z"),
                ).copy(
                    exchangeOrderId = "exchange-exit-1",
                    clientOrderId = "time-BTCUSDT-exit-1",
                ),
            )
            val gateway =
                RecordingExecutionGateway(
                    openOrders =
                        listOf(
                            ExchangeOpenOrder(
                                exchangeOrderId = "exchange-exit-1",
                                clientOrderId = "time-BTCUSDT-exit-1",
                                symbol = symbol,
                                side = Side.SELL,
                                orderType = OrderType.MARKET,
                                status = OrderStatus.SUBMITTED,
                                quantity = BigDecimal("1"),
                                createdAt = Instant.parse("2024-06-29T23:20:00Z"),
                            ),
                        ),
                    positions =
                        listOf(
                            ExchangePosition(
                                symbol = symbol,
                                side = Side.BUY,
                                size = BigDecimal("1"),
                                openedAt = Instant.parse("2024-06-29T23:00:00Z"),
                                entryPrice = BigDecimal("100"),
                                markPrice = BigDecimal("105"),
                                unrealizedPnl = BigDecimal("5"),
                                updatedAt = Instant.parse("2024-06-29T23:20:00Z"),
                                takeProfit = BigDecimal("112.5"),
                                stopLoss = BigDecimal("100"),
                            ),
                        ),
                )
            val service = testService(store = store, gateway = gateway, config = ExchangeExecutionConfig(enabled = true))

            val report = service.persistExchangeState(symbol)

            report.lifecycleEvent shouldBe null
            store.latestLifecycleEvent(ExecutionRuntimeMode.TESTNET, symbol)?.state shouldBe ExecutionLifecycleState.EXIT_SUBMITTED
        }

        "exchange reconciliation allows an acknowledged exit time to appear before flagging an error" {
            val symbol = Symbol("BTCUSDT")
            val store = InMemoryTradingStore()
            store.recordLifecycleEvent(
                testLifecycleEvent(
                    state = ExecutionLifecycleState.EXIT_SUBMITTED,
                    occurredAt = Instant.parse("2024-06-29T23:59:30Z"),
                ).copy(
                    exchangeOrderId = "exchange-exit-1",
                    clientOrderId = "policy-BTCUSDT-exit-1",
                ),
            )
            val gateway =
                RecordingExecutionGateway(
                    positions =
                        listOf(
                            ExchangePosition(
                                symbol = symbol,
                                side = Side.BUY,
                                size = BigDecimal.ONE,
                                openedAt = Instant.parse("2024-06-29T23:00:00Z"),
                                entryPrice = BigDecimal("100"),
                                markPrice = BigDecimal("105"),
                                unrealizedPnl = BigDecimal("5"),
                                updatedAt = Instant.parse("2024-06-29T23:59:30Z"),
                                takeProfit = BigDecimal("112.5"),
                                stopLoss = BigDecimal("90"),
                            ),
                        ),
                )
            val service = testService(store = store, gateway = gateway, config = ExchangeExecutionConfig(enabled = true))

            val report = service.persistExchangeState(symbol)

            report.lifecycleEvent shouldBe null
            store.latestLifecycleEvent(ExecutionRuntimeMode.TESTNET, symbol)?.state shouldBe ExecutionLifecycleState.EXIT_SUBMITTED
        }

        "exchange reconciliation records partial entry fills without claiming an open position" {
            val symbol = Symbol("BTCUSDT")
            val store = InMemoryTradingStore()
            store.recordLifecycleEvent(testLifecycleEvent())
            val gateway =
                RecordingExecutionGateway(
                    openOrders =
                        listOf(
                            ExchangeOpenOrder(
                                exchangeOrderId = "exchange-entry-1",
                                clientOrderId = "client-entry-1",
                                symbol = symbol,
                                side = Side.BUY,
                                orderType = OrderType.MARKET,
                                status = OrderStatus.PARTIALLY_FILLED,
                                quantity = BigDecimal("1"),
                                createdAt = Instant.parse("2024-06-29T23:10:00Z"),
                            ),
                        ),
                    executions =
                        listOf(
                            ExchangeExecutionFill(
                                executionId = "exec-entry-1",
                                exchangeOrderId = "exchange-entry-1",
                                clientOrderId = "client-entry-1",
                                symbol = symbol,
                                side = Side.BUY,
                                price = BigDecimal("101"),
                                quantity = BigDecimal("0.4"),
                                fee = BigDecimal("0.02"),
                                executedAt = Instant.parse("2024-06-29T23:11:00Z"),
                            ),
                        ),
                )
            val service = testService(store = store, gateway = gateway, config = ExchangeExecutionConfig(enabled = true))

            service.persistExchangeState(symbol)

            val latest = store.latestLifecycleEvent(ExecutionRuntimeMode.TESTNET, symbol)
            latest?.state shouldBe ExecutionLifecycleState.PARTIALLY_FILLED
            latest?.filledQuantity shouldBe BigDecimal("0.4")
            latest?.fillVwap shouldBe BigDecimal("101")
            val storedFill = store.fillEvents.single().fill
            storedFill.executionId shouldBe "exec-entry-1"
            service.persistExecutionFill(storedFill) shouldBe null
            store.fillEvents.size shouldBe 1
        }

        "private order update distinguishes unfilled cancellation from partial remainder cancellation" {
            val symbol = Symbol("BTCUSDT")
            val cancelledStore = InMemoryTradingStore()
            cancelledStore.recordLifecycleEvent(testLifecycleEvent())
            val cancelledService =
                testService(
                    store = cancelledStore,
                    gateway = RecordingExecutionGateway(),
                    config = ExchangeExecutionConfig(enabled = true),
                )

            val cancelled =
                cancelledService.observeOrderUpdate(
                    testOrderUpdate(status = OrderStatus.CANCELLED, cancelType = "CancelByUser"),
                )

            cancelled?.state shouldBe ExecutionLifecycleState.ENTRY_CANCELLED
            cancelled?.filledQuantity shouldBe null
            cancelled?.reasonCode shouldBe "ENTRY_ORDER_CANCELLED_UNFILLED|CancelByUser"

            val partialStore = InMemoryTradingStore()
            partialStore.recordLifecycleEvent(testLifecycleEvent())
            val partialService =
                testService(
                    store = partialStore,
                    gateway = RecordingExecutionGateway(),
                    config = ExchangeExecutionConfig(enabled = true),
                )

            val partial =
                partialService.observeOrderUpdate(
                    testOrderUpdate(
                        status = OrderStatus.CANCELLED,
                        cumulativeFilledQuantity = BigDecimal("0.4"),
                        leavesQuantity = BigDecimal("0.6"),
                        averageFillPrice = BigDecimal("101"),
                        cancelType = "CancelByUser",
                    ),
                )

            partial?.state shouldBe ExecutionLifecycleState.PARTIALLY_FILLED
            partial?.filledQuantity shouldBe BigDecimal("0.4")
            partial?.reasonCode shouldBe "ENTRY_PARTIAL_FILL_REMAINDER_CANCELLED|CancelByUser"
        }

        "private order update confirms entry fill but waits for position reconciliation" {
            val symbol = Symbol("BTCUSDT")
            val store = InMemoryTradingStore()
            store.recordLifecycleEvent(testLifecycleEvent())
            val service =
                testService(
                    store = store,
                    gateway = RecordingExecutionGateway(),
                    config = ExchangeExecutionConfig(enabled = true),
                )

            val filled =
                service.observeOrderUpdate(
                    testOrderUpdate(
                        status = OrderStatus.FILLED,
                        cumulativeFilledQuantity = BigDecimal.ONE,
                        leavesQuantity = BigDecimal.ZERO,
                        averageFillPrice = BigDecimal("101"),
                    ),
                )

            filled?.state shouldBe ExecutionLifecycleState.ENTRY_FILLED
            filled?.fillVwap shouldBe BigDecimal("101")
            filled?.reasonCode shouldBe "ENTRY_FILL_CONFIRMED_PENDING_POSITION"
            service.observeOrderUpdate(
                testOrderUpdate(
                    status = OrderStatus.PARTIALLY_FILLED,
                    cumulativeFilledQuantity = BigDecimal("0.4"),
                    leavesQuantity = BigDecimal("0.6"),
                ).copy(updatedAt = Instant.parse("2024-06-29T23:10:30Z")),
            ) shouldBe null
            store.latestLifecycleEvent(ExecutionRuntimeMode.TESTNET, symbol)?.state shouldBe ExecutionLifecycleState.ENTRY_FILLED
        }

        "private order update records exchange rejection without claiming a fill" {
            val symbol = Symbol("BTCUSDT")
            val store = InMemoryTradingStore()
            store.recordLifecycleEvent(testLifecycleEvent())
            val service =
                testService(
                    store = store,
                    gateway = RecordingExecutionGateway(),
                    config = ExchangeExecutionConfig(enabled = true),
                )

            val rejected =
                service.observeOrderUpdate(
                    testOrderUpdate(
                        status = OrderStatus.REJECTED,
                        rejectReason = "EC_TooLateToCancel",
                    ),
                )

            rejected?.state shouldBe ExecutionLifecycleState.ENTRY_REJECTED
            rejected?.reasonCode shouldBe "ENTRY_ORDER_REJECTED|EC_TooLateToCancel"
        }

        "reconciliation errors when a confirmed fill never becomes a position or closure" {
            val symbol = Symbol("BTCUSDT")
            val store = InMemoryTradingStore()
            store.recordLifecycleEvent(
                testLifecycleEvent(state = ExecutionLifecycleState.ENTRY_FILLED).copy(
                    filledQuantity = BigDecimal.ONE,
                    fillVwap = BigDecimal("101"),
                    protectionRequired = true,
                    plannedEntryPrice = BigDecimal("101"),
                    structuralStopPrice = BigDecimal("100"),
                    expectedR = BigDecimal("1.5"),
                    protectionDeadlineAt = Instant.parse("2024-06-29T23:12:00Z"),
                ),
            )
            val service =
                testService(
                    store = store,
                    gateway = RecordingExecutionGateway(),
                    config = ExchangeExecutionConfig(enabled = true),
                )

            val report = service.persistExchangeState(symbol)

            report.lifecycleEvent?.state shouldBe ExecutionLifecycleState.ERROR
            report.lifecycleEvent?.reasonCode shouldBe "ENTRY_FILL_POSITION_MISSING"
        }

        "exchange reconciliation closes the active lifecycle from closed PnL" {
            val symbol = Symbol("BTCUSDT")
            val store = InMemoryTradingStore()
            store.recordLifecycleEvent(
                testLifecycleEvent(
                    state = ExecutionLifecycleState.EXIT_SUBMITTED,
                    occurredAt = Instant.parse("2024-06-29T23:20:00Z"),
                ),
            )
            store.upsertExecutionPositionRuntimeState(testExecutionPositionRuntimeState())
            val gateway =
                RecordingExecutionGateway(
                    closedPnls =
                        listOf(
                            testClosedPnl(
                                exchangeOrderId = "exchange-exit-1",
                                closedAt = Instant.parse("2024-06-29T23:30:00Z"),
                            ),
                        ),
                )
            val service = testService(store = store, gateway = gateway, config = ExchangeExecutionConfig(enabled = true))

            service.persistExchangeState(symbol)

            val latest = store.latestLifecycleEvent(ExecutionRuntimeMode.TESTNET, symbol)
            latest?.state shouldBe ExecutionLifecycleState.CLOSED
            latest?.reasonCode shouldBe "TAKE_PROFIT"
            latest?.exchangeOrderId shouldBe "exchange-exit-1"
            latest?.fillVwap shouldBe BigDecimal("105")
            store.executionPositionRuntimeState(ExecutionRuntimeMode.TESTNET, symbol) shouldBe null
        }

        "exchange reconciliation persists account risk state without replaying a closure" {
            val symbol = Symbol("BTCUSDT")
            val store = InMemoryTradingStore()
            val loss =
                testClosedPnl(exchangeOrderId = "loss-1").copy(
                    grossPnl = BigDecimal("-4.88"),
                    fees = BigDecimal("0.12"),
                    netPnl = BigDecimal("-5"),
                    exitReason = "STOP_LOSS",
                )
            val gateway = RecordingExecutionGateway(closedPnls = listOf(loss))
            val service = testService(store = store, gateway = gateway, config = ExchangeExecutionConfig(enabled = true))

            service.persistExchangeState(symbol)
            service.persistExchangeState(symbol)

            val state = store.executionRiskState(ExecutionRuntimeMode.TESTNET)
            state?.peakEquity shouldBe BigDecimal("1000")
            state?.latestEquity shouldBe BigDecimal("1000")
            state?.consecutiveLosses shouldBe 1
            state?.lastClosureId shouldBe 1L
        }

        "exchange reconciliation persists account transactions and tracked coin balances idempotently" {
            val symbol = Symbol("BTCUSDT")
            val store = InMemoryTradingStore()
            val transaction = testAccountTransaction()
            val accountBalance =
                ExchangeAccountBalance(
                    accountType = "UNIFIED",
                    totalEquity = BigDecimal("1000"),
                    totalWalletBalance = BigDecimal("990"),
                    totalMarginBalance = BigDecimal("1000"),
                    totalAvailableBalance = BigDecimal("900"),
                    totalPerpUnrealizedPnl = BigDecimal("10"),
                    totalInitialMargin = BigDecimal("50"),
                    totalMaintenanceMargin = BigDecimal("20"),
                    coins =
                        listOf(
                            ExchangeCoinBalance(
                                coin = "USDT",
                                equity = BigDecimal("1000"),
                                usdValue = BigDecimal("1000"),
                                walletBalance = BigDecimal("990"),
                                locked = BigDecimal.ZERO,
                                unrealizedPnl = BigDecimal("10"),
                                cumulativeRealizedPnl = BigDecimal("25"),
                            ),
                        ),
                    capturedAt = Instant.parse("2024-06-30T00:00:00Z"),
                )
            val gateway =
                RecordingExecutionGateway(
                    accountTransactions = listOf(transaction),
                    accountBalance = accountBalance,
                )
            val service =
                testService(
                    store = store,
                    gateway = gateway,
                    config = ExchangeExecutionConfig(enabled = true, walletReconciliationEnabled = true),
                )

            service.persistExchangeState(symbol)
            service.persistExchangeState(symbol)

            store.accountTransactionEvents.map { it.transaction } shouldBe listOf(transaction)
            store.accountSnapshots.last().trackedCoinWalletBalance shouldBe BigDecimal("990")
            store.accountSnapshots.last().trackedCoinCumulativeRealizedPnl shouldBe BigDecimal("25")
            gateway.accountTransactionRequests shouldBe 2
            store.walletReconciliationStates[ExecutionRuntimeMode.TESTNET to "USDT"]?.status shouldBe
                ExecutionWalletReconciliationStatus.MATCHED
            store.executionRiskState(ExecutionRuntimeMode.TESTNET)?.navStatus shouldBe ExecutionRiskNavStatus.READY
            store.executionRiskState(ExecutionRuntimeMode.TESTNET)?.latestUnitizedNav shouldBe BigDecimal.ONE
        }

        "transaction sync failure delays the unitized nav baseline without losing recovery" {
            val symbol = Symbol("BTCUSDT")
            val store = InMemoryTradingStore()
            val accountBalance =
                ExchangeAccountBalance(
                    accountType = "UNIFIED",
                    totalEquity = BigDecimal("100"),
                    totalWalletBalance = BigDecimal("100"),
                    totalMarginBalance = BigDecimal("100"),
                    totalAvailableBalance = BigDecimal("100"),
                    totalPerpUnrealizedPnl = BigDecimal.ZERO,
                    totalInitialMargin = BigDecimal.ZERO,
                    totalMaintenanceMargin = BigDecimal.ZERO,
                    coins =
                        listOf(
                            ExchangeCoinBalance(
                                coin = "USDT",
                                equity = BigDecimal("100"),
                                usdValue = BigDecimal("100"),
                                walletBalance = BigDecimal("100"),
                                locked = BigDecimal.ZERO,
                                unrealizedPnl = BigDecimal.ZERO,
                            ),
                        ),
                    capturedAt = Instant.parse("2024-06-30T00:00:00Z"),
                )
            val gateway =
                RecordingExecutionGateway(
                    accountTransactions =
                        listOf(
                            testAccountTransaction().copy(
                                type = "TRANSFER_IN",
                                cashFlow = BigDecimal("100"),
                                change = BigDecimal("100"),
                            ),
                        ),
                    accountTransactionFailure = IllegalStateException("transaction sync unavailable"),
                    accountBalance = accountBalance,
                )
            val service =
                testService(
                    store = store,
                    gateway = gateway,
                    config = ExchangeExecutionConfig(enabled = true, walletReconciliationEnabled = true),
                )

            service.persistExchangeState(symbol)
            store.executionRiskState(ExecutionRuntimeMode.TESTNET) shouldBe null
            store.walletReconciliationState(ExecutionRuntimeMode.TESTNET, "USDT")?.status shouldBe
                ExecutionWalletReconciliationStatus.SYNC_ERROR

            gateway.accountTransactionFailure = null
            service.persistExchangeState(symbol)
            store.executionRiskState(ExecutionRuntimeMode.TESTNET)?.navStatus shouldBe ExecutionRiskNavStatus.BASELINE
            service.persistExchangeState(symbol)
            store.executionRiskState(ExecutionRuntimeMode.TESTNET)?.navStatus shouldBe ExecutionRiskNavStatus.READY
            store.executionRiskState(ExecutionRuntimeMode.TESTNET)?.latestUnitizedNav shouldBe BigDecimal.ONE
        }

        "exchange reconciliation classifies the close from Bybit execution metadata" {
            val symbol = Symbol("BTCUSDT")
            val store = InMemoryTradingStore()
            store.recordLifecycleEvent(
                testLifecycleEvent(
                    state = ExecutionLifecycleState.EXIT_SUBMITTED,
                    occurredAt = Instant.parse("2024-06-29T23:20:00Z"),
                ),
            )
            val gateway =
                RecordingExecutionGateway(
                    executions =
                        listOf(
                            ExchangeExecutionFill(
                                exchangeOrderId = "exchange-exit-1",
                                clientOrderId = "close-BTCUSDT-1-B",
                                symbol = symbol,
                                side = Side.SELL,
                                price = BigDecimal("105"),
                                quantity = BigDecimal("1"),
                                fee = BigDecimal("0.06"),
                                executedAt = Instant.parse("2024-06-29T23:30:00Z"),
                                executionType = "Trade",
                                createType = "CreateByStopLoss",
                                stopOrderType = "StopLoss",
                                closedSize = BigDecimal.ONE,
                                executionPnl = BigDecimal("5"),
                            ),
                        ),
                    closedPnls =
                        listOf(
                            testClosedPnl(
                                exchangeOrderId = "exchange-exit-1",
                                closedAt = Instant.parse("2024-06-29T23:30:00Z"),
                                exitReason = "CLOSED_PNL",
                            ),
                        ),
                )
            val service = testService(store = store, gateway = gateway, config = ExchangeExecutionConfig(enabled = true))

            service.persistExchangeState(symbol)

            store.closures.single().exitReason shouldBe "STOP_LOSS"
            store.latestLifecycleEvent(ExecutionRuntimeMode.TESTNET, symbol)?.reasonCode shouldBe "STOP_LOSS"
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

        "live performance reports account equity drawdown separately from closed trade pnl" {
            val now = Instant.parse("2024-06-30T00:00:00Z")
            val store = InMemoryTradingStore()
            val service = testService(store = store, config = ExchangeExecutionConfig(enabled = true))
            listOf("100", "120", "90").forEachIndexed { index, equity ->
                store.recordAccountSnapshot(
                    ExecutionAccountSnapshot(
                        mode = ExecutionRuntimeMode.TESTNET,
                        accountType = "UNIFIED",
                        totalEquity = BigDecimal(equity),
                        totalWalletBalance = BigDecimal(equity),
                        totalMarginBalance = BigDecimal(equity),
                        totalAvailableBalance = BigDecimal(equity),
                        totalPerpUnrealizedPnl = BigDecimal.ZERO,
                        capturedAt = now.plusSeconds(index.toLong()),
                    ),
                )
            }

            val summary = service.livePerformanceSummary(null, LivePerformanceWindow.ALL)

            summary?.accountEquity shouldBe BigDecimal("90")
            summary?.accountPeakEquity shouldBe BigDecimal("120")
            summary?.maxAccountDrawdownPct shouldBe BigDecimal("25.00000000")
            summary?.maxClosedTradeDrawdownPct shouldBe BigDecimal.ZERO
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
                    openedAt = Instant.parse("2024-06-29T20:40:00Z").plusSeconds(index * 300L),
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

private class InMemoryTradingStore :
    PaperTradingStore,
    ExecutionProjectionStore,
    ExecutionLifecycleStore,
    ExecutionPositionRuntimeStateStore {
    val signals = mutableListOf<PaperSignalRecord>()
    val orders = mutableListOf<PaperOrderRecord>()
    val closures = mutableListOf<ExecutionTradeClosure>()
    val fillEvents = mutableListOf<ExecutionFillEvent>()
    val lifecycleRecords = mutableListOf<ExecutionLifecycleEvent>()
    val positionRuntimeStates = mutableMapOf<Pair<ExecutionRuntimeMode, Symbol>, ExecutionPositionRuntimeState>()
    val performance = mutableListOf<LivePerformanceSnapshot>()
    val accountSnapshots = mutableListOf<ExecutionAccountSnapshot>()
    val accountTransactionEvents = mutableListOf<ExecutionAccountTransactionEvent>()
    val riskStates = mutableMapOf<ExecutionRuntimeMode, ExecutionRiskState>()
    val walletReconciliationStates =
        mutableMapOf<Pair<ExecutionRuntimeMode, String>, ExecutionWalletReconciliationState>()
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

    override suspend fun recordExecutionFill(event: ExecutionFillEvent): Long? {
        val executionId = event.fill.executionId
        if (
            fillEvents.any { existing ->
                existing.mode == event.mode &&
                    existing.fill.symbol == event.fill.symbol &&
                    executionId != null &&
                    existing.fill.executionId == executionId
            }
        ) {
            return null
        }
        val id = fillEvents.size + 1L
        fillEvents += event.copy(id = id)
        return id
    }

    override suspend fun executionFills(
        mode: ExecutionRuntimeMode,
        symbol: Symbol,
        executedAtOrAfter: Instant?,
        limit: Int,
    ): List<ExecutionFillEvent> =
        fillEvents
            .filter { event ->
                event.mode == mode &&
                    event.fill.symbol == symbol &&
                    (executedAtOrAfter == null || !event.fill.executedAt.isBefore(executedAtOrAfter))
            }.sortedByDescending { event -> event.fill.executedAt }
            .take(limit)

    override suspend fun recordPosition(position: PaperPositionRecord): Long = 0

    override suspend fun recordPerformanceSnapshot(snapshot: PaperPerformanceSnapshot): Long = 0

    override suspend fun latestPerformanceSummary(): PaperPerformanceSnapshot? = null

    override suspend fun recentSignals(limit: Int): List<PaperSignalRecord> = signals.asReversed().take(limit)

    override suspend fun recentTrades(limit: Int): List<PaperTradeRecord> = emptyList()

    override suspend fun recordLifecycleEvent(event: ExecutionLifecycleEvent): Long? {
        if (lifecycleRecords.any {
                it.mode == event.mode &&
                    it.lifecycleId == event.lifecycleId &&
                    it.state == event.state &&
                    it.clientOrderId == event.clientOrderId &&
                    it.occurredAt == event.occurredAt
            }
        ) {
            return null
        }
        val id = lifecycleRecords.size + 1L
        lifecycleRecords += event.copy(id = id)
        return id
    }

    override suspend fun latestLifecycleEvent(
        mode: ExecutionRuntimeMode,
        symbol: Symbol,
    ): ExecutionLifecycleEvent? =
        lifecycleRecords
            .filter { event -> event.mode == mode && event.symbol == symbol }
            .maxByOrNull(ExecutionLifecycleEvent::id)

    override suspend fun lifecycleEvents(
        mode: ExecutionRuntimeMode?,
        symbol: Symbol?,
        limit: Int,
    ): List<ExecutionLifecycleEvent> =
        lifecycleRecords
            .filter { event ->
                (mode == null || event.mode == mode) && (symbol == null || event.symbol == symbol)
            }.sortedByDescending(ExecutionLifecycleEvent::id)
            .take(limit)

    override suspend fun executionPositionRuntimeState(
        mode: ExecutionRuntimeMode,
        symbol: Symbol,
    ): ExecutionPositionRuntimeState? = positionRuntimeStates[mode to symbol]

    override suspend fun upsertExecutionPositionRuntimeState(state: ExecutionPositionRuntimeState) {
        positionRuntimeStates[state.mode to state.symbol] = state
    }

    override suspend fun deleteExecutionPositionRuntimeState(
        mode: ExecutionRuntimeMode,
        symbol: Symbol,
    ) {
        positionRuntimeStates.remove(mode to symbol)
    }

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

    override suspend fun recordAccountSnapshot(snapshot: ExecutionAccountSnapshot): Long {
        val id = accountSnapshots.size + 1L
        accountSnapshots += snapshot.copy(id = id)
        return id
    }

    override suspend fun accountSnapshots(
        mode: ExecutionRuntimeMode,
        capturedAtOrAfter: Instant?,
    ): List<ExecutionAccountSnapshot> =
        accountSnapshots.filter { snapshot ->
            snapshot.mode == mode && (capturedAtOrAfter == null || !snapshot.capturedAt.isBefore(capturedAtOrAfter))
        }

    override suspend fun latestAccountSnapshot(
        mode: ExecutionRuntimeMode,
        capturedAtOrBefore: Instant,
    ): ExecutionAccountSnapshot? =
        accountSnapshots
            .filter { snapshot -> snapshot.mode == mode && !snapshot.capturedAt.isAfter(capturedAtOrBefore) }
            .maxByOrNull(ExecutionAccountSnapshot::capturedAt)

    override suspend fun upsertExecutionRiskState(state: ExecutionRiskState) {
        riskStates[state.mode] = state
    }

    override suspend fun executionRiskState(mode: ExecutionRuntimeMode): ExecutionRiskState? = riskStates[mode]

    override suspend fun recordAccountTransaction(event: ExecutionAccountTransactionEvent): Long? {
        if (accountTransactionEvents.any { existing -> existing.mode == event.mode && existing.transaction == event.transaction }) {
            return null
        }
        val id = accountTransactionEvents.size + 1L
        accountTransactionEvents += event.copy(id = id)
        return id
    }

    override suspend fun accountTransactions(
        mode: ExecutionRuntimeMode,
        currency: String,
        transactionAtOrAfter: Instant?,
        transactionAtOrBefore: Instant?,
    ): List<ExecutionAccountTransactionEvent> =
        accountTransactionEvents.filter { event ->
            event.mode == mode &&
                event.transaction.currency == currency &&
                (transactionAtOrAfter == null || !event.transaction.transactionAt.isBefore(transactionAtOrAfter)) &&
                (transactionAtOrBefore == null || !event.transaction.transactionAt.isAfter(transactionAtOrBefore))
        }

    override suspend fun latestAccountTransaction(
        mode: ExecutionRuntimeMode,
        currency: String,
    ): ExecutionAccountTransactionEvent? =
        accountTransactionEvents
            .filter { event -> event.mode == mode && event.transaction.currency == currency }
            .maxByOrNull { event -> event.transaction.transactionAt }

    override suspend fun accountTransactionsAfterId(
        mode: ExecutionRuntimeMode,
        currency: String,
        afterId: Long?,
        transactionAtOrBefore: Instant,
    ): List<ExecutionAccountTransactionEvent> =
        accountTransactionEvents
            .filter { event ->
                event.mode == mode &&
                    event.transaction.currency == currency &&
                    (afterId == null || event.id > afterId) &&
                    !event.transaction.transactionAt.isAfter(transactionAtOrBefore)
            }.sortedBy(ExecutionAccountTransactionEvent::id)

    override suspend fun upsertWalletReconciliationState(state: ExecutionWalletReconciliationState) {
        walletReconciliationStates[state.mode to state.currency] = state
    }

    override suspend fun walletReconciliationState(
        mode: ExecutionRuntimeMode,
        currency: String,
    ): ExecutionWalletReconciliationState? = walletReconciliationStates[mode to currency]
}

private class RecordingExecutionGateway(
    openOrders: List<ExchangeOpenOrder> = emptyList(),
    positions: List<ExchangePosition> = emptyList(),
    private val executions: List<ExchangeExecutionFill> = emptyList(),
    private val closedPnls: List<ExchangeClosedPnl> = emptyList(),
    private val accountTransactions: List<ExchangeAccountTransaction> = emptyList(),
    var accountTransactionFailure: Throwable? = null,
    private val protectionFailure: Throwable? = null,
    private val closeImmediatelyOnReduceOnly: Boolean = false,
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
    private var currentOpenOrders = openOrders
    private var currentPositions = positions
    val leverageRequests = mutableListOf<Pair<Symbol, BigDecimal>>()
    val placedOrders = mutableListOf<ExchangeOrderRequest>()
    val protectionRequests = mutableListOf<ExchangePositionProtectionRequest>()
    var openOrderRequests: Int = 0
    var positionRequests: Int = 0
    var executionRequests: Int = 0
    var closedPnlRequests: Int = 0
    var accountTransactionRequests: Int = 0

    override suspend fun setLeverage(
        symbol: Symbol,
        leverage: BigDecimal,
    ) {
        leverageRequests += symbol to leverage
    }

    override suspend fun placeOrder(request: ExchangeOrderRequest): ExchangeOrderResult {
        placedOrders += request
        if (request.reduceOnly && closeImmediatelyOnReduceOnly) {
            currentPositions = emptyList()
        }
        return ExchangeOrderResult(
            exchangeOrderId = "exchange-1",
            clientOrderId = request.clientOrderId,
            status = OrderStatus.SUBMITTED,
        )
    }

    override suspend fun setPositionProtection(request: ExchangePositionProtectionRequest) {
        protectionRequests += request
        protectionFailure?.let { throw it }
        currentPositions =
            currentPositions.map { position ->
                if (position.symbol == request.symbol && position.size > BigDecimal.ZERO) {
                    position.copy(takeProfit = request.takeProfit, stopLoss = request.stopLoss)
                } else {
                    position
                }
            }
    }

    override suspend fun cancelOrder(request: ExchangeCancelRequest): ExchangeCancelResult {
        currentOpenOrders =
            currentOpenOrders.filterNot { order ->
                (!request.exchangeOrderId.isNullOrBlank() && order.exchangeOrderId == request.exchangeOrderId) ||
                    (!request.clientOrderId.isNullOrBlank() && order.clientOrderId == request.clientOrderId)
            }
        return ExchangeCancelResult(
            exchangeOrderId = request.exchangeOrderId,
            clientOrderId = request.clientOrderId,
        )
    }

    override suspend fun openOrders(symbol: Symbol): List<ExchangeOpenOrder> {
        openOrderRequests += 1
        return currentOpenOrders
    }

    override suspend fun positions(symbol: Symbol): List<ExchangePosition> {
        positionRequests += 1
        return currentPositions
    }

    override suspend fun executions(symbol: Symbol): List<ExchangeExecutionFill> {
        executionRequests += 1
        return executions
    }

    override suspend fun closedPnls(symbol: Symbol): List<ExchangeClosedPnl> {
        closedPnlRequests += 1
        return closedPnls
    }

    override suspend fun accountBalance(coin: String?): ExchangeAccountBalance = accountBalance

    override suspend fun accountTransactions(
        currency: String,
        startAt: Instant,
        endAt: Instant,
    ): List<ExchangeAccountTransaction> {
        accountTransactionRequests += 1
        accountTransactionFailure?.let { throw it }
        return accountTransactions.filter { transaction ->
            transaction.currency == currency &&
                !transaction.transactionAt.isBefore(startAt) &&
                !transaction.transactionAt.isAfter(endAt)
        }
    }
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

private class CostDistortedTargetStrategy : TradingStrategy {
    override val name: String = "cost-distorted-target-test"
    override val warmupCandles: Int = 2

    override fun evaluate(candles: List<Candle>): StrategyDecision {
        val latest = candles.last()
        return StrategyDecision(
            intent =
                SignalIntent(
                    symbol = latest.symbol,
                    side = Side.BUY,
                    strategy = name,
                    score = SignalScore(80, listOf("COST_DISTORTED_TARGET")),
                    invalidationPrice = Price(BigDecimal("104")),
                    expectedR = BigDecimal("1.2"),
                ),
            reasonCodes = listOf("COST_DISTORTED_TARGET"),
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

private fun causalPositionCandles(symbol: Symbol): List<Candle> =
    listOf(
        "2024-06-29T23:40:00Z",
        "2024-06-29T23:45:00Z",
        "2024-06-29T23:50:00Z",
        "2024-06-29T23:55:00Z",
    ).mapIndexed { index, openedAt ->
        val isLast = index == 3
        Candle(
            symbol = symbol,
            timeframe = Timeframe.M5,
            openedAt = Instant.parse(openedAt),
            open = BigDecimal("100"),
            high = if (isLast) BigDecimal("110") else BigDecimal("102"),
            low = if (isLast) BigDecimal("99") else BigDecimal("100"),
            close = if (isLast) BigDecimal("109") else BigDecimal("101"),
            volume = BigDecimal("100"),
        )
    }

private fun testClosedPnl(
    exchangeOrderId: String,
    closedAt: Instant = Instant.parse("2024-06-29T23:30:00Z"),
    exitReason: String = "TAKE_PROFIT",
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
        exitReason = exitReason,
    )

private fun testAccountTransaction(): ExchangeAccountTransaction =
    ExchangeAccountTransaction(
        transactionId = "transaction-1",
        symbol = Symbol("BTCUSDT"),
        category = "linear",
        side = Side.BUY,
        transactionAt = Instant.parse("2024-06-29T23:30:00Z"),
        type = "TRADE",
        subtype = null,
        quantity = BigDecimal("0.1"),
        size = BigDecimal("0.1"),
        currency = "USDT",
        tradePrice = BigDecimal("60000"),
        funding = BigDecimal.ZERO,
        fee = BigDecimal("3.6"),
        cashFlow = BigDecimal("3.5"),
        change = BigDecimal("-0.1"),
        cashBalance = BigDecimal("990"),
        feeRate = BigDecimal("0.0006"),
        tradeId = "trade-1",
        exchangeOrderId = "exchange-1",
        clientOrderId = "client-1",
    )

private fun testLifecycleEvent(
    state: ExecutionLifecycleState = ExecutionLifecycleState.ENTRY_SUBMITTED,
    occurredAt: Instant = Instant.parse("2024-06-29T23:10:00Z"),
): ExecutionLifecycleEvent =
    ExecutionLifecycleEvent(
        mode = ExecutionRuntimeMode.TESTNET,
        lifecycleId = "client-entry-1",
        symbol = Symbol("BTCUSDT"),
        state = state,
        side = Side.BUY,
        requestedQuantity = BigDecimal("1"),
        filledQuantity = null,
        fillVwap = null,
        takeProfit = BigDecimal("112.5"),
        stopLoss = BigDecimal("100"),
        exchangeOrderId = "exchange-entry-1",
        clientOrderId = "client-entry-1",
        reasonCode = "TEST_LIFECYCLE",
        occurredAt = occurredAt,
    )

private fun testExecutionPositionRuntimeState(): ExecutionPositionRuntimeState =
    ExecutionPositionRuntimeState(
        mode = ExecutionRuntimeMode.TESTNET,
        lifecycleId = "client-entry-1",
        symbol = Symbol("BTCUSDT"),
        timeframe = Timeframe.M5,
        lastProcessedCandleAt = Instant.parse("2024-06-29T23:15:00Z"),
        policyState =
            CausalPositionState(
                side = Side.BUY,
                entryAt = Instant.parse("2024-06-29T23:00:00Z"),
                entryPrice = 100.0,
                initialStopPrice = 90.0,
                currentStopPrice = 95.0,
                riskPerUnit = 10.0,
                expectedR = 2.0,
                initialQuantity = 1.0,
                remainingQuantity = 1.0,
                fullTargetPrice = null,
                partialTargetPrice = 110.0,
                bestHigh = 105.0,
                bestLow = 99.0,
                processedCandles = 3,
            ),
        updatedAt = Instant.parse("2024-06-29T23:20:00Z"),
    )

private fun testManagedPosition(symbol: Symbol): ExchangePosition =
    ExchangePosition(
        symbol = symbol,
        side = Side.BUY,
        size = BigDecimal.ONE,
        openedAt = Instant.parse("2024-06-29T23:00:00Z"),
        entryPrice = BigDecimal("100"),
        markPrice = BigDecimal("105"),
        unrealizedPnl = BigDecimal("5"),
        updatedAt = Instant.parse("2024-06-29T23:55:00Z"),
        takeProfit = null,
        stopLoss = BigDecimal("95"),
    )

private fun testAutomaticPositionPolicy(): AutomaticPositionPolicy =
    AutomaticPositionPolicy(
        timeframe = Timeframe.M5,
        maxHoldCandles = 36,
        maxTradesPerUtcDay = 1,
        atrTrailingPeriod = 2,
        atrTrailingMultiplier = 1.0,
        fixedTargetEnabled = false,
    )

private fun testOrderUpdate(
    status: OrderStatus,
    cumulativeFilledQuantity: BigDecimal = BigDecimal.ZERO,
    leavesQuantity: BigDecimal = BigDecimal.ONE,
    averageFillPrice: BigDecimal? = null,
    rejectReason: String = "EC_NoError",
    cancelType: String? = null,
): ExchangeOrderUpdate =
    ExchangeOrderUpdate(
        exchangeOrderId = "exchange-entry-1",
        clientOrderId = "client-entry-1",
        parentClientOrderId = null,
        symbol = Symbol("BTCUSDT"),
        side = Side.BUY,
        orderType = OrderType.LIMIT,
        status = status,
        quantity = BigDecimal.ONE,
        cumulativeFilledQuantity = cumulativeFilledQuantity,
        leavesQuantity = leavesQuantity,
        averageFillPrice = averageFillPrice,
        reduceOnly = false,
        rejectReason = rejectReason,
        cancelType = cancelType,
        updatedAt = Instant.parse("2024-06-29T23:11:00Z"),
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
