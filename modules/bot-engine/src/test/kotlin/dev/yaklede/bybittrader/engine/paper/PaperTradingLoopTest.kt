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
import dev.yaklede.bybittrader.engine.market.MarketDataFeed
import dev.yaklede.bybittrader.engine.market.MarketDataSyncService
import dev.yaklede.bybittrader.strategy.StrategyDecision
import dev.yaklede.bybittrader.strategy.TradingStrategy
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class PaperTradingLoopTest :
    StringSpec({
        "run once syncs public candles before evaluating paper trading" {
            val store = LoopPaperStore()
            val resultEvents = mutableListOf<PaperEvaluationResult>()
            val paperTradingService =
                PaperTradingService(
                    stateStore = LoopStateStore(),
                    candleStore = store,
                    paperTradingStore = store,
                    strategy = LoopAlwaysBuyStrategy(),
                    clock = fixedLoopClock(),
                )
            val loop =
                PaperTradingLoop(
                    marketDataSyncService =
                        MarketDataSyncService(
                            marketDataFeed = LoopMarketDataFeed(loopCandles()),
                            candleStore = store,
                            clock = fixedLoopClock(),
                        ),
                    paperTradingService = paperTradingService,
                    config =
                        PaperTradingLoopConfig(
                            symbol = Symbol("BTCUSDT"),
                            timeframe = Timeframe.M15,
                            candleLimit = 30,
                            interval = Duration.ofSeconds(1),
                        ),
                    onResult = { resultEvents += it },
                )

            val result = loop.runOnce()

            result.status shouldBe PaperEvaluationStatus.FILLED
            store.savedCandles shouldHaveSize 30
            store.orders shouldHaveSize 1
            resultEvents.single().status shouldBe PaperEvaluationStatus.FILLED
        }
    })

private class LoopStateStore : BotStateStore {
    override suspend fun current(): BotRuntimeStatus =
        BotRuntimeStatus(
            mode = BotMode.RUNNING,
            updatedAt = Instant.parse("2026-06-30T00:00:00Z"),
            heartbeatAt = null,
        )

    override suspend fun update(status: BotRuntimeStatus) = Unit
}

private class LoopMarketDataFeed(
    private val candles: List<Candle>,
) : MarketDataFeed {
    override suspend fun fetchRecentCandles(
        symbol: Symbol,
        timeframe: Timeframe,
        limit: Int,
    ): List<Candle> =
        candles
            .filter { it.symbol == symbol && it.timeframe == timeframe }
            .take(limit)
}

private class LoopPaperStore :
    MarketCandleStore,
    PaperTradingStore {
    val savedCandles = mutableListOf<Candle>()
    val signals = mutableListOf<PaperSignalRecord>()
    val orders = mutableListOf<PaperOrderRecord>()
    val fills = mutableListOf<PaperFillRecord>()
    val positions = mutableListOf<PaperPositionRecord>()
    val performanceSnapshots = mutableListOf<PaperPerformanceSnapshot>()

    override suspend fun upsert(candles: List<Candle>) {
        savedCandles += candles
    }

    override suspend fun recentCandles(
        symbol: Symbol,
        timeframe: Timeframe,
        limit: Int,
    ): List<Candle> =
        savedCandles
            .filter { it.symbol == symbol && it.timeframe == timeframe }
            .sortedByDescending { it.openedAt }
            .take(limit)

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

    override suspend fun recentTrades(limit: Int): List<PaperTradeRecord> = emptyList()
}

private class LoopAlwaysBuyStrategy : TradingStrategy {
    override val name: String = "loop-always-buy-test"
    override val warmupCandles: Int = 20

    override fun evaluate(candles: List<Candle>): StrategyDecision {
        val latest = candles.last()
        return StrategyDecision(
            intent =
                SignalIntent(
                    symbol = latest.symbol,
                    side = Side.BUY,
                    strategy = name,
                    score = SignalScore(90, listOf("LOOP_TEST_EDGE")),
                    invalidationPrice = Price(latest.close.subtract(BigDecimal("5"))),
                    expectedR = BigDecimal("1.5"),
                ),
            reasonCodes = listOf("LOOP_TEST_EDGE"),
        )
    }
}

private fun loopCandles(): List<Candle> =
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

private fun fixedLoopClock(): Clock = Clock.fixed(Instant.parse("2026-06-30T01:00:00Z"), ZoneOffset.UTC)
