package dev.yaklede.bybittrader.engine.paper

import dev.yaklede.bybittrader.domain.BotMode
import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Price
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.SignalIntent
import dev.yaklede.bybittrader.domain.SignalScore
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.control.BotRuntimeStatus
import dev.yaklede.bybittrader.engine.control.BotStateStore
import dev.yaklede.bybittrader.engine.market.MarketCandleStore
import dev.yaklede.bybittrader.strategy.StrategyDecision
import dev.yaklede.bybittrader.strategy.TradingStrategy
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PaperTradingServiceTest :
    StringSpec({
        "running mode records a paper signal order fill position and performance snapshot" {
            val paperStore = InMemoryPaperTradingStore()
            val service =
                PaperTradingService(
                    stateStore = InMemoryStateStore(BotMode.RUNNING),
                    candleStore = InMemoryCandleStore(paperCandles()),
                    paperTradingStore = paperStore,
                    strategy = AlwaysBuyPaperStrategy(),
                    clock = fixedPaperClock(),
                )

            val result = service.evaluateOnce(Symbol("BTCUSDT"), Timeframe.M15, 30)

            result.status shouldBe PaperEvaluationStatus.FILLED
            result.signalId shouldBe 1L
            result.orderId shouldBe 1L
            paperStore.signals shouldHaveSize 1
            paperStore.orders shouldHaveSize 1
            paperStore.fills shouldHaveSize 1
            paperStore.positions shouldHaveSize 1
            paperStore.performanceSnapshots shouldHaveSize 1
        }

        "pause new entries mode skips paper entries without recording an order" {
            val paperStore = InMemoryPaperTradingStore()
            val service =
                PaperTradingService(
                    stateStore = InMemoryStateStore(BotMode.PAUSE_NEW_ENTRIES),
                    candleStore = InMemoryCandleStore(paperCandles()),
                    paperTradingStore = paperStore,
                    strategy = AlwaysBuyPaperStrategy(),
                    clock = fixedPaperClock(),
                )

            val result = service.evaluateOnce(Symbol("BTCUSDT"), Timeframe.M15, 30)

            result.status shouldBe PaperEvaluationStatus.SKIPPED_BY_MODE
            paperStore.orders shouldHaveSize 0
            paperStore.fills shouldHaveSize 0
        }

        "duplicate keyed paper signal is skipped without recording another order" {
            val paperStore = InMemoryPaperTradingStore()
            val service =
                PaperTradingService(
                    stateStore = InMemoryStateStore(BotMode.RUNNING),
                    candleStore = InMemoryCandleStore(paperCandles()),
                    paperTradingStore = paperStore,
                    strategy = KeyedAlwaysBuyPaperStrategy(),
                    clock = fixedPaperClock(),
                )

            service.evaluateOnce(Symbol("BTCUSDT"), Timeframe.M15, 30).status shouldBe PaperEvaluationStatus.FILLED
            val second = service.evaluateOnce(Symbol("BTCUSDT"), Timeframe.M15, 30)

            second.status shouldBe PaperEvaluationStatus.NO_TRADE
            second.reasonCodes shouldBe listOf("DUPLICATE_SIGNAL", "ENTRY_AT_2026-06-30T07:15:00Z")
            paperStore.orders shouldHaveSize 1
            paperStore.fills shouldHaveSize 1
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
    private val candles: List<Candle>,
) : MarketCandleStore {
    override suspend fun upsert(candles: List<Candle>) = Unit

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

private class InMemoryPaperTradingStore : PaperTradingStore {
    val signals = mutableListOf<PaperSignalRecord>()
    val orders = mutableListOf<PaperOrderRecord>()
    val fills = mutableListOf<PaperFillRecord>()
    val positions = mutableListOf<PaperPositionRecord>()
    val performanceSnapshots = mutableListOf<PaperPerformanceSnapshot>()

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

private fun paperCandles(): List<Candle> =
    (0 until 30).map { index ->
        val close = BigDecimal(100 + index)
        Candle(
            symbol = Symbol("BTCUSDT"),
            timeframe = Timeframe.M15,
            openedAt = Instant.parse("2026-06-30T00:00:00Z").plusSeconds(index * 900L),
            open = close,
            high = close.add(BigDecimal("2")),
            low = close.subtract(BigDecimal("2")),
            close = close,
            volume = BigDecimal("10"),
        )
    }

private fun fixedPaperClock(): Clock = Clock.fixed(Instant.parse("2026-06-30T01:00:00Z"), ZoneOffset.UTC)
