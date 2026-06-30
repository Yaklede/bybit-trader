package dev.yaklede.bybittrader.engine.paper

import dev.yaklede.bybittrader.domain.BotMode
import dev.yaklede.bybittrader.domain.OrderStatus
import dev.yaklede.bybittrader.domain.OrderType
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.control.BotStateStore
import dev.yaklede.bybittrader.engine.market.MarketCandleStore
import dev.yaklede.bybittrader.strategy.TradingStrategy
import java.math.BigDecimal
import java.math.MathContext
import java.time.Clock
import java.time.Instant

class PaperTradingService(
    private val stateStore: BotStateStore,
    private val candleStore: MarketCandleStore,
    private val paperTradingStore: PaperTradingStore,
    private val strategy: TradingStrategy,
    private val config: PaperTradingConfig = PaperTradingConfig(),
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun evaluateOnce(
        symbol: Symbol,
        timeframe: Timeframe,
        candleLimit: Int,
    ): PaperEvaluationResult {
        require(candleLimit in strategy.warmupCandles..1000) {
            "Candle limit must be between strategy warmup candles and 1000."
        }

        val now = Instant.now(clock)
        val mode = stateStore.current().mode
        if (mode.blocksNewEntries()) {
            return PaperEvaluationResult(
                symbol = symbol,
                timeframe = timeframe,
                mode = mode.name,
                status = PaperEvaluationStatus.SKIPPED_BY_MODE,
                evaluatedAt = now,
                candleCount = 0,
                reasonCodes = listOf("MODE_${mode.name}_BLOCKS_NEW_ENTRIES"),
                signalId = null,
                orderId = null,
                fillPrice = null,
                quantity = null,
                fee = null,
            )
        }

        val candles = candleStore.recentCandles(symbol, timeframe, candleLimit).sortedBy { it.openedAt }
        require(candles.size >= strategy.warmupCandles) {
            "At least ${strategy.warmupCandles} candles are required for ${strategy.name}."
        }

        val decision = strategy.evaluate(candles)
        val signal = decision.intent
        if (signal == null) {
            return PaperEvaluationResult(
                symbol = symbol,
                timeframe = timeframe,
                mode = mode.name,
                status = PaperEvaluationStatus.NO_TRADE,
                evaluatedAt = now,
                candleCount = candles.size,
                reasonCodes = decision.reasonCodes.ifEmpty { listOf("NO_SIGNAL") },
                signalId = null,
                orderId = null,
                fillPrice = null,
                quantity = null,
                fee = null,
            )
        }

        val entryPrice = candles.last().close
        val riskPerUnit = entryPrice.subtract(signal.invalidationPrice.value).abs()
        if (riskPerUnit <= BigDecimal.ZERO) {
            val rejectedSignalId =
                paperTradingStore.recordSignal(
                    signal.toRecord(
                        accepted = false,
                        rejectionReason = "INVALID_RISK_DISTANCE",
                        createdAt = now,
                    ),
                )
            return PaperEvaluationResult(
                symbol = symbol,
                timeframe = timeframe,
                mode = mode.name,
                status = PaperEvaluationStatus.REJECTED,
                evaluatedAt = now,
                candleCount = candles.size,
                reasonCodes = listOf("INVALID_RISK_DISTANCE"),
                signalId = rejectedSignalId,
                orderId = null,
                fillPrice = null,
                quantity = null,
                fee = null,
            )
        }

        val riskAmount = config.initialEquity.multiply(config.riskFraction, MathContext.DECIMAL64)
        val quantity = riskAmount.divide(riskPerUnit, MathContext.DECIMAL64)
        val fee = entryPrice.multiply(quantity, MathContext.DECIMAL64).multiply(config.feeRate, MathContext.DECIMAL64)
        val signalId =
            paperTradingStore.recordSignal(
                signal.toRecord(
                    accepted = true,
                    rejectionReason = null,
                    createdAt = now,
                ),
            )
        val orderId =
            paperTradingStore.recordOrder(
                PaperOrderRecord(
                    clientOrderId = "paper-${symbol.value}-${now.toEpochMilli()}-$signalId",
                    signalId = signalId,
                    side = signal.side,
                    orderType = OrderType.MARKET,
                    orderStatus = OrderStatus.FILLED,
                    intendedRisk = riskAmount,
                    createdAt = now,
                ),
            )
        paperTradingStore.recordFill(
            PaperFillRecord(
                orderId = orderId,
                fillPrice = entryPrice,
                quantity = quantity,
                fee = fee,
                liquidityRole = "PAPER",
                filledAt = now,
            ),
        )
        paperTradingStore.recordPosition(
            PaperPositionRecord(
                symbol = symbol,
                side = signal.side,
                quantity = quantity,
                entryPrice = entryPrice,
                realizedPnl = fee.negate(),
                unrealizedPnl = BigDecimal.ZERO,
                capturedAt = now,
            ),
        )
        paperTradingStore.recordPerformanceSnapshot(
            PaperPerformanceSnapshot(
                period = "paper-runtime",
                netPnl = fee.negate(),
                profitFactor = null,
                expectancy = null,
                maxDrawdown = BigDecimal.ZERO,
                capturedAt = now,
            ),
        )

        return PaperEvaluationResult(
            symbol = symbol,
            timeframe = timeframe,
            mode = mode.name,
            status = PaperEvaluationStatus.FILLED,
            evaluatedAt = now,
            candleCount = candles.size,
            reasonCodes = decision.reasonCodes.ifEmpty { signal.score.reasonCodes },
            signalId = signalId,
            orderId = orderId,
            fillPrice = entryPrice,
            quantity = quantity,
            fee = fee,
        )
    }

    private fun BotMode.blocksNewEntries(): Boolean = this != BotMode.RUNNING
}

private fun dev.yaklede.bybittrader.domain.SignalIntent.toRecord(
    accepted: Boolean,
    rejectionReason: String?,
    createdAt: Instant,
): PaperSignalRecord =
    PaperSignalRecord(
        strategy = strategy,
        symbol = symbol,
        side = side,
        score = score.total,
        grade = score.total.toGrade(),
        reasonCodes = score.reasonCodes,
        accepted = accepted,
        rejectionReason = rejectionReason,
        createdAt = createdAt,
    )

private fun Int.toGrade(): String =
    when {
        this >= 85 -> "A"
        this >= 75 -> "B"
        else -> "C"
    }
