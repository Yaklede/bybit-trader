package dev.yaklede.bybittrader.ledger

import app.cash.sqldelight.db.SqlDriver
import dev.yaklede.bybittrader.alerts.AlertDeliveryRecord
import dev.yaklede.bybittrader.alerts.AlertDeliveryRecorder
import dev.yaklede.bybittrader.domain.BotMode
import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.OrderStatus
import dev.yaklede.bybittrader.domain.OrderType
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.control.BotRuntimeStatus
import dev.yaklede.bybittrader.engine.control.BotStateStore
import dev.yaklede.bybittrader.engine.control.ControlEvent
import dev.yaklede.bybittrader.engine.control.ControlEventRecorder
import dev.yaklede.bybittrader.engine.market.MarketCandleStore
import dev.yaklede.bybittrader.engine.paper.PaperFillRecord
import dev.yaklede.bybittrader.engine.paper.PaperOrderRecord
import dev.yaklede.bybittrader.engine.paper.PaperPerformanceSnapshot
import dev.yaklede.bybittrader.engine.paper.PaperPositionRecord
import dev.yaklede.bybittrader.engine.paper.PaperSignalRecord
import dev.yaklede.bybittrader.engine.paper.PaperTradeRecord
import dev.yaklede.bybittrader.engine.paper.PaperTradingStore
import dev.yaklede.bybittrader.ledger.db.LedgerDatabase
import dev.yaklede.bybittrader.ledger.db.PerformanceSnapshots
import dev.yaklede.bybittrader.ledger.db.SelectRecentMarketCandles
import dev.yaklede.bybittrader.ledger.db.SelectRecentTrades
import dev.yaklede.bybittrader.ledger.db.Signals
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant

class SqlDelightLedger(
    private val database: LedgerDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val initialMode: BotMode = BotMode.RUNNING,
) : BotStateStore,
    ControlEventRecorder,
    AlertDeliveryRecorder,
    MarketCandleStore,
    PaperTradingStore {
    override suspend fun current(): BotRuntimeStatus {
        val row = database.ledgerQueries.selectBotState().executeAsOneOrNull()
        if (row != null) {
            return BotRuntimeStatus(
                mode = BotMode.valueOf(row.mode),
                updatedAt = Instant.parse(row.updated_at),
                heartbeatAt = row.heartbeat_at?.let(Instant::parse),
            )
        }

        val now = Instant.now(clock)
        val status =
            BotRuntimeStatus(
                mode = initialMode,
                updatedAt = now,
                heartbeatAt = null,
            )
        update(status)
        return status
    }

    override suspend fun update(status: BotRuntimeStatus) {
        if (database.ledgerQueries.selectBotState().executeAsOneOrNull() == null) {
            database.ledgerQueries.insertBotState(
                mode = status.mode.name,
                updated_at = status.updatedAt.toString(),
                heartbeat_at = status.heartbeatAt?.toString(),
            )
        } else {
            database.ledgerQueries.updateBotState(
                mode = status.mode.name,
                updated_at = status.updatedAt.toString(),
                heartbeat_at = status.heartbeatAt?.toString(),
            )
        }
    }

    override suspend fun record(event: ControlEvent) {
        database.ledgerQueries.insertControlEvent(
            action = event.action.name,
            actor = event.actor,
            previous_mode = event.previousMode.name,
            new_mode = event.newMode.name,
            reason = event.reason,
            created_at = event.createdAt.toString(),
        )
    }

    override suspend fun record(record: AlertDeliveryRecord) {
        database.ledgerQueries.insertAlertEvent(
            sink = record.sinkName,
            severity = record.severity.name,
            title = record.title,
            delivery_status = record.deliveryStatus.name,
            failure_reason = record.failureReason,
            created_at = record.createdAt.toString(),
        )
    }

    override suspend fun upsert(candles: List<Candle>) {
        val sourceTimestamp = Instant.now(clock).toString()
        database.ledgerQueries.transaction {
            candles.forEach { candle ->
                database.ledgerQueries.upsertMarketCandle(
                    symbol = candle.symbol.value,
                    timeframe = candle.timeframe.name,
                    opened_at = candle.openedAt.toString(),
                    open_ = candle.open.toPlainString(),
                    high = candle.high.toPlainString(),
                    low = candle.low.toPlainString(),
                    close = candle.close.toPlainString(),
                    volume = candle.volume.toPlainString(),
                    source_timestamp = sourceTimestamp,
                )
            }
        }
    }

    override suspend fun recentCandles(
        symbol: Symbol,
        timeframe: Timeframe,
        limit: Int,
    ): List<Candle> {
        require(limit in 1..200_000) { "Limit must be between 1 and 200000." }
        return database.ledgerQueries
            .selectRecentMarketCandles(
                symbol = symbol.value,
                timeframe = timeframe.name,
                value_ = limit.toLong(),
            ).executeAsList()
            .map(SelectRecentMarketCandles::toCandle)
    }

    override suspend fun recordSignal(signal: PaperSignalRecord): Long {
        database.ledgerQueries.insertSignal(
            strategy = signal.strategy,
            symbol = signal.symbol.value,
            side = signal.side.name,
            score = signal.score.toLong(),
            grade = signal.grade,
            reason_codes = signal.reasonCodes.joinToString(REASON_SEPARATOR),
            accepted = if (signal.accepted) 1L else 0L,
            rejection_reason = signal.rejectionReason,
            created_at = signal.createdAt.toString(),
        )
        return database.ledgerQueries.lastInsertRowId().executeAsOne()
    }

    override suspend fun recordOrder(order: PaperOrderRecord): Long {
        database.ledgerQueries.insertOrder(
            exchange_order_id = order.exchangeOrderId,
            client_order_id = order.clientOrderId,
            signal_id = order.signalId,
            side = order.side.name,
            order_type = order.orderType.name,
            order_status = order.orderStatus.name,
            intended_risk = order.intendedRisk.toPlainString(),
            created_at = order.createdAt.toString(),
        )
        return database.ledgerQueries.lastInsertRowId().executeAsOne()
    }

    override suspend fun recordFill(fill: PaperFillRecord): Long {
        database.ledgerQueries.insertFill(
            order_id = fill.orderId,
            fill_price = fill.fillPrice.toPlainString(),
            quantity = fill.quantity.toPlainString(),
            fee = fill.fee.toPlainString(),
            liquidity_role = fill.liquidityRole,
            filled_at = fill.filledAt.toString(),
        )
        return database.ledgerQueries.lastInsertRowId().executeAsOne()
    }

    override suspend fun recordPosition(position: PaperPositionRecord): Long {
        database.ledgerQueries.insertPosition(
            symbol = position.symbol.value,
            side = position.side.name,
            quantity = position.quantity.toPlainString(),
            entry_price = position.entryPrice.toPlainString(),
            realized_pnl = position.realizedPnl.toPlainString(),
            unrealized_pnl = position.unrealizedPnl.toPlainString(),
            captured_at = position.capturedAt.toString(),
        )
        return database.ledgerQueries.lastInsertRowId().executeAsOne()
    }

    override suspend fun recordPerformanceSnapshot(snapshot: PaperPerformanceSnapshot): Long {
        database.ledgerQueries.insertPerformanceSnapshot(
            period = snapshot.period,
            net_pnl = snapshot.netPnl.toPlainString(),
            profit_factor = snapshot.profitFactor?.toPlainString(),
            expectancy = snapshot.expectancy?.toPlainString(),
            max_drawdown = snapshot.maxDrawdown.toPlainString(),
            captured_at = snapshot.capturedAt.toString(),
        )
        return database.ledgerQueries.lastInsertRowId().executeAsOne()
    }

    override suspend fun latestPerformanceSummary(): PaperPerformanceSnapshot? =
        database.ledgerQueries
            .selectLatestPerformanceSnapshot()
            .executeAsOneOrNull()
            ?.toPaperPerformanceSnapshot()

    override suspend fun recentSignals(limit: Int): List<PaperSignalRecord> {
        require(limit in 1..100) { "Limit must be between 1 and 100." }
        return database.ledgerQueries
            .selectRecentSignals(limit.toLong())
            .executeAsList()
            .map(Signals::toPaperSignalRecord)
    }

    override suspend fun recentTrades(limit: Int): List<PaperTradeRecord> {
        require(limit in 1..100) { "Limit must be between 1 and 100." }
        return database.ledgerQueries
            .selectRecentTrades(limit.toLong())
            .executeAsList()
            .map(SelectRecentTrades::toPaperTradeRecord)
    }
}

fun createLedgerDatabase(driver: SqlDriver): LedgerDatabase = LedgerDatabase(driver)

private const val REASON_SEPARATOR = ","

private fun SelectRecentMarketCandles.toCandle(): Candle =
    Candle(
        symbol = Symbol(symbol),
        timeframe = Timeframe.valueOf(timeframe),
        openedAt = Instant.parse(opened_at),
        open = BigDecimal(open_),
        high = BigDecimal(high),
        low = BigDecimal(low),
        close = BigDecimal(close),
        volume = BigDecimal(volume),
    )

private fun PerformanceSnapshots.toPaperPerformanceSnapshot(): PaperPerformanceSnapshot =
    PaperPerformanceSnapshot(
        id = id,
        period = period,
        netPnl = BigDecimal(net_pnl),
        profitFactor = profit_factor?.let(::BigDecimal),
        expectancy = expectancy?.let(::BigDecimal),
        maxDrawdown = BigDecimal(max_drawdown),
        capturedAt = Instant.parse(captured_at),
    )

private fun Signals.toPaperSignalRecord(): PaperSignalRecord =
    PaperSignalRecord(
        id = id,
        strategy = strategy,
        symbol = Symbol(symbol),
        side = Side.valueOf(side),
        score = score.toInt(),
        grade = grade,
        reasonCodes = reason_codes.splitReasons(),
        accepted = accepted == 1L,
        rejectionReason = rejection_reason,
        createdAt = Instant.parse(created_at),
    )

private fun SelectRecentTrades.toPaperTradeRecord(): PaperTradeRecord =
    PaperTradeRecord(
        orderId = order_id,
        clientOrderId = client_order_id,
        signalId = signal_id,
        side = Side.valueOf(side),
        orderType = OrderType.valueOf(order_type),
        orderStatus = OrderStatus.valueOf(order_status),
        intendedRisk = BigDecimal(intended_risk),
        orderCreatedAt = Instant.parse(order_created_at),
        fillId = fill_id,
        fillPrice = fill_price?.let(::BigDecimal),
        quantity = quantity?.let(::BigDecimal),
        fee = fee?.let(::BigDecimal),
        filledAt = filled_at?.let(Instant::parse),
    )

private fun String.splitReasons(): List<String> =
    split(REASON_SEPARATOR)
        .map(String::trim)
        .filter(String::isNotEmpty)
