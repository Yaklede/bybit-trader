package dev.yaklede.bybittrader.engine.market.capture

import dev.yaklede.bybittrader.domain.Symbol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.MathContext
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val ONE_MINUTE: Duration = Duration.ofMinutes(1)
private val DECIMAL_CONTEXT: MathContext = MathContext.DECIMAL64

sealed interface ForwardMarketCaptureEvent {
    val symbol: Symbol
    val capturedAt: Instant
}

data class OrderBookDepthSnapshot(
    override val symbol: Symbol,
    override val capturedAt: Instant,
    val bidNotional: BigDecimal,
    val askNotional: BigDecimal,
    val spreadBps: BigDecimal,
) : ForwardMarketCaptureEvent {
    init {
        require(bidNotional >= BigDecimal.ZERO) { "Order book bid notional must not be negative." }
        require(askNotional >= BigDecimal.ZERO) { "Order book ask notional must not be negative." }
        require(spreadBps >= BigDecimal.ZERO) { "Order book spread must not be negative." }
    }

    val imbalance: BigDecimal
        get() {
            val totalNotional = bidNotional + askNotional
            return if (totalNotional == BigDecimal.ZERO) {
                BigDecimal.ZERO
            } else {
                (bidNotional - askNotional).divide(totalNotional, DECIMAL_CONTEXT)
            }
        }
}

enum class LiquidatedPositionSide {
    LONG,
    SHORT,
}

data class LiquidationEvent(
    override val symbol: Symbol,
    override val capturedAt: Instant,
    val liquidatedSide: LiquidatedPositionSide,
    val notional: BigDecimal,
) : ForwardMarketCaptureEvent {
    init {
        require(notional > BigDecimal.ZERO) { "Liquidation notional must be positive." }
    }
}

data class OrderBookImbalanceBar(
    val symbol: Symbol,
    val openedAt: Instant,
    val sampleCount: Int,
    val meanBidNotional: BigDecimal,
    val meanAskNotional: BigDecimal,
    val meanImbalance: BigDecimal,
    val meanSpreadBps: BigDecimal,
    val maxSpreadBps: BigDecimal,
) {
    init {
        require(sampleCount > 0) { "Order book sample count must be positive." }
        require(meanBidNotional >= BigDecimal.ZERO) { "Mean bid notional must not be negative." }
        require(meanAskNotional >= BigDecimal.ZERO) { "Mean ask notional must not be negative." }
        require(meanImbalance >= BigDecimal.ONE.negate() && meanImbalance <= BigDecimal.ONE) {
            "Mean order book imbalance must be between minus one and one."
        }
        require(meanSpreadBps >= BigDecimal.ZERO) { "Mean spread must not be negative." }
        require(maxSpreadBps >= meanSpreadBps) { "Maximum spread must be at least mean spread." }
    }

    val availableAt: Instant
        get() = openedAt.plus(ONE_MINUTE)
}

data class LiquidationFlowBar(
    val symbol: Symbol,
    val openedAt: Instant,
    val longLiquidationNotional: BigDecimal,
    val shortLiquidationNotional: BigDecimal,
    val longLiquidationCount: Int,
    val shortLiquidationCount: Int,
) {
    init {
        require(longLiquidationNotional >= BigDecimal.ZERO) { "Long liquidation notional must not be negative." }
        require(shortLiquidationNotional >= BigDecimal.ZERO) { "Short liquidation notional must not be negative." }
        require(longLiquidationCount >= 0) { "Long liquidation count must not be negative." }
        require(shortLiquidationCount >= 0) { "Short liquidation count must not be negative." }
    }

    val availableAt: Instant
        get() = openedAt.plus(ONE_MINUTE)
}

interface ForwardMarketCaptureStore {
    suspend fun upsertOrderBookImbalanceBars(bars: List<OrderBookImbalanceBar>)

    suspend fun orderBookImbalanceBarsBetween(
        symbol: Symbol,
        startAt: Instant,
        endAt: Instant,
        limit: Int,
    ): List<OrderBookImbalanceBar>

    suspend fun upsertLiquidationFlowBars(bars: List<LiquidationFlowBar>)

    suspend fun liquidationFlowBarsBetween(
        symbol: Symbol,
        startAt: Instant,
        endAt: Instant,
        limit: Int,
    ): List<LiquidationFlowBar>
}

fun interface ForwardMarketCaptureFeed {
    suspend fun collect(
        symbol: Symbol,
        onEvent: suspend (ForwardMarketCaptureEvent) -> Unit,
    )
}

class ForwardMarketCaptureService(
    private val store: ForwardMarketCaptureStore,
) {
    private val mutex = Mutex()
    private val orderBookAccumulators = mutableMapOf<CaptureBucketKey, OrderBookAccumulator>()
    private val liquidationAccumulators = mutableMapOf<CaptureBucketKey, LiquidationAccumulator>()

    suspend fun record(event: ForwardMarketCaptureEvent) {
        val key = CaptureBucketKey(symbol = event.symbol, openedAt = event.capturedAt.minuteStart())
        mutex.withLock {
            when (event) {
                is OrderBookDepthSnapshot ->
                    orderBookAccumulators.getOrPut(key, ::OrderBookAccumulator).add(event)
                is LiquidationEvent ->
                    liquidationAccumulators.getOrPut(key, ::LiquidationAccumulator).add(event)
            }
        }
    }

    suspend fun flushClosedBars(now: Instant): ForwardMarketCaptureFlushResult {
        val closingBefore = now.minuteStart()
        val pending =
            mutex.withLock {
                PendingCaptureBars(
                    orderBook = orderBookAccumulators.filterKeys { key -> key.openedAt.isBefore(closingBefore) },
                    liquidation = liquidationAccumulators.filterKeys { key -> key.openedAt.isBefore(closingBefore) },
                )
            }
        if (pending.isEmpty()) return ForwardMarketCaptureFlushResult.EMPTY

        val orderBookBars = pending.orderBook.map { (key, accumulator) -> accumulator.toBar(key) }
        val liquidationBars = pending.liquidation.map { (key, accumulator) -> accumulator.toBar(key) }
        if (orderBookBars.isNotEmpty()) store.upsertOrderBookImbalanceBars(orderBookBars)
        if (liquidationBars.isNotEmpty()) store.upsertLiquidationFlowBars(liquidationBars)

        mutex.withLock {
            pending.orderBook.forEach { (key, accumulator) ->
                if (orderBookAccumulators[key] === accumulator) orderBookAccumulators.remove(key)
            }
            pending.liquidation.forEach { (key, accumulator) ->
                if (liquidationAccumulators[key] === accumulator) liquidationAccumulators.remove(key)
            }
        }
        return ForwardMarketCaptureFlushResult(
            orderBookBars = orderBookBars.size,
            liquidationBars = liquidationBars.size,
        )
    }
}

data class ForwardMarketCaptureFlushResult(
    val orderBookBars: Int,
    val liquidationBars: Int,
) {
    companion object {
        val EMPTY = ForwardMarketCaptureFlushResult(orderBookBars = 0, liquidationBars = 0)
    }
}

class ForwardMarketCaptureLoop(
    private val feed: ForwardMarketCaptureFeed,
    private val captureService: ForwardMarketCaptureService,
    private val config: ForwardMarketCaptureLoopConfig,
    private val clock: Clock = Clock.systemUTC(),
    private val retryDelay: suspend (Long) -> Unit = { millis -> kotlinx.coroutines.delay(millis) },
) {
    private val logger = LoggerFactory.getLogger(ForwardMarketCaptureLoop::class.java)

    fun start(scope: CoroutineScope): Job =
        scope.launch {
            coroutineScope {
                launch {
                    while (isActive) {
                        try {
                            feed.collect(config.symbol) { event -> captureService.record(event) }
                        } catch (error: Throwable) {
                            if (error is kotlin.coroutines.cancellation.CancellationException) throw error
                            logger.warn("forward market capture stream failed symbol={}", config.symbol.value, error)
                        }
                        retryDelay(config.reconnectDelay.toMillis())
                    }
                }
                launch {
                    while (isActive) {
                        retryDelay(config.flushInterval.toMillis())
                        try {
                            captureService.flushClosedBars(Instant.now(clock))
                        } catch (error: Throwable) {
                            if (error is kotlin.coroutines.cancellation.CancellationException) throw error
                            logger.warn("forward market capture flush failed symbol={}", config.symbol.value, error)
                        }
                    }
                }
            }
        }
}

data class ForwardMarketCaptureLoopConfig(
    val symbol: Symbol,
    val flushInterval: Duration = ONE_MINUTE,
    val reconnectDelay: Duration = Duration.ofSeconds(5),
) {
    init {
        require(!flushInterval.isNegative && !flushInterval.isZero) { "Capture flush interval must be positive." }
        require(!reconnectDelay.isNegative && !reconnectDelay.isZero) { "Capture reconnect delay must be positive." }
    }
}

private data class CaptureBucketKey(
    val symbol: Symbol,
    val openedAt: Instant,
)

private data class PendingCaptureBars(
    val orderBook: Map<CaptureBucketKey, OrderBookAccumulator>,
    val liquidation: Map<CaptureBucketKey, LiquidationAccumulator>,
) {
    fun isEmpty(): Boolean = orderBook.isEmpty() && liquidation.isEmpty()
}

private class OrderBookAccumulator {
    private var sampleCount = 0
    private var bidNotionalTotal = BigDecimal.ZERO
    private var askNotionalTotal = BigDecimal.ZERO
    private var imbalanceTotal = BigDecimal.ZERO
    private var spreadTotal = BigDecimal.ZERO
    private var maxSpread = BigDecimal.ZERO

    fun add(snapshot: OrderBookDepthSnapshot) {
        sampleCount += 1
        bidNotionalTotal += snapshot.bidNotional
        askNotionalTotal += snapshot.askNotional
        imbalanceTotal += snapshot.imbalance
        spreadTotal += snapshot.spreadBps
        maxSpread = maxOf(maxSpread, snapshot.spreadBps)
    }

    fun toBar(key: CaptureBucketKey): OrderBookImbalanceBar =
        OrderBookImbalanceBar(
            symbol = key.symbol,
            openedAt = key.openedAt,
            sampleCount = sampleCount,
            meanBidNotional = bidNotionalTotal.average(sampleCount),
            meanAskNotional = askNotionalTotal.average(sampleCount),
            meanImbalance = imbalanceTotal.average(sampleCount),
            meanSpreadBps = spreadTotal.average(sampleCount),
            maxSpreadBps = maxSpread,
        )
}

private class LiquidationAccumulator {
    private var longNotional = BigDecimal.ZERO
    private var shortNotional = BigDecimal.ZERO
    private var longCount = 0
    private var shortCount = 0

    fun add(event: LiquidationEvent) {
        when (event.liquidatedSide) {
            LiquidatedPositionSide.LONG -> {
                longNotional += event.notional
                longCount += 1
            }
            LiquidatedPositionSide.SHORT -> {
                shortNotional += event.notional
                shortCount += 1
            }
        }
    }

    fun toBar(key: CaptureBucketKey): LiquidationFlowBar =
        LiquidationFlowBar(
            symbol = key.symbol,
            openedAt = key.openedAt,
            longLiquidationNotional = longNotional,
            shortLiquidationNotional = shortNotional,
            longLiquidationCount = longCount,
            shortLiquidationCount = shortCount,
        )
}

private fun Instant.minuteStart(): Instant = Instant.ofEpochMilli((toEpochMilli() / ONE_MINUTE.toMillis()) * ONE_MINUTE.toMillis())

private fun BigDecimal.average(count: Int): BigDecimal = divide(BigDecimal(count), DECIMAL_CONTEXT)
