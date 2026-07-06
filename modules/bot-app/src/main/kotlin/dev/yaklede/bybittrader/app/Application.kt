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
import dev.yaklede.bybittrader.api.backtest.FileVolumeFlowCompositeCurrentConfigProvider
import dev.yaklede.bybittrader.api.configureApi
import dev.yaklede.bybittrader.api.operations.SmokeAlertDeliveryResponse
import dev.yaklede.bybittrader.domain.BotMode
import dev.yaklede.bybittrader.domain.ControlAction
import dev.yaklede.bybittrader.engine.backtest.BacktestRunner
import dev.yaklede.bybittrader.engine.backtest.BacktestService
import dev.yaklede.bybittrader.engine.backtest.MeanReversionSweepService
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowAggressiveBacktestService
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowBacktestService
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowCompositeBacktestService
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowSweepService
import dev.yaklede.bybittrader.engine.control.BotControlService
import dev.yaklede.bybittrader.engine.control.ControlResult
import dev.yaklede.bybittrader.engine.execution.ExchangeEvaluationResult
import dev.yaklede.bybittrader.engine.execution.ExchangeEvaluationStatus
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionConfig
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionService
import dev.yaklede.bybittrader.engine.execution.ExchangeTradingLoop
import dev.yaklede.bybittrader.engine.execution.ExchangeTradingLoopConfig
import dev.yaklede.bybittrader.engine.market.MarketDataSyncService
import dev.yaklede.bybittrader.engine.paper.PaperEvaluationResult
import dev.yaklede.bybittrader.engine.paper.PaperEvaluationStatus
import dev.yaklede.bybittrader.engine.paper.PaperTradingConfig
import dev.yaklede.bybittrader.engine.paper.PaperTradingLoop
import dev.yaklede.bybittrader.engine.paper.PaperTradingLoopConfig
import dev.yaklede.bybittrader.engine.paper.PaperTradingService
import dev.yaklede.bybittrader.engine.strategy.VolumeFlowAggressiveStrategy
import dev.yaklede.bybittrader.exchange.bybit.BybitMarketDataClient
import dev.yaklede.bybittrader.exchange.bybit.BybitPrivateClient
import dev.yaklede.bybittrader.exchange.bybit.BybitPrivateClientConfig
import dev.yaklede.bybittrader.exchange.bybit.BybitTradingCategory
import dev.yaklede.bybittrader.ledger.SqlDelightLedger
import dev.yaklede.bybittrader.ledger.createLedgerDatabase
import dev.yaklede.bybittrader.ledger.db.LedgerDatabase
import dev.yaklede.bybittrader.strategy.MeanReversionStrategy
import dev.yaklede.bybittrader.strategy.TradingStrategy
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.server.cio.CIO as ServerCIO

private val logger = LoggerFactory.getLogger("dev.yaklede.bybittrader.app")

fun main() {
    val config = AppConfig.fromEnvironment()
    logger.info(
        "application starting mode={} api={}:{} privateExecution={} executionLoop={} symbol={} timeframes={}",
        config.runtimeMode.name,
        config.api.host,
        config.api.port,
        config.execution.enabled,
        config.executionLoop.enabled,
        config.marketData.symbol.value,
        config.marketData.timeframes.joinToString(",") { it.name },
    )
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
    val backtestService =
        BacktestService(
            candleStore = ledger,
            runner = BacktestRunner(MeanReversionStrategy()),
        )
    val meanReversionSweepService = MeanReversionSweepService(candleStore = ledger)
    val volumeFlowBacktestService = VolumeFlowBacktestService(candleStore = ledger)
    val volumeFlowAggressiveBacktestService = VolumeFlowAggressiveBacktestService(candleStore = ledger)
    val volumeFlowCompositeBacktestService = VolumeFlowCompositeBacktestService(candleStore = ledger)
    val volumeFlowSweepService = VolumeFlowSweepService(candleStore = ledger)
    val paperTradingService =
        PaperTradingService(
            stateStore = ledger,
            candleStore = ledger,
            paperTradingStore = ledger,
            strategy = createPaperStrategy(config.paperLoop.strategy),
            config =
                PaperTradingConfig(
                    initialEquity = config.paperTrading.initialEquity,
                    riskFraction = config.paperTrading.riskFraction,
                    feeRate = config.paperTrading.feeRate,
                ),
        )
    val executionService =
        if (config.bybitPrivate.credentialsAvailable) {
            logger.info(
                "private exchange client configured baseUrl={} accountType={} executionEnabled={}",
                config.bybitPrivate.baseUrl,
                config.bybitPrivate.accountType,
                config.execution.enabled,
            )
            ExchangeExecutionService(
                stateStore = ledger,
                candleStore = ledger,
                tradingStore = ledger,
                strategy = VolumeFlowAggressiveStrategy(),
                gateway =
                    BybitPrivateClient(
                        httpClient = httpClient,
                        config =
                            BybitPrivateClientConfig(
                                keyId = config.bybitPrivate.keyId!!,
                                signingCredential = config.bybitPrivate.signingCredential!!,
                                baseUrl = config.bybitPrivate.baseUrl,
                                recvWindowMillis = config.bybitPrivate.recvWindowMillis,
                                category = BybitTradingCategory.valueOf(config.bybitPrivate.category.uppercase()),
                                positionIdx = config.bybitPrivate.positionIdx,
                                accountType = config.bybitPrivate.accountType,
                            ),
                    ),
                config =
                    ExchangeExecutionConfig(
                        enabled = config.execution.enabled,
                        accountEquity = config.execution.accountEquity,
                        riskFraction = config.execution.riskFraction,
                        feeRate = config.execution.feeRate,
                        quantityStep = config.execution.quantityStep,
                        minQuantity = config.execution.minQuantity,
                        maxQuantity = config.execution.maxQuantity,
                        maxNotional = config.execution.maxNotional,
                    ),
            )
        } else {
            logger.info("private exchange client not configured")
            null
        }
    val paperLoopScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val paperLoopJob =
        if (config.runtimeMode == RuntimeMode.PAPER && config.paperLoop.enabled) {
            logger.info("paper trading loop enabled")
            PaperTradingLoop(
                marketDataSyncService = marketDataSyncService,
                paperTradingService = paperTradingService,
                config =
                    PaperTradingLoopConfig(
                        symbol = config.marketData.symbol,
                        timeframe = config.paperLoop.timeframe,
                        candleLimit = config.paperLoop.candleLimit,
                        syncLimit = config.paperLoop.syncLimit,
                        interval = Duration.ofSeconds(config.paperLoop.intervalSeconds),
                    ),
                onResult = { result -> alertingService.sendPaperLoopResult(result) },
                onFailure = { error -> alertingService.sendPaperLoopFailure(error) },
            ).start(paperLoopScope)
        } else {
            logger.info("paper trading loop disabled")
            null
        }
    val executionLoopScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val executionLoopJob =
        if (executionService != null && config.executionLoop.enabled) {
            logger.info("execution loop enabled intervalSeconds={}", config.executionLoop.intervalSeconds)
            ExchangeTradingLoop(
                marketDataSyncService = marketDataSyncService,
                executionService = executionService,
                config =
                    ExchangeTradingLoopConfig(
                        symbol = config.marketData.symbol,
                        timeframe = config.executionLoop.timeframe,
                        candleLimit = config.executionLoop.candleLimit,
                        syncLimit = config.executionLoop.syncLimit,
                        intervalSeconds = config.executionLoop.intervalSeconds,
                    ),
                onResult = { result -> alertingService.sendExecutionLoopResult(result) },
                onFailure = { error -> alertingService.sendExecutionLoopFailure(error) },
            ).start(executionLoopScope)
        } else {
            logger.info("execution loop disabled")
            null
        }

    runBlocking {
        alertingService.send(
            AlertMessage(
                severity = AlertSeverity.INFO,
                title = "봇 시작",
                body = "Bybit Trader가 ${config.runtimeMode.toKoreanLabel()} 모드로 시작됐어요.",
            ),
        )
    }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            runBlocking {
                alertingService.send(
                    AlertMessage(
                        severity = AlertSeverity.INFO,
                        title = "봇 종료",
                        body = "Bybit Trader가 종료되고 있어요.",
                    ),
                )
            }
            paperLoopJob?.cancel()
            executionLoopJob?.cancel()
            paperLoopScope.cancel()
            executionLoopScope.cancel()
            httpClient.close()
        },
    )

    val server =
        embeddedServer(ServerCIO, host = config.api.host, port = config.api.port) {
            configureApi(
                stateStore = ledger,
                controlService = controlService,
                marketDataSyncService = marketDataSyncService,
                backtestService = backtestService,
                meanReversionSweepService = meanReversionSweepService,
                volumeFlowBacktestService = volumeFlowBacktestService,
                volumeFlowAggressiveBacktestService = volumeFlowAggressiveBacktestService,
                volumeFlowCompositeBacktestService = volumeFlowCompositeBacktestService,
                volumeFlowCompositeCurrentConfigProvider =
                    FileVolumeFlowCompositeCurrentConfigProvider(
                        Path.of(config.volumeFlowComposite.currentConfigPath),
                    ),
                volumeFlowSweepService = volumeFlowSweepService,
                paperTradingService = paperTradingService,
                paperTradingReportStore = ledger,
                executionService = executionService,
                runtimeMode = config.runtimeMode.name,
                onControlResult = { result -> alertingService.sendControlResult(result) },
                onSmokeAlert = { message -> alertingService.sendSmokeAlert(message) },
                controlCredential = config.api.controlCredential,
            )
        }

    logger.info("http server starting host={} port={}", config.api.host, config.api.port)
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

private fun createPaperStrategy(strategy: PaperStrategyKind): TradingStrategy =
    when (strategy) {
        PaperStrategyKind.VOLUME_FLOW_AGGRESSIVE -> VolumeFlowAggressiveStrategy()
        PaperStrategyKind.MEAN_REVERSION -> MeanReversionStrategy()
    }

private suspend fun AlertingService.sendControlResult(result: ControlResult) {
    send(
        AlertMessage(
            severity =
                when (result.action.name) {
                    "EMERGENCY_STOP" -> AlertSeverity.CRITICAL
                    "PAUSE_ALL",
                    "PAUSE_NEW_ENTRIES",
                    -> AlertSeverity.WARNING
                    else -> AlertSeverity.INFO
                },
            title = result.action.toKoreanTitle(),
            body =
                "봇 상태가 ${result.previousMode.toKoreanLabel()}에서 ${result.newMode.toKoreanLabel()}로 바뀌었어요. " +
                    "변경 시각: ${result.changedAt}",
        ),
    )
}

private suspend fun AlertingService.sendSmokeAlert(message: String): SmokeAlertDeliveryResponse {
    val result =
        send(
            AlertMessage(
                severity = AlertSeverity.INFO,
                title = "기능 테스트 알림",
                body = message,
            ),
        )
    return SmokeAlertDeliveryResponse(
        delivered = result.delivered,
        sinkName = result.sinkName,
        failureReason = result.failureReason,
    )
}

private suspend fun AlertingService.sendPaperLoopResult(result: PaperEvaluationResult) {
    when (result.status) {
        PaperEvaluationStatus.FILLED ->
            send(
                AlertMessage(
                    severity = AlertSeverity.INFO,
                    title = "모의 주문 기록",
                    body =
                        "${result.symbol.value} ${result.timeframe.name} 모의 주문을 체결로 기록했어요. " +
                            "신호 ID: ${result.signalId}, 주문 ID: ${result.orderId}, 수수료: ${result.fee?.toPlainString()}",
                ),
            )

        PaperEvaluationStatus.REJECTED ->
            send(
                AlertMessage(
                    severity = AlertSeverity.WARNING,
                    title = "모의 주문 보류",
                    body =
                        "${result.symbol.value} ${result.timeframe.name} 신호가 주문 조건을 통과하지 못했어요. 사유 코드: " +
                            result.reasonCodes.joinToString(","),
                ),
            )

        PaperEvaluationStatus.SKIPPED_BY_MODE,
        PaperEvaluationStatus.NO_TRADE,
        -> Unit
    }
}

private suspend fun AlertingService.sendPaperLoopFailure(error: Throwable) {
    send(
        AlertMessage(
            severity = AlertSeverity.WARNING,
            title = "모의 거래 점검 필요",
            body = "모의 거래 루프에서 오류가 발생했어요. 로그에서 ${error::class.simpleName ?: "알 수 없는 오류"} 내용을 확인해 주세요.",
        ),
    )
}

private suspend fun AlertingService.sendExecutionLoopResult(result: ExchangeEvaluationResult) {
    when (result.status) {
        ExchangeEvaluationStatus.SUBMITTED ->
            send(
                AlertMessage(
                    severity = AlertSeverity.INFO,
                    title = "실거래 주문 제출",
                    body =
                        "${result.symbol.value} ${result.timeframe.name} 실거래 주문을 제출했어요. " +
                            "수량: ${result.quantity?.toPlainString()}, 거래소 주문 ID: ${result.exchangeOrderId}, " +
                            "내부 주문 ID: ${result.orderId}",
                ),
            )

        ExchangeEvaluationStatus.REJECTED ->
            send(
                AlertMessage(
                    severity = AlertSeverity.WARNING,
                    title = "실주문 보류",
                    body =
                        "${result.symbol.value} ${result.timeframe.name} 신호가 주문 조건을 통과하지 못했어요. 사유 코드: " +
                            result.reasonCodes.joinToString(","),
                ),
            )

        ExchangeEvaluationStatus.DISABLED,
        ExchangeEvaluationStatus.SKIPPED_BY_MODE,
        ExchangeEvaluationStatus.NO_TRADE,
        -> Unit
    }
}

private suspend fun AlertingService.sendExecutionLoopFailure(error: Throwable) {
    send(
        AlertMessage(
            severity = AlertSeverity.WARNING,
            title = "실거래 점검 필요",
            body = "실거래 루프에서 오류가 발생했어요. 로그에서 ${error::class.simpleName ?: "알 수 없는 오류"} 내용을 확인해 주세요.",
        ),
    )
}

private fun RuntimeMode.toKoreanLabel(): String =
    when (this) {
        RuntimeMode.PAPER -> "모의거래"
        RuntimeMode.TESTNET -> "테스트넷"
        RuntimeMode.LIVE -> "실거래"
    }

private fun ControlAction.toKoreanTitle(): String =
    when (this) {
        ControlAction.PAUSE_NEW_ENTRIES -> "신규 진입 정지"
        ControlAction.PAUSE_ALL -> "봇 정지"
        ControlAction.RESUME -> "봇 재가동"
        ControlAction.EMERGENCY_STOP -> "긴급 정지"
    }

private fun BotMode.toKoreanLabel(): String =
    when (this) {
        BotMode.RUNNING -> "운영 중"
        BotMode.PAUSE_NEW_ENTRIES -> "신규 진입 정지"
        BotMode.PAUSE_ALL -> "전체 정지"
        BotMode.EMERGENCY_STOP -> "긴급 정지"
        BotMode.RESUME_PENDING_CHECK -> "운영 중"
    }
