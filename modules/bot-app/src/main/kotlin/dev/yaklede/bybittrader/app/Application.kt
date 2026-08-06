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
import dev.yaklede.bybittrader.api.strategy.VolumeConfirmedTrendApprovalArtifactExportResponse
import dev.yaklede.bybittrader.domain.BotMode
import dev.yaklede.bybittrader.domain.ControlAction
import dev.yaklede.bybittrader.engine.backtest.BacktestRunner
import dev.yaklede.bybittrader.engine.backtest.BacktestService
import dev.yaklede.bybittrader.engine.backtest.MeanReversionSweepService
import dev.yaklede.bybittrader.engine.backtest.MultiHorizonMomentumResearchProfiles
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
import dev.yaklede.bybittrader.engine.execution.ExchangeReconciliationLoop
import dev.yaklede.bybittrader.engine.execution.ExchangeReconciliationLoopConfig
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
import dev.yaklede.bybittrader.engine.market.flow.FundingRateSyncService
import dev.yaklede.bybittrader.engine.market.maker.MakerShadowConfig
import dev.yaklede.bybittrader.engine.market.maker.MakerShadowEngine
import dev.yaklede.bybittrader.engine.paper.PaperEvaluationResult
import dev.yaklede.bybittrader.engine.paper.PaperEvaluationStatus
import dev.yaklede.bybittrader.engine.paper.PaperTradingConfig
import dev.yaklede.bybittrader.engine.paper.PaperTradingLoop
import dev.yaklede.bybittrader.engine.paper.PaperTradingLoopConfig
import dev.yaklede.bybittrader.engine.paper.PaperTradingService
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalService
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowConfig
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowEvaluationStatus
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowLoop
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowLoopConfig
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowService
import dev.yaklede.bybittrader.engine.strategy.VolumeFlowAggressiveStrategy
import dev.yaklede.bybittrader.exchange.bybit.BybitMarketDataClient
import dev.yaklede.bybittrader.exchange.bybit.BybitPrivateClient
import dev.yaklede.bybittrader.exchange.bybit.BybitPrivateClientConfig
import dev.yaklede.bybittrader.exchange.bybit.BybitPrivateExecutionStream
import dev.yaklede.bybittrader.exchange.bybit.BybitPrivateExecutionStreamConfig
import dev.yaklede.bybittrader.exchange.bybit.BybitPublicMarketCaptureClient
import dev.yaklede.bybittrader.exchange.bybit.BybitTradingCategory
import dev.yaklede.bybittrader.ledger.GzipNdjsonForwardMarketRawEventArchive
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
import java.util.UUID
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.server.cio.CIO as ServerCIO

private val logger = LoggerFactory.getLogger("dev.yaklede.bybittrader.app")

fun main() {
    val config = AppConfig.fromEnvironment()
    logger.info(
        "application starting mode={} api={}:{} privateExecution={} privateExecutionStream={} reconciliationLoop={} executionLoop={} forwardCapture={} rawArchive={} makerShadow={} trendShadow={} trendLive={} symbol={} timeframes={}",
        config.runtimeMode.name,
        config.api.host,
        config.api.port,
        config.execution.enabled,
        config.bybitPrivate.privateExecutionStreamEnabled,
        config.executionReconciliation.enabled,
        config.executionLoop.enabled,
        config.forwardMarketCapture.enabled,
        config.forwardMarketCapture.rawArchiveEnabled,
        config.makerShadow.enabled,
        config.volumeConfirmedTrendShadow.enabled,
        config.volumeConfirmedTrendLive.enabled,
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
    val publicMarketDataClient =
        BybitMarketDataClient(
            httpClient = httpClient,
            baseUrl = config.marketData.bybitPublicBaseUrl,
        )
    val marketDataSyncService =
        MarketDataSyncService(
            marketDataFeed = publicMarketDataClient,
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
            runtimeStateStore = ledger,
            strategy = createPaperStrategy(config.paperLoop.strategy),
            config = createPaperTradingConfig(config),
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
                        minimumNetRiskReward = config.execution.minimumNetRiskReward,
                        priceTick = config.execution.priceTick,
                        protectionGracePeriod = config.execution.protectionGracePeriod,
                        maximumEntryDelay = config.execution.maximumEntryDelay,
                        maximumActualRiskOverrunFraction = config.execution.maximumActualRiskOverrunFraction,
                        safetyVerificationAttempts = config.execution.safetyVerificationAttempts,
                        safetyVerificationInterval = config.execution.safetyVerificationInterval,
                        circuitBreakerEnabled = config.execution.circuitBreakerEnabled,
                        maximumDailyLossFraction = config.execution.maximumDailyLossFraction,
                        maximumAccountDrawdownFraction = config.execution.maximumAccountDrawdownFraction,
                        maximumConsecutiveLosses = config.execution.maximumConsecutiveLosses,
                        riskStateMaximumAge = config.execution.riskStateMaximumAge,
                        walletReconciliationEnabled = config.execution.walletReconciliationEnabled,
                        walletReconciliationTolerance = config.execution.walletReconciliationTolerance,
                        walletReconciliationMaximumAge = config.execution.walletReconciliationMaximumAge,
                        walletReconciliationConfirmedMismatchCount =
                            config.execution.walletReconciliationConfirmedMismatchCount,
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
    val executionSafetyAlertPolicy = ExchangeSafetyAlertPolicy()
    val executionReconciliationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val executionReconciliationLoop =
        if (executionService != null && config.executionReconciliation.enabled) {
            logger.info(
                "execution reconciliation loop enabled intervalSeconds={}",
                config.executionReconciliation.intervalSeconds,
            )
            ExchangeReconciliationLoop(
                executionService = executionService,
                config =
                    ExchangeReconciliationLoopConfig(
                        symbol = config.marketData.symbol,
                        alertBatchLimit = config.executionReconciliation.alertBatchLimit,
                        intervalSeconds = config.executionReconciliation.intervalSeconds,
                    ),
                onClosure = { closure -> alertingService.sendExecutionClosure(closure) },
                onLifecycleEvent = { event -> alertingService.sendExecutionLifecycleEvent(event) },
                onSafetyResult = { result ->
                    alertingService.sendExchangeSafetyResult(result, executionSafetyAlertPolicy)
                },
                onFailure = { error -> alertingService.sendExecutionReconciliationFailure(error) },
            )
        } else {
            logger.info("execution reconciliation loop disabled")
            null
        }
    val executionReconciliationJob = executionReconciliationLoop?.start(executionReconciliationScope)
    val privateExecutionStreamScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val privateExecutionStreamJob =
        if (executionService != null &&
            executionReconciliationLoop != null &&
            config.bybitPrivate.privateExecutionStreamEnabled
        ) {
            logger.info(
                "private execution stream enabled symbol={} streamUrl={}",
                config.marketData.symbol.value,
                config.bybitPrivate.privateWebSocketUrl,
            )
            BybitPrivateExecutionStream(
                httpClient = httpClient,
                config =
                    BybitPrivateExecutionStreamConfig(
                        keyId = config.bybitPrivate.keyId!!,
                        signingCredential = config.bybitPrivate.signingCredential!!,
                        webSocketUrl = config.bybitPrivate.privateWebSocketUrl,
                    ),
                onExecution = { execution ->
                    if (execution.symbol == config.marketData.symbol) {
                        logger.info(
                            "private execution observed; requesting immediate reconciliation symbol={} executionId={} closedSize={}",
                            execution.symbol.value,
                            execution.executionId,
                            execution.closedSize?.toPlainString(),
                        )
                        try {
                            executionService.persistExecutionFill(execution)
                        } finally {
                            executionReconciliationLoop.requestImmediateReconciliation()
                        }
                    }
                },
                onOrder = { order ->
                    if (order.symbol == config.marketData.symbol) {
                        logger.info(
                            "private order observed; updating lifecycle symbol={} orderId={} clientOrderId={} status={}",
                            order.symbol.value,
                            order.exchangeOrderId,
                            order.clientOrderId,
                            order.status.name,
                        )
                        try {
                            executionService.observeOrderUpdate(order)?.let { event ->
                                alertingService.sendExecutionLifecycleEvent(event)
                            }
                        } finally {
                            executionReconciliationLoop.requestImmediateReconciliation()
                        }
                    }
                },
            ).start(privateExecutionStreamScope)
        } else {
            logger.info("private execution stream disabled")
            null
        }
    val executionLoopScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val executionRiskAlertPolicy = ExecutionRiskAlertPolicy()
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
                onResult = { result -> alertingService.sendExecutionLoopResult(result, executionRiskAlertPolicy) },
                onFailure = { error -> alertingService.sendExecutionLoopFailure(error) },
            ).start(executionLoopScope)
        } else {
            logger.info("execution loop disabled")
            null
        }
    val trendShadowScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val trendShadowAlertPolicy = VolumeConfirmedTrendShadowAlertPolicy()
    val trendShadowService =
        if (config.volumeConfirmedTrendShadow.enabled) {
            val settings = config.volumeConfirmedTrendShadow
            val runtimeDefinition =
                loadVolumeConfirmedTrendRuntimeDefinition(
                    protocolPath = Path.of(settings.protocolPath),
                    bootstrapPath = Path.of(settings.bootstrapPath),
                )
            require(runtimeDefinition.protocol.symbol == config.marketData.symbol) {
                "Trend shadow protocol symbol does not match BOT_SYMBOL."
            }
            logger.info(
                "volume-confirmed trend shadow enabled protocolId={} candidateId={} protocolSha256={} initialEquity={} bootstrapLastH4={}",
                runtimeDefinition.protocol.protocolId,
                runtimeDefinition.protocol.candidateId,
                runtimeDefinition.protocol.protocolSha256,
                settings.initialEquity,
                runtimeDefinition.bootstrap.indicatorState.lastBarOpenedAt,
            )
            VolumeConfirmedTrendShadowService(
                candleStore = ledger,
                flowStore = ledger,
                shadowStore = ledger,
                config =
                    VolumeConfirmedTrendShadowConfig(
                        symbol = config.marketData.symbol,
                        bootstrap = runtimeDefinition.bootstrap,
                        initialEquity = settings.initialEquity.toDouble(),
                        parameters = runtimeDefinition.protocol.parameters,
                        executionContract = runtimeDefinition.protocol.executionContract,
                        maximumObservationDelay = settings.maximumObservationDelay,
                    ),
            )
        } else {
            logger.info("volume-confirmed trend shadow disabled")
            null
        }
    val trendApprovalDefinition =
        loadVolumeConfirmedTrendApprovalDefinition(
            protocolPath = Path.of(config.volumeConfirmedTrendShadow.protocolPath),
        )
    val trendApprovalService =
        VolumeConfirmedTrendApprovalService(
            historicalEvidence = trendApprovalDefinition.historicalEvidence,
            forwardPolicy = trendApprovalDefinition.forwardPolicy,
            shadowReportProvider = { trendShadowService?.report(100_000) },
        )
    val trendApprovalArtifactWriter =
        trendShadowService?.let { shadowService ->
            VolumeConfirmedTrendApprovalArtifactWriter(
                outputDirectory = Path.of(config.volumeConfirmedTrendLive.approvalExportDirectory),
                shadowReportProvider = { shadowService.report(100_000) },
                approvalReportProvider = trendApprovalService::evaluate,
            )
        }
    logger.info(
        "volume-confirmed trend approval evidence loaded protocolId={} policyId={} automaticExecution=false liveExecution=false",
        trendApprovalDefinition.historicalEvidence.protocolId,
        trendApprovalDefinition.forwardPolicy.policyId,
    )
    val trendShadowJob =
        trendShadowService?.let { shadowService ->
            val settings = config.volumeConfirmedTrendShadow
            VolumeConfirmedTrendShadowLoop(
                marketDataSyncService = marketDataSyncService,
                fundingRateSyncService =
                    FundingRateSyncService(
                        feed = publicMarketDataClient,
                        store = ledger,
                    ),
                shadowService = shadowService,
                config =
                    VolumeConfirmedTrendShadowLoopConfig(
                        symbol = config.marketData.symbol,
                        recentSyncLimit = settings.recentSyncLimit,
                        historyPageLimit = settings.historyPageLimit,
                        maximumHistoryRequests = settings.maximumHistoryRequests,
                        boundaryDelay = settings.boundaryDelay,
                        failureRetryDelay = settings.failureRetryDelay,
                    ),
                onResult = { result ->
                    logger.info(
                        "volume-confirmed trend shadow evaluated status={} sessionId={} h4Bars={} events={} equity={} position={}",
                        result.status.name,
                        result.state.sessionId,
                        result.evaluatedH4Bars,
                        result.emittedEvents,
                        result.state.equity,
                        result.state.position
                            ?.side
                            ?.name ?: "FLAT",
                    )
                    if (result.status == VolumeConfirmedTrendShadowEvaluationStatus.SESSION_RESET) {
                        alertingService.send(
                            AlertMessage(
                                severity = AlertSeverity.WARNING,
                                title = "추세 검증 세션 재시작",
                                body = "확정 H4 평가 공백이 감지되어 가상 포지션을 정리하고 검증 기간을 다시 시작했어요.",
                            ),
                        )
                    }
                    trendShadowAlertPolicy.recordSuccess()
                },
                onFailure = { error ->
                    if (trendShadowAlertPolicy.shouldAlert(error)) {
                        alertingService.send(
                            AlertMessage(
                                severity = AlertSeverity.WARNING,
                                title = "추세 Shadow 점검 필요",
                                body =
                                    "가상 검증 루프가 안전하게 중단됐어요. " +
                                        "오류: ${error::class.simpleName}. 원인: ${error.message ?: "상세 원인 없음"}",
                            ),
                        )
                    }
                },
            ).start(trendShadowScope)
        }
    val forwardMarketCaptureScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val forwardMarketRawEventArchive =
        if (config.forwardMarketCapture.enabled && config.forwardMarketCapture.rawArchiveEnabled) {
            GzipNdjsonForwardMarketRawEventArchive(Path.of(config.forwardMarketCapture.rawArchivePath))
        } else {
            null
        }
    val makerShadowEngine =
        if (config.makerShadow.enabled) {
            val settings = config.makerShadow
            val sessionId = settings.sessionId ?: "maker-shadow-${UUID.randomUUID()}"
            logger.info(
                "maker shadow enabled sessionId={} initialEquity={} orderQuantity={} maxNotional={} queueMultiplier={} minSpreadBps={} makerFeeRate={} takerFeeRate={}",
                sessionId,
                settings.initialEquity,
                settings.orderQuantity,
                settings.maxNotional,
                settings.queueMultiplier,
                settings.minSpreadBps,
                settings.makerFeeRate,
                settings.takerFeeRate,
            )
            MakerShadowEngine(
                config =
                    MakerShadowConfig(
                        sessionId = sessionId,
                        symbol = config.marketData.symbol,
                        initialEquity = settings.initialEquity,
                        orderQuantity = settings.orderQuantity,
                        maxNotional = settings.maxNotional,
                        queueMultiplier = settings.queueMultiplier,
                        queueBufferQuantity = settings.queueBufferQuantity,
                        minSpreadBps = settings.minSpreadBps,
                        makerFeeRate = settings.makerFeeRate,
                        takerFeeRate = settings.takerFeeRate,
                        takerExitSlippageBps = settings.takerExitSlippageBps,
                        maxQuoteAge = settings.maxQuoteAge,
                        maxHoldingDuration = settings.maxHoldingDuration,
                        maxEventDelay = settings.maxEventDelay,
                    ),
                ledger = ledger,
            )
        } else {
            logger.info("maker shadow disabled")
            null
        }
    val forwardMarketCaptureLoop =
        if (config.forwardMarketCapture.enabled) {
            logger.info(
                "forward market capture enabled symbol={} depth={} streamUrl={} rawArchive={} rawArchivePath={}",
                config.marketData.symbol.value,
                config.forwardMarketCapture.orderBookDepth,
                config.forwardMarketCapture.publicWebSocketUrl,
                config.forwardMarketCapture.rawArchiveEnabled,
                config.forwardMarketCapture.rawArchivePath,
            )
            ForwardMarketCaptureLoop(
                feed =
                    BybitPublicMarketCaptureClient(
                        httpClient = httpClient,
                        baseUrl = config.forwardMarketCapture.publicWebSocketUrl,
                        orderBookDepth = config.forwardMarketCapture.orderBookDepth,
                    ),
                captureService =
                    ForwardMarketCaptureService(
                        store = ledger,
                        rawEventArchive = forwardMarketRawEventArchive,
                        batchObservers = listOfNotNull(makerShadowEngine),
                    ),
                config = ForwardMarketCaptureLoopConfig(symbol = config.marketData.symbol),
                onFailure = { error -> alertingService.sendForwardMarketCaptureFailure(error) },
            ).also { loop -> loop.start(forwardMarketCaptureScope) }
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
                require(!config.executionReconciliation.enabled || executionReconciliationJob?.isActive == true) {
                    "Execution reconciliation loop is not active."
                }
                require(!config.volumeConfirmedTrendShadow.enabled || trendShadowJob?.isActive == true) {
                    "Volume-confirmed trend shadow loop is not active."
                }
                marketDataSyncService.ticker(config.marketData.symbol)
                executionService?.accountBalance(DEFAULT_ACCOUNT_COIN)
                executionService?.reconcile(config.marketData.symbol)
            },
        ).start(
            scope = resumeReadinessScope,
            onConfirmed = { result ->
                executionSafetyAlertPolicy.reset()
                alertingService.sendControlResult(result)
            },
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
                forwardMarketCaptureLoop?.stop()
            }
            privateExecutionStreamJob?.cancel()
            paperLoopJob?.cancel()
            executionReconciliationJob?.cancel()
            executionLoopJob?.cancel()
            trendShadowJob?.cancel()
            resumeReadinessJob.cancel()
            paperLoopScope.cancel()
            executionReconciliationScope.cancel()
            executionLoopScope.cancel()
            trendShadowScope.cancel()
            privateExecutionStreamScope.cancel()
            forwardMarketCaptureScope.cancel()
            forwardMarketRawEventArchive?.close()
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
                controlSymbol = config.marketData.symbol,
                strategyProfileService = strategyProfileService,
                volumeConfirmedTrendShadowReportProvider = trendShadowService?.let { service -> service::report },
                volumeConfirmedTrendApprovalReportProvider = trendApprovalService::evaluate,
                volumeConfirmedTrendApprovalArtifactExportProvider =
                    trendApprovalArtifactWriter?.let { writer ->
                        {
                            writer.export().let { exported ->
                                VolumeConfirmedTrendApprovalArtifactExportResponse(
                                    available = true,
                                    exportDirectory = exported.exportDirectory.toString(),
                                    shadowEvidencePath = exported.shadowEvidencePath.toString(),
                                    shadowEvidenceSha256 = exported.shadowEvidenceSha256,
                                    approvalReportPath = exported.approvalReportPath.toString(),
                                    approvalReportSha256 = exported.approvalReportSha256,
                                    manifestPath = exported.manifestPath.toString(),
                                    sessionId = exported.sessionId,
                                    evaluatedAt = exported.evaluatedAt.toString(),
                                    liveExecutionAllowed = false,
                                )
                            }
                        }
                    },
                runtimeMode = config.runtimeMode.name,
                forwardMarketCaptureStatusService =
                    ForwardMarketCaptureStatusService(
                        store = ledger,
                        rawEventArchive = forwardMarketRawEventArchive,
                    ),
                forwardMarketCaptureEnabled = config.forwardMarketCapture.enabled,
                onControlResult = { result ->
                    if (result.newMode != BotMode.PAUSE_ALL && result.newMode != BotMode.EMERGENCY_STOP) {
                        executionSafetyAlertPolicy.reset()
                    }
                    alertingService.sendControlResult(result)
                },
                onSafetyResult = { result ->
                    alertingService.sendExchangeSafetyResult(result, executionSafetyAlertPolicy)
                },
                onSmokeAlert = { message -> alertingService.sendSmokeAlert(message) },
                controlCredential = config.api.controlCredential,
            )
        }

    logger.info("http server starting host={} port={}", config.api.host, config.api.port)
    server.start(wait = true)
}

internal fun openLedgerDatabase(path: Path): LedgerDatabase {
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
        PaperStrategyKind.MULTI_HORIZON_MOMENTUM -> MultiHorizonMomentumResearchProfiles.current().strategy()
        PaperStrategyKind.VOLUME_FLOW_AGGRESSIVE -> VolumeFlowAggressiveStrategy()
        PaperStrategyKind.MEAN_REVERSION -> MeanReversionStrategy()
    }

private fun createPaperTradingConfig(config: AppConfig): PaperTradingConfig {
    val settings = config.paperTrading
    if (config.paperLoop.strategy != PaperStrategyKind.MULTI_HORIZON_MOMENTUM) {
        return PaperTradingConfig(
            initialEquity = settings.initialEquity,
            riskFraction = settings.riskFraction,
            feeRate = settings.feeRate,
        )
    }
    val backtest = MultiHorizonMomentumResearchProfiles.current().backtestConfig()
    return PaperTradingConfig(
        initialEquity = settings.initialEquity,
        riskFraction = settings.riskFraction,
        feeRate = settings.feeRate,
        entrySlippageRate = BigDecimal.valueOf(backtest.slippageRate),
        exitSlippageRate = BigDecimal.valueOf(backtest.exitSlippageRate),
        fundingRatePer8h = BigDecimal.valueOf(backtest.fundingRatePer8h),
        partialTakeProfitR = BigDecimal.valueOf(backtest.partialTakeProfitR),
        partialTakeProfitFraction = BigDecimal.valueOf(backtest.partialTakeProfitFraction),
        breakevenAfterPartialTakeProfit = backtest.breakevenAfterPartialTakeProfit,
        atrTrailingPeriod = backtest.atrTrailingPeriod,
        atrTrailingMultiplier = BigDecimal.valueOf(backtest.atrTrailingMultiplier),
        fixedTargetEnabled = backtest.fixedTargetEnabled,
        maxHoldCandles = backtest.maxHoldCandles,
        maxTradesPerUtcDay = backtest.maxTradesPerUtcDay,
        minimumEntryRiskFraction = backtest.minimumEntryRiskFraction?.let(BigDecimal::valueOf),
        maximumEntryRiskFraction = backtest.maximumEntryRiskFraction?.let(BigDecimal::valueOf),
    )
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

        PaperEvaluationStatus.CLOSED ->
            send(
                AlertMessage(
                    severity =
                        if ((result.realizedPnl ?: BigDecimal.ZERO) >= BigDecimal.ZERO) {
                            AlertSeverity.INFO
                        } else {
                            AlertSeverity.WARNING
                        },
                    title = "모의 포지션 종료",
                    body =
                        "${result.symbol.value} ${result.timeframe.name} 모의 포지션이 종료됐어요. " +
                            "종료 사유: ${result.exitReason}, 순손익: ${result.realizedPnl?.toPlainString()}, " +
                            "잔고: ${result.equity?.toPlainString()}",
                ),
            )

        PaperEvaluationStatus.ENTRY_PENDING,
        PaperEvaluationStatus.POSITION_UPDATED,
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

private suspend fun AlertingService.sendExecutionLoopResult(
    result: ExchangeEvaluationResult,
    riskAlertPolicy: ExecutionRiskAlertPolicy,
) {
    riskAlertPolicy.messages(result).forEach { message -> send(message) }
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

private suspend fun AlertingService.sendExecutionReconciliationFailure(error: Throwable) {
    send(
        AlertMessage(
            severity = AlertSeverity.WARNING,
            title = "거래 상태 확인 필요",
            body = loopFailureAlertBody(loopName = "거래 상태 확인", error = error),
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

            ExecutionLifecycleState.ENTRY_FILLED ->
                AlertMessage(
                    severity = AlertSeverity.INFO,
                    title = "실거래 진입 체결 확인",
                    body =
                        "${event.symbol.value} ${event.side.name} 진입 주문의 체결을 확인했어요. " +
                            "체결 수량: ${event.filledQuantity?.toPlainString()}, " +
                            "평균 체결가: ${event.fillVwap?.toPlainString()}. 포지션 보호 상태를 확인하고 있어요.",
                )

            ExecutionLifecycleState.ENTRY_CANCELLED ->
                AlertMessage(
                    severity = AlertSeverity.INFO,
                    title = "실거래 진입 주문 취소",
                    body =
                        "${event.symbol.value} ${event.side.name} 진입 주문이 체결 없이 종료됐어요. " +
                            "사유 코드: ${event.reasonCode}",
                )

            ExecutionLifecycleState.ENTRY_REJECTED ->
                AlertMessage(
                    severity = AlertSeverity.WARNING,
                    title = "실거래 진입 주문 거절",
                    body =
                        "${event.symbol.value} ${event.side.name} 진입 주문을 거래소가 거절했어요. " +
                            "사유 코드: ${event.reasonCode}",
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
