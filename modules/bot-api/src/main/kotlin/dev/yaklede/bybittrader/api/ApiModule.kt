package dev.yaklede.bybittrader.api

import dev.yaklede.bybittrader.api.backtest.VolumeFlowCompositeCurrentConfigProvider
import dev.yaklede.bybittrader.api.backtest.configureBacktestRoutes
import dev.yaklede.bybittrader.api.backtest.configureMeanReversionSweepRoutes
import dev.yaklede.bybittrader.api.backtest.configureVolumeFlowAggressiveBacktestRoutes
import dev.yaklede.bybittrader.api.backtest.configureVolumeFlowBacktestRoutes
import dev.yaklede.bybittrader.api.backtest.configureVolumeFlowCompositeBacktestRoutes
import dev.yaklede.bybittrader.api.backtest.configureVolumeFlowSweepRoutes
import dev.yaklede.bybittrader.api.control.configureControlRoutes
import dev.yaklede.bybittrader.api.dashboard.configureDashboardRoutes
import dev.yaklede.bybittrader.api.execution.configureExecutionRoutes
import dev.yaklede.bybittrader.api.health.configureHealthRoutes
import dev.yaklede.bybittrader.api.market.configureMarketDataRoutes
import dev.yaklede.bybittrader.api.operations.SmokeAlertDeliveryResponse
import dev.yaklede.bybittrader.api.operations.configureOperationsSmokeRoutes
import dev.yaklede.bybittrader.api.paper.configurePaperTradingRoutes
import dev.yaklede.bybittrader.api.security.configureControlAuthentication
import dev.yaklede.bybittrader.api.status.configureStatusRoutes
import dev.yaklede.bybittrader.engine.backtest.BacktestService
import dev.yaklede.bybittrader.engine.backtest.MeanReversionSweepService
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowAggressiveBacktestService
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowBacktestService
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowCompositeBacktestService
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowSweepService
import dev.yaklede.bybittrader.engine.control.BotControlService
import dev.yaklede.bybittrader.engine.control.BotStateStore
import dev.yaklede.bybittrader.engine.control.ControlResult
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionException
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionService
import dev.yaklede.bybittrader.engine.market.MarketDataException
import dev.yaklede.bybittrader.engine.market.MarketDataSyncService
import dev.yaklede.bybittrader.engine.paper.EmptyPaperTradingReportStore
import dev.yaklede.bybittrader.engine.paper.PaperTradingReportStore
import dev.yaklede.bybittrader.engine.paper.PaperTradingService
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

fun Application.configureApi(
    stateStore: BotStateStore,
    controlService: BotControlService,
    marketDataSyncService: MarketDataSyncService,
    backtestService: BacktestService,
    meanReversionSweepService: MeanReversionSweepService,
    volumeFlowBacktestService: VolumeFlowBacktestService,
    volumeFlowAggressiveBacktestService: VolumeFlowAggressiveBacktestService? = null,
    volumeFlowCompositeBacktestService: VolumeFlowCompositeBacktestService? = null,
    volumeFlowCompositeCurrentConfigProvider: VolumeFlowCompositeCurrentConfigProvider? = null,
    volumeFlowSweepService: VolumeFlowSweepService? = null,
    paperTradingService: PaperTradingService? = null,
    paperTradingReportStore: PaperTradingReportStore = EmptyPaperTradingReportStore,
    executionService: ExchangeExecutionService? = null,
    runtimeMode: String? = null,
    onControlResult: suspend (ControlResult) -> Unit = {},
    onSmokeAlert: (suspend (String) -> SmokeAlertDeliveryResponse)? = null,
    controlCredential: String?,
) {
    val logger = LoggerFactory.getLogger("dev.yaklede.bybittrader.api")
    install(ContentNegotiation) {
        json()
    }
    install(CallLogging) {
        level = Level.INFO
        format { call ->
            val status =
                call.response
                    .status()
                    ?.value
                    ?.toString() ?: "unhandled"
            "http ${call.request.httpMethod.value} ${call.request.path()} -> $status"
        }
    }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            logger.warn("api validation failed path={} reason={}", call.request.path(), cause.message)
            call.respond(
                status = HttpStatusCode.BadRequest,
                message = ErrorResponse(code = "VALIDATION_ERROR", message = cause.message ?: "Invalid request."),
            )
        }
        exception<MarketDataException> { call, cause ->
            logger.warn("market data provider unavailable path={} error={}", call.request.path(), cause::class.simpleName)
            call.respond(
                status = HttpStatusCode.BadGateway,
                message =
                    ErrorResponse(
                        code = "MARKET_DATA_UNAVAILABLE",
                        message = "Market data provider is unavailable.",
                    ),
            )
        }
        exception<ExchangeExecutionException> { call, cause ->
            logger.warn("exchange execution provider unavailable path={} error={}", call.request.path(), cause::class.simpleName)
            call.respond(
                status = HttpStatusCode.BadGateway,
                message =
                    ErrorResponse(
                        code = "EXCHANGE_EXECUTION_UNAVAILABLE",
                        message = "Private exchange execution provider is unavailable.",
                    ),
            )
        }
    }
    configureControlAuthentication(controlCredential)
    routing {
        configureHealthRoutes()
        configureStatusRoutes(stateStore, paperTradingReportStore)
        configureDashboardRoutes(stateStore, paperTradingReportStore, marketDataSyncService, executionService, runtimeMode)
        configureControlRoutes(controlService, onControlResult)
        configureMarketDataRoutes(marketDataSyncService)
        configureOperationsSmokeRoutes(
            controlService = controlService,
            marketDataSyncService = marketDataSyncService,
            executionService = executionService,
            runtimeMode = runtimeMode,
            onControlResult = onControlResult,
            onSmokeAlert = onSmokeAlert,
        )
        configureBacktestRoutes(backtestService)
        configureMeanReversionSweepRoutes(meanReversionSweepService)
        configureVolumeFlowBacktestRoutes(volumeFlowBacktestService)
        volumeFlowAggressiveBacktestService?.let(::configureVolumeFlowAggressiveBacktestRoutes)
        volumeFlowCompositeBacktestService?.let {
            configureVolumeFlowCompositeBacktestRoutes(it, volumeFlowCompositeCurrentConfigProvider)
        }
        volumeFlowSweepService?.let(::configureVolumeFlowSweepRoutes)
        paperTradingService?.let(::configurePaperTradingRoutes)
        executionService?.let(::configureExecutionRoutes)
    }
}

@Serializable
data class ErrorResponse(
    val code: String,
    val message: String,
)
