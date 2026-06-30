package dev.yaklede.bybittrader.api

import dev.yaklede.bybittrader.api.control.configureControlRoutes
import dev.yaklede.bybittrader.api.health.configureHealthRoutes
import dev.yaklede.bybittrader.api.market.configureMarketDataRoutes
import dev.yaklede.bybittrader.api.security.configureControlAuthentication
import dev.yaklede.bybittrader.api.status.configureStatusRoutes
import dev.yaklede.bybittrader.engine.control.BotControlService
import dev.yaklede.bybittrader.engine.control.BotStateStore
import dev.yaklede.bybittrader.engine.market.MarketDataException
import dev.yaklede.bybittrader.engine.market.MarketDataSyncService
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
        configureStatusRoutes(stateStore)
        configureControlRoutes(controlService)
        configureMarketDataRoutes(marketDataSyncService)
    }
}

@Serializable
data class ErrorResponse(
    val code: String,
    val message: String,
)
