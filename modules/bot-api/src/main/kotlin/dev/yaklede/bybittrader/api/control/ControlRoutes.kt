package dev.yaklede.bybittrader.api.control

import dev.yaklede.bybittrader.engine.control.BotControlService
import dev.yaklede.bybittrader.engine.control.ControlResult
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

fun Route.configureControlRoutes(controlService: BotControlService) {
    authenticate("control") {
        post("/control/pause-new-entries") {
            val request = call.receive<ControlRequest>().validated()
            val result =
                controlService.pauseNewEntries(
                    actor = call.controlActor(),
                    reason = request.reason,
                )
            call.respond(result.toResponse())
        }

        post("/control/pause-all") {
            val request = call.receive<ControlRequest>().validated()
            val result =
                controlService.pauseAll(
                    actor = call.controlActor(),
                    reason = request.reason,
                )
            call.respond(result.toResponse())
        }

        post("/control/resume") {
            val request = call.receive<ControlRequest>().validated()
            val result =
                controlService.resume(
                    actor = call.controlActor(),
                    reason = request.reason,
                )
            call.respond(result.toResponse())
        }

        post("/control/emergency-stop") {
            val request = call.receive<ControlRequest>().validated()
            val result =
                controlService.emergencyStop(
                    actor = call.controlActor(),
                    reason = request.reason,
                )
            call.respond(result.toResponse())
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.controlActor(): String =
    principal<UserIdPrincipal>()?.name ?: "authenticated-operator"

@Serializable
data class ControlRequest(
    val reason: String? = null,
) {
    fun validated(): ControlRequest {
        require(reason == null || reason.length <= 240) {
            "Reason must be 240 characters or shorter."
        }
        return copy(reason = reason?.trim()?.takeIf { it.isNotEmpty() })
    }
}

@Serializable
data class ControlResponse(
    val action: String,
    val previousMode: String,
    val newMode: String,
    val changedAt: String,
)

private fun ControlResult.toResponse(): ControlResponse =
    ControlResponse(
        action = action.name,
        previousMode = previousMode.name,
        newMode = newMode.name,
        changedAt = changedAt.toString(),
    )
