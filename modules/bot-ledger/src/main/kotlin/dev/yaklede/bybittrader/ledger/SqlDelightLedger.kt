package dev.yaklede.bybittrader.ledger

import app.cash.sqldelight.db.SqlDriver
import dev.yaklede.bybittrader.alerts.AlertDeliveryRecord
import dev.yaklede.bybittrader.alerts.AlertDeliveryRecorder
import dev.yaklede.bybittrader.domain.BotMode
import dev.yaklede.bybittrader.engine.control.BotRuntimeStatus
import dev.yaklede.bybittrader.engine.control.BotStateStore
import dev.yaklede.bybittrader.engine.control.ControlEvent
import dev.yaklede.bybittrader.engine.control.ControlEventRecorder
import dev.yaklede.bybittrader.ledger.db.LedgerDatabase
import java.time.Clock
import java.time.Instant

class SqlDelightLedger(
    private val database: LedgerDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val initialMode: BotMode = BotMode.RUNNING,
) : BotStateStore,
    ControlEventRecorder,
    AlertDeliveryRecorder {
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
}

fun createLedgerDatabase(driver: SqlDriver): LedgerDatabase = LedgerDatabase(driver)
