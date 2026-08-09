package dev.yaklede.bybittrader.ledger

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.yaklede.bybittrader.alerts.AlertDeliveryRecord
import dev.yaklede.bybittrader.alerts.AlertDeliveryStatus
import dev.yaklede.bybittrader.alerts.AlertSeverity
import dev.yaklede.bybittrader.domain.BotMode
import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.ControlAction
import dev.yaklede.bybittrader.domain.OrderStatus
import dev.yaklede.bybittrader.domain.OrderType
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.control.ControlEvent
import dev.yaklede.bybittrader.engine.execution.ExchangeAccountBalance
import dev.yaklede.bybittrader.engine.execution.ExchangeAccountTransaction
import dev.yaklede.bybittrader.engine.execution.ExchangeClosedPnl
import dev.yaklede.bybittrader.engine.execution.ExchangeCoinBalance
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionFill
import dev.yaklede.bybittrader.engine.execution.ExecutionAccountSnapshot
import dev.yaklede.bybittrader.engine.execution.ExecutionAccountTransactionEvent
import dev.yaklede.bybittrader.engine.execution.ExecutionFillEvent
import dev.yaklede.bybittrader.engine.execution.ExecutionLifecycleEvent
import dev.yaklede.bybittrader.engine.execution.ExecutionLifecycleState
import dev.yaklede.bybittrader.engine.execution.ExecutionPositionRuntimeState
import dev.yaklede.bybittrader.engine.execution.ExecutionRiskNavStatus
import dev.yaklede.bybittrader.engine.execution.ExecutionRiskState
import dev.yaklede.bybittrader.engine.execution.ExecutionRuntimeMode
import dev.yaklede.bybittrader.engine.execution.ExecutionTradeClosure
import dev.yaklede.bybittrader.engine.execution.ExecutionWalletReconciliationState
import dev.yaklede.bybittrader.engine.execution.ExecutionWalletReconciliationStatus
import dev.yaklede.bybittrader.engine.execution.LivePerformanceSnapshot
import dev.yaklede.bybittrader.engine.execution.LivePerformanceWindow
import dev.yaklede.bybittrader.engine.market.MarketSyncCheckpoint
import dev.yaklede.bybittrader.engine.market.MarketSyncStatus
import dev.yaklede.bybittrader.engine.market.capture.LiquidationFlowBar
import dev.yaklede.bybittrader.engine.market.capture.OrderBookImbalanceBar
import dev.yaklede.bybittrader.engine.market.flow.AccountRatioPeriod
import dev.yaklede.bybittrader.engine.market.flow.AccountRatioSnapshot
import dev.yaklede.bybittrader.engine.market.flow.FundingRateSnapshot
import dev.yaklede.bybittrader.engine.market.flow.OpenInterestInterval
import dev.yaklede.bybittrader.engine.market.flow.OpenInterestSnapshot
import dev.yaklede.bybittrader.engine.market.flow.PremiumIndexBar
import dev.yaklede.bybittrader.engine.market.flow.TakerFlowBar
import dev.yaklede.bybittrader.engine.market.maker.MAKER_SHADOW_ENGINE_VERSION
import dev.yaklede.bybittrader.engine.market.maker.MakerShadowLedgerEvent
import dev.yaklede.bybittrader.engine.market.maker.MakerShadowLedgerEventType
import dev.yaklede.bybittrader.engine.paper.PaperFillRecord
import dev.yaklede.bybittrader.engine.paper.PaperOpenPosition
import dev.yaklede.bybittrader.engine.paper.PaperOrderRecord
import dev.yaklede.bybittrader.engine.paper.PaperPerformanceSnapshot
import dev.yaklede.bybittrader.engine.paper.PaperPositionRecord
import dev.yaklede.bybittrader.engine.paper.PaperRuntimePhase
import dev.yaklede.bybittrader.engine.paper.PaperRuntimeState
import dev.yaklede.bybittrader.engine.paper.PaperSignalRecord
import dev.yaklede.bybittrader.engine.position.CausalPositionState
import dev.yaklede.bybittrader.engine.strategy.LedgerVolumeConfirmedTrendLiveProjectionSink
import dev.yaklede.bybittrader.engine.strategy.PersistedVolumeConfirmedTrendLiveOrderOwnership
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendEmaState
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendIndicatorState
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveAccountingObservation
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveAccountingRequest
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveEvent
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveEventType
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveEvidenceService
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveRiskPolicy
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveState
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveStatus
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowEvent
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowEventType
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowPosition
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowState
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowStatus
import dev.yaklede.bybittrader.ledger.db.LedgerDatabase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.math.MathContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class SqlDelightLedgerTest :
    StringSpec({
        "fresh database creates initial bot state and records control events" {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            LedgerDatabase.Schema.create(driver)
            val database = createLedgerDatabase(driver)
            val ledger =
                SqlDelightLedger(
                    database = database,
                    clock = Clock.fixed(Instant.parse("2026-06-30T00:00:00Z"), ZoneOffset.UTC),
                )

            ledger.current().mode shouldBe BotMode.RUNNING

            ledger.record(
                ControlEvent(
                    action = ControlAction.PAUSE_ALL,
                    actor = "operator",
                    previousMode = BotMode.RUNNING,
                    newMode = BotMode.PAUSE_ALL,
                    reason = "test",
                    createdAt = Instant.parse("2026-06-30T00:01:00Z"),
                ),
            )

            database.ledgerQueries
                .selectRecentControlEvents(10)
                .executeAsList()
                .size shouldBe 1
        }

        "stored resume pending state is preserved until readiness check completes" {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            LedgerDatabase.Schema.create(driver)
            val database = createLedgerDatabase(driver)
            database.ledgerQueries.insertBotState(
                mode = BotMode.RESUME_PENDING_CHECK.name,
                updated_at = "2026-06-29T00:00:00Z",
                heartbeat_at = null,
            )
            val ledger =
                SqlDelightLedger(
                    database = database,
                    clock = Clock.fixed(Instant.parse("2026-06-30T00:00:00Z"), ZoneOffset.UTC),
                )

            ledger.current().mode shouldBe BotMode.RESUME_PENDING_CHECK
            database.ledgerQueries
                .selectBotState()
                .executeAsOne()
                .mode shouldBe BotMode.RESUME_PENDING_CHECK.name
        }

        "fresh database records alert delivery events" {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            LedgerDatabase.Schema.create(driver)
            val database = createLedgerDatabase(driver)
            val ledger = SqlDelightLedger(database = database)

            ledger.record(
                AlertDeliveryRecord(
                    sinkName = "telegram",
                    severity = AlertSeverity.INFO,
                    title = "startup",
                    deliveryStatus = AlertDeliveryStatus.DELIVERED,
                    failureReason = null,
                    createdAt = Instant.parse("2026-06-30T00:02:00Z"),
                ),
            )

            database.ledgerQueries.countAlertEvents().executeAsOne() shouldBe 1L
        }

        "maker shadow events append atomically and reject reused event IDs" {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            LedgerDatabase.Schema.create(driver)
            val database = createLedgerDatabase(driver)
            val ledger = SqlDelightLedger(database = database)
            val events =
                listOf(
                    sampleMakerShadowEvent("shadow-e-1", MakerShadowLedgerEventType.QUOTE_OPENED),
                    sampleMakerShadowEvent("shadow-e-2", MakerShadowLedgerEventType.FILL),
                )

            ledger.append(events)
            val duplicateFailure =
                try {
                    ledger.append(events)
                    null
                } catch (error: Throwable) {
                    error
                }

            val rows = database.ledgerQueries.selectMakerShadowEventsBySession("shadow-session").executeAsList()
            (duplicateFailure != null) shouldBe true
            rows.size shouldBe 2
            rows.map { it.event_id } shouldBe listOf("shadow-e-1", "shadow-e-2")
            rows.last().event_type shouldBe MakerShadowLedgerEventType.FILL.name
            rows.last().price shouldBe "100.5"
            rows.last().inventory_quantity shouldBe "0.001"
            database.ledgerQueries.countMakerShadowEventsBySession("shadow-session").executeAsOne() shouldBe 2L
        }

        "upserts and reads recent market candles" {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            LedgerDatabase.Schema.create(driver)
            val database = createLedgerDatabase(driver)
            val ledger =
                SqlDelightLedger(
                    database = database,
                    clock = Clock.fixed(Instant.parse("2026-06-30T00:03:00Z"), ZoneOffset.UTC),
                )
            val symbol = Symbol("BTCUSDT")

            ledger.upsert(
                listOf(
                    sampleCandle(symbol, Timeframe.M15, "2026-06-30T00:00:00Z", "100"),
                    sampleCandle(symbol, Timeframe.M15, "2026-06-30T00:15:00Z", "101"),
                    sampleCandle(symbol, Timeframe.M15, "2026-06-30T00:15:00Z", "102"),
                ),
            )

            val candles = ledger.recentCandles(symbol, Timeframe.M15, 2)

            candles.size shouldBe 2
            candles.first().openedAt shouldBe Instant.parse("2026-06-30T00:15:00Z")
            candles.first().open shouldBe BigDecimal("102")
            database.ledgerQueries
                .selectRecentMarketCandles("BTCUSDT", "M15", 10)
                .executeAsList()
                .size shouldBe 2
        }

        "reads bounded warmup candles before a replay start" {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            LedgerDatabase.Schema.create(driver)
            val ledger = SqlDelightLedger(createLedgerDatabase(driver))
            val symbol = Symbol("BTCUSDT")
            ledger.upsert(
                listOf(
                    sampleCandle(symbol, Timeframe.M5, "2026-06-30T00:00:00Z", "100"),
                    sampleCandle(symbol, Timeframe.M5, "2026-06-30T00:05:00Z", "101"),
                    sampleCandle(symbol, Timeframe.M5, "2026-06-30T00:10:00Z", "102"),
                ),
            )

            val candles = ledger.candlesBefore(symbol, Timeframe.M5, Instant.parse("2026-06-30T00:10:00Z"), 2)

            candles.map { it.openedAt } shouldBe
                listOf(
                    Instant.parse("2026-06-30T00:05:00Z"),
                    Instant.parse("2026-06-30T00:00:00Z"),
                )
        }

        "upserts flow data idempotently and reads bounded point-in-time windows" {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            LedgerDatabase.Schema.create(driver)
            val database = createLedgerDatabase(driver)
            val ledger = SqlDelightLedger(database = database)
            val symbol = Symbol("BTCUSDT")

            ledger.upsertTakerFlowBars(
                listOf(
                    sampleTakerFlowBar(symbol, "2026-06-30T00:00:00Z", buyBase = "1", sellBase = "2"),
                    sampleTakerFlowBar(symbol, "2026-06-30T00:01:00Z", buyBase = "3", sellBase = "4"),
                    sampleTakerFlowBar(symbol, "2026-06-30T00:01:00Z", buyBase = "5", sellBase = "6"),
                    sampleTakerFlowBar(symbol, "2026-06-30T00:02:00Z", buyBase = "7", sellBase = "8"),
                ),
            )
            val betweenFlow =
                ledger.takerFlowBarsBetween(
                    symbol = symbol,
                    startAt = Instant.parse("2026-06-30T00:00:00Z"),
                    endAt = Instant.parse("2026-06-30T00:02:00Z"),
                    limit = 10,
                )
            betweenFlow.map { it.openedAt } shouldBe
                listOf(
                    Instant.parse("2026-06-30T00:00:00Z"),
                    Instant.parse("2026-06-30T00:01:00Z"),
                    Instant.parse("2026-06-30T00:02:00Z"),
                )
            betweenFlow[1].takerBuyBase shouldBe BigDecimal("5")
            betweenFlow[1].availableAt shouldBe Instant.parse("2026-06-30T00:02:00Z")
            ledger.takerFlowBarsBefore(symbol, Instant.parse("2026-06-30T00:02:00Z"), 2).map { it.openedAt } shouldBe
                listOf(
                    Instant.parse("2026-06-30T00:01:00Z"),
                    Instant.parse("2026-06-30T00:00:00Z"),
                )

            ledger.upsertOpenInterestSnapshots(
                listOf(
                    OpenInterestSnapshot(symbol, OpenInterestInterval.M5, Instant.parse("2026-06-30T00:00:00Z"), BigDecimal("10")),
                    OpenInterestSnapshot(symbol, OpenInterestInterval.M5, Instant.parse("2026-06-30T00:05:00Z"), BigDecimal("11")),
                    OpenInterestSnapshot(symbol, OpenInterestInterval.M5, Instant.parse("2026-06-30T00:05:00Z"), BigDecimal("12")),
                ),
            )
            ledger
                .openInterestSnapshotsBetween(
                    symbol = symbol,
                    interval = OpenInterestInterval.M5,
                    startAt = Instant.parse("2026-06-30T00:00:00Z"),
                    endAt = Instant.parse("2026-06-30T00:05:00Z"),
                    limit = 10,
                ).map { it.openInterest } shouldBe listOf(BigDecimal("10"), BigDecimal("12"))

            ledger.upsertAccountRatioSnapshots(
                listOf(
                    AccountRatioSnapshot(
                        symbol,
                        AccountRatioPeriod.M5,
                        Instant.parse("2026-06-30T00:00:00Z"),
                        BigDecimal("0.45"),
                        BigDecimal("0.55"),
                    ),
                    AccountRatioSnapshot(
                        symbol,
                        AccountRatioPeriod.M5,
                        Instant.parse("2026-06-30T00:05:00Z"),
                        BigDecimal("0.55"),
                        BigDecimal("0.45"),
                    ),
                    AccountRatioSnapshot(
                        symbol,
                        AccountRatioPeriod.M5,
                        Instant.parse("2026-06-30T00:05:00Z"),
                        BigDecimal("0.60"),
                        BigDecimal("0.40"),
                    ),
                ),
            )
            ledger
                .accountRatioSnapshotsBetween(
                    symbol = symbol,
                    period = AccountRatioPeriod.M5,
                    startAt = Instant.parse("2026-06-30T00:00:00Z"),
                    endAt = Instant.parse("2026-06-30T00:05:00Z"),
                    limit = 10,
                ).map { it.buyRatio } shouldBe listOf(BigDecimal("0.45"), BigDecimal("0.60"))
            ledger
                .accountRatioSnapshotsBefore(
                    symbol = symbol,
                    period = AccountRatioPeriod.M5,
                    beforeAt = Instant.parse("2026-06-30T00:05:00Z"),
                    limit = 1,
                ).single()
                .sellRatio shouldBe BigDecimal("0.55")

            ledger.upsertOrderBookImbalanceBars(
                listOf(
                    OrderBookImbalanceBar(
                        symbol = symbol,
                        openedAt = Instant.parse("2026-06-30T00:00:00Z"),
                        sampleCount = 2,
                        meanBidNotional = BigDecimal("100"),
                        meanAskNotional = BigDecimal("80"),
                        meanImbalance = BigDecimal("0.1"),
                        meanSpreadBps = BigDecimal("1.2"),
                        maxSpreadBps = BigDecimal("1.5"),
                    ),
                ),
            )
            ledger
                .orderBookImbalanceBarsBetween(
                    symbol = symbol,
                    startAt = Instant.parse("2026-06-30T00:00:00Z"),
                    endAt = Instant.parse("2026-06-30T00:00:00Z"),
                    limit = 1,
                ).single()
                .meanImbalance shouldBe BigDecimal("0.1")

            ledger.upsertLiquidationFlowBars(
                listOf(
                    LiquidationFlowBar(
                        symbol = symbol,
                        openedAt = Instant.parse("2026-06-30T00:00:00Z"),
                        longLiquidationNotional = BigDecimal("150"),
                        shortLiquidationNotional = BigDecimal("80"),
                        longLiquidationCount = 1,
                        shortLiquidationCount = 2,
                    ),
                ),
            )
            ledger
                .liquidationFlowBarsBetween(
                    symbol = symbol,
                    startAt = Instant.parse("2026-06-30T00:00:00Z"),
                    endAt = Instant.parse("2026-06-30T00:00:00Z"),
                    limit = 1,
                ).single()
                .shortLiquidationCount shouldBe 2

            ledger.upsertPremiumIndexBars(
                listOf(
                    samplePremiumIndexBar(symbol, "2026-06-30T00:00:00Z", close = "0.01"),
                    samplePremiumIndexBar(symbol, "2026-06-30T00:15:00Z", close = "0.02"),
                ),
            )
            val premium =
                ledger.premiumIndexBarsBefore(symbol, Timeframe.M15, Instant.parse("2026-06-30T00:30:00Z"), 1).single()
            premium.openedAt shouldBe Instant.parse("2026-06-30T00:15:00Z")
            premium.availableAt shouldBe Instant.parse("2026-06-30T00:30:00Z")

            ledger.upsertFundingRateSnapshots(
                listOf(
                    FundingRateSnapshot(symbol, Instant.parse("2026-06-30T00:00:00Z"), BigDecimal("0.0001")),
                    FundingRateSnapshot(symbol, Instant.parse("2026-06-30T08:00:00Z"), BigDecimal("0.0002")),
                ),
            )
            ledger
                .fundingRateSnapshotsBetween(
                    symbol = symbol,
                    startAt = Instant.parse("2026-06-30T00:00:00Z"),
                    endAt = Instant.parse("2026-06-30T08:00:00Z"),
                    limit = 10,
                ).map { it.fundingRate } shouldBe listOf(BigDecimal("0.0001"), BigDecimal("0.0002"))
            database.ledgerQueries
                .selectTakerFlowBarsBetween("BTCUSDT", "2026-06-30T00:00:00Z", "2026-06-30T00:02:00Z", 10)
                .executeAsList()
                .size shouldBe 3
        }

        "records and reads paper trading audit events" {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            LedgerDatabase.Schema.create(driver)
            val database = createLedgerDatabase(driver)
            val ledger = SqlDelightLedger(database = database)
            val createdAt = Instant.parse("2026-06-30T00:04:00Z")

            val signalId =
                ledger.recordSignal(
                    PaperSignalRecord(
                        strategy = "mean-reversion-v1",
                        symbol = Symbol("BTCUSDT"),
                        side = Side.BUY,
                        score = 88,
                        grade = "A",
                        reasonCodes = listOf("TEST_EDGE"),
                        accepted = true,
                        rejectionReason = null,
                        createdAt = createdAt,
                    ),
                )
            val orderId =
                ledger.recordOrder(
                    PaperOrderRecord(
                        clientOrderId = "paper-test-1",
                        signalId = signalId,
                        side = Side.BUY,
                        orderType = OrderType.MARKET,
                        orderStatus = OrderStatus.FILLED,
                        intendedRisk = BigDecimal("50"),
                        createdAt = createdAt,
                    ),
                )
            ledger.recordFill(
                PaperFillRecord(
                    orderId = orderId,
                    fillPrice = BigDecimal("100"),
                    quantity = BigDecimal("0.5"),
                    fee = BigDecimal("0.03"),
                    liquidityRole = "PAPER",
                    filledAt = createdAt,
                ),
            )
            ledger.recordPosition(
                PaperPositionRecord(
                    symbol = Symbol("BTCUSDT"),
                    side = Side.BUY,
                    quantity = BigDecimal("0.5"),
                    entryPrice = BigDecimal("100"),
                    realizedPnl = BigDecimal("-0.03"),
                    unrealizedPnl = BigDecimal.ZERO,
                    capturedAt = createdAt,
                ),
            )
            ledger.recordPerformanceSnapshot(
                PaperPerformanceSnapshot(
                    period = "paper-runtime",
                    netPnl = BigDecimal("-0.03"),
                    profitFactor = null,
                    expectancy = null,
                    maxDrawdown = BigDecimal.ZERO,
                    capturedAt = createdAt,
                ),
            )

            ledger.recentSignals(10).single().id shouldBe signalId
            ledger.recentTrades(10).single().orderId shouldBe orderId
            ledger.latestPerformanceSummary()?.netPnl shouldBe BigDecimal("-0.03")
        }

        "records market checkpoints closed trades and live performance projections idempotently" {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            LedgerDatabase.Schema.create(driver)
            val database = createLedgerDatabase(driver)
            val ledger = SqlDelightLedger(database = database)
            val symbol = Symbol("BTCUSDT")

            ledger.upsertCheckpoint(
                MarketSyncCheckpoint(
                    symbol = symbol,
                    timeframe = Timeframe.M5,
                    latestClosedOpenedAt = Instant.parse("2026-06-30T00:00:00Z"),
                    lastSyncAt = Instant.parse("2026-06-30T00:05:01Z"),
                    lastSyncStatus = MarketSyncStatus.SUCCESS,
                    consecutiveRateLimitCount = 0,
                ),
            )
            ledger.checkpoints(symbol).single().latestClosedOpenedAt shouldBe Instant.parse("2026-06-30T00:00:00Z")

            val closure =
                ExecutionTradeClosure(
                    mode = ExecutionRuntimeMode.LIVE,
                    symbol = symbol,
                    side = Side.BUY,
                    openedAt = Instant.parse("2026-06-30T00:00:00Z"),
                    closedAt = Instant.parse("2026-06-30T00:10:00Z"),
                    entryPrice = BigDecimal("100"),
                    exitPrice = BigDecimal("105"),
                    quantity = BigDecimal("1"),
                    grossPnl = BigDecimal("5.12"),
                    fees = BigDecimal("0.12"),
                    netPnl = BigDecimal("5"),
                    exitReason = "TAKE_PROFIT",
                    exchangeOrderId = "ex-1",
                    clientOrderId = "client-1",
                )
            ledger.recordTradeClosure(closure) shouldBe 1L
            ledger.recordTradeClosure(closure) shouldBe null
            ledger.closedTrades(symbol, ExecutionRuntimeMode.LIVE, 10, null).single().netPnl shouldBe BigDecimal("5")

            ledger.recordLivePerformanceSnapshot(
                LivePerformanceSnapshot(
                    mode = ExecutionRuntimeMode.LIVE,
                    window = LivePerformanceWindow.ALL,
                    tradeCount = 1,
                    winRatePct = BigDecimal("100"),
                    grossProfit = BigDecimal("5"),
                    grossLoss = BigDecimal.ZERO,
                    fees = BigDecimal("0.12"),
                    netPnl = BigDecimal("5"),
                    profitFactor = null,
                    expectancy = BigDecimal("5"),
                    maxClosedTradeDrawdownPct = BigDecimal.ZERO,
                    lastClosedAt = closure.closedAt,
                    capturedAt = Instant.parse("2026-06-30T00:11:00Z"),
                    accountEquity = BigDecimal("1005"),
                    accountPeakEquity = BigDecimal("1005"),
                    maxAccountDrawdownPct = BigDecimal.ZERO,
                    accountEquityCapturedAt = Instant.parse("2026-06-30T00:11:00Z"),
                ),
            )
            val performance = ledger.latestLivePerformanceSummary(ExecutionRuntimeMode.LIVE, LivePerformanceWindow.ALL)
            performance?.tradeCount shouldBe 1
            performance?.accountEquity shouldBe BigDecimal("1005")
            performance?.maxAccountDrawdownPct shouldBe BigDecimal.ZERO

            ledger.recordAccountSnapshot(
                ExecutionAccountSnapshot(
                    mode = ExecutionRuntimeMode.LIVE,
                    accountType = "UNIFIED",
                    totalEquity = BigDecimal("1000"),
                    totalWalletBalance = BigDecimal("1000"),
                    totalMarginBalance = BigDecimal("1000"),
                    totalAvailableBalance = BigDecimal("900"),
                    totalPerpUnrealizedPnl = BigDecimal.ZERO,
                    capturedAt = Instant.parse("2026-06-30T00:00:00Z"),
                    totalInitialMargin = BigDecimal("50"),
                    totalMaintenanceMargin = BigDecimal("20"),
                    trackedCoin = "USDT",
                    trackedCoinEquity = BigDecimal("1000"),
                    trackedCoinWalletBalance = BigDecimal("990"),
                    trackedCoinUnrealizedPnl = BigDecimal("10"),
                    trackedCoinCumulativeRealizedPnl = BigDecimal("25"),
                ),
            ) shouldBe 1L
            ledger.accountSnapshots(ExecutionRuntimeMode.LIVE, null).single().totalEquity shouldBe BigDecimal("1000")
            ledger
                .latestAccountSnapshot(ExecutionRuntimeMode.LIVE, Instant.parse("2026-06-30T00:05:00Z"))
                ?.totalEquity shouldBe BigDecimal("1000")
            ledger.accountSnapshots(ExecutionRuntimeMode.LIVE, null).single().trackedCoinWalletBalance shouldBe BigDecimal("990")

            val riskState =
                ExecutionRiskState(
                    mode = ExecutionRuntimeMode.LIVE,
                    peakEquity = BigDecimal("1010"),
                    utcDayStartedAt = Instant.parse("2026-06-30T00:00:00Z"),
                    dayStartEquity = BigDecimal("1000"),
                    latestEquity = BigDecimal("995"),
                    consecutiveLosses = 2,
                    lastClosureId = 7,
                    updatedAt = Instant.parse("2026-06-30T00:12:00Z"),
                )
            ledger.upsertExecutionRiskState(riskState)
            ledger.executionRiskState(ExecutionRuntimeMode.LIVE) shouldBe riskState

            val transactionEvent =
                ExecutionAccountTransactionEvent(
                    mode = ExecutionRuntimeMode.LIVE,
                    transaction =
                        ExchangeAccountTransaction(
                            transactionId = "transaction-1",
                            symbol = symbol,
                            category = "linear",
                            side = Side.BUY,
                            transactionAt = Instant.parse("2026-06-30T00:10:30Z"),
                            type = "TRADE",
                            subtype = null,
                            quantity = BigDecimal("0.1"),
                            size = BigDecimal("0.1"),
                            currency = "USDT",
                            tradePrice = BigDecimal("60000"),
                            funding = BigDecimal.ZERO,
                            fee = BigDecimal("3.6"),
                            cashFlow = BigDecimal("10"),
                            change = BigDecimal("6.4"),
                            cashBalance = BigDecimal("996.4"),
                            feeRate = BigDecimal("0.0006"),
                            tradeId = "trade-1",
                            exchangeOrderId = "exchange-1",
                            clientOrderId = "client-1",
                        ),
                    receivedAt = Instant.parse("2026-06-30T00:11:00Z"),
                )
            ledger.recordAccountTransaction(transactionEvent) shouldBe 1L
            ledger.recordAccountTransaction(transactionEvent) shouldBe null
            ledger
                .latestAccountTransaction(ExecutionRuntimeMode.LIVE, "USDT")
                ?.transaction shouldBe transactionEvent.transaction
            ledger
                .accountTransactions(
                    mode = ExecutionRuntimeMode.LIVE,
                    currency = "USDT",
                    transactionAtOrAfter = Instant.parse("2026-06-30T00:10:00Z"),
                    transactionAtOrBefore = Instant.parse("2026-06-30T00:11:00Z"),
                ).size shouldBe 1
            ledger
                .accountTransactionsAfterId(
                    mode = ExecutionRuntimeMode.LIVE,
                    currency = "USDT",
                    afterId = null,
                    transactionAtOrBefore = Instant.parse("2026-06-30T00:11:00Z"),
                ).single()
                .id shouldBe 1L
            ledger
                .accountTransactionsAfterId(
                    mode = ExecutionRuntimeMode.LIVE,
                    currency = "USDT",
                    afterId = 1,
                    transactionAtOrBefore = Instant.parse("2026-06-30T00:11:00Z"),
                ) shouldBe emptyList()

            val reconciliationState =
                ExecutionWalletReconciliationState(
                    mode = ExecutionRuntimeMode.LIVE,
                    currency = "USDT",
                    status = ExecutionWalletReconciliationStatus.MATCHED,
                    baselineSnapshotId = 1,
                    baselineCapturedAt = Instant.parse("2026-06-30T00:00:00Z"),
                    baselineWalletBalance = BigDecimal("990"),
                    currentSnapshotId = 2,
                    currentCapturedAt = Instant.parse("2026-06-30T00:11:00Z"),
                    currentWalletBalance = BigDecimal("996.4"),
                    observedWalletChange = BigDecimal("6.4"),
                    ledgerChange = BigDecimal("6.4"),
                    difference = BigDecimal.ZERO,
                    tolerance = BigDecimal("0.01"),
                    consecutiveMismatches = 0,
                    lastMatchedAt = Instant.parse("2026-06-30T00:11:01Z"),
                    reconciledAt = Instant.parse("2026-06-30T00:11:01Z"),
                )
            ledger.upsertWalletReconciliationState(reconciliationState)
            ledger.walletReconciliationState(ExecutionRuntimeMode.LIVE, "USDT") shouldBe reconciliationState
        }

        "deduplicates nullable closure ids at the database identity key" {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            LedgerDatabase.Schema.create(driver)
            val ledger = SqlDelightLedger(database = createLedgerDatabase(driver))
            val closure = nullableIdClosure()

            ledger.recordTradeClosure(closure) shouldBe 1L
            ledger.recordTradeClosure(closure) shouldBe null

            ledger.closedTrades(closure.symbol, closure.mode, 10, null).size shouldBe 1
            val pending = ledger.pendingClosureAlerts(closure.mode, closure.symbol, 10).single()
            pending.attemptCount shouldBe 0
            pending.lastAttemptAt shouldBe null
            ledger.recordClosureAlertAttempt(
                closureId = pending.closure.id,
                attemptedAt = Instant.parse("2026-06-30T00:11:00Z"),
                delivered = false,
            )
            ledger.pendingClosureAlerts(closure.mode, closure.symbol, 10).single().attemptCount shouldBe 1
            ledger.recordClosureAlertAttempt(
                closureId = pending.closure.id,
                attemptedAt = Instant.parse("2026-06-30T00:16:00Z"),
                delivered = true,
            )
            ledger.pendingClosureAlerts(closure.mode, closure.symbol, 10) shouldBe emptyList()

            val freshColumns = executionClosureColumnDefaults(driver)
            freshColumns.keys.containsAll(setOf("delivered_at", "suppressed_at", "attempt_count", "last_attempt_at")) shouldBe true
            freshColumns["attempt_count"] shouldBe "0"
        }

        "stores append-only execution lifecycle events and deduplicates retries" {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            LedgerDatabase.Schema.create(driver)
            val ledger = SqlDelightLedger(database = createLedgerDatabase(driver))
            val event =
                ExecutionLifecycleEvent(
                    mode = ExecutionRuntimeMode.LIVE,
                    lifecycleId = "bt-BTCUSDT-entry-1",
                    symbol = Symbol("BTCUSDT"),
                    state = ExecutionLifecycleState.ENTRY_SUBMITTED,
                    side = Side.BUY,
                    requestedQuantity = BigDecimal("0.001"),
                    filledQuantity = null,
                    fillVwap = null,
                    takeProfit = BigDecimal("65000"),
                    stopLoss = BigDecimal("62000"),
                    exchangeOrderId = "exchange-1",
                    clientOrderId = "client-1",
                    reasonCode = "AUTOMATIC_ENTRY_SUBMITTED",
                    occurredAt = Instant.parse("2026-06-30T00:00:00Z"),
                    protectionRequired = true,
                    plannedEntryPrice = BigDecimal("64000"),
                    structuralStopPrice = BigDecimal("62000"),
                    entryAnchoredStopDistance = BigDecimal("2000"),
                    expectedR = BigDecimal("1.5"),
                    protectionDeadlineAt = Instant.parse("2026-06-30T00:02:00Z"),
                    fixedTargetEnabled = false,
                    intendedRisk = BigDecimal("10"),
                )

            ledger.recordLifecycleEvent(event) shouldBe 1L
            ledger.recordLifecycleEvent(event) shouldBe null

            val stored = ledger.latestLifecycleEvent(ExecutionRuntimeMode.LIVE, Symbol("BTCUSDT"))
            stored?.id shouldBe 1L
            stored?.state shouldBe ExecutionLifecycleState.ENTRY_SUBMITTED
            stored?.takeProfit shouldBe BigDecimal("65000")
            stored?.protectionRequired shouldBe true
            stored?.plannedEntryPrice shouldBe BigDecimal("64000")
            stored?.protectionDeadlineAt shouldBe Instant.parse("2026-06-30T00:02:00Z")
            stored?.fixedTargetEnabled shouldBe false
            stored?.intendedRisk shouldBe BigDecimal("10")
            ledger.lifecycleEvents(ExecutionRuntimeMode.LIVE, Symbol("BTCUSDT"), 10).size shouldBe 1
        }

        "stores exchange fills once by execution id and preserves private metadata" {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            LedgerDatabase.Schema.create(driver)
            val ledger = SqlDelightLedger(database = createLedgerDatabase(driver))
            val event =
                ExecutionFillEvent(
                    mode = ExecutionRuntimeMode.LIVE,
                    fill =
                        ExchangeExecutionFill(
                            executionId = "exec-1",
                            exchangeOrderId = "order-1",
                            clientOrderId = "bt-BTCUSDT-entry-1",
                            symbol = Symbol("BTCUSDT"),
                            side = Side.BUY,
                            price = BigDecimal("64000.5"),
                            quantity = BigDecimal("0.001"),
                            fee = BigDecimal("0.0352"),
                            executedAt = Instant.parse("2026-06-30T00:00:01Z"),
                            executionType = "Trade",
                            createType = "CreateByUser",
                            stopOrderType = "UNKNOWN",
                            closedSize = BigDecimal.ZERO,
                            executionPnl = BigDecimal.ZERO,
                        ),
                    receivedAt = Instant.parse("2026-06-30T00:00:02Z"),
                )

            ledger.recordExecutionFill(event) shouldBe 1L
            ledger.recordExecutionFill(event.copy(receivedAt = Instant.parse("2026-06-30T00:00:03Z"))) shouldBe null

            val stored =
                ledger
                    .executionFills(
                        mode = ExecutionRuntimeMode.LIVE,
                        symbol = Symbol("BTCUSDT"),
                        executedAtOrAfter = Instant.parse("2026-06-30T00:00:00Z"),
                        limit = 10,
                    ).single()
            stored.id shouldBe 1L
            stored.fill.executionId shouldBe "exec-1"
            stored.fill.createType shouldBe "CreateByUser"
            stored.fill.fee shouldBe BigDecimal("0.0352")
            stored.receivedAt shouldBe Instant.parse("2026-06-30T00:00:02Z")
        }

        "trend live projection stores account equity and deduplicated exact fills" {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            LedgerDatabase.Schema.create(driver)
            val ledger = SqlDelightLedger(database = createLedgerDatabase(driver))
            val ownedClientOrderId = "vct-entry-live-order-001"
            persistTrendOrderOwnership(ledger, ownedClientOrderId)
            val ownership = trendOrderOwnership(ledger)
            val sink =
                LedgerVolumeConfirmedTrendLiveProjectionSink(
                    store = ledger,
                    runtimeMode = ExecutionRuntimeMode.LIVE,
                    ownedClientOrderIds = ownership::clientOrderIds,
                )
            val capturedAt = Instant.parse("2026-08-08T00:00:00Z")
            val balance =
                ExchangeAccountBalance(
                    accountType = "UNIFIED",
                    totalEquity = BigDecimal("660.50"),
                    totalWalletBalance = BigDecimal("655.25"),
                    totalMarginBalance = BigDecimal("660.50"),
                    totalAvailableBalance = BigDecimal("300.00"),
                    totalPerpUnrealizedPnl = BigDecimal("5.25"),
                    totalInitialMargin = BigDecimal("350.00"),
                    totalMaintenanceMargin = BigDecimal("10.00"),
                    coins =
                        listOf(
                            ExchangeCoinBalance(
                                coin = "USDT",
                                equity = BigDecimal("660.50"),
                                usdValue = BigDecimal("660.50"),
                                walletBalance = BigDecimal("655.25"),
                                locked = BigDecimal.ZERO,
                                unrealizedPnl = BigDecimal("5.25"),
                                cumulativeRealizedPnl = BigDecimal("20.00"),
                            ),
                        ),
                    capturedAt = capturedAt,
                )
            val fill =
                ExchangeExecutionFill(
                    executionId = "trend-exec-001",
                    exchangeOrderId = "trend-order-001",
                    clientOrderId = ownedClientOrderId,
                    symbol = Symbol("BTCUSDT"),
                    side = Side.BUY,
                    price = BigDecimal("60000"),
                    quantity = BigDecimal("0.007"),
                    fee = BigDecimal("0.252"),
                    executedAt = capturedAt,
                    executionType = "Trade",
                    executionPnl = BigDecimal.ZERO,
                )

            sink.accountSnapshotDue(capturedAt) shouldBe true
            sink.recordAccountBalance(balance)
            sink.recordExecutionFills(listOf(fill, fill), capturedAt.plusSeconds(1))

            sink.accountSnapshotDue(capturedAt.plusSeconds(30)) shouldBe false
            sink.accountSnapshotDue(capturedAt.plusSeconds(60)) shouldBe true
            ledger.latestAccountSnapshot(ExecutionRuntimeMode.LIVE, capturedAt)?.apply {
                totalEquity shouldBe BigDecimal("660.50")
                trackedCoin shouldBe "USDT"
                trackedCoinWalletBalance shouldBe BigDecimal("655.25")
            }
            ledger.executionFills(ExecutionRuntimeMode.LIVE, Symbol("BTCUSDT"), null, 10).single().apply {
                this.fill.executionId shouldBe "trend-exec-001"
                this.fill.fee shouldBe BigDecimal("0.252")
            }
        }

        "trend live accounting attributes only owned closures and reconciles USDT transactions" {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            LedgerDatabase.Schema.create(driver)
            val ledger = SqlDelightLedger(database = createLedgerDatabase(driver))
            val capturedAt = Instant.parse("2026-08-08T00:05:00Z")
            val ownedClientOrderId = "vct-exit-accounting-001"
            persistTrendOrderOwnership(ledger, ownedClientOrderId)
            val ownership = trendOrderOwnership(ledger)
            val sink =
                LedgerVolumeConfirmedTrendLiveProjectionSink(
                    store = ledger,
                    runtimeMode = ExecutionRuntimeMode.LIVE,
                    ownedClientOrderIds = ownership::clientOrderIds,
                    sessionStartedAt = capturedAt.minusSeconds(60),
                )
            sink.recordAccountBalance(
                ExchangeAccountBalance(
                    accountType = "UNIFIED",
                    totalEquity = BigDecimal("666.24"),
                    totalWalletBalance = BigDecimal("666.24"),
                    totalMarginBalance = BigDecimal("666.24"),
                    totalAvailableBalance = BigDecimal("666.24"),
                    totalPerpUnrealizedPnl = BigDecimal.ZERO,
                    totalInitialMargin = BigDecimal.ZERO,
                    totalMaintenanceMargin = BigDecimal.ZERO,
                    coins =
                        listOf(
                            ExchangeCoinBalance(
                                coin = "USDT",
                                equity = BigDecimal("666.24"),
                                usdValue = BigDecimal("666.24"),
                                walletBalance = BigDecimal("666.24"),
                                locked = BigDecimal.ZERO,
                                unrealizedPnl = BigDecimal.ZERO,
                            ),
                        ),
                    capturedAt = capturedAt,
                ),
            )
            val request = requireNotNull(sink.reserveAccountingRequest(capturedAt))
            request.closureStartAt shouldBe capturedAt.minusSeconds(360)
            val ownedFill =
                ExchangeExecutionFill(
                    executionId = "trend-accounting-exec-001",
                    exchangeOrderId = "trend-accounting-order-001",
                    clientOrderId = ownedClientOrderId,
                    symbol = Symbol("BTCUSDT"),
                    side = Side.SELL,
                    price = BigDecimal("60000"),
                    quantity = BigDecimal("0.007"),
                    fee = BigDecimal("0.25"),
                    executedAt = capturedAt.minusSeconds(1),
                    executionType = "Trade",
                    executionPnl = BigDecimal("7"),
                )
            val unrelatedFill =
                ownedFill.copy(
                    executionId = "manual-exec-001",
                    exchangeOrderId = "manual-order-001",
                    clientOrderId = "vct-spoof-unowned",
                )
            val ownedClosure =
                ExchangeClosedPnl(
                    exchangeOrderId = ownedFill.exchangeOrderId,
                    clientOrderId = null,
                    symbol = Symbol("BTCUSDT"),
                    side = Side.BUY,
                    openedAt = capturedAt.minusSeconds(14_400),
                    closedAt = capturedAt.minusSeconds(1),
                    entryPrice = BigDecimal("59000"),
                    exitPrice = BigDecimal("60000"),
                    quantity = BigDecimal("0.007"),
                    grossPnl = BigDecimal("7.5"),
                    fees = BigDecimal("0.5"),
                    netPnl = BigDecimal("7"),
                    exitReason = "CLOSED_PNL",
                )
            val unrelatedClosure =
                ownedClosure.copy(
                    exchangeOrderId = "manual-order-001",
                    clientOrderId = "vct-spoof-unowned",
                )
            val funding =
                ExchangeAccountTransaction(
                    transactionId = "trend-funding-001",
                    symbol = Symbol("BTCUSDT"),
                    category = "linear",
                    side = Side.BUY,
                    transactionAt = capturedAt.minusSeconds(30),
                    type = "SETTLEMENT",
                    subtype = "FUNDING",
                    quantity = null,
                    size = BigDecimal("0.007"),
                    currency = "USDT",
                    tradePrice = BigDecimal("60000"),
                    funding = BigDecimal("-0.01"),
                    fee = BigDecimal.ZERO,
                    cashFlow = BigDecimal.ZERO,
                    change = BigDecimal("-0.01"),
                    cashBalance = BigDecimal("666.24"),
                    feeRate = null,
                    tradeId = null,
                    exchangeOrderId = null,
                    clientOrderId = null,
                )
            sink.recordAccounting(
                VolumeConfirmedTrendLiveAccountingObservation(
                    request = request,
                    executions = listOf(ownedFill, unrelatedFill),
                    closedPnls = listOf(ownedClosure, unrelatedClosure),
                    accountTransactions = listOf(funding, funding.copy(transactionId = "usdc-transaction", currency = "USDC")),
                    receivedAt = capturedAt,
                ),
            )

            ledger
                .executionFills(ExecutionRuntimeMode.LIVE, Symbol("BTCUSDT"), null, 10)
                .single()
                .fill
                .executionId shouldBe "trend-accounting-exec-001"
            ledger.closedTrades(Symbol("BTCUSDT"), ExecutionRuntimeMode.LIVE, 10, null).single().apply {
                clientOrderId shouldBe "vct-exit-accounting-001"
                exitReason shouldBe "STRATEGY_EXIT"
                netPnl shouldBe BigDecimal("7")
            }
            ledger.accountTransactions(ExecutionRuntimeMode.LIVE, "USDT", null, capturedAt).single().transaction.apply {
                transactionId shouldBe "trend-funding-001"
                this.funding shouldBe BigDecimal("-0.01")
            }
            ledger.walletReconciliationState(ExecutionRuntimeMode.LIVE, "USDT")?.status shouldBe
                ExecutionWalletReconciliationStatus.BASELINE
            val evidence =
                VolumeConfirmedTrendLiveEvidenceService(
                    store = ledger,
                    runtimeMode = ExecutionRuntimeMode.LIVE,
                    sessionStartedAt = capturedAt.minusSeconds(60),
                    ownedClientOrderIds = ownership::clientOrderIds,
                ).read(capturedAt, 10)
            evidence.performance.single { it.snapshot.window == LivePerformanceWindow.ALL }.apply {
                snapshot.tradeCount shouldBe 1
                snapshot.netPnl shouldBe BigDecimal("7")
                snapshot.fees shouldBe BigDecimal("0.5")
                btcFundingPnl shouldBe BigDecimal("-0.01")
            }
            evidence.recentClosures.single().clientOrderId shouldBe "vct-exit-accounting-001"
            evidence.recentExecutionFills
                .single()
                .fill.executionId shouldBe "trend-accounting-exec-001"
            evidence.recentAccountTransactions
                .single()
                .transaction.transactionId shouldBe "trend-funding-001"
            ledger.latestLivePerformanceSummary(ExecutionRuntimeMode.LIVE, LivePerformanceWindow.ALL) shouldBe null

            sink.reserveAccountingRequest(capturedAt.plusSeconds(30)) shouldBe null
            sink.reserveAccountingRequest(capturedAt.plusSeconds(60))?.apply {
                closuresDue shouldBe true
                transactionsDue shouldBe false
                closureStartAt shouldBe capturedAt.minusSeconds(300)
            }
            val failedTransactionRequest = requireNotNull(sink.reserveAccountingRequest(capturedAt.plusSeconds(300)))
            failedTransactionRequest.transactionsDue shouldBe true
            failedTransactionRequest.closureStartAt shouldBe capturedAt.minusSeconds(300)
            failedTransactionRequest.transactionStartAt shouldBe funding.transactionAt.minusSeconds(300)
            sink.recordAccountingFailure(failedTransactionRequest, capturedAt.plusSeconds(300))
            ledger.walletReconciliationState(ExecutionRuntimeMode.LIVE, "USDT")?.status shouldBe
                ExecutionWalletReconciliationStatus.SYNC_ERROR
            sink.reserveAccountingRequest(capturedAt.plusSeconds(360))?.closureStartAt shouldBe
                capturedAt.minusSeconds(300)
        }

        "trend live risk assessment blocks a reconciled account drawdown above the frozen limit" {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            LedgerDatabase.Schema.create(driver)
            val ledger = SqlDelightLedger(database = createLedgerDatabase(driver))
            val baselineAt = Instant.parse("2026-08-08T00:00:00Z")
            val currentAt = baselineAt.plusSeconds(300)
            val sink =
                LedgerVolumeConfirmedTrendLiveProjectionSink(
                    store = ledger,
                    runtimeMode = ExecutionRuntimeMode.LIVE,
                    ownedClientOrderIds = { emptySet() },
                    sessionStartedAt = baselineAt,
                )

            sink.recordAccountBalance(trendLiveBalance("666.24", baselineAt))
            val baselineRequest = requireNotNull(sink.reserveAccountingRequest(baselineAt))
            sink.recordAccounting(
                VolumeConfirmedTrendLiveAccountingObservation(
                    request = baselineRequest,
                    executions = emptyList(),
                    closedPnls = emptyList(),
                    accountTransactions = emptyList(),
                    receivedAt = baselineAt,
                ),
            )
            val baseline =
                sink.assessEntryRisk(
                    previous = null,
                    now = baselineAt,
                    policy = VolumeConfirmedTrendLiveRiskPolicy(),
                )
            baseline.reasonCodes shouldBe
                listOf(
                    "RISK_NAV_BASELINE_PENDING",
                    "ACCOUNT_RECONCILIATION_BASELINE_PENDING",
                )

            sink.recordAccountBalance(trendLiveBalance("400.00", currentAt))
            val currentRequest = requireNotNull(sink.reserveAccountingRequest(currentAt))
            val lossTransaction =
                ExchangeAccountTransaction(
                    transactionId = "trend-loss-001",
                    symbol = Symbol("BTCUSDT"),
                    category = "linear",
                    side = Side.SELL,
                    transactionAt = currentAt.minusSeconds(60),
                    type = "TRADE",
                    subtype = null,
                    quantity = BigDecimal("0.007"),
                    size = BigDecimal("0.007"),
                    currency = "USDT",
                    tradePrice = BigDecimal("60000"),
                    funding = BigDecimal.ZERO,
                    fee = BigDecimal.ZERO,
                    cashFlow = BigDecimal.ZERO,
                    change = BigDecimal("-266.24"),
                    cashBalance = BigDecimal("400.00"),
                    feeRate = null,
                    tradeId = "trend-loss-trade-001",
                    exchangeOrderId = "trend-loss-order-001",
                    clientOrderId = "vct-exit-loss-001",
                )
            sink.recordAccounting(
                VolumeConfirmedTrendLiveAccountingObservation(
                    request = currentRequest,
                    executions = emptyList(),
                    closedPnls = emptyList(),
                    accountTransactions = listOf(lossTransaction),
                    receivedAt = currentAt,
                ),
            )

            val current =
                sink.assessEntryRisk(
                    previous = baseline.state,
                    now = currentAt,
                    policy = VolumeConfirmedTrendLiveRiskPolicy(),
                )

            current.reasonCodes shouldBe listOf("ACCOUNT_DRAWDOWN_LIMIT_REACHED")
            current.state?.navStatus shouldBe ExecutionRiskNavStatus.READY
            current.state?.latestUnitizedNav shouldBe
                BigDecimal("400.00").divide(BigDecimal("666.24"), MathContext.DECIMAL128)
            ledger.walletReconciliationState(ExecutionRuntimeMode.LIVE, "USDT")?.status shouldBe
                ExecutionWalletReconciliationStatus.MATCHED
        }

        "trend live accounting failures remain entry blocking until every stream recovers" {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            LedgerDatabase.Schema.create(driver)
            val ledger = SqlDelightLedger(database = createLedgerDatabase(driver))
            val startedAt = Instant.parse("2026-08-08T00:00:00Z")
            val sink =
                LedgerVolumeConfirmedTrendLiveProjectionSink(
                    store = ledger,
                    runtimeMode = ExecutionRuntimeMode.LIVE,
                    ownedClientOrderIds = { emptySet() },
                    sessionStartedAt = startedAt,
                )
            sink.recordAccountBalance(trendLiveBalance("660", startedAt))
            val initialRequest = requireNotNull(sink.reserveAccountingRequest(startedAt))
            sink.recordAccounting(emptyAccountingObservation(initialRequest, startedAt))
            var assessment =
                sink.assessEntryRisk(
                    previous = null,
                    now = startedAt,
                    policy = VolumeConfirmedTrendLiveRiskPolicy(),
                )
            assessment.reasonCodes.contains("ACCOUNT_CLOSURE_SYNC_UNAVAILABLE") shouldBe false
            assessment.reasonCodes.contains("ACCOUNT_TRANSACTION_SYNC_UNAVAILABLE") shouldBe false

            val closureFailureAt = startedAt.plusSeconds(60)
            val closureFailure = requireNotNull(sink.reserveAccountingRequest(closureFailureAt))
            closureFailure.closuresDue shouldBe true
            closureFailure.transactionsDue shouldBe false
            sink.recordAccountingFailure(closureFailure, closureFailureAt)
            assessment =
                sink.assessEntryRisk(
                    previous = assessment.state,
                    now = closureFailureAt,
                    policy = VolumeConfirmedTrendLiveRiskPolicy(),
                )
            assessment.reasonCodes.contains("ACCOUNT_CLOSURE_SYNC_UNAVAILABLE") shouldBe true
            assessment.reasonCodes.contains("ACCOUNT_TRANSACTION_SYNC_UNAVAILABLE") shouldBe false

            val closureRecoveryAt = startedAt.plusSeconds(120)
            val closureRecovery = requireNotNull(sink.reserveAccountingRequest(closureRecoveryAt))
            sink.recordAccounting(emptyAccountingObservation(closureRecovery, closureRecoveryAt))
            assessment =
                sink.assessEntryRisk(
                    previous = assessment.state,
                    now = closureRecoveryAt,
                    policy = VolumeConfirmedTrendLiveRiskPolicy(),
                )
            assessment.reasonCodes.contains("ACCOUNT_CLOSURE_SYNC_UNAVAILABLE") shouldBe false

            val allFailureAt = startedAt.plusSeconds(300)
            val allFailure = requireNotNull(sink.reserveAccountingRequest(allFailureAt))
            allFailure.closuresDue shouldBe true
            allFailure.transactionsDue shouldBe true
            sink.recordAccountingFailure(allFailure, allFailureAt)
            assessment =
                sink.assessEntryRisk(
                    previous = assessment.state,
                    now = allFailureAt,
                    policy = VolumeConfirmedTrendLiveRiskPolicy(),
                )
            assessment.reasonCodes.contains("ACCOUNT_CLOSURE_SYNC_UNAVAILABLE") shouldBe true
            assessment.reasonCodes.contains("ACCOUNT_TRANSACTION_SYNC_UNAVAILABLE") shouldBe true

            val recoveryAt = startedAt.plusSeconds(600)
            val recovery = requireNotNull(sink.reserveAccountingRequest(recoveryAt))
            sink.recordAccounting(emptyAccountingObservation(recovery, recoveryAt))
            assessment =
                sink.assessEntryRisk(
                    previous = assessment.state,
                    now = recoveryAt,
                    policy = VolumeConfirmedTrendLiveRiskPolicy(),
                )
            assessment.reasonCodes.contains("ACCOUNT_CLOSURE_SYNC_UNAVAILABLE") shouldBe false
            assessment.reasonCodes.contains("ACCOUNT_TRANSACTION_SYNC_UNAVAILABLE") shouldBe false
        }

        "additive migration adds actual-fill protection metadata to a legacy lifecycle table" {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            driver.execute(
                null,
                """
                CREATE TABLE executionLifecycleEvents (
                  id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                  mode TEXT NOT NULL,
                  lifecycle_id TEXT NOT NULL,
                  symbol TEXT NOT NULL,
                  state TEXT NOT NULL,
                  side TEXT NOT NULL,
                  requested_quantity TEXT NOT NULL,
                  filled_quantity TEXT,
                  fill_vwap TEXT,
                  take_profit TEXT,
                  stop_loss TEXT,
                  exchange_order_id TEXT,
                  client_order_id TEXT,
                  reason_code TEXT NOT NULL,
                  occurred_at TEXT NOT NULL,
                  identity_key TEXT NOT NULL
                )
                """.trimIndent(),
                0,
            )
            driver.execute(
                null,
                """
                CREATE TABLE executionRiskStates (
                  mode TEXT NOT NULL PRIMARY KEY,
                  peak_equity TEXT NOT NULL,
                  utc_day_started_at TEXT NOT NULL,
                  day_start_equity TEXT NOT NULL,
                  latest_equity TEXT NOT NULL,
                  consecutive_losses INTEGER NOT NULL,
                  last_closure_id INTEGER,
                  updated_at TEXT NOT NULL
                )
                """.trimIndent(),
                0,
            )
            driver.execute(
                null,
                """
                INSERT INTO executionRiskStates(
                  mode, peak_equity, utc_day_started_at, day_start_equity, latest_equity,
                  consecutive_losses, last_closure_id, updated_at
                ) VALUES (
                  'LIVE', '110', '2026-06-30T00:00:00Z', '100', '95', 2, 7,
                  '2026-06-30T00:10:00Z'
                )
                """.trimIndent(),
                0,
            )

            ensureAdditiveLedgerSchema(driver)

            tableColumnNames(driver, "executionLifecycleEvents").containsAll(
                setOf(
                    "protection_required",
                    "planned_entry_price",
                    "structural_stop_price",
                    "entry_anchored_stop_distance",
                    "expected_r",
                    "protection_deadline_at",
                    "fixed_target_enabled",
                    "intended_risk",
                ),
            ) shouldBe true
            tableColumnNames(driver, "executionFillEvents").containsAll(
                setOf("execution_id", "executed_at", "received_at", "identity_key"),
            ) shouldBe true
            tableColumnNames(driver, "executionRiskStates").containsAll(
                setOf(
                    "nav_status",
                    "strategy_units",
                    "latest_unitized_nav",
                    "peak_unitized_nav",
                    "day_start_unitized_nav",
                    "cumulative_external_cash_flow",
                    "last_account_transaction_id",
                ),
            ) shouldBe true
            val migratedRisk = SqlDelightLedger(createLedgerDatabase(driver)).executionRiskState(ExecutionRuntimeMode.LIVE)
            migratedRisk?.navStatus shouldBe ExecutionRiskNavStatus.UNAVAILABLE
            migratedRisk?.latestEquity shouldBe BigDecimal("95")
        }

        "performance closure query is not capped by API page size" {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            LedgerDatabase.Schema.create(driver)
            val ledger = SqlDelightLedger(database = createLedgerDatabase(driver))
            val base = nullableIdClosure()
            repeat(120) { index ->
                ledger.recordTradeClosure(
                    base.copy(
                        closedAt = base.closedAt.plusSeconds(index.toLong()),
                        exchangeOrderId = "performance-$index",
                    ),
                )
            }

            ledger.performanceClosures(ExecutionRuntimeMode.LIVE, null).size shouldBe 120
        }

        "additive migration backfills identity and removes nullable id duplicates" {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            driver.execute(
                null,
                """
                CREATE TABLE executionTradeClosures (
                  id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                  mode TEXT NOT NULL,
                  symbol TEXT NOT NULL,
                  side TEXT NOT NULL,
                  opened_at TEXT NOT NULL,
                  closed_at TEXT NOT NULL,
                  entry_price TEXT NOT NULL,
                  exit_price TEXT NOT NULL,
                  quantity TEXT NOT NULL,
                  gross_pnl TEXT NOT NULL,
                  fees TEXT NOT NULL,
                  net_pnl TEXT NOT NULL,
                  exit_reason TEXT NOT NULL,
                  exchange_order_id TEXT,
                  client_order_id TEXT
                )
                """.trimIndent(),
                0,
            )
            repeat(2) {
                driver.execute(
                    null,
                    """
                    INSERT INTO executionTradeClosures(
                      mode, symbol, side, opened_at, closed_at, entry_price, exit_price, quantity,
                      gross_pnl, fees, net_pnl, exit_reason, exchange_order_id, client_order_id
                    ) VALUES (
                      'LIVE', 'BTCUSDT', 'BUY', '2026-06-30T00:00:00Z', '2026-06-30T00:10:00Z',
                      '100', '105', '1', '5.12', '0.12', '5', 'TAKE_PROFIT', NULL, NULL
                    )
                    """.trimIndent(),
                    0,
                )
            }

            ensureAdditiveLedgerSchema(driver)
            val ledger = SqlDelightLedger(database = createLedgerDatabase(driver))

            ledger.closedTrades(Symbol("BTCUSDT"), ExecutionRuntimeMode.LIVE, 10, null).size shouldBe 1
            ledger.recordTradeClosure(nullableIdClosure()) shouldBe null
            ledger.pendingClosureAlerts(ExecutionRuntimeMode.LIVE, Symbol("BTCUSDT"), 10) shouldBe emptyList()
            val migratedColumns = executionClosureColumnDefaults(driver)
            migratedColumns.keys.containsAll(setOf("delivered_at", "suppressed_at", "attempt_count", "last_attempt_at")) shouldBe true
            migratedColumns["attempt_count"] shouldBe "0"
            val migratedState = executionClosureAlertState(driver)
            migratedState.deliveredAt shouldBe null
            (migratedState.suppressedAt != null) shouldBe true
            migratedState.attemptCount shouldBe 0L
            migratedState.lastAttemptAt shouldBe null
        }

        "additive migration creates flow tables on legacy database" {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            driver.execute(
                null,
                """
                CREATE TABLE executionTradeClosures (
                  id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                  mode TEXT NOT NULL,
                  symbol TEXT NOT NULL,
                  side TEXT NOT NULL,
                  opened_at TEXT NOT NULL,
                  closed_at TEXT NOT NULL,
                  entry_price TEXT NOT NULL,
                  exit_price TEXT NOT NULL,
                  quantity TEXT NOT NULL,
                  gross_pnl TEXT NOT NULL,
                  fees TEXT NOT NULL,
                  net_pnl TEXT NOT NULL,
                  exit_reason TEXT NOT NULL,
                  exchange_order_id TEXT,
                  client_order_id TEXT,
                  identity_key TEXT NOT NULL
                )
                """.trimIndent(),
                0,
            )

            ensureAdditiveLedgerSchema(driver)
            val ledger = SqlDelightLedger(database = createLedgerDatabase(driver))
            val symbol = Symbol("BTCUSDT")

            ledger.upsertTakerFlowBars(listOf(sampleTakerFlowBar(symbol, "2026-06-30T00:00:00Z", "1", "2")))
            ledger
                .takerFlowBarsBetween(
                    symbol = symbol,
                    startAt = Instant.parse("2026-06-30T00:00:00Z"),
                    endAt = Instant.parse("2026-06-30T00:00:00Z"),
                    limit = 1,
                ).single()
                .takerSellBase shouldBe BigDecimal("2")
            tableNames(driver).containsAll(
                setOf(
                    "takerFlowBars",
                    "openInterestSnapshots",
                    "accountRatioSnapshots",
                    "orderBookImbalanceBars",
                    "liquidationFlowBars",
                    "premiumIndexBars",
                    "fundingRates",
                    "executionLifecycleEvents",
                    "executionAccountSnapshots",
                    "executionRiskStates",
                    "executionAccountTransactions",
                    "executionPositionRuntimeStates",
                    "paperRuntimeStates",
                    "makerShadowEvents",
                    "volumeConfirmedTrendShadowStates",
                    "volumeConfirmedTrendShadowEvents",
                    "volumeConfirmedTrendLiveStates",
                    "volumeConfirmedTrendLiveEvents",
                ),
            ) shouldBe true
        }

        "paper runtime state round trips an open causal position" {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            LedgerDatabase.Schema.create(driver)
            val ledger = SqlDelightLedger(database = createLedgerDatabase(driver))
            val state = samplePaperRuntimeState()

            ledger.upsertPaperRuntimeState(state)
            val restored = ledger.paperRuntimeState(state.strategy, state.symbol, state.timeframe)

            restored shouldBe state
        }

        "execution position runtime state round trips and can be deleted" {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            LedgerDatabase.Schema.create(driver)
            val ledger = SqlDelightLedger(database = createLedgerDatabase(driver))
            val symbol = Symbol("BTCUSDT")
            val state =
                ExecutionPositionRuntimeState(
                    mode = ExecutionRuntimeMode.TESTNET,
                    lifecycleId = "auto-BTCUSDT-1",
                    symbol = symbol,
                    timeframe = Timeframe.M5,
                    lastProcessedCandleAt = Instant.parse("2026-06-30T00:20:00Z"),
                    policyState = requireNotNull(samplePaperRuntimeState().openPosition).policyState,
                    updatedAt = Instant.parse("2026-06-30T00:21:00Z"),
                )

            ledger.upsertExecutionPositionRuntimeState(state)
            ledger.executionPositionRuntimeState(state.mode, symbol) shouldBe state

            ledger.deleteExecutionPositionRuntimeState(state.mode, symbol)
            ledger.executionPositionRuntimeState(state.mode, symbol) shouldBe null
        }

        "trend shadow state and idempotent events commit atomically" {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            LedgerDatabase.Schema.create(driver)
            val ledger = SqlDelightLedger(database = createLedgerDatabase(driver))
            val state = sampleTrendShadowState()
            val event = sampleTrendShadowEvent(state)
            val previousEvent =
                event.copy(
                    eventId = "c".repeat(64),
                    sessionId = "trend-shadow-ledger-previous",
                    eventAt = event.eventAt.minusSeconds(14_400),
                    observedAt = event.observedAt.minusSeconds(14_400),
                )

            ledger.commitTrendShadow(state, listOf(previousEvent, event))
            ledger.commitTrendShadow(state, listOf(previousEvent, event))

            ledger.trendShadowState(state.protocolId, state.symbol) shouldBe state
            ledger.trendShadowEvents(state.sessionId, 10) shouldBe listOf(event)
            ledger.trendShadowEvents(state.protocolId, state.symbol, 10) shouldBe listOf(previousEvent, event)
            ledger.trendShadowSnapshot(state.protocolId, state.symbol, 10).let { snapshot ->
                snapshot.state shouldBe state
                snapshot.recentEvents shouldBe listOf(event)
            }
        }

        "trend live state and idempotent events commit atomically" {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            LedgerDatabase.Schema.create(driver)
            val ledger = SqlDelightLedger(database = createLedgerDatabase(driver))
            val state = sampleTrendLiveState()
            val event = sampleTrendLiveEvent(state)

            ledger.commitTrendLive(state, listOf(event))
            ledger.commitTrendLive(state, listOf(event))

            ledger.trendLiveState(state.protocolId, state.symbol) shouldBe state
            ledger.trendLiveEvents(state.protocolId, state.symbol, 10) shouldBe listOf(event)
        }

        "trend live state schema v1 remains readable with a missing risk baseline" {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            LedgerDatabase.Schema.create(driver)
            val database = createLedgerDatabase(driver)
            val ledger = SqlDelightLedger(database = database)
            val protocolId = "volume-confirmed-trend-ensemble-v1"
            database.ledgerQueries.upsertVolumeConfirmedTrendLiveState(
                protocol_id = protocolId,
                candidate_id = "vcte_4h_majority_001",
                protocol_sha256 = "a".repeat(64),
                symbol = "BTCUSDT",
                status = VolumeConfirmedTrendLiveStatus.FLAT.name,
                state_payload = """{"schemaVersion":1,"approvalId":"approval-v1"}""",
                updated_at = "2026-08-07T00:00:00Z",
            )

            ledger.trendLiveState(protocolId, Symbol("BTCUSDT"))?.apply {
                status shouldBe VolumeConfirmedTrendLiveStatus.FLAT
                approvalId shouldBe "approval-v1"
                riskState shouldBe null
            }
        }
    })

private data class StoredClosureAlertState(
    val deliveredAt: String?,
    val suppressedAt: String?,
    val attemptCount: Long,
    val lastAttemptAt: String?,
)

private fun sampleTrendLiveState(): VolumeConfirmedTrendLiveState =
    VolumeConfirmedTrendLiveState(
        protocolId = "volume-confirmed-trend-ensemble-v1",
        candidateId = "vcte_4h_majority_001",
        protocolSha256 = "a".repeat(64),
        symbol = Symbol("BTCUSDT"),
        status = VolumeConfirmedTrendLiveStatus.ENTRY_SUBMITTED,
        approvalId = "human-approval-test",
        activeDecisionKey = "decision-2026-08-07T00:00:00Z-BUY",
        pendingTargetSide = Side.BUY,
        clientOrderId = "vct-e-b-1786060800-12345678",
        exchangeOrderId = "exchange-order-1",
        observedPositionSide = null,
        observedPositionQuantity = null,
        lastExecutionId = null,
        haltedReasonCode = null,
        riskState =
            ExecutionRiskState(
                mode = ExecutionRuntimeMode.LIVE,
                peakEquity = BigDecimal("700"),
                utcDayStartedAt = Instant.parse("2026-08-07T00:00:00Z"),
                dayStartEquity = BigDecimal("680"),
                latestEquity = BigDecimal("660"),
                consecutiveLosses = 1,
                lastClosureId = 7,
                updatedAt = Instant.parse("2026-08-07T00:00:01Z"),
                navStatus = ExecutionRiskNavStatus.READY,
                strategyUnits = BigDecimal("660"),
                latestUnitizedNav = BigDecimal("0.97"),
                peakUnitizedNav = BigDecimal.ONE,
                dayStartUnitizedNav = BigDecimal("0.99"),
                cumulativeExternalCashFlow = BigDecimal("20"),
                lastAccountTransactionId = 9,
            ),
        riskReasonCodes = listOf("ACCOUNT_LEDGER_MISMATCH_PENDING"),
        updatedAt = Instant.parse("2026-08-07T00:00:02Z"),
    )

private fun trendOrderOwnership(ledger: SqlDelightLedger): PersistedVolumeConfirmedTrendLiveOrderOwnership =
    PersistedVolumeConfirmedTrendLiveOrderOwnership(
        store = ledger,
        protocolId = "volume-confirmed-trend-ensemble-v1",
        protocolSha256 = "a".repeat(64),
        symbol = Symbol("BTCUSDT"),
    )

private suspend fun persistTrendOrderOwnership(
    ledger: SqlDelightLedger,
    clientOrderId: String,
) {
    val state =
        sampleTrendLiveState().copy(
            clientOrderId = clientOrderId,
            exchangeOrderId = "exchange-$clientOrderId",
        )
    val event =
        sampleTrendLiveEvent(state).copy(
            eventId = "event-$clientOrderId",
            clientOrderId = clientOrderId,
            exchangeOrderId = state.exchangeOrderId,
        )
    ledger.commitTrendLive(state, listOf(event))
}

private fun trendLiveBalance(
    amount: String,
    capturedAt: Instant,
): ExchangeAccountBalance =
    ExchangeAccountBalance(
        accountType = "UNIFIED",
        totalEquity = BigDecimal(amount),
        totalWalletBalance = BigDecimal(amount),
        totalMarginBalance = BigDecimal(amount),
        totalAvailableBalance = BigDecimal(amount),
        totalPerpUnrealizedPnl = BigDecimal.ZERO,
        totalInitialMargin = BigDecimal.ZERO,
        totalMaintenanceMargin = BigDecimal.ZERO,
        coins =
            listOf(
                ExchangeCoinBalance(
                    coin = "USDT",
                    equity = BigDecimal(amount),
                    usdValue = BigDecimal(amount),
                    walletBalance = BigDecimal(amount),
                    locked = BigDecimal.ZERO,
                    unrealizedPnl = BigDecimal.ZERO,
                ),
            ),
        capturedAt = capturedAt,
    )

private fun emptyAccountingObservation(
    request: VolumeConfirmedTrendLiveAccountingRequest,
    receivedAt: Instant,
): VolumeConfirmedTrendLiveAccountingObservation =
    VolumeConfirmedTrendLiveAccountingObservation(
        request = request,
        executions = emptyList(),
        closedPnls = emptyList(),
        accountTransactions = emptyList(),
        receivedAt = receivedAt,
    )

private fun sampleTrendLiveEvent(state: VolumeConfirmedTrendLiveState): VolumeConfirmedTrendLiveEvent =
    VolumeConfirmedTrendLiveEvent(
        eventId = "trend-live-event-1",
        protocolId = state.protocolId,
        protocolSha256 = state.protocolSha256,
        symbol = state.symbol,
        decisionKey = state.activeDecisionKey,
        type = VolumeConfirmedTrendLiveEventType.ENTRY_SUBMITTED,
        targetSide = Side.BUY,
        orderSide = Side.BUY,
        orderQuantity = BigDecimal("0.007"),
        referencePrice = BigDecimal("60000"),
        limitPrice = BigDecimal("60012"),
        clientOrderId = state.clientOrderId,
        exchangeOrderId = state.exchangeOrderId,
        executionId = null,
        reasonCode = "TARGET_POSITION_ENTRY_SUBMITTED",
        occurredAt = state.updatedAt,
    )

private fun sampleTrendShadowState(): VolumeConfirmedTrendShadowState =
    VolumeConfirmedTrendShadowState(
        protocolId = "volume-confirmed-trend-ensemble-v1",
        candidateId = "vcte_4h_majority_001",
        protocolSha256 = "a".repeat(64),
        symbol = Symbol("BTCUSDT"),
        sessionId = "trend-shadow-ledger-test",
        status = VolumeConfirmedTrendShadowStatus.OBSERVING,
        sessionStartedAt = Instant.parse("2026-08-07T00:00:10Z"),
        indicatorState =
            VolumeConfirmedTrendIndicatorState(
                processedBars = 540,
                lastBarOpenedAt = Instant.parse("2026-08-06T20:00:00Z"),
                emaStates =
                    listOf(
                        VolumeConfirmedTrendEmaState(100.0, 99.0),
                        VolumeConfirmedTrendEmaState(101.0, 98.0),
                    ),
                targetSide = Side.BUY,
                recentVolumes = listOf(10.0, 20.0),
            ),
        lastAppliedFundingAt = Instant.parse("2026-08-07T00:00:00Z"),
        lastObservedAt = Instant.parse("2026-08-07T00:00:10Z"),
        position =
            VolumeConfirmedTrendShadowPosition(
                side = Side.BUY,
                quantity = 0.001,
                entryAt = Instant.parse("2026-08-07T00:00:00Z"),
                entryPrice = 60_012.0,
                entryFee = 0.0360072,
                fundingPnl = -0.001,
            ),
        sessionStartingEquity = 100.0,
        cash = 99.9639928,
        equity = 99.952,
        peakEquity = 101.0,
        maximumDrawdownPct = 1.0376237624,
        totalFees = 0.0360072,
        totalSlippage = 0.012,
        totalFundingPnl = -0.001,
        closedTrades = 0,
        executedTransitions = 1,
        invalidatedSessionCount = 0,
        updatedAt = Instant.parse("2026-08-07T00:00:10Z"),
    )

private fun sampleTrendShadowEvent(state: VolumeConfirmedTrendShadowState): VolumeConfirmedTrendShadowEvent =
    VolumeConfirmedTrendShadowEvent(
        eventId = "b".repeat(64),
        sessionId = state.sessionId,
        protocolId = state.protocolId,
        protocolSha256 = state.protocolSha256,
        symbol = state.symbol,
        type = VolumeConfirmedTrendShadowEventType.POSITION_OPENED,
        eventAt = Instant.parse("2026-08-07T00:00:00Z"),
        observedAt = Instant.parse("2026-08-07T00:00:10Z"),
        h4OpenedAt = Instant.parse("2026-08-06T20:00:00Z"),
        side = Side.BUY,
        referencePrice = 60_000.0,
        fillPrice = 60_012.0,
        quantity = 0.001,
        fee = 0.0360072,
        slippage = 0.012,
        fundingPnl = 0.0,
        grossPnl = 0.0,
        netPnl = -0.0360072,
        cash = state.cash,
        equity = state.equity,
        reason = "VOLUME_CONFIRMED_TREND_TRANSITION",
    )

private fun samplePaperRuntimeState(): PaperRuntimeState =
    PaperRuntimeState(
        strategy = "paper-runtime-test",
        symbol = Symbol("BTCUSDT"),
        timeframe = Timeframe.M15,
        phase = PaperRuntimePhase.OPEN,
        lastProcessedCandleAt = Instant.parse("2026-06-30T00:15:00Z"),
        equity = 10_100.0,
        peakEquity = 10_200.0,
        maxDrawdownPct = 0.9803921568627451,
        grossProfit = 200.0,
        grossLoss = 100.0,
        sumReturnR = 1.25,
        closedTrades = 2,
        entryCountDate = LocalDate.parse("2026-06-30"),
        entryCount = 1,
        pendingEntry = null,
        openPosition =
            PaperOpenPosition(
                signalId = 7,
                signalAt = Instant.parse("2026-06-30T00:00:00Z"),
                entryOrderId = 9,
                entryFee = 0.6,
                riskAmount = 100.0,
                policyState =
                    CausalPositionState(
                        side = Side.BUY,
                        entryAt = Instant.parse("2026-06-30T00:15:00Z"),
                        entryPrice = 100.0,
                        initialStopPrice = 95.0,
                        currentStopPrice = 100.0,
                        riskPerUnit = 5.0,
                        expectedR = 3.0,
                        initialQuantity = 20.0,
                        remainingQuantity = 10.0,
                        fullTargetPrice = 115.0,
                        partialTargetPrice = 105.0,
                        bestHigh = 110.0,
                        bestLow = 96.0,
                        processedCandles = 2,
                        partialTaken = true,
                        partialTakeProfitAt = Instant.parse("2026-06-30T00:15:00Z"),
                        partialExitPrice = 105.0,
                        partialQuantity = 10.0,
                        partialGrossPnl = 50.0,
                        partialFees = 0.63,
                    ),
            ),
        updatedAt = Instant.parse("2026-06-30T00:30:00Z"),
    )

private fun executionClosureColumnDefaults(driver: JdbcSqliteDriver): Map<String, String?> {
    val connection = driver.getConnection()
    try {
        return connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(executionTradeClosures)").use { rows ->
                buildMap {
                    while (rows.next()) {
                        put(rows.getString("name"), rows.getString("dflt_value"))
                    }
                }
            }
        }
    } finally {
        driver.closeConnection(connection)
    }
}

private fun tableColumnNames(
    driver: JdbcSqliteDriver,
    table: String,
): Set<String> {
    val connection = driver.getConnection()
    try {
        return connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($table)").use { rows ->
                buildSet {
                    while (rows.next()) add(rows.getString("name"))
                }
            }
        }
    } finally {
        driver.closeConnection(connection)
    }
}

private fun executionClosureAlertState(driver: JdbcSqliteDriver): StoredClosureAlertState {
    val connection = driver.getConnection()
    try {
        return connection.createStatement().use { statement ->
            statement
                .executeQuery(
                    "SELECT delivered_at, suppressed_at, attempt_count, last_attempt_at FROM executionTradeClosures LIMIT 1",
                ).use { rows ->
                    check(rows.next()) { "Expected migrated execution closure row." }
                    StoredClosureAlertState(
                        deliveredAt = rows.getString("delivered_at"),
                        suppressedAt = rows.getString("suppressed_at"),
                        attemptCount = rows.getLong("attempt_count"),
                        lastAttemptAt = rows.getString("last_attempt_at"),
                    )
                }
        }
    } finally {
        driver.closeConnection(connection)
    }
}

private fun nullableIdClosure(): ExecutionTradeClosure =
    ExecutionTradeClosure(
        mode = ExecutionRuntimeMode.LIVE,
        symbol = Symbol("BTCUSDT"),
        side = Side.BUY,
        openedAt = Instant.parse("2026-06-30T00:00:00Z"),
        closedAt = Instant.parse("2026-06-30T00:10:00Z"),
        entryPrice = BigDecimal("100"),
        exitPrice = BigDecimal("105"),
        quantity = BigDecimal("1"),
        grossPnl = BigDecimal("5.12"),
        fees = BigDecimal("0.12"),
        netPnl = BigDecimal("5"),
        exitReason = "TAKE_PROFIT",
        exchangeOrderId = null,
        clientOrderId = null,
    )

private fun sampleCandle(
    symbol: Symbol,
    timeframe: Timeframe,
    openedAt: String,
    open: String,
): Candle =
    Candle(
        symbol = symbol,
        timeframe = timeframe,
        openedAt = Instant.parse(openedAt),
        open = BigDecimal(open),
        high = BigDecimal("110"),
        low = BigDecimal("90"),
        close = BigDecimal("105"),
        volume = BigDecimal("10.5"),
    )

private fun sampleMakerShadowEvent(
    eventId: String,
    type: MakerShadowLedgerEventType,
): MakerShadowLedgerEvent =
    MakerShadowLedgerEvent(
        eventId = eventId,
        sessionId = "shadow-session",
        engineVersion = MAKER_SHADOW_ENGINE_VERSION,
        configFingerprint = "maker-shadow-config-fingerprint",
        type = type,
        symbol = Symbol("BTCUSDT"),
        eventAt = Instant.parse("2026-08-06T00:00:00Z"),
        receivedAt = Instant.parse("2026-08-06T00:00:00.010Z"),
        bookEpoch = 1,
        crossSequence = 100,
        quoteId = "shadow-q-1",
        tradeId = "trade-1",
        side = Side.BUY,
        price = BigDecimal("100.5"),
        quantity = BigDecimal("0.001"),
        fee = BigDecimal("0.0000201"),
        queueAhead = BigDecimal.ZERO,
        inventoryQuantity = BigDecimal("0.001"),
        cash = BigDecimal("99.8994799"),
        equity = BigDecimal("100.0001"),
        markOutBps = null,
        reason = "test",
    )

private fun sampleTakerFlowBar(
    symbol: Symbol,
    openedAt: String,
    buyBase: String,
    sellBase: String,
): TakerFlowBar =
    TakerFlowBar(
        symbol = symbol,
        openedAt = Instant.parse(openedAt),
        takerBuyBase = BigDecimal(buyBase),
        takerBuyNotional = BigDecimal(buyBase).multiply(BigDecimal("100")),
        takerSellBase = BigDecimal(sellBase),
        takerSellNotional = BigDecimal(sellBase).multiply(BigDecimal("100")),
        buyTradeCount = 1,
        sellTradeCount = 1,
    )

private fun samplePremiumIndexBar(
    symbol: Symbol,
    openedAt: String,
    close: String,
): PremiumIndexBar =
    PremiumIndexBar(
        symbol = symbol,
        timeframe = Timeframe.M15,
        openedAt = Instant.parse(openedAt),
        open = BigDecimal.ZERO,
        high = BigDecimal(close),
        low = BigDecimal.ZERO,
        close = BigDecimal(close),
    )

private fun tableNames(driver: JdbcSqliteDriver): Set<String> {
    val connection = driver.getConnection()
    try {
        return connection.createStatement().use { statement ->
            statement
                .executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")
                .use { rows ->
                    buildSet {
                        while (rows.next()) add(rows.getString("name"))
                    }
                }
        }
    } finally {
        driver.closeConnection(connection)
    }
}
