package dev.yaklede.bybittrader.ledger

import app.cash.sqldelight.db.SqlDriver
import dev.yaklede.bybittrader.alerts.AlertDeliveryRecord
import dev.yaklede.bybittrader.alerts.AlertDeliveryRecorder
import dev.yaklede.bybittrader.domain.BotMode
import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.control.BotRuntimeStatus
import dev.yaklede.bybittrader.engine.control.BotStateStore
import dev.yaklede.bybittrader.engine.control.ControlEvent
import dev.yaklede.bybittrader.engine.control.ControlEventRecorder
import dev.yaklede.bybittrader.engine.market.MarketCandleStore
import dev.yaklede.bybittrader.ledger.db.LedgerDatabase
import dev.yaklede.bybittrader.ledger.db.SelectRecentMarketCandles
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
    MarketCandleStore {
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
        require(limit in 1..1000) { "Limit must be between 1 and 1000." }
        return database.ledgerQueries
            .selectRecentMarketCandles(
                symbol = symbol.value,
                timeframe = timeframe.name,
                value_ = limit.toLong(),
            ).executeAsList()
            .map(SelectRecentMarketCandles::toCandle)
    }
}

fun createLedgerDatabase(driver: SqlDriver): LedgerDatabase = LedgerDatabase(driver)

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
