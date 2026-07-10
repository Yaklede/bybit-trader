package dev.yaklede.bybittrader.ledger

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.sql.Connection

fun ensureAdditiveLedgerSchema(driver: JdbcSqliteDriver) {
    val connection = driver.getConnection()
    val previousAutoCommit = connection.autoCommit
    try {
        connection.autoCommit = false
        try {
            connection.createStatement().use { statement ->
                ADDITIVE_LEDGER_SCHEMA_STATEMENTS.forEach(statement::execute)
                statement.execute("DROP INDEX IF EXISTS executionTradeClosures_identity_idx")
                if (!connection.hasColumn("executionTradeClosures", "identity_key")) {
                    statement.execute(
                        "ALTER TABLE executionTradeClosures ADD COLUMN identity_key TEXT NOT NULL DEFAULT ''",
                    )
                }
                val missingDeliveredAt = !connection.hasColumn("executionTradeClosures", "delivered_at")
                val missingSuppressedAt = !connection.hasColumn("executionTradeClosures", "suppressed_at")
                val missingAttemptCount = !connection.hasColumn("executionTradeClosures", "attempt_count")
                val missingLastAttemptAt = !connection.hasColumn("executionTradeClosures", "last_attempt_at")
                val requiresAlertStateBaseline =
                    missingDeliveredAt || missingSuppressedAt || missingAttemptCount || missingLastAttemptAt
                if (missingDeliveredAt) {
                    statement.execute("ALTER TABLE executionTradeClosures ADD COLUMN delivered_at TEXT")
                }
                if (missingSuppressedAt) {
                    statement.execute("ALTER TABLE executionTradeClosures ADD COLUMN suppressed_at TEXT")
                }
                if (missingAttemptCount) {
                    statement.execute(
                        "ALTER TABLE executionTradeClosures ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0",
                    )
                }
                if (missingLastAttemptAt) {
                    statement.execute("ALTER TABLE executionTradeClosures ADD COLUMN last_attempt_at TEXT")
                }
                if (requiresAlertStateBaseline) {
                    statement.execute(
                        "UPDATE executionTradeClosures SET suppressed_at = CURRENT_TIMESTAMP WHERE delivered_at IS NULL AND suppressed_at IS NULL",
                    )
                }
                statement.execute(EXECUTION_CLOSURE_IDENTITY_BACKFILL)
                statement.execute(EXECUTION_CLOSURE_DUPLICATE_CLEANUP)
                statement.execute(
                    "CREATE UNIQUE INDEX executionTradeClosures_identity_idx ON executionTradeClosures(identity_key)",
                )
            }
            connection.commit()
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    } finally {
        driver.closeConnection(connection)
    }
}

private fun Connection.hasColumn(
    table: String,
    column: String,
): Boolean =
    createStatement().use { statement ->
        statement.executeQuery("PRAGMA table_info($table)").use { rows ->
            while (rows.next()) {
                if (rows.getString("name") == column) return true
            }
            false
        }
    }

private val ADDITIVE_LEDGER_SCHEMA_STATEMENTS =
    listOf(
        """
        CREATE TABLE IF NOT EXISTS takerFlowBars (
          id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
          symbol TEXT NOT NULL,
          opened_at TEXT NOT NULL,
          taker_buy_base TEXT NOT NULL,
          taker_buy_notional TEXT NOT NULL,
          taker_sell_base TEXT NOT NULL,
          taker_sell_notional TEXT NOT NULL,
          buy_trade_count INTEGER NOT NULL,
          sell_trade_count INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX IF NOT EXISTS takerFlowBars_symbol_openedAt_idx ON takerFlowBars(symbol, opened_at)",
        """
        CREATE TABLE IF NOT EXISTS openInterestSnapshots (
          id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
          symbol TEXT NOT NULL,
          interval TEXT NOT NULL,
          timestamp TEXT NOT NULL,
          open_interest TEXT NOT NULL
        )
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX IF NOT EXISTS openInterestSnapshots_symbol_interval_timestamp_idx
        ON openInterestSnapshots(symbol, interval, timestamp)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS accountRatioSnapshots (
          id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
          symbol TEXT NOT NULL,
          period TEXT NOT NULL,
          timestamp TEXT NOT NULL,
          buy_ratio TEXT NOT NULL,
          sell_ratio TEXT NOT NULL
        )
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX IF NOT EXISTS accountRatioSnapshots_symbol_period_timestamp_idx
        ON accountRatioSnapshots(symbol, period, timestamp)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS premiumIndexBars (
          id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
          symbol TEXT NOT NULL,
          timeframe TEXT NOT NULL,
          opened_at TEXT NOT NULL,
          open TEXT NOT NULL,
          high TEXT NOT NULL,
          low TEXT NOT NULL,
          close TEXT NOT NULL
        )
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX IF NOT EXISTS premiumIndexBars_symbol_timeframe_openedAt_idx
        ON premiumIndexBars(symbol, timeframe, opened_at)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS fundingRates (
          id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
          symbol TEXT NOT NULL,
          timestamp TEXT NOT NULL,
          funding_rate TEXT NOT NULL
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX IF NOT EXISTS fundingRates_symbol_timestamp_idx ON fundingRates(symbol, timestamp)",
        """
        CREATE TABLE IF NOT EXISTS marketSyncCheckpoints (
          id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
          symbol TEXT NOT NULL,
          timeframe TEXT NOT NULL,
          latest_closed_opened_at TEXT NOT NULL,
          last_sync_at TEXT NOT NULL,
          last_sync_status TEXT NOT NULL,
          consecutive_rate_limit_count INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX IF NOT EXISTS marketSyncCheckpoints_symbol_timeframe_idx ON marketSyncCheckpoints(symbol, timeframe)",
        """
        CREATE TABLE IF NOT EXISTS executionTradeClosures (
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
          identity_key TEXT NOT NULL,
          delivered_at TEXT,
          suppressed_at TEXT,
          attempt_count INTEGER NOT NULL DEFAULT 0,
          last_attempt_at TEXT
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS executionTradeClosures_symbol_mode_id_idx ON executionTradeClosures(symbol, mode, id DESC)",
        """
        CREATE TABLE IF NOT EXISTS livePerformanceSnapshots (
          id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
          mode TEXT NOT NULL,
          window TEXT NOT NULL,
          trade_count INTEGER NOT NULL,
          win_rate_pct TEXT NOT NULL,
          gross_profit TEXT NOT NULL,
          gross_loss TEXT NOT NULL,
          fees TEXT NOT NULL,
          net_pnl TEXT NOT NULL,
          profit_factor TEXT,
          expectancy TEXT,
          max_closed_trade_drawdown_pct TEXT NOT NULL,
          last_closed_at TEXT,
          captured_at TEXT NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS livePerformanceSnapshots_mode_window_id_idx ON livePerformanceSnapshots(mode, window, id DESC)",
    )

private val EXECUTION_CLOSURE_IDENTITY_BACKFILL =
    """
    UPDATE executionTradeClosures
    SET identity_key =
      mode || '|' || symbol || '|' ||
      CASE
        WHEN NULLIF(TRIM(exchange_order_id), '') IS NOT NULL
          THEN 'exchange|' || TRIM(exchange_order_id)
        WHEN NULLIF(TRIM(client_order_id), '') IS NOT NULL
          THEN 'client|' || TRIM(client_order_id)
        ELSE 'fallback|' || opened_at || '|' || closed_at || '|' || side || '|' ||
          quantity || '|' || entry_price || '|' || exit_price
      END
    """.trimIndent()

private val EXECUTION_CLOSURE_DUPLICATE_CLEANUP =
    """
    DELETE FROM executionTradeClosures
    WHERE id NOT IN (
      SELECT MIN(id)
      FROM executionTradeClosures
      GROUP BY identity_key
    )
    """.trimIndent()
