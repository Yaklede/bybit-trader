package dev.yaklede.bybittrader.api

import dev.yaklede.bybittrader.api.backtest.configureBacktestRoutes
import dev.yaklede.bybittrader.api.backtest.configureMeanReversionSweepRoutes
import dev.yaklede.bybittrader.api.backtest.configureVolumeFlowBacktestRoutes
import dev.yaklede.bybittrader.api.backtest.configureVolumeFlowCompositeBacktestRoutes
import dev.yaklede.bybittrader.api.backtest.configureVolumeFlowSweepRoutes
import dev.yaklede.bybittrader.api.control.configureControlRoutes
import dev.yaklede.bybittrader.api.health.configureHealthRoutes
import dev.yaklede.bybittrader.api.market.configureMarketDataRoutes
import dev.yaklede.bybittrader.api.paper.configurePaperTradingRoutes
import dev.yaklede.bybittrader.api.security.configureControlAuthentication
import dev.yaklede.bybittrader.api.status.configureStatusRoutes
import dev.yaklede.bybittrader.engine.backtest.BacktestService
import dev.yaklede.bybittrader.engine.backtest.MeanReversionSweepService
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowBacktestService
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowCompositeBacktestService
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowSweepService
import dev.yaklede.bybittrader.engine.control.BotControlService
import dev.yaklede.bybittrader.engine.control.BotStateStore
import dev.yaklede.bybittrader.engine.market.MarketDataException
import dev.yaklede.bybittrader.engine.market.MarketDataSyncService
import dev.yaklede.bybittrader.engine.paper.EmptyPaperTradingReportStore
import dev.yaklede.bybittrader.engine.paper.PaperTradingReportStore
import dev.yaklede.bybittrader.engine.paper.PaperTradingService
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

fun Application.configureApi(
    stateStore: BotStateStore,
    controlService: BotControlService,
    marketDataSyncService: MarketDataSyncService,
    backtestService: BacktestService,
    meanReversionSweepService: MeanReversionSweepService,
    volumeFlowBacktestService: VolumeFlowBacktestService,
    volumeFlowCompositeBacktestService: VolumeFlowCompositeBacktestService? = null,
    volumeFlowSweepService: VolumeFlowSweepService? = null,
    paperTradingService: PaperTradingService? = null,
    paperTradingReportStore: PaperTradingReportStore = EmptyPaperTradingReportStore,
    controlCredential: String?,
) {
    install(ContentNegotiation) {
        json()
    }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                status = HttpStatusCode.BadRequest,
                message = ErrorResponse(code = "VALIDATION_ERROR", message = cause.message ?: "Invalid request."),
            )
        }
        exception<MarketDataException> { call, _ ->
            call.respond(
                status = HttpStatusCode.BadGateway,
                message =
                    ErrorResponse(
                        code = "MARKET_DATA_UNAVAILABLE",
                        message = "Market data provider is unavailable.",
                    ),
            )
        }
    }
    configureControlAuthentication(controlCredential)
    routing {
        configureHealthRoutes()
        configureStatusRoutes(stateStore, paperTradingReportStore)
        configureControlRoutes(controlService)
        configureMarketDataRoutes(marketDataSyncService)
        configureBacktestRoutes(backtestService)
        configureMeanReversionSweepRoutes(meanReversionSweepService)
        configureVolumeFlowBacktestRoutes(volumeFlowBacktestService)
        volumeFlowCompositeBacktestService?.let(::configureVolumeFlowCompositeBacktestRoutes)
        volumeFlowSweepService?.let(::configureVolumeFlowSweepRoutes)
        paperTradingService?.let(::configurePaperTradingRoutes)
    }
}

@Serializable
data class ErrorResponse(
    val code: String,
    val message: String,
)
