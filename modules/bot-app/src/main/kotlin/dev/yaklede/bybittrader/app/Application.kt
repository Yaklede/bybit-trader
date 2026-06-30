package dev.yaklede.bybittrader.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.yaklede.bybittrader.alerts.AlertMessage
import dev.yaklede.bybittrader.alerts.AlertSeverity
import dev.yaklede.bybittrader.alerts.AlertSink
import dev.yaklede.bybittrader.alerts.AlertingService
import dev.yaklede.bybittrader.alerts.CompositeAlertSink
import dev.yaklede.bybittrader.alerts.DiscordWebhookAlertSink
import dev.yaklede.bybittrader.alerts.NoopAlertSink
import dev.yaklede.bybittrader.alerts.TelegramAlertSink
import dev.yaklede.bybittrader.api.configureApi
import dev.yaklede.bybittrader.engine.control.BotControlService
import dev.yaklede.bybittrader.engine.market.MarketDataSyncService
import dev.yaklede.bybittrader.exchange.bybit.BybitMarketDataClient
import dev.yaklede.bybittrader.ledger.SqlDelightLedger
import dev.yaklede.bybittrader.ledger.createLedgerDatabase
import dev.yaklede.bybittrader.ledger.db.LedgerDatabase
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.server.cio.CIO as ServerCIO

fun main() {
    val config = AppConfig.fromEnvironment()
    val database = openLedgerDatabase(Path.of(config.database.path))
    val ledger = SqlDelightLedger(database)
    val httpClient = createJsonHttpClient()
    val alertingService =
        AlertingService(
            sink = createAlertSink(config.alerts, httpClient),
            recorder = ledger,
        )
    val marketDataSyncService =
        MarketDataSyncService(
            marketDataFeed =
                BybitMarketDataClient(
                    httpClient = httpClient,
                    baseUrl = config.marketData.bybitPublicBaseUrl,
                ),
            candleStore = ledger,
        )
    val controlService =
        BotControlService(
            stateStore = ledger,
            eventRecorder = ledger,
        )

    runBlocking {
        alertingService.send(
            AlertMessage(
                severity = AlertSeverity.INFO,
                title = "startup",
                body = "Bybit Trader started in ${config.runtimeMode} mode.",
            ),
        )
    }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            runBlocking {
                alertingService.send(
                    AlertMessage(
                        severity = AlertSeverity.INFO,
                        title = "shutdown",
                        body = "Bybit Trader is shutting down.",
                    ),
                )
            }
            httpClient.close()
        },
    )

    val server =
        embeddedServer(ServerCIO, host = config.api.host, port = config.api.port) {
            configureApi(
                stateStore = ledger,
                controlService = controlService,
                marketDataSyncService = marketDataSyncService,
                controlCredential = config.api.controlCredential,
            )
        }

    server.start(wait = true)
}

private fun openLedgerDatabase(path: Path): LedgerDatabase {
    path.parent?.let(Files::createDirectories)
    val shouldCreateSchema = Files.notExists(path)
    val driver = JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}")
    if (shouldCreateSchema) {
        LedgerDatabase.Schema.create(driver)
    }
    return createLedgerDatabase(driver)
}

private fun createJsonHttpClient(): HttpClient =
    HttpClient(ClientCIO) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                },
            )
        }
    }

private fun createAlertSink(
    config: AlertsConfig,
    client: HttpClient,
): AlertSink {
    val sinks =
        buildList {
            config.telegram?.let { telegram ->
                add(
                    TelegramAlertSink(
                        client = client,
                        botCredential = telegram.botCredential,
                        chatId = telegram.chatId,
                    ),
                )
            }
            config.discord?.let { discord ->
                add(
                    DiscordWebhookAlertSink(
                        client = client,
                        webhookUrl = discord.webhookUrl,
                    ),
                )
            }
        }
    return when (sinks.size) {
        0 -> NoopAlertSink()
        1 -> sinks.single()
        else -> CompositeAlertSink(sinks)
    }
}
