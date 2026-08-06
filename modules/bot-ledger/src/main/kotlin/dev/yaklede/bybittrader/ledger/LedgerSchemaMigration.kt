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
                val performanceSnapshotColumns =
                    listOf(
                        "account_equity" to "TEXT",
                        "account_peak_equity" to "TEXT",
                        "max_account_drawdown_pct" to "TEXT",
                        "account_equity_captured_at" to "TEXT",
                    )
                performanceSnapshotColumns.forEach { (column, type) ->
                    if (!connection.hasColumn("livePerformanceSnapshots", column)) {
                        statement.execute("ALTER TABLE livePerformanceSnapshots ADD COLUMN $column $type")
                    }
                }
                val executionLifecycleColumns =
                    listOf(
                        "protection_required" to "INTEGER NOT NULL DEFAULT 0",
                        "planned_entry_price" to "TEXT",
                        "structural_stop_price" to "TEXT",
                        "entry_anchored_stop_distance" to "TEXT",
                        "expected_r" to "TEXT",
                        "protection_deadline_at" to "TEXT",
                        "fixed_target_enabled" to "INTEGER NOT NULL DEFAULT 1",
                        "intended_risk" to "TEXT",
                    )
                executionLifecycleColumns.forEach { (column, type) ->
                    if (!connection.hasColumn("executionLifecycleEvents", column)) {
                        statement.execute("ALTER TABLE executionLifecycleEvents ADD COLUMN $column $type")
                    }
                }
                val executionAccountSnapshotColumns =
                    listOf(
                        "total_initial_margin" to "TEXT",
                        "total_maintenance_margin" to "TEXT",
                        "tracked_coin" to "TEXT",
                        "tracked_coin_equity" to "TEXT",
                        "tracked_coin_wallet_balance" to "TEXT",
                        "tracked_coin_unrealized_pnl" to "TEXT",
                        "tracked_coin_cumulative_realized_pnl" to "TEXT",
                    )
                executionAccountSnapshotColumns.forEach { (column, type) ->
                    if (!connection.hasColumn("executionAccountSnapshots", column)) {
                        statement.execute("ALTER TABLE executionAccountSnapshots ADD COLUMN $column $type")
                    }
                }
                val executionRiskStateColumns =
                    listOf(
                        "nav_status" to "TEXT NOT NULL DEFAULT 'UNAVAILABLE'",
                        "strategy_units" to "TEXT NOT NULL DEFAULT '0'",
                        "latest_unitized_nav" to "TEXT NOT NULL DEFAULT '0'",
                        "peak_unitized_nav" to "TEXT NOT NULL DEFAULT '0'",
                        "day_start_unitized_nav" to "TEXT NOT NULL DEFAULT '0'",
                        "cumulative_external_cash_flow" to "TEXT NOT NULL DEFAULT '0'",
                        "last_account_transaction_id" to "INTEGER",
                    )
                executionRiskStateColumns.forEach { (column, type) ->
                    if (!connection.hasColumn("executionRiskStates", column)) {
                        statement.execute("ALTER TABLE executionRiskStates ADD COLUMN $column $type")
                    }
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
        CREATE TABLE IF NOT EXISTS orderBookImbalanceBars (
          id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
          symbol TEXT NOT NULL,
          opened_at TEXT NOT NULL,
          sample_count INTEGER NOT NULL,
          mean_bid_notional TEXT NOT NULL,
          mean_ask_notional TEXT NOT NULL,
          mean_imbalance TEXT NOT NULL,
          mean_spread_bps TEXT NOT NULL,
          max_spread_bps TEXT NOT NULL
        )
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX IF NOT EXISTS orderBookImbalanceBars_symbol_openedAt_idx
        ON orderBookImbalanceBars(symbol, opened_at)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS liquidationFlowBars (
          id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
          symbol TEXT NOT NULL,
          opened_at TEXT NOT NULL,
          long_liquidation_notional TEXT NOT NULL,
          short_liquidation_notional TEXT NOT NULL,
          long_liquidation_count INTEGER NOT NULL,
          short_liquidation_count INTEGER NOT NULL
        )
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX IF NOT EXISTS liquidationFlowBars_symbol_openedAt_idx
        ON liquidationFlowBars(symbol, opened_at)
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
        CREATE TABLE IF NOT EXISTS paperRuntimeStates (
          strategy TEXT NOT NULL,
          symbol TEXT NOT NULL,
          timeframe TEXT NOT NULL,
          phase TEXT NOT NULL,
          state_payload TEXT NOT NULL,
          updated_at TEXT NOT NULL,
          PRIMARY KEY (strategy, symbol, timeframe)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS executionPositionRuntimeStates (
          mode TEXT NOT NULL,
          symbol TEXT NOT NULL,
          lifecycle_id TEXT NOT NULL,
          timeframe TEXT NOT NULL,
          state_payload TEXT NOT NULL,
          updated_at TEXT NOT NULL,
          PRIMARY KEY (mode, symbol)
        )
        """.trimIndent(),
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
        CREATE TABLE IF NOT EXISTS executionLifecycleEvents (
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
          protection_required INTEGER NOT NULL DEFAULT 0,
          planned_entry_price TEXT,
          structural_stop_price TEXT,
          entry_anchored_stop_distance TEXT,
          expected_r TEXT,
          protection_deadline_at TEXT,
          identity_key TEXT NOT NULL
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX IF NOT EXISTS executionLifecycleEvents_identity_idx ON executionLifecycleEvents(identity_key)",
        "CREATE INDEX IF NOT EXISTS executionLifecycleEvents_mode_symbol_id_idx ON executionLifecycleEvents(mode, symbol, id DESC)",
        """
        CREATE TABLE IF NOT EXISTS executionFillEvents (
          id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
          mode TEXT NOT NULL,
          execution_id TEXT,
          exchange_order_id TEXT,
          client_order_id TEXT,
          symbol TEXT NOT NULL,
          side TEXT NOT NULL,
          price TEXT NOT NULL,
          quantity TEXT NOT NULL,
          fee TEXT NOT NULL,
          executed_at TEXT NOT NULL,
          received_at TEXT NOT NULL,
          execution_type TEXT,
          create_type TEXT,
          stop_order_type TEXT,
          closed_size TEXT,
          execution_pnl TEXT,
          identity_key TEXT NOT NULL
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX IF NOT EXISTS executionFillEvents_identity_idx ON executionFillEvents(identity_key)",
        "CREATE INDEX IF NOT EXISTS executionFillEvents_mode_symbol_executedAt_id_idx ON executionFillEvents(mode, symbol, executed_at DESC, id DESC)",
        """
        CREATE TABLE IF NOT EXISTS executionAccountSnapshots (
          id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
          mode TEXT NOT NULL,
          account_type TEXT NOT NULL,
          total_equity TEXT,
          total_wallet_balance TEXT,
          total_margin_balance TEXT,
          total_available_balance TEXT,
          total_perp_unrealized_pnl TEXT,
          total_initial_margin TEXT,
          total_maintenance_margin TEXT,
          tracked_coin TEXT,
          tracked_coin_equity TEXT,
          tracked_coin_wallet_balance TEXT,
          tracked_coin_unrealized_pnl TEXT,
          tracked_coin_cumulative_realized_pnl TEXT,
          captured_at TEXT NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS executionAccountSnapshots_mode_capturedAt_id_idx ON executionAccountSnapshots(mode, captured_at, id DESC)",
        """
        CREATE TABLE IF NOT EXISTS executionRiskStates (
          mode TEXT NOT NULL PRIMARY KEY,
          peak_equity TEXT NOT NULL,
          utc_day_started_at TEXT NOT NULL,
          day_start_equity TEXT NOT NULL,
          latest_equity TEXT NOT NULL,
          consecutive_losses INTEGER NOT NULL,
          last_closure_id INTEGER,
          updated_at TEXT NOT NULL,
          nav_status TEXT NOT NULL,
          strategy_units TEXT NOT NULL,
          latest_unitized_nav TEXT NOT NULL,
          peak_unitized_nav TEXT NOT NULL,
          day_start_unitized_nav TEXT NOT NULL,
          cumulative_external_cash_flow TEXT NOT NULL,
          last_account_transaction_id INTEGER
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS executionAccountTransactions (
          id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
          mode TEXT NOT NULL,
          transaction_id TEXT NOT NULL,
          symbol TEXT,
          category TEXT NOT NULL,
          side TEXT,
          transaction_at TEXT NOT NULL,
          transaction_type TEXT NOT NULL,
          transaction_subtype TEXT,
          quantity TEXT,
          size TEXT,
          currency TEXT NOT NULL,
          trade_price TEXT,
          funding TEXT NOT NULL,
          fee TEXT NOT NULL,
          cash_flow TEXT NOT NULL,
          balance_change TEXT NOT NULL,
          cash_balance TEXT,
          fee_rate TEXT,
          trade_id TEXT,
          exchange_order_id TEXT,
          client_order_id TEXT,
          received_at TEXT NOT NULL,
          identity_key TEXT NOT NULL
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX IF NOT EXISTS executionAccountTransactions_identity_idx ON executionAccountTransactions(identity_key)",
        "CREATE INDEX IF NOT EXISTS executionAccountTransactions_mode_currency_transactionAt_id_idx ON executionAccountTransactions(mode, currency, transaction_at, id)",
        """
        CREATE TABLE IF NOT EXISTS executionWalletReconciliationStates (
          mode TEXT NOT NULL,
          currency TEXT NOT NULL,
          status TEXT NOT NULL,
          baseline_snapshot_id INTEGER,
          baseline_captured_at TEXT,
          baseline_wallet_balance TEXT,
          current_snapshot_id INTEGER,
          current_captured_at TEXT,
          current_wallet_balance TEXT,
          observed_wallet_change TEXT,
          ledger_change TEXT,
          difference TEXT,
          tolerance TEXT NOT NULL,
          consecutive_mismatches INTEGER NOT NULL,
          last_matched_at TEXT,
          reconciled_at TEXT NOT NULL,
          PRIMARY KEY (mode, currency)
        )
        """.trimIndent(),
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
          captured_at TEXT NOT NULL,
          account_equity TEXT,
          account_peak_equity TEXT,
          max_account_drawdown_pct TEXT,
          account_equity_captured_at TEXT
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS livePerformanceSnapshots_mode_window_id_idx ON livePerformanceSnapshots(mode, window, id DESC)",
        """
        CREATE TABLE IF NOT EXISTS makerShadowEvents (
          id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
          event_id TEXT NOT NULL UNIQUE,
          session_id TEXT NOT NULL,
          engine_version TEXT NOT NULL,
          config_fingerprint TEXT NOT NULL,
          event_type TEXT NOT NULL,
          symbol TEXT NOT NULL,
          event_at TEXT NOT NULL,
          received_at TEXT NOT NULL,
          book_epoch INTEGER,
          cross_sequence INTEGER,
          quote_id TEXT,
          trade_id TEXT,
          side TEXT,
          price TEXT,
          quantity TEXT,
          fee TEXT,
          queue_ahead TEXT,
          inventory_quantity TEXT NOT NULL,
          cash TEXT NOT NULL,
          equity TEXT NOT NULL,
          mark_out_bps TEXT,
          reason TEXT
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS makerShadowEvents_session_id_idx ON makerShadowEvents(session_id, id)",
        """
        CREATE TABLE IF NOT EXISTS volumeConfirmedTrendShadowStates (
          protocol_id TEXT NOT NULL,
          candidate_id TEXT NOT NULL,
          protocol_sha256 TEXT NOT NULL,
          symbol TEXT NOT NULL,
          session_id TEXT NOT NULL,
          status TEXT NOT NULL,
          state_payload TEXT NOT NULL,
          updated_at TEXT NOT NULL,
          PRIMARY KEY (protocol_id, symbol)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS volumeConfirmedTrendShadowEvents (
          id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
          event_id TEXT NOT NULL UNIQUE,
          session_id TEXT NOT NULL,
          protocol_id TEXT NOT NULL,
          protocol_sha256 TEXT NOT NULL,
          symbol TEXT NOT NULL,
          event_type TEXT NOT NULL,
          event_at TEXT NOT NULL,
          observed_at TEXT NOT NULL,
          h4_opened_at TEXT,
          side TEXT,
          reference_price TEXT,
          fill_price TEXT,
          quantity TEXT,
          fee TEXT NOT NULL,
          slippage TEXT NOT NULL,
          funding_pnl TEXT NOT NULL,
          gross_pnl TEXT NOT NULL,
          net_pnl TEXT NOT NULL,
          cash TEXT NOT NULL,
          equity TEXT NOT NULL,
          reason TEXT NOT NULL
        )
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS volumeConfirmedTrendShadowEvents_session_id_idx
        ON volumeConfirmedTrendShadowEvents(session_id, id)
        """.trimIndent(),
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
