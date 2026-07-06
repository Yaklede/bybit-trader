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

        "stored resume pending state is normalized to running" {
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

            ledger.current().mode shouldBe BotMode.RUNNING
            database.ledgerQueries
                .selectBotState()
                .executeAsOne()
                .mode shouldBe BotMode.RUNNING.name
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
    })

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
