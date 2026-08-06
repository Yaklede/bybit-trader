package dev.yaklede.bybittrader.engine.paper

import dev.yaklede.bybittrader.domain.BotMode
import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Price
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.SignalIntent
import dev.yaklede.bybittrader.domain.SignalScore
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.backtest.BacktestConfig
import dev.yaklede.bybittrader.engine.backtest.BacktestRunner
import dev.yaklede.bybittrader.engine.control.BotRuntimeStatus
import dev.yaklede.bybittrader.engine.control.BotStateStore
import dev.yaklede.bybittrader.engine.market.MarketCandleStore
import dev.yaklede.bybittrader.strategy.StrategyDecision
import dev.yaklede.bybittrader.strategy.TradingStrategy
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class PaperTradingServiceTest :
    StringSpec({
        "running mode waits for the next candle open and then manages the position to exit" {
            val paperStore = InMemoryPaperTradingStore()
            val candleStore = InMemoryCandleStore(paperCandles())
            val clock = MutablePaperClock(Instant.parse("2026-06-30T07:30:00Z"))
            val service =
                PaperTradingService(
                    stateStore = InMemoryStateStore(BotMode.RUNNING),
                    candleStore = candleStore,
                    paperTradingStore = paperStore,
                    runtimeStateStore = paperStore,
                    strategy = AlwaysBuyPaperStrategy(),
                    clock = clock,
                )

            val pending = service.evaluateOnce(Symbol("BTCUSDT"), Timeframe.M15, 30)
            candleStore.append(paperCandleAt(30, open = 130, high = 132, low = 128, close = 131))
            clock.current = Instant.parse("2026-06-30T07:45:00Z")
            val filled = service.evaluateOnce(Symbol("BTCUSDT"), Timeframe.M15, 30)
            candleStore.append(paperCandleAt(31, open = 131, high = 132, low = 123, close = 124))
            clock.current = Instant.parse("2026-06-30T08:00:00Z")
            val closed = service.evaluateOnce(Symbol("BTCUSDT"), Timeframe.M15, 30)

            pending.status shouldBe PaperEvaluationStatus.ENTRY_PENDING
            filled.status shouldBe PaperEvaluationStatus.FILLED
            filled.signalId shouldBe 1L
            filled.orderId shouldBe 1L
            closed.status shouldBe PaperEvaluationStatus.CLOSED
            closed.exitReason shouldBe "STOP"
            paperStore.signals shouldHaveSize 1
            paperStore.orders shouldHaveSize 2
            paperStore.fills shouldHaveSize 2
            paperStore.positions shouldHaveSize 2
            paperStore.performanceSnapshots shouldHaveSize 1
        }

        "pause new entries mode skips paper entries without recording an order" {
            val paperStore = InMemoryPaperTradingStore()
            val service =
                PaperTradingService(
                    stateStore = InMemoryStateStore(BotMode.PAUSE_NEW_ENTRIES),
                    candleStore = InMemoryCandleStore(paperCandles()),
                    paperTradingStore = paperStore,
                    runtimeStateStore = paperStore,
                    strategy = AlwaysBuyPaperStrategy(),
                    clock = MutablePaperClock(Instant.parse("2026-06-30T07:30:00Z")),
                )

            val result = service.evaluateOnce(Symbol("BTCUSDT"), Timeframe.M15, 30)

            result.status shouldBe PaperEvaluationStatus.SKIPPED_BY_MODE
            paperStore.orders shouldHaveSize 0
            paperStore.fills shouldHaveSize 0
        }

        "the same closed candle is not evaluated twice after state persistence" {
            val paperStore = InMemoryPaperTradingStore()
            val service =
                PaperTradingService(
                    stateStore = InMemoryStateStore(BotMode.RUNNING),
                    candleStore = InMemoryCandleStore(paperCandles()),
                    paperTradingStore = paperStore,
                    runtimeStateStore = paperStore,
                    strategy = KeyedAlwaysBuyPaperStrategy(),
                    clock = MutablePaperClock(Instant.parse("2026-06-30T07:30:00Z")),
                )

            service.evaluateOnce(Symbol("BTCUSDT"), Timeframe.M15, 30).status shouldBe
                PaperEvaluationStatus.ENTRY_PENDING
            val second = service.evaluateOnce(Symbol("BTCUSDT"), Timeframe.M15, 30)

            second.status shouldBe PaperEvaluationStatus.NO_TRADE
            second.reasonCodes shouldBe listOf("NO_NEW_CLOSED_CANDLE")
            paperStore.signals shouldHaveSize 1
            paperStore.orders shouldHaveSize 0
            paperStore.fills shouldHaveSize 0
        }

        "restart catch-up expires pending entries instead of backfilling downtime trades" {
            val paperStore = InMemoryPaperTradingStore()
            val candleStore = InMemoryCandleStore(paperCandles())
            val clock = MutablePaperClock(Instant.parse("2026-06-30T07:30:00Z"))
            val service =
                PaperTradingService(
                    stateStore = InMemoryStateStore(BotMode.RUNNING),
                    candleStore = candleStore,
                    paperTradingStore = paperStore,
                    runtimeStateStore = paperStore,
                    strategy = AlwaysBuyPaperStrategy(),
                    clock = clock,
                )

            service.evaluateOnce(Symbol("BTCUSDT"), Timeframe.M15, 30).status shouldBe
                PaperEvaluationStatus.ENTRY_PENDING
            candleStore.append(paperCandleAt(30, open = 130, high = 132, low = 128, close = 131))
            candleStore.append(paperCandleAt(31, open = 131, high = 133, low = 129, close = 132))
            candleStore.append(paperCandleAt(32, open = 132, high = 134, low = 130, close = 133))
            clock.current = Instant.parse("2026-06-30T08:15:00Z")

            val result = service.evaluateOnce(Symbol("BTCUSDT"), Timeframe.M15, 30)

            result.status shouldBe PaperEvaluationStatus.REJECTED
            result.reasonCodes shouldBe listOf("PENDING_ENTRY_EXPIRED_DURING_DOWNTIME")
            result.phase shouldBe PaperRuntimePhase.ENTRY_PENDING
            paperStore.orders shouldHaveSize 0
            paperStore.fills shouldHaveSize 0
            paperStore.signals shouldHaveSize 2
        }

        "incremental paper execution matches batch backtest exit and pnl" {
            val allCandles = paperParityCandles()
            val signalAt = allCandles[20].openedAt
            val strategy = TimedPaperStrategy(signalAt)
            val paperStore = InMemoryPaperTradingStore()
            val candleStore = InMemoryCandleStore(allCandles.take(21))
            val clock = MutablePaperClock(allCandles[20].openedAt.plusSeconds(900))
            val paperConfig =
                PaperTradingConfig(
                    initialEquity = BigDecimal("10000"),
                    riskFraction = BigDecimal("0.01"),
                    feeRate = BigDecimal.ZERO,
                    entrySlippageRate = BigDecimal.ZERO,
                    exitSlippageRate = BigDecimal.ZERO,
                    partialTakeProfitFraction = BigDecimal.ZERO,
                    fixedTargetEnabled = false,
                    maxHoldCandles = 2,
                )
            val service =
                PaperTradingService(
                    stateStore = InMemoryStateStore(BotMode.RUNNING),
                    candleStore = candleStore,
                    paperTradingStore = paperStore,
                    runtimeStateStore = paperStore,
                    strategy = strategy,
                    config = paperConfig,
                    clock = clock,
                )

            service.evaluateOnce(Symbol("BTCUSDT"), Timeframe.M15, 30).status shouldBe
                PaperEvaluationStatus.ENTRY_PENDING
            var paperResult: PaperEvaluationResult? = null
            allCandles.drop(21).forEach { candle ->
                candleStore.append(candle)
                clock.current = candle.openedAt.plusSeconds(900)
                paperResult = service.evaluateOnce(Symbol("BTCUSDT"), Timeframe.M15, 30)
            }
            val backtest =
                BacktestRunner(strategy).run(
                    allCandles,
                    BacktestConfig(
                        initialEquity = 10_000.0,
                        riskFraction = 0.01,
                        feeRate = 0.0,
                        slippageRate = 0.0,
                        exitSlippageRate = 0.0,
                        partialTakeProfitFraction = 0.0,
                        fixedTargetEnabled = false,
                        maxHoldCandles = 2,
                    ),
                )

            paperResult?.status shouldBe PaperEvaluationStatus.CLOSED
            paperResult?.realizedPnl?.toDouble() shouldBe (backtest.trades.single().pnl plusOrMinus 0.000001)
            paperResult?.equity?.toDouble() shouldBe (backtest.finalEquity plusOrMinus 0.000001)
            paperStore.performanceSnapshots
                .single()
                .netPnl
                .toDouble() shouldBe
                (backtest.netPnl plusOrMinus 0.000001)
        }
    })

private class InMemoryStateStore(
    initialMode: BotMode,
) : BotStateStore {
    private var status =
        BotRuntimeStatus(
            mode = initialMode,
            updatedAt = Instant.parse("2026-06-30T00:00:00Z"),
            heartbeatAt = null,
        )

    override suspend fun current(): BotRuntimeStatus = status

    override suspend fun update(status: BotRuntimeStatus) {
        this.status = status
    }
}

private class InMemoryCandleStore(
    candles: List<Candle>,
) : MarketCandleStore {
    private val candles = candles.toMutableList()

    override suspend fun upsert(candles: List<Candle>) = Unit

    fun append(candle: Candle) {
        candles += candle
    }

    override suspend fun recentCandles(
        symbol: Symbol,
        timeframe: Timeframe,
        limit: Int,
    ): List<Candle> =
        candles
            .filter { it.symbol == symbol && it.timeframe == timeframe }
            .sortedByDescending { it.openedAt }
            .take(limit)
}

private class InMemoryPaperTradingStore :
    PaperTradingStore,
    PaperRuntimeStateStore {
    val signals = mutableListOf<PaperSignalRecord>()
    val orders = mutableListOf<PaperOrderRecord>()
    val fills = mutableListOf<PaperFillRecord>()
    val positions = mutableListOf<PaperPositionRecord>()
    val performanceSnapshots = mutableListOf<PaperPerformanceSnapshot>()
    private val runtimeStates = mutableMapOf<Triple<String, Symbol, Timeframe>, PaperRuntimeState>()

    override suspend fun recordSignal(signal: PaperSignalRecord): Long {
        val id = signals.size + 1L
        signals += signal.copy(id = id)
        return id
    }

    override suspend fun recordOrder(order: PaperOrderRecord): Long {
        val id = orders.size + 1L
        orders += order.copy(id = id)
        return id
    }

    override suspend fun recordFill(fill: PaperFillRecord): Long {
        val id = fills.size + 1L
        fills += fill.copy(id = id)
        return id
    }

    override suspend fun recordPosition(position: PaperPositionRecord): Long {
        val id = positions.size + 1L
        positions += position.copy(id = id)
        return id
    }

    override suspend fun recordPerformanceSnapshot(snapshot: PaperPerformanceSnapshot): Long {
        val id = performanceSnapshots.size + 1L
        performanceSnapshots += snapshot.copy(id = id)
        return id
    }

    override suspend fun latestPerformanceSummary(): PaperPerformanceSnapshot? = performanceSnapshots.lastOrNull()

    override suspend fun recentSignals(limit: Int): List<PaperSignalRecord> = signals.asReversed().take(limit)

    override suspend fun recentTrades(limit: Int): List<PaperTradeRecord> =
        orders
            .asReversed()
            .take(limit)
            .map { order ->
                val fill = fills.firstOrNull { it.orderId == order.id }
                PaperTradeRecord(
                    orderId = order.id,
                    clientOrderId = order.clientOrderId,
                    signalId = order.signalId,
                    side = order.side,
                    orderType = order.orderType,
                    orderStatus = order.orderStatus,
                    intendedRisk = order.intendedRisk,
                    orderCreatedAt = order.createdAt,
                    fillId = fill?.id,
                    fillPrice = fill?.fillPrice,
                    quantity = fill?.quantity,
                    fee = fill?.fee,
                    filledAt = fill?.filledAt,
                )
            }

    override suspend fun paperRuntimeState(
        strategy: String,
        symbol: Symbol,
        timeframe: Timeframe,
    ): PaperRuntimeState? = runtimeStates[Triple(strategy, symbol, timeframe)]

    override suspend fun upsertPaperRuntimeState(state: PaperRuntimeState) {
        runtimeStates[Triple(state.strategy, state.symbol, state.timeframe)] = state
    }
}

private class AlwaysBuyPaperStrategy : TradingStrategy {
    override val name: String = "always-buy-paper-test"
    override val warmupCandles: Int = 20

    override fun evaluate(candles: List<Candle>): StrategyDecision {
        val latest = candles.last()
        return StrategyDecision(
            intent =
                SignalIntent(
                    symbol = latest.symbol,
                    side = Side.BUY,
                    strategy = name,
                    score = SignalScore(88, listOf("TEST_EDGE")),
                    invalidationPrice = Price(latest.close.subtract(BigDecimal("5"))),
                    expectedR = BigDecimal("1.5"),
                ),
            reasonCodes = listOf("TEST_EDGE"),
        )
    }
}

private class KeyedAlwaysBuyPaperStrategy : TradingStrategy {
    override val name: String = "keyed-always-buy-paper-test"
    override val warmupCandles: Int = 20

    override fun evaluate(candles: List<Candle>): StrategyDecision {
        val latest = candles.last()
        val reasonCodes = listOf("TEST_EDGE", "ENTRY_AT_${latest.openedAt}")
        return StrategyDecision(
            intent =
                SignalIntent(
                    symbol = latest.symbol,
                    side = Side.BUY,
                    strategy = name,
                    score = SignalScore(88, reasonCodes),
                    invalidationPrice = Price(latest.close.subtract(BigDecimal("5"))),
                    expectedR = BigDecimal("1.5"),
                ),
            reasonCodes = reasonCodes,
        )
    }
}

private class TimedPaperStrategy(
    private val signalAt: Instant,
) : TradingStrategy {
    override val name: String = "timed-paper-parity-test"
    override val warmupCandles: Int = 2

    override fun evaluate(candles: List<Candle>): StrategyDecision {
        val latest = candles.last()
        if (latest.openedAt != signalAt) return StrategyDecision.noTrade("NOT_SIGNAL_TIME")
        return StrategyDecision(
            intent =
                SignalIntent(
                    symbol = latest.symbol,
                    side = Side.BUY,
                    strategy = name,
                    score = SignalScore(80, listOf("PARITY_TEST")),
                    invalidationPrice = Price(BigDecimal("90")),
                    expectedR = BigDecimal("10"),
                ),
            reasonCodes = listOf("PARITY_TEST"),
        )
    }
}

private fun paperCandles(): List<Candle> =
    (0 until 30).map { index -> paperCandleAt(index, 100 + index, 102 + index, 98 + index, 100 + index) }

private fun paperCandleAt(
    index: Int,
    open: Int,
    high: Int,
    low: Int,
    close: Int,
): Candle =
    Candle(
        symbol = Symbol("BTCUSDT"),
        timeframe = Timeframe.M15,
        openedAt = Instant.parse("2026-06-30T00:00:00Z").plusSeconds(index * 900L),
        open = BigDecimal(open),
        high = BigDecimal(high),
        low = BigDecimal(low),
        close = BigDecimal(close),
        volume = BigDecimal("10"),
    )

private fun paperParityCandles(): List<Candle> =
    (0 until 24).map { index ->
        val close = if (index == 23) 105 else 100
        Candle(
            symbol = Symbol("BTCUSDT"),
            timeframe = Timeframe.M15,
            openedAt = Instant.parse("2026-06-30T00:00:00Z").plusSeconds(index * 900L),
            open = BigDecimal("100"),
            high = BigDecimal(if (index == 23) 106 else 101),
            low = BigDecimal("99"),
            close = BigDecimal(close),
            volume = BigDecimal.TEN,
        )
    }

private class MutablePaperClock(
    var current: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = current
}
