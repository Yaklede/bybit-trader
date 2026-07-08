package dev.yaklede.bybittrader.engine.execution

import dev.yaklede.bybittrader.domain.OrderStatus
import dev.yaklede.bybittrader.domain.OrderType
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import java.math.BigDecimal
import java.time.Instant

data class ExchangeExecutionConfig(
    val enabled: Boolean = false,
    val accountEquity: BigDecimal = BigDecimal("1000000"),
    val useLiveAccountEquity: Boolean = false,
    val riskFraction: BigDecimal = BigDecimal("0.055"),
    val feeRate: BigDecimal = BigDecimal("0.0006"),
    val quantityStep: BigDecimal = BigDecimal("0.001"),
    val minQuantity: BigDecimal = BigDecimal("0.001"),
    val maxQuantity: BigDecimal? = null,
    val maxNotional: BigDecimal? = null,
    val leverage: BigDecimal? = null,
    val duplicateSignalLookback: Int = 50,
) {
    init {
        require(accountEquity > BigDecimal.ZERO) { "Execution account equity must be positive." }
        require(riskFraction > BigDecimal.ZERO && riskFraction <= BigDecimal("0.20")) {
            "Execution risk fraction must be between 0 and 0.20."
        }
        require(feeRate >= BigDecimal.ZERO && feeRate <= BigDecimal("0.01")) {
            "Execution fee rate must be between 0 and 0.01."
        }
        require(quantityStep > BigDecimal.ZERO) { "Execution quantity step must be positive." }
        require(minQuantity > BigDecimal.ZERO) { "Execution minimum quantity must be positive." }
        require(maxQuantity == null || maxQuantity >= minQuantity) {
            "Execution max quantity must be greater than or equal to min quantity."
        }
        require(maxNotional == null || maxNotional > BigDecimal.ZERO) { "Execution max notional must be positive." }
        require(leverage == null || leverage > BigDecimal.ONE) { "Execution leverage must be greater than 1." }
        require(duplicateSignalLookback in 1..100) { "Duplicate signal lookback must be between 1 and 100." }
    }
}

data class ExchangeOrderRequest(
    val symbol: Symbol,
    val side: Side,
    val orderType: OrderType,
    val quantity: BigDecimal,
    val clientOrderId: String,
    val takeProfit: BigDecimal?,
    val stopLoss: BigDecimal?,
    val reduceOnly: Boolean = false,
) {
    init {
        require(quantity > BigDecimal.ZERO) { "Order quantity must be positive." }
        require(clientOrderId.isNotBlank()) { "Client order id must not be blank." }
        require(clientOrderId.length <= 36) { "Client order id must be 36 characters or shorter." }
    }
}

data class ExchangeOrderResult(
    val exchangeOrderId: String?,
    val clientOrderId: String,
    val status: OrderStatus,
)

data class ExchangeCancelRequest(
    val symbol: Symbol,
    val exchangeOrderId: String?,
    val clientOrderId: String?,
) {
    init {
        require(!exchangeOrderId.isNullOrBlank() || !clientOrderId.isNullOrBlank()) {
            "Cancel request needs exchange order id or client order id."
        }
    }
}

data class ExchangeCancelResult(
    val exchangeOrderId: String?,
    val clientOrderId: String?,
)

data class ExchangeOpenOrder(
    val exchangeOrderId: String?,
    val clientOrderId: String?,
    val symbol: Symbol,
    val side: Side,
    val orderType: OrderType,
    val status: OrderStatus,
    val quantity: BigDecimal?,
    val createdAt: Instant?,
)

data class ExchangePosition(
    val symbol: Symbol,
    val side: Side,
    val size: BigDecimal,
    val entryPrice: BigDecimal?,
    val markPrice: BigDecimal?,
    val unrealizedPnl: BigDecimal?,
    val updatedAt: Instant?,
)

data class ExchangeExecutionFill(
    val exchangeOrderId: String?,
    val clientOrderId: String?,
    val symbol: Symbol,
    val side: Side,
    val price: BigDecimal,
    val quantity: BigDecimal,
    val fee: BigDecimal,
    val executedAt: Instant,
)

data class ExchangeAccountBalance(
    val accountType: String,
    val totalEquity: BigDecimal?,
    val totalWalletBalance: BigDecimal?,
    val totalMarginBalance: BigDecimal?,
    val totalAvailableBalance: BigDecimal?,
    val totalPerpUnrealizedPnl: BigDecimal?,
    val totalInitialMargin: BigDecimal?,
    val totalMaintenanceMargin: BigDecimal?,
    val coins: List<ExchangeCoinBalance>,
    val capturedAt: Instant,
)

data class ExchangeCoinBalance(
    val coin: String,
    val equity: BigDecimal?,
    val usdValue: BigDecimal?,
    val walletBalance: BigDecimal?,
    val locked: BigDecimal?,
    val unrealizedPnl: BigDecimal?,
)

data class ExchangeReconciliationReport(
    val symbol: Symbol,
    val reconciledAt: Instant,
    val openOrders: List<ExchangeOpenOrder>,
    val positions: List<ExchangePosition>,
    val executions: List<ExchangeExecutionFill>,
)

data class ExchangeEvaluationResult(
    val symbol: Symbol,
    val timeframe: Timeframe,
    val mode: String,
    val status: ExchangeEvaluationStatus,
    val evaluatedAt: Instant,
    val candleCount: Int,
    val reasonCodes: List<String>,
    val signalId: Long?,
    val orderId: Long?,
    val exchangeOrderId: String?,
    val clientOrderId: String?,
    val entryPrice: BigDecimal?,
    val takeProfit: BigDecimal?,
    val stopLoss: BigDecimal?,
    val quantity: BigDecimal?,
    val intendedRisk: BigDecimal?,
)

data class ExchangeSmokeOrderResult(
    val symbol: Symbol,
    val side: Side,
    val quantity: BigDecimal,
    val exchangeOrderId: String?,
    val clientOrderId: String,
    val orderId: Long,
    val status: String,
    val submittedAt: Instant,
)

data class ExchangeManualOrderResult(
    val symbol: Symbol,
    val side: Side,
    val quantity: BigDecimal,
    val reduceOnly: Boolean,
    val exchangeOrderId: String?,
    val clientOrderId: String,
    val orderId: Long,
    val status: String,
    val submittedAt: Instant,
)

enum class ExchangeEvaluationStatus {
    DISABLED,
    SKIPPED_BY_MODE,
    NO_TRADE,
    REJECTED,
    SUBMITTED,
}

interface ExchangeExecutionGateway {
    suspend fun setLeverage(
        symbol: Symbol,
        leverage: BigDecimal,
    )

    suspend fun placeOrder(request: ExchangeOrderRequest): ExchangeOrderResult

    suspend fun cancelOrder(request: ExchangeCancelRequest): ExchangeCancelResult

    suspend fun openOrders(symbol: Symbol): List<ExchangeOpenOrder>

    suspend fun positions(symbol: Symbol): List<ExchangePosition>

    suspend fun executions(symbol: Symbol): List<ExchangeExecutionFill>

    suspend fun accountBalance(coin: String? = null): ExchangeAccountBalance
}

class ExchangeExecutionException(
    message: String,
    val providerCode: String? = null,
    val providerMessage: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
