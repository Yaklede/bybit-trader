package dev.yaklede.bybittrader.api.control

import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.control.BotControlService
import dev.yaklede.bybittrader.engine.control.ControlResult
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionService
import dev.yaklede.bybittrader.engine.execution.ExchangeSafetyResult
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

fun Route.configureControlRoutes(
    controlService: BotControlService,
    executionService: ExchangeExecutionService? = null,
    controlSymbol: Symbol? = null,
    onControlResult: suspend (ControlResult) -> Unit = {},
) {
    val logger = LoggerFactory.getLogger("dev.yaklede.bybittrader.api.control")
    authenticate("control") {
        post("/control/pause-new-entries") {
            val request = call.receive<ControlRequest>().validated()
            val result =
                controlService.pauseNewEntries(
                    actor = call.controlActor(),
                    reason = request.reason,
                )
            notifyControlResult(result, onControlResult)
            logger.info("control action completed action={} mode={}->{}", result.action.name, result.previousMode.name, result.newMode.name)
            call.respond(result.toResponse())
        }

        post("/control/pause-all") {
            val request = call.receive<ControlRequest>().validated()
            val result =
                controlService.pauseAll(
                    actor = call.controlActor(),
                    reason = request.reason,
                )
            call.respondSafetyControl(result, executionService, controlSymbol, onControlResult, logger)
        }

        post("/control/safe-stop") {
            val request = call.receive<ControlRequest>().validated()
            val result =
                controlService.pauseAll(
                    actor = call.controlActor(),
                    reason = request.reason,
                )
            call.respondSafetyControl(result, executionService, controlSymbol, onControlResult, logger)
        }

        post("/control/resume") {
            val request = call.receive<ControlRequest>().validated()
            val result =
                controlService.resume(
                    actor = call.controlActor(),
                    reason = request.reason,
                )
            notifyControlResult(result, onControlResult)
            logger.info("control action completed action={} mode={}->{}", result.action.name, result.previousMode.name, result.newMode.name)
            call.respond(result.toResponse())
        }

        post("/control/emergency-stop") {
            val request = call.receive<ControlRequest>().validated()
            val result =
                controlService.emergencyStop(
                    actor = call.controlActor(),
                    reason = request.reason,
                )
            call.respondSafetyControl(result, executionService, controlSymbol, onControlResult, logger)
        }

        post("/control/flatten") {
            val request = call.receive<ControlRequest>().validated()
            val result =
                controlService.emergencyStop(
                    actor = call.controlActor(),
                    reason = request.reason,
                )
            call.respondSafetyControl(result, executionService, controlSymbol, onControlResult, logger)
        }
    }
}

private suspend fun ApplicationCall.respondSafetyControl(
    result: ControlResult,
    executionService: ExchangeExecutionService?,
    controlSymbol: Symbol?,
    onControlResult: suspend (ControlResult) -> Unit,
    logger: org.slf4j.Logger,
) {
    val safety =
        if (executionService != null && controlSymbol != null) {
            executionService.enforceCurrentSafetyMode(controlSymbol)
        } else {
            null
        }
    notifyControlResult(result, onControlResult)
    logger.warn(
        "control safety action completed action={} mode={}->{} safetyStatus={}",
        result.action.name,
        result.previousMode.name,
        result.newMode.name,
        safety?.status?.name ?: "NOT_APPLICABLE",
    )
    respond(result.toSafetyResponse(safety))
}

private suspend fun notifyControlResult(
    result: ControlResult,
    onControlResult: suspend (ControlResult) -> Unit,
) {
    try {
        onControlResult(result)
    } catch (_: Throwable) {
        // Control plane commands must not fail because a webhook or push sink is unavailable.
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

@Serializable
data class ControlSafetyResponse(
    val action: String,
    val previousMode: String,
    val newMode: String,
    val changedAt: String,
    val safety: ExchangeSafetyResponse?,
)

@Serializable
data class ExchangeSafetyResponse(
    val action: String,
    val status: String,
    val mode: String,
    val symbol: String,
    val requestedAt: String,
    val verifiedAt: String,
    val cancelledEntryOrderCount: Int,
    val submittedCloseOrderCount: Int,
    val protectedPositionCount: Int,
    val remainingOpenOrderCount: Int?,
    val remainingPositionCount: Int?,
    val issueCodes: List<String>,
)

private fun ControlResult.toResponse(): ControlResponse =
    ControlResponse(
        action = action.name,
        previousMode = previousMode.name,
        newMode = newMode.name,
        changedAt = changedAt.toString(),
    )

private fun ControlResult.toSafetyResponse(safety: ExchangeSafetyResult?): ControlSafetyResponse =
    ControlSafetyResponse(
        action = action.name,
        previousMode = previousMode.name,
        newMode = newMode.name,
        changedAt = changedAt.toString(),
        safety = safety?.toResponse(),
    )

private fun ExchangeSafetyResult.toResponse(): ExchangeSafetyResponse =
    ExchangeSafetyResponse(
        action = action.name,
        status = status.name,
        mode = mode,
        symbol = symbol.value,
        requestedAt = requestedAt.toString(),
        verifiedAt = verifiedAt.toString(),
        cancelledEntryOrderCount = cancelledEntryOrderCount,
        submittedCloseOrderCount = submittedCloseOrderCount,
        protectedPositionCount = protectedPositionCount,
        remainingOpenOrderCount = remainingOpenOrderCount,
        remainingPositionCount = remainingPositionCount,
        issueCodes = issueCodes,
    )
