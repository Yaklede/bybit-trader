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
import dev.yaklede.bybittrader.api.strategy.StrategyProfileService
import dev.yaklede.bybittrader.domain.BotMode
import dev.yaklede.bybittrader.domain.ControlAction
import dev.yaklede.bybittrader.engine.backtest.BacktestRunner
import dev.yaklede.bybittrader.engine.backtest.BacktestService
import dev.yaklede.bybittrader.engine.backtest.MeanReversionSweepService
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowAggressiveBacktestService
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowAggressiveProfiles
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowBacktestService
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowCompositeBacktestService
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowSweepService
import dev.yaklede.bybittrader.engine.backtest.positionPolicy
import dev.yaklede.bybittrader.engine.control.BotControlService
import dev.yaklede.bybittrader.engine.control.BotResumeReadinessService
import dev.yaklede.bybittrader.engine.control.ControlResult
import dev.yaklede.bybittrader.engine.execution.ExchangeEvaluationResult
import dev.yaklede.bybittrader.engine.execution.ExchangeEvaluationStatus
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionConfig
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionException
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionService
import dev.yaklede.bybittrader.engine.execution.ExchangeTradingLoop
import dev.yaklede.bybittrader.engine.execution.ExchangeTradingLoopConfig
import dev.yaklede.bybittrader.engine.execution.ExecutionLifecycleEvent
import dev.yaklede.bybittrader.engine.execution.ExecutionLifecycleState
import dev.yaklede.bybittrader.engine.execution.ExecutionRuntimeMode
import dev.yaklede.bybittrader.engine.execution.ExecutionTradeClosure
import dev.yaklede.bybittrader.engine.market.MarketDataSyncService
import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketCaptureLoop
import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketCaptureLoopConfig
import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketCaptureService
import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketCaptureStatusService
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
import dev.yaklede.bybittrader.exchange.bybit.BybitPublicMarketCaptureClient
import dev.yaklede.bybittrader.exchange.bybit.BybitTradingCategory
import dev.yaklede.bybittrader.ledger.SqlDelightLedger
import dev.yaklede.bybittrader.ledger.createLedgerDatabase
import dev.yaklede.bybittrader.ledger.db.LedgerDatabase
import dev.yaklede.bybittrader.ledger.ensureAdditiveLedgerSchema
import dev.yaklede.bybittrader.strategy.MeanReversionStrategy
import dev.yaklede.bybittrader.strategy.TradingStrategy
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.server.cio.CIO as ServerCIO

private val logger = LoggerFactory.getLogger("dev.yaklede.bybittrader.app")

fun main() {
    val config = AppConfig.fromEnvironment()
    logger.info(
        "application starting mode={} api={}:{} privateExecution={} executionLoop={} forwardCapture={} symbol={} timeframes={}",
        config.runtimeMode.name,
        config.api.host,
        config.api.port,
        config.execution.enabled,
        config.executionLoop.enabled,
        config.forwardMarketCapture.enabled,
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
    val aggressiveRuntimeProfile = VolumeFlowAggressiveProfiles.current()
    val strategyProfileService =
        StrategyProfileService(
            statePath = Path.of(config.strategyProfiles.statePath),
            runtimeExecutionContract = config.execution.toAggressiveExecutionContract(),
        )
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
                strategy = VolumeFlowAggressiveStrategy(aggressiveRuntimeProfile.strategyConfig),
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
                        useLiveAccountEquity = config.execution.useLiveAccountEquity,
                        riskFraction = config.execution.riskFraction,
                        feeRate = config.execution.feeRate,
                        slippageBufferRate = config.execution.slippageBufferRate,
                        quantityStep = config.execution.quantityStep,
                        minQuantity = config.execution.minQuantity,
                        maxQuantity = config.execution.maxQuantity,
                        maxNotional = config.execution.maxNotional,
                        leverage = config.execution.leverage,
                        liquidationBufferPct = config.execution.liquidationBufferPct,
                    ),
                runtimeMode = config.runtimeMode.toExecutionRuntimeMode(),
                positionPolicy = aggressiveRuntimeProfile.strategyConfig.positionPolicy(),
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
                        alertBatchLimit = config.executionLoop.alertBatchLimit,
                        intervalSeconds = config.executionLoop.intervalSeconds,
                    ),
                onResult = { result -> alertingService.sendExecutionLoopResult(result) },
                onClosure = { closure -> alertingService.sendExecutionClosure(closure) },
                onLifecycleEvent = { event -> alertingService.sendExecutionLifecycleEvent(event) },
                onFailure = { error -> alertingService.sendExecutionLoopFailure(error) },
            ).start(executionLoopScope)
        } else {
            logger.info("execution loop disabled")
            null
        }
    val forwardMarketCaptureScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val forwardMarketCaptureJob =
        if (config.forwardMarketCapture.enabled) {
            logger.info(
                "forward market capture enabled symbol={} depth={} streamUrl={}",
                config.marketData.symbol.value,
                config.forwardMarketCapture.orderBookDepth,
                config.forwardMarketCapture.publicWebSocketUrl,
            )
            ForwardMarketCaptureLoop(
                feed =
                    BybitPublicMarketCaptureClient(
                        httpClient = httpClient,
                        baseUrl = config.forwardMarketCapture.publicWebSocketUrl,
                        orderBookDepth = config.forwardMarketCapture.orderBookDepth,
                    ),
                captureService = ForwardMarketCaptureService(store = ledger),
                config = ForwardMarketCaptureLoopConfig(symbol = config.marketData.symbol),
                onFailure = { error -> alertingService.sendForwardMarketCaptureFailure(error) },
            ).start(forwardMarketCaptureScope)
        } else {
            logger.info("forward market capture disabled")
            null
        }
    val resumeReadinessScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val resumeReadinessJob =
        BotResumeReadinessService(
            stateStore = ledger,
            controlService = controlService,
            readinessProbe = {
                require(config.runtimeMode != RuntimeMode.PAPER || !config.paperLoop.enabled || paperLoopJob?.isActive == true) {
                    "Paper trading loop is not active."
                }
                require(!config.executionLoop.enabled || executionLoopJob?.isActive == true) {
                    "Execution trading loop is not active."
                }
                marketDataSyncService.ticker(config.marketData.symbol)
                executionService?.accountBalance(DEFAULT_ACCOUNT_COIN)
                executionService?.reconcile(config.marketData.symbol)
            },
        ).start(
            scope = resumeReadinessScope,
            onConfirmed = { result -> alertingService.sendControlResult(result) },
        )

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
            forwardMarketCaptureJob?.cancel()
            resumeReadinessJob.cancel()
            paperLoopScope.cancel()
            executionLoopScope.cancel()
            forwardMarketCaptureScope.cancel()
            resumeReadinessScope.cancel()
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
                strategyProfileService = strategyProfileService,
                runtimeMode = config.runtimeMode.name,
                forwardMarketCaptureStatusService = ForwardMarketCaptureStatusService(store = ledger),
                forwardMarketCaptureEnabled = config.forwardMarketCapture.enabled,
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
    } else {
        ensureAdditiveLedgerSchema(driver)
    }
    return createLedgerDatabase(driver)
}

private fun createJsonHttpClient(): HttpClient =
    HttpClient(ClientCIO) {
        install(WebSockets)
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
            body = loopFailureAlertBody(loopName = "모의 거래", error = error),
        ),
    )
}

private suspend fun AlertingService.sendForwardMarketCaptureFailure(error: Throwable) {
    send(
        AlertMessage(
            severity = AlertSeverity.WARNING,
            title = "시장 흐름 수집 점검 필요",
            body = loopFailureAlertBody(loopName = "시장 흐름 수집", error = error),
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

        ExchangeEvaluationStatus.EXIT_SUBMITTED ->
            send(
                AlertMessage(
                    severity = AlertSeverity.INFO,
                    title = "보유 시간 종료 주문 제출",
                    body =
                        "${result.symbol.value} ${result.timeframe.name} 포지션이 최대 보유 시간에 도달해 종료 주문을 제출했어요. " +
                            "수량: ${result.quantity?.toPlainString()}, 거래소 주문 ID: ${result.exchangeOrderId}",
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
            body = loopFailureAlertBody(loopName = "실거래", error = error),
        ),
    )
}

private suspend fun AlertingService.sendExecutionLifecycleEvent(event: ExecutionLifecycleEvent) {
    val message =
        when (event.state) {
            ExecutionLifecycleState.PARTIALLY_FILLED ->
                AlertMessage(
                    severity = AlertSeverity.INFO,
                    title = "실거래 부분 체결 확인",
                    body =
                        "${event.symbol.value} ${event.side.name} 진입 주문이 부분 체결됐어요. " +
                            "요청 수량: ${event.requestedQuantity.toPlainString()}, " +
                            "체결 수량: ${event.filledQuantity?.toPlainString()}, " +
                            "평균 체결가: ${event.fillVwap?.toPlainString()}",
                )

            ExecutionLifecycleState.OPEN_UNPROTECTED ->
                AlertMessage(
                    severity = AlertSeverity.CRITICAL,
                    title = "실거래 보호 주문 없음",
                    body =
                        "${event.symbol.value} ${event.side.name} 포지션이 열렸지만 TP/SL을 모두 확인하지 못했어요. " +
                            "수량: ${event.filledQuantity?.toPlainString()}, 진입가: ${event.fillVwap?.toPlainString()}. " +
                            "Bybit 포지션의 보호 주문을 즉시 확인해 주세요.",
                )

            ExecutionLifecycleState.OPEN_PROTECTED ->
                AlertMessage(
                    severity = AlertSeverity.INFO,
                    title = "실거래 포지션 보호 확인",
                    body =
                        "${event.symbol.value} ${event.side.name} 포지션의 TP/SL을 확인했어요. " +
                            "수량: ${event.filledQuantity?.toPlainString()}, " +
                            "익절가: ${event.takeProfit?.toPlainString()}, 손절가: ${event.stopLoss?.toPlainString()}",
                )

            ExecutionLifecycleState.ERROR ->
                AlertMessage(
                    severity = AlertSeverity.CRITICAL,
                    title = "실거래 상태 확인 실패",
                    body =
                        "${event.symbol.value} 주문 상태를 확정하지 못했어요. 사유 코드: ${event.reasonCode}. " +
                            "신규 진입을 중지하고 거래소 주문과 포지션을 확인해 주세요.",
                )

            ExecutionLifecycleState.ENTRY_SUBMITTED,
            ExecutionLifecycleState.EXIT_SUBMITTED,
            ExecutionLifecycleState.CLOSED,
            -> null
        }
    if (message != null) send(message)
}

private suspend fun AlertingService.sendExecutionClosure(closure: ExecutionTradeClosure): Boolean {
    val duration = Duration.between(closure.openedAt, closure.closedAt)
    return send(
        AlertMessage(
            severity = if (closure.netPnl >= BigDecimal.ZERO) AlertSeverity.INFO else AlertSeverity.WARNING,
            title = "실거래 포지션 종료",
            body =
                "${closure.symbol.value} ${closure.side.name} 포지션이 종료됐어요.\n" +
                    "진입가: ${closure.entryPrice.toPlainString()}, 종료가: ${closure.exitPrice.toPlainString()}, " +
                    "수량: ${closure.quantity.toPlainString()}\n" +
                    "수수료: ${closure.fees.toPlainString()}, 순손익: ${closure.netPnl.toPlainString()}\n" +
                    "보유 시간: ${duration.toMinutes()}분, 종료 사유: ${closure.exitReason}",
        ),
    ).delivered
}

internal fun loopFailureAlertBody(
    loopName: String,
    error: Throwable,
): String {
    val errorType = error::class.simpleName ?: "알 수 없는 오류"
    val reason = error.message?.sanitizeAlertDetail()?.takeIf { it.isNotBlank() } ?: "오류 메시지가 비어 있어요."
    return "$loopName 루프에서 오류가 발생했어요.\n" +
        "오류: $errorType\n" +
        "원인: $reason\n" +
        "확인할 일: ${error.recoveryAction()}"
}

private fun Throwable.recoveryAction(): String =
    when {
        message.orEmpty().contains("candles are required", ignoreCase = true) ->
            "히스토리 캔들 동기화가 충분한지 확인해 주세요. 공격형 M5 전략은 약 60일 이상 캔들이 필요해요."
        message.orEmpty().contains("Candle limit", ignoreCase = true) ->
            "BOT_EXECUTION_CANDLE_LIMIT와 BOT_EXECUTION_SYNC_LIMIT 설정을 확인해 주세요."
        this is ExchangeExecutionException ->
            "Bybit API 권한, 계정 모드, 주문 가능 지역, 포지션 모드를 확인해 주세요."
        else ->
            "서버 로그에서 같은 시각의 stack trace와 최근 배포 설정을 확인해 주세요."
    }

private fun String.sanitizeAlertDetail(): String =
    take(600)
        .replace(Regex("(?i)(api[-_ ]?key|secret|signature|token|credential)"), "[redacted]")
        .replace(Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+=*"), "$1[redacted]")
        .replace(Regex("(?i)(x-bapi-[a-z-]+\\s*[:=]\\s*)[^\\s,;]+"), "$1[redacted]")

private fun RuntimeMode.toKoreanLabel(): String =
    when (this) {
        RuntimeMode.PAPER -> "모의거래"
        RuntimeMode.TESTNET -> "테스트넷"
        RuntimeMode.LIVE -> "실거래"
    }

private fun RuntimeMode.toExecutionRuntimeMode(): ExecutionRuntimeMode =
    when (this) {
        RuntimeMode.PAPER,
        RuntimeMode.TESTNET,
        -> ExecutionRuntimeMode.TESTNET
        RuntimeMode.LIVE -> ExecutionRuntimeMode.LIVE
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
        BotMode.RESUME_PENDING_CHECK -> "재가동 확인 중"
    }

private const val DEFAULT_ACCOUNT_COIN = "USDT"
