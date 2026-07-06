package dev.yaklede.bybittrader.api.dashboard

import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.control.BotRuntimeStatus
import dev.yaklede.bybittrader.engine.control.BotStateStore
import dev.yaklede.bybittrader.engine.execution.ExchangeAccountBalance
import dev.yaklede.bybittrader.engine.execution.ExchangeCoinBalance
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionFill
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionService
import dev.yaklede.bybittrader.engine.execution.ExchangeOpenOrder
import dev.yaklede.bybittrader.engine.execution.ExchangePosition
import dev.yaklede.bybittrader.engine.execution.ExchangeReconciliationReport
import dev.yaklede.bybittrader.engine.paper.PaperPerformanceSnapshot
import dev.yaklede.bybittrader.engine.paper.PaperSignalRecord
import dev.yaklede.bybittrader.engine.paper.PaperTradeRecord
import dev.yaklede.bybittrader.engine.paper.PaperTradingReportStore
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import java.time.Instant

fun Route.configureDashboardRoutes(
    stateStore: BotStateStore,
    paperTradingReportStore: PaperTradingReportStore,
    executionService: ExchangeExecutionService?,
    runtimeMode: String? = null,
) {
    authenticate("control") {
        get("/dashboard/summary") {
            val request =
                DashboardSummaryQuery(
                    symbol = call.request.queryParameters["symbol"] ?: "BTCUSDT",
                    coin = call.request.queryParameters["coin"] ?: "USDT",
                    limit = call.request.queryParameters["limit"],
                ).validated()
            val status = stateStore.current()
            val balance = executionService?.accountBalance(request.coin)
            val reconciliation = executionService?.reconcile(request.symbol)
            val response =
                DashboardSummaryResponse(
                    capturedAt = Instant.now().toString(),
                    executionAvailable = executionService != null,
                    runtimeMode = runtimeMode,
                    bot = status.toResponse(),
                    account = balance?.toResponse(),
                    reconciliation = reconciliation?.toResponse(),
                    performance = paperTradingReportStore.latestPerformanceSummary().toResponse(),
                    recentSignals =
                        paperTradingReportStore
                            .recentSignals(request.limit)
                            .map(PaperSignalRecord::toResponse),
                    recentTrades =
                        paperTradingReportStore
                            .recentTrades(request.limit)
                            .map(PaperTradeRecord::toResponse),
                )
            call.respond(response)
        }
    }
}

private data class DashboardSummaryQuery(
    val symbol: String,
    val coin: String?,
    val limit: String?,
) {
    fun validated(): DashboardSummaryQueryValues {
        val normalizedSymbol = symbol.trim().uppercase()
        Symbol(normalizedSymbol)
        val normalizedCoin = coin?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        require(normalizedCoin == null || COIN_PATTERN.matches(normalizedCoin)) {
            "Coin must contain 2 to 20 uppercase letters or numbers."
        }
        val normalizedLimit =
            if (limit == null) {
                20
            } else {
                limit.toIntOrNull() ?: throw IllegalArgumentException("Limit must be a number.")
            }
        require(normalizedLimit in 1..100) { "Limit must be between 1 and 100." }
        return DashboardSummaryQueryValues(
            symbol = Symbol(normalizedSymbol),
            coin = normalizedCoin,
            limit = normalizedLimit,
        )
    }
}

private data class DashboardSummaryQueryValues(
    val symbol: Symbol,
    val coin: String?,
    val limit: Int,
)

@Serializable
data class DashboardSummaryResponse(
    val capturedAt: String,
    val executionAvailable: Boolean,
    val runtimeMode: String?,
    val bot: DashboardBotStatusResponse,
    val account: DashboardAccountBalanceResponse?,
    val reconciliation: DashboardReconciliationResponse?,
    val performance: DashboardPerformanceResponse,
    val recentSignals: List<DashboardSignalResponse>,
    val recentTrades: List<DashboardTradeResponse>,
)

@Serializable
data class DashboardBotStatusResponse(
    val mode: String,
    val updatedAt: String,
    val heartbeatAt: String?,
)

@Serializable
data class DashboardAccountBalanceResponse(
    val accountType: String,
    val totalEquity: String?,
    val totalWalletBalance: String?,
    val totalMarginBalance: String?,
    val totalAvailableBalance: String?,
    val totalPerpUnrealizedPnl: String?,
    val totalInitialMargin: String?,
    val totalMaintenanceMargin: String?,
    val capturedAt: String,
    val coins: List<DashboardCoinBalanceResponse>,
)

@Serializable
data class DashboardCoinBalanceResponse(
    val coin: String,
    val equity: String?,
    val usdValue: String?,
    val walletBalance: String?,
    val locked: String?,
    val unrealizedPnl: String?,
)

@Serializable
data class DashboardReconciliationResponse(
    val symbol: String,
    val reconciledAt: String,
    val openOrders: List<DashboardOpenOrderResponse>,
    val positions: List<DashboardPositionResponse>,
    val executions: List<DashboardExecutionFillResponse>,
)

@Serializable
data class DashboardOpenOrderResponse(
    val exchangeOrderId: String?,
    val clientOrderId: String?,
    val symbol: String,
    val side: String,
    val orderType: String,
    val status: String,
    val quantity: String?,
    val createdAt: String?,
)

@Serializable
data class DashboardPositionResponse(
    val symbol: String,
    val side: String,
    val size: String,
    val entryPrice: String?,
    val markPrice: String?,
    val unrealizedPnl: String?,
    val updatedAt: String?,
)

@Serializable
data class DashboardExecutionFillResponse(
    val exchangeOrderId: String?,
    val clientOrderId: String?,
    val symbol: String,
    val side: String,
    val price: String,
    val quantity: String,
    val fee: String,
    val executedAt: String,
)

@Serializable
data class DashboardPerformanceResponse(
    val period: String,
    val netPnl: String,
    val profitFactor: String?,
    val expectancy: String?,
    val maxDrawdown: String,
    val capturedAt: String?,
)

@Serializable
data class DashboardSignalResponse(
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
data class DashboardTradeResponse(
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

private fun BotRuntimeStatus.toResponse(): DashboardBotStatusResponse =
    DashboardBotStatusResponse(
        mode = mode.name,
        updatedAt = updatedAt.toString(),
        heartbeatAt = heartbeatAt?.toString(),
    )

private fun ExchangeAccountBalance.toResponse(): DashboardAccountBalanceResponse =
    DashboardAccountBalanceResponse(
        accountType = accountType,
        totalEquity = totalEquity?.toPlainString(),
        totalWalletBalance = totalWalletBalance?.toPlainString(),
        totalMarginBalance = totalMarginBalance?.toPlainString(),
        totalAvailableBalance = totalAvailableBalance?.toPlainString(),
        totalPerpUnrealizedPnl = totalPerpUnrealizedPnl?.toPlainString(),
        totalInitialMargin = totalInitialMargin?.toPlainString(),
        totalMaintenanceMargin = totalMaintenanceMargin?.toPlainString(),
        capturedAt = capturedAt.toString(),
        coins = coins.map(ExchangeCoinBalance::toResponse),
    )

private fun ExchangeCoinBalance.toResponse(): DashboardCoinBalanceResponse =
    DashboardCoinBalanceResponse(
        coin = coin,
        equity = equity?.toPlainString(),
        usdValue = usdValue?.toPlainString(),
        walletBalance = walletBalance?.toPlainString(),
        locked = locked?.toPlainString(),
        unrealizedPnl = unrealizedPnl?.toPlainString(),
    )

private fun ExchangeReconciliationReport.toResponse(): DashboardReconciliationResponse =
    DashboardReconciliationResponse(
        symbol = symbol.value,
        reconciledAt = reconciledAt.toString(),
        openOrders = openOrders.map(ExchangeOpenOrder::toResponse),
        positions = positions.map(ExchangePosition::toResponse),
        executions = executions.map(ExchangeExecutionFill::toResponse),
    )

private fun ExchangeOpenOrder.toResponse(): DashboardOpenOrderResponse =
    DashboardOpenOrderResponse(
        exchangeOrderId = exchangeOrderId,
        clientOrderId = clientOrderId,
        symbol = symbol.value,
        side = side.name,
        orderType = orderType.name,
        status = status.name,
        quantity = quantity?.toPlainString(),
        createdAt = createdAt?.toString(),
    )

private fun ExchangePosition.toResponse(): DashboardPositionResponse =
    DashboardPositionResponse(
        symbol = symbol.value,
        side = side.name,
        size = size.toPlainString(),
        entryPrice = entryPrice?.toPlainString(),
        markPrice = markPrice?.toPlainString(),
        unrealizedPnl = unrealizedPnl?.toPlainString(),
        updatedAt = updatedAt?.toString(),
    )

private fun ExchangeExecutionFill.toResponse(): DashboardExecutionFillResponse =
    DashboardExecutionFillResponse(
        exchangeOrderId = exchangeOrderId,
        clientOrderId = clientOrderId,
        symbol = symbol.value,
        side = side.name,
        price = price.toPlainString(),
        quantity = quantity.toPlainString(),
        fee = fee.toPlainString(),
        executedAt = executedAt.toString(),
    )

private fun PaperPerformanceSnapshot?.toResponse(): DashboardPerformanceResponse =
    if (this == null) {
        DashboardPerformanceResponse(
            period = "none",
            netPnl = "0",
            profitFactor = null,
            expectancy = null,
            maxDrawdown = "0",
            capturedAt = null,
        )
    } else {
        DashboardPerformanceResponse(
            period = period,
            netPnl = netPnl.toPlainString(),
            profitFactor = profitFactor?.toPlainString(),
            expectancy = expectancy?.toPlainString(),
            maxDrawdown = maxDrawdown.toPlainString(),
            capturedAt = capturedAt.toString(),
        )
    }

private fun PaperSignalRecord.toResponse(): DashboardSignalResponse =
    DashboardSignalResponse(
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

private fun PaperTradeRecord.toResponse(): DashboardTradeResponse =
    DashboardTradeResponse(
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

private val COIN_PATTERN = Regex("[A-Z0-9]{2,20}")
