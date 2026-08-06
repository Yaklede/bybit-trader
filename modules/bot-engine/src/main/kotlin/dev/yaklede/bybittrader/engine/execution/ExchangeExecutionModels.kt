package dev.yaklede.bybittrader.engine.execution

import dev.yaklede.bybittrader.domain.OrderStatus
import dev.yaklede.bybittrader.domain.OrderType
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

data class ExchangeExecutionConfig(
    val enabled: Boolean = false,
    val accountEquity: BigDecimal = BigDecimal("1000000"),
    val useLiveAccountEquity: Boolean = false,
    val riskFraction: BigDecimal = BigDecimal("0.055"),
    val feeRate: BigDecimal = BigDecimal("0.0006"),
    val slippageBufferRate: BigDecimal = BigDecimal("0.0002"),
    val quantityStep: BigDecimal = BigDecimal("0.001"),
    val minQuantity: BigDecimal = BigDecimal("0.001"),
    val maxQuantity: BigDecimal? = null,
    val maxNotional: BigDecimal? = null,
    val leverage: BigDecimal? = null,
    val liquidationBufferPct: BigDecimal = BigDecimal("0.6"),
    val minimumNetRiskReward: BigDecimal = BigDecimal("1.0"),
    val duplicateSignalLookback: Int = 50,
    val priceTick: BigDecimal = BigDecimal("0.1"),
    val protectionGracePeriod: Duration = Duration.ofSeconds(120),
    val maximumEntryDelay: Duration = Duration.ofSeconds(30),
    val maximumActualRiskOverrunFraction: BigDecimal = BigDecimal("0.05"),
    val safetyVerificationAttempts: Int = 5,
    val safetyVerificationInterval: Duration = Duration.ofMillis(250),
    val circuitBreakerEnabled: Boolean = true,
    val maximumDailyLossFraction: BigDecimal = BigDecimal("0.03"),
    val maximumAccountDrawdownFraction: BigDecimal = BigDecimal("0.20"),
    val maximumConsecutiveLosses: Int = 3,
    val riskStateMaximumAge: Duration = Duration.ofSeconds(120),
    val walletReconciliationEnabled: Boolean = false,
    val walletReconciliationTolerance: BigDecimal = BigDecimal("0.01"),
    val walletReconciliationMaximumAge: Duration = Duration.ofSeconds(180),
    val walletReconciliationConfirmedMismatchCount: Int = 3,
) {
    init {
        require(accountEquity > BigDecimal.ZERO) { "Execution account equity must be positive." }
        require(riskFraction > BigDecimal.ZERO && riskFraction <= BigDecimal("0.20")) {
            "Execution risk fraction must be between 0 and 0.20."
        }
        require(feeRate >= BigDecimal.ZERO && feeRate <= BigDecimal("0.01")) {
            "Execution fee rate must be between 0 and 0.01."
        }
        require(slippageBufferRate >= BigDecimal.ZERO && slippageBufferRate <= BigDecimal("0.01")) {
            "Execution slippage buffer rate must be between 0 and 0.01."
        }
        require(quantityStep > BigDecimal.ZERO) { "Execution quantity step must be positive." }
        require(minQuantity > BigDecimal.ZERO) { "Execution minimum quantity must be positive." }
        require(maxQuantity == null || maxQuantity >= minQuantity) {
            "Execution max quantity must be greater than or equal to min quantity."
        }
        require(maxNotional == null || maxNotional > BigDecimal.ZERO) { "Execution max notional must be positive." }
        require(leverage == null || leverage > BigDecimal.ONE) { "Execution leverage must be greater than 1." }
        require(liquidationBufferPct >= BigDecimal.ZERO && liquidationBufferPct <= BigDecimal("10")) {
            "Execution liquidation buffer must be between 0 and 10 percent."
        }
        require(minimumNetRiskReward > BigDecimal.ZERO) {
            "Execution minimum net risk reward must be positive."
        }
        require(duplicateSignalLookback in 1..100) { "Duplicate signal lookback must be between 1 and 100." }
        require(priceTick > BigDecimal.ZERO) { "Execution price tick must be positive." }
        require(!protectionGracePeriod.isNegative && !protectionGracePeriod.isZero) {
            "Execution protection grace period must be positive."
        }
        require(!maximumEntryDelay.isNegative && !maximumEntryDelay.isZero) {
            "Execution maximum entry delay must be positive."
        }
        require(maximumActualRiskOverrunFraction >= BigDecimal.ZERO && maximumActualRiskOverrunFraction <= BigDecimal.ONE) {
            "Execution maximum actual-risk overrun fraction must be between 0 and 1."
        }
        require(safetyVerificationAttempts in 1..20) {
            "Execution safety verification attempts must be between 1 and 20."
        }
        require(!safetyVerificationInterval.isNegative && !safetyVerificationInterval.isZero) {
            "Execution safety verification interval must be positive."
        }
        require(maximumDailyLossFraction > BigDecimal.ZERO && maximumDailyLossFraction <= BigDecimal.ONE) {
            "Execution maximum daily loss fraction must be between 0 and 1."
        }
        require(maximumAccountDrawdownFraction > BigDecimal.ZERO && maximumAccountDrawdownFraction <= BigDecimal.ONE) {
            "Execution maximum account drawdown fraction must be between 0 and 1."
        }
        require(maximumConsecutiveLosses in 1..100) {
            "Execution maximum consecutive losses must be between 1 and 100."
        }
        require(!riskStateMaximumAge.isNegative && !riskStateMaximumAge.isZero) {
            "Execution risk-state maximum age must be positive."
        }
        require(walletReconciliationTolerance >= BigDecimal.ZERO) {
            "Execution wallet-reconciliation tolerance must not be negative."
        }
        require(!walletReconciliationMaximumAge.isNegative && !walletReconciliationMaximumAge.isZero) {
            "Execution wallet-reconciliation maximum age must be positive."
        }
        require(walletReconciliationConfirmedMismatchCount in 1..100) {
            "Execution wallet-reconciliation mismatch count must be between 1 and 100."
        }
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
    val price: BigDecimal? = null,
    val timeInForce: ExchangeTimeInForce =
        if (orderType == OrderType.MARKET) ExchangeTimeInForce.IOC else ExchangeTimeInForce.GTC,
) {
    init {
        require(quantity > BigDecimal.ZERO) { "Order quantity must be positive." }
        require(clientOrderId.isNotBlank()) { "Client order id must not be blank." }
        require(clientOrderId.length <= 36) { "Client order id must be 36 characters or shorter." }
        require(
            (orderType == OrderType.MARKET && price == null) ||
                (orderType == OrderType.LIMIT && price != null && price > BigDecimal.ZERO),
        ) { "Limit orders require a positive price and market orders must not include one." }
    }
}

enum class ExchangeTimeInForce {
    GTC,
    IOC,
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
    val reduceOnly: Boolean = false,
    val stopOrderType: String? = null,
)

data class ExchangePosition(
    val symbol: Symbol,
    val side: Side,
    val size: BigDecimal,
    val openedAt: Instant?,
    val entryPrice: BigDecimal?,
    val markPrice: BigDecimal?,
    val unrealizedPnl: BigDecimal?,
    val updatedAt: Instant?,
    val takeProfit: BigDecimal? = null,
    val stopLoss: BigDecimal? = null,
)

data class ExchangePositionProtectionRequest(
    val symbol: Symbol,
    val takeProfit: BigDecimal?,
    val stopLoss: BigDecimal,
) {
    init {
        require(takeProfit == null || takeProfit > BigDecimal.ZERO) { "Position take profit must be positive." }
        require(stopLoss > BigDecimal.ZERO) { "Position stop loss must be positive." }
    }
}

data class ExchangeExecutionFill(
    val exchangeOrderId: String?,
    val clientOrderId: String?,
    val symbol: Symbol,
    val side: Side,
    val price: BigDecimal,
    val quantity: BigDecimal,
    val fee: BigDecimal,
    val executedAt: Instant,
    val executionId: String? = null,
    val executionType: String? = null,
    val createType: String? = null,
    val stopOrderType: String? = null,
    val closedSize: BigDecimal? = null,
    val executionPnl: BigDecimal? = null,
)

data class ExchangeOrderUpdate(
    val exchangeOrderId: String?,
    val clientOrderId: String?,
    val parentClientOrderId: String?,
    val symbol: Symbol,
    val side: Side,
    val orderType: OrderType,
    val status: OrderStatus,
    val quantity: BigDecimal,
    val cumulativeFilledQuantity: BigDecimal,
    val leavesQuantity: BigDecimal,
    val averageFillPrice: BigDecimal?,
    val reduceOnly: Boolean,
    val rejectReason: String?,
    val cancelType: String?,
    val updatedAt: Instant,
) {
    init {
        require(!exchangeOrderId.isNullOrBlank() || !clientOrderId.isNullOrBlank()) {
            "Order update needs an exchange order id or client order id."
        }
        require(quantity > BigDecimal.ZERO) { "Order update quantity must be positive." }
        require(cumulativeFilledQuantity >= BigDecimal.ZERO) { "Order update cumulative fill must not be negative." }
        require(leavesQuantity >= BigDecimal.ZERO) { "Order update leaves quantity must not be negative." }
        require(cumulativeFilledQuantity <= quantity) { "Order update cumulative fill must not exceed order quantity." }
        require(averageFillPrice == null || averageFillPrice > BigDecimal.ZERO) {
            "Order update average fill price must be positive."
        }
    }
}

data class ExchangeClosedPnl(
    val exchangeOrderId: String?,
    val clientOrderId: String?,
    val symbol: Symbol,
    val side: Side,
    val openedAt: Instant?,
    val closedAt: Instant,
    val entryPrice: BigDecimal,
    val exitPrice: BigDecimal,
    val quantity: BigDecimal,
    val grossPnl: BigDecimal,
    val fees: BigDecimal,
    val netPnl: BigDecimal,
    val exitReason: String?,
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

data class ExchangeAccountTransaction(
    val transactionId: String,
    val symbol: Symbol?,
    val category: String,
    val side: Side?,
    val transactionAt: Instant,
    val type: String,
    val subtype: String?,
    val quantity: BigDecimal?,
    val size: BigDecimal?,
    val currency: String,
    val tradePrice: BigDecimal?,
    val funding: BigDecimal,
    val fee: BigDecimal,
    val cashFlow: BigDecimal,
    val change: BigDecimal,
    val cashBalance: BigDecimal?,
    val feeRate: BigDecimal?,
    val tradeId: String?,
    val exchangeOrderId: String?,
    val clientOrderId: String?,
)

data class ExchangeCoinBalance(
    val coin: String,
    val equity: BigDecimal?,
    val usdValue: BigDecimal?,
    val walletBalance: BigDecimal?,
    val locked: BigDecimal?,
    val unrealizedPnl: BigDecimal?,
    val cumulativeRealizedPnl: BigDecimal? = null,
)

data class ExchangeReconciliationReport(
    val symbol: Symbol,
    val reconciledAt: Instant,
    val openOrders: List<ExchangeOpenOrder>,
    val positions: List<ExchangePosition>,
    val executions: List<ExchangeExecutionFill>,
    val closedPnls: List<ExchangeClosedPnl> = emptyList(),
    val persistedClosures: List<ExecutionTradeClosure> = emptyList(),
    val lifecycleEvent: ExecutionLifecycleEvent? = null,
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

data class ExchangeSafetyResult(
    val action: ExchangeSafetyAction,
    val status: ExchangeSafetyStatus,
    val mode: String,
    val symbol: Symbol,
    val requestedAt: Instant,
    val verifiedAt: Instant,
    val cancelledEntryOrderCount: Int,
    val submittedCloseOrderCount: Int,
    val protectedPositionCount: Int,
    val remainingOpenOrderCount: Int?,
    val remainingPositionCount: Int?,
    val issueCodes: List<String>,
)

enum class ExchangeSafetyAction {
    SAFE_STOP,
    FLATTEN,
}

enum class ExchangeSafetyStatus {
    CONFIRMED,
    PENDING,
    FAILED,
}

enum class ExchangeEvaluationStatus {
    DISABLED,
    SKIPPED_BY_MODE,
    NO_TRADE,
    REJECTED,
    SUBMITTED,
    EXIT_SUBMITTED,
}

interface ExchangeExecutionGateway {
    suspend fun setLeverage(
        symbol: Symbol,
        leverage: BigDecimal,
    )

    suspend fun placeOrder(request: ExchangeOrderRequest): ExchangeOrderResult

    suspend fun setPositionProtection(request: ExchangePositionProtectionRequest): Unit =
        throw ExchangeExecutionException("Exchange position protection is unavailable.")

    suspend fun cancelOrder(request: ExchangeCancelRequest): ExchangeCancelResult

    suspend fun openOrders(symbol: Symbol): List<ExchangeOpenOrder>

    suspend fun positions(symbol: Symbol): List<ExchangePosition>

    suspend fun executions(symbol: Symbol): List<ExchangeExecutionFill>

    suspend fun closedPnls(symbol: Symbol): List<ExchangeClosedPnl> = emptyList()

    suspend fun accountBalance(coin: String? = null): ExchangeAccountBalance

    suspend fun accountTransactions(
        currency: String,
        startAt: Instant,
        endAt: Instant,
    ): List<ExchangeAccountTransaction> = emptyList()
}

class ExchangeExecutionException(
    message: String,
    val providerCode: String? = null,
    val providerMessage: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
