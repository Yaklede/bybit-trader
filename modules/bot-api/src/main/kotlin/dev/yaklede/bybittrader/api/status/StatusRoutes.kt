package dev.yaklede.bybittrader.api.status

import dev.yaklede.bybittrader.engine.control.BotStateStore
import dev.yaklede.bybittrader.engine.paper.PaperPerformanceSnapshot
import dev.yaklede.bybittrader.engine.paper.PaperSignalRecord
import dev.yaklede.bybittrader.engine.paper.PaperTradeRecord
import dev.yaklede.bybittrader.engine.paper.PaperTradingReportStore
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

fun Route.configureStatusRoutes(
    stateStore: BotStateStore,
    paperTradingReportStore: PaperTradingReportStore,
) {
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
            call.respond(paperTradingReportStore.latestPerformanceSummary().toResponse())
        }

        get("/signals/recent") {
            call.respond(
                paperTradingReportStore
                    .recentSignals(call.queryLimit())
                    .map(PaperSignalRecord::toResponse),
            )
        }

        get("/trades/recent") {
            call.respond(
                paperTradingReportStore
                    .recentTrades(call.queryLimit())
                    .map(PaperTradeRecord::toResponse),
            )
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
    val period: String,
    val netPnl: String,
    val profitFactor: String?,
    val expectancy: String?,
    val maxDrawdown: String,
    val capturedAt: String?,
)

@Serializable
data class RecentSignalResponse(
    val id: Long,
    val strategy: String,
    val symbol: String,
    val side: String,
    val score: Int,
    val grade: String,
    val reasonCodes: List<String>,
    val accepted: Boolean,
    val rejectionReason: String?,
    val createdAt: String,
)

@Serializable
data class RecentTradeResponse(
    val orderId: Long,
    val clientOrderId: String,
    val signalId: Long?,
    val side: String,
    val orderType: String,
    val orderStatus: String,
    val intendedRisk: String,
    val orderCreatedAt: String,
    val fillId: Long?,
    val fillPrice: String?,
    val quantity: String?,
    val fee: String?,
    val filledAt: String?,
)

private fun PaperPerformanceSnapshot?.toResponse(): PerformanceSummaryResponse =
    if (this == null) {
        PerformanceSummaryResponse(
            period = "none",
            netPnl = "0",
            profitFactor = null,
            expectancy = null,
            maxDrawdown = "0",
            capturedAt = null,
        )
    } else {
        PerformanceSummaryResponse(
            period = period,
            netPnl = netPnl.toPlainString(),
            profitFactor = profitFactor?.toPlainString(),
            expectancy = expectancy?.toPlainString(),
            maxDrawdown = maxDrawdown.toPlainString(),
            capturedAt = capturedAt.toString(),
        )
    }

private fun PaperSignalRecord.toResponse(): RecentSignalResponse =
    RecentSignalResponse(
        id = id,
        strategy = strategy,
        symbol = symbol.value,
        side = side.name,
        score = score,
        grade = grade,
        reasonCodes = reasonCodes,
        accepted = accepted,
        rejectionReason = rejectionReason,
        createdAt = createdAt.toString(),
    )

private fun PaperTradeRecord.toResponse(): RecentTradeResponse =
    RecentTradeResponse(
        orderId = orderId,
        clientOrderId = clientOrderId,
        signalId = signalId,
        side = side.name,
        orderType = orderType.name,
        orderStatus = orderStatus.name,
        intendedRisk = intendedRisk.toPlainString(),
        orderCreatedAt = orderCreatedAt.toString(),
        fillId = fillId,
        fillPrice = fillPrice?.toPlainString(),
        quantity = quantity?.toPlainString(),
        fee = fee?.toPlainString(),
        filledAt = filledAt?.toString(),
    )

private fun ApplicationCall.queryLimit(): Int {
    val rawLimit = request.queryParameters["limit"] ?: return 20
    val limit = rawLimit.toIntOrNull() ?: throw IllegalArgumentException("Limit must be a number.")
    require(limit in 1..100) { "Limit must be between 1 and 100." }
    return limit
}
