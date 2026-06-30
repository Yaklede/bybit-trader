package dev.yaklede.bybittrader.api.health

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

fun Route.configureHealthRoutes() {
    get("/health") {
        call.respond(HealthResponse(status = "ok"))
    }
}

@Serializable
data class HealthResponse(
    val status: String,
)
