package dev.yaklede.bybittrader.api.status

import dev.yaklede.bybittrader.engine.control.BotStateStore
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

fun Route.configureStatusRoutes(stateStore: BotStateStore) {
    authenticate("control") {
        get("/status") {
            val status = stateStore.current()
            call.respond(
                StatusResponse(
                    mode = status.mode.name,
                    updatedAt = status.updatedAt.toString(),
                    heartbeatAt = status.heartbeatAt?.toString(),
                ),
            )
        }

        get("/performance/summary") {
            call.respond(
                PerformanceSummaryResponse(
                    netPnl = "0",
                    profitFactor = null,
                    expectancy = null,
                    maxDrawdown = "0",
                ),
            )
        }

        get("/signals/recent") {
            call.respond(emptyList<RecentSignalResponse>())
        }

        get("/trades/recent") {
            call.respond(emptyList<RecentTradeResponse>())
        }
    }
}

@Serializable
data class StatusResponse(
    val mode: String,
    val updatedAt: String,
    val heartbeatAt: String?,
)

@Serializable
data class PerformanceSummaryResponse(
    val netPnl: String,
    val profitFactor: String?,
    val expectancy: String?,
    val maxDrawdown: String,
)

@Serializable
data class RecentSignalResponse(
    val id: String,
)

@Serializable
data class RecentTradeResponse(
    val id: String,
)
