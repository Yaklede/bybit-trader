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
import dev.yaklede.bybittrader.engine.execution.ExecutionRuntimeMode
import dev.yaklede.bybittrader.engine.execution.ExecutionTradeClosure
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
import dev.yaklede.bybittrader.engine.paper.PaperFillRecord
import dev.yaklede.bybittrader.engine.paper.PaperOrderRecord
import dev.yaklede.bybittrader.engine.paper.PaperPerformanceSnapshot
import dev.yaklede.bybittrader.engine.paper.PaperPositionRecord
import dev.yaklede.bybittrader.engine.paper.PaperSignalRecord
import dev.yaklede.bybittrader.ledger.db.LedgerDatabase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
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
                ),
            )
            ledger.latestLivePerformanceSummary(ExecutionRuntimeMode.LIVE, LivePerformanceWindow.ALL)?.tradeCount shouldBe 1
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
                ),
            ) shouldBe true
        }
    })

private data class StoredClosureAlertState(
    val deliveredAt: String?,
    val suppressedAt: String?,
    val attemptCount: Long,
    val lastAttemptAt: String?,
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
