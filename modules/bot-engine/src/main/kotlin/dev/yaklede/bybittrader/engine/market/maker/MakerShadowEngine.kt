package dev.yaklede.bybittrader.engine.market.maker

import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketCaptureBatch
import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketCaptureBatchObserver
import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketDataQuality
import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketEventKind
import dev.yaklede.bybittrader.engine.market.capture.OrderBookDepthSnapshot
import dev.yaklede.bybittrader.engine.market.capture.TakerTradeEvent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.math.MathContext
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashSet

const val MAKER_SHADOW_ENGINE_VERSION: String = "maker-shadow-v1"

private val DECIMAL_CONTEXT = MathContext.DECIMAL64
private val BPS_DIVISOR = BigDecimal("10000")
private val MARK_OUT_HORIZONS = listOf(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30))

data class MakerShadowConfig(
    val sessionId: String,
    val symbol: Symbol,
    val initialEquity: BigDecimal,
    val orderQuantity: BigDecimal,
    val maxNotional: BigDecimal,
    val queueMultiplier: BigDecimal,
    val queueBufferQuantity: BigDecimal,
    val minSpreadBps: BigDecimal,
    val makerFeeRate: BigDecimal,
    val takerFeeRate: BigDecimal,
    val takerExitSlippageBps: BigDecimal,
    val maxQuoteAge: Duration,
    val maxHoldingDuration: Duration,
    val maxEventDelay: Duration,
    val tradeIdCacheSize: Int = 100_000,
) {
    init {
        require(sessionId.isNotBlank()) { "Maker shadow session ID must not be blank." }
        require(initialEquity > BigDecimal.ZERO) { "Maker shadow initial equity must be positive." }
        require(orderQuantity > BigDecimal.ZERO) { "Maker shadow order quantity must be positive." }
        require(maxNotional > BigDecimal.ZERO) { "Maker shadow maximum notional must be positive." }
        require(queueMultiplier >= BigDecimal.ONE) { "Maker shadow queue multiplier must be at least one." }
        require(queueBufferQuantity >= BigDecimal.ZERO) { "Maker shadow queue buffer must not be negative." }
        require(minSpreadBps >= BigDecimal.ZERO) { "Maker shadow minimum spread must not be negative." }
        require(makerFeeRate > BigDecimal("-0.01") && makerFeeRate < BigDecimal("0.01")) {
            "Maker shadow maker fee rate must be between -1% and 1%."
        }
        require(takerFeeRate >= BigDecimal.ZERO && takerFeeRate < BigDecimal("0.01")) {
            "Maker shadow taker fee rate must be between zero and 1%."
        }
        require(takerExitSlippageBps >= BigDecimal.ZERO) { "Maker shadow exit slippage must not be negative." }
        require(maxQuoteAge.isPositive()) { "Maker shadow maximum quote age must be positive." }
        require(maxHoldingDuration.isPositive()) { "Maker shadow maximum holding duration must be positive." }
        require(maxEventDelay.isPositive()) { "Maker shadow maximum event delay must be positive." }
        require(tradeIdCacheSize > 0) { "Maker shadow trade ID cache size must be positive." }
    }

    val fingerprint: String =
        listOf(
            MAKER_SHADOW_ENGINE_VERSION,
            symbol.value,
            initialEquity.canonical(),
            orderQuantity.canonical(),
            maxNotional.canonical(),
            queueMultiplier.canonical(),
            queueBufferQuantity.canonical(),
            minSpreadBps.canonical(),
            makerFeeRate.canonical(),
            takerFeeRate.canonical(),
            takerExitSlippageBps.canonical(),
            maxQuoteAge.toMillis(),
            maxHoldingDuration.toMillis(),
            maxEventDelay.toMillis(),
            tradeIdCacheSize,
        ).joinToString("|")
            .sha256()
}

enum class MakerShadowState {
    WAITING_FOR_BOOK,
    QUOTING,
    INVENTORY_OPEN,
    HALTED_DATA_QUALITY,
}

enum class MakerShadowLedgerEventType {
    SHADOW_STARTED,
    BOOK_ACCEPTED,
    BOOK_REJECTED,
    QUOTE_OPENED,
    QUOTE_CANCELLED,
    QUOTE_INVALIDATED,
    QUEUE_PROGRESS,
    QUEUE_DEPLETED,
    PARTIAL_FILL,
    FILL,
    POSITION_OPENED,
    POSITION_CLOSED,
    FORCED_TAKER_EXIT,
    MARK_OUT_1S,
    MARK_OUT_5S,
    MARK_OUT_30S,
    SHADOW_HALTED,
}

data class MakerShadowLedgerEvent(
    val eventId: String,
    val sessionId: String,
    val engineVersion: String,
    val configFingerprint: String,
    val type: MakerShadowLedgerEventType,
    val symbol: Symbol,
    val eventAt: Instant,
    val receivedAt: Instant,
    val bookEpoch: Long? = null,
    val crossSequence: Long? = null,
    val quoteId: String? = null,
    val tradeId: String? = null,
    val side: Side? = null,
    val price: BigDecimal? = null,
    val quantity: BigDecimal? = null,
    val fee: BigDecimal? = null,
    val queueAhead: BigDecimal? = null,
    val inventoryQuantity: BigDecimal = BigDecimal.ZERO,
    val cash: BigDecimal,
    val equity: BigDecimal,
    val markOutBps: BigDecimal? = null,
    val reason: String? = null,
)

data class MakerShadowQuoteSnapshot(
    val quoteId: String,
    val side: Side,
    val price: BigDecimal,
    val originalQuantity: BigDecimal,
    val remainingQuantity: BigDecimal,
    val queueAhead: BigDecimal,
    val bookEpoch: Long,
    val openedSequence: Long,
    val openedAt: Instant,
)

data class MakerShadowSnapshot(
    val state: MakerShadowState,
    val configFingerprint: String,
    val cash: BigDecimal,
    val equity: BigDecimal,
    val inventoryQuantity: BigDecimal,
    val currentMidpoint: BigDecimal?,
    val totalMakerFees: BigDecimal,
    val totalTakerFees: BigDecimal,
    val activeQuotes: List<MakerShadowQuoteSnapshot>,
    val processedTradeIds: Int,
)

fun interface MakerShadowLedger {
    suspend fun append(events: List<MakerShadowLedgerEvent>)
}

class MakerShadowEngine(
    private val config: MakerShadowConfig,
    private val ledger: MakerShadowLedger,
) : ForwardMarketCaptureBatchObserver {
    private val mutex = Mutex()
    private val activeQuotes = linkedMapOf<Side, MutableShadowQuote>()
    private val processedTradeIds = LinkedHashSet<String>()
    private val pendingMarkOuts = mutableListOf<PendingMarkOut>()
    private var state = MakerShadowState.WAITING_FOR_BOOK
    private var started = false
    private var bookReady = false
    private var currentBook: ShadowBook? = null
    private var lastValuationMidpoint: BigDecimal? = null
    private var lastBookEpoch: Long? = null
    private var lastBookSequence: Long? = null
    private var cash = config.initialEquity
    private var inventoryQuantity = BigDecimal.ZERO
    private var positionOpenedAt: Instant? = null
    private var totalMakerFees = BigDecimal.ZERO
    private var totalTakerFees = BigDecimal.ZERO
    private var nextQuoteNumber = 0L
    private var nextFillNumber = 0L
    private var nextEventNumber = 0L

    override suspend fun onBatch(batch: ForwardMarketCaptureBatch) {
        mutex.withLock {
            require(batch.rawEvent.symbol == config.symbol) {
                "Maker shadow batch symbol ${batch.rawEvent.symbol.value} does not match ${config.symbol.value}."
            }
            val events = mutableListOf<MakerShadowLedgerEvent>()
            if (!started) {
                started = true
                emit(
                    events = events,
                    type = MakerShadowLedgerEventType.SHADOW_STARTED,
                    eventAt = batch.rawEvent.exchangeTimestamp ?: batch.rawEvent.receivedAt,
                    receivedAt = batch.rawEvent.receivedAt,
                    reason = "session_started",
                )
            }

            if (batch.rawEvent.eventKind == ForwardMarketEventKind.ORDER_BOOK &&
                batch.rawEvent.quality.requiresReconnect
            ) {
                haltForDataQuality(
                    events = events,
                    eventAt = batch.rawEvent.exchangeTimestamp ?: batch.rawEvent.receivedAt,
                    receivedAt = batch.rawEvent.receivedAt,
                    reason =
                        batch.rawEvent.quality.name
                            .lowercase(),
                    bookEpoch = batch.rawEvent.bookEpoch,
                    sequence = batch.rawEvent.sequenceEnd,
                )
            } else {
                batch.normalizedEvents.forEach { event ->
                    when (event) {
                        is OrderBookDepthSnapshot -> processBook(event, events)
                        is TakerTradeEvent -> processTrade(event, events)
                        else -> Unit
                    }
                }
            }

            if (events.isNotEmpty()) {
                try {
                    ledger.append(events)
                } catch (error: Throwable) {
                    activeQuotes.clear()
                    bookReady = false
                    state = MakerShadowState.HALTED_DATA_QUALITY
                    throw error
                }
            }
        }
    }

    suspend fun snapshot(): MakerShadowSnapshot =
        mutex.withLock {
            MakerShadowSnapshot(
                state = state,
                configFingerprint = config.fingerprint,
                cash = cash,
                equity = equity(),
                inventoryQuantity = inventoryQuantity,
                currentMidpoint = currentBook?.midpoint,
                totalMakerFees = totalMakerFees,
                totalTakerFees = totalTakerFees,
                activeQuotes = activeQuotes.values.map(MutableShadowQuote::snapshot),
                processedTradeIds = processedTradeIds.size,
            )
        }

    private fun processBook(
        event: OrderBookDepthSnapshot,
        events: MutableList<MakerShadowLedgerEvent>,
    ) {
        val book = event.toShadowBookOrNull()
        if (book == null) {
            rejectBook(event, events, "missing_best_level_evidence")
            return
        }
        if (eventDelay(event.capturedAt, book.receivedAt) > config.maxEventDelay) {
            rejectBook(event, events, "stale_book_event")
            return
        }

        if (event.quality == ForwardMarketDataQuality.SNAPSHOT_RESET) {
            cancelAllQuotes(events, event.capturedAt, book.receivedAt, "snapshot_reset", invalidated = true)
            bookReady = true
            lastBookEpoch = book.epoch
            lastBookSequence = book.sequence
            state = MakerShadowState.WAITING_FOR_BOOK
        } else {
            if (!bookReady || state == MakerShadowState.HALTED_DATA_QUALITY) return
            val sequenceIsInvalid =
                lastBookEpoch != book.epoch ||
                    lastBookSequence?.let { previous -> book.sequence <= previous } == true
            if (sequenceIsInvalid) {
                haltForDataQuality(
                    events = events,
                    eventAt = event.capturedAt,
                    receivedAt = book.receivedAt,
                    reason = "book_epoch_or_sequence_mismatch",
                    bookEpoch = book.epoch,
                    sequence = book.sequence,
                )
                return
            }
            lastBookSequence = book.sequence
        }

        currentBook = book
        lastValuationMidpoint = book.midpoint
        settleMarkOuts(book, events)
        emit(
            events = events,
            type = MakerShadowLedgerEventType.BOOK_ACCEPTED,
            eventAt = event.capturedAt,
            receivedAt = book.receivedAt,
            bookEpoch = book.epoch,
            sequence = book.sequence,
        )

        if (positionOpenedAt?.let { openedAt -> elapsed(openedAt, book.receivedAt) >= config.maxHoldingDuration } == true) {
            forceTakerExit(book, events, "max_holding_duration")
            return
        }

        reconcileQuotes(book, events)
        if (activeQuotes.isEmpty() &&
            (inventoryQuantity != BigDecimal.ZERO || book.spreadBps >= config.minSpreadBps)
        ) {
            openRequiredQuotes(book, events)
        }
        updateState()
    }

    private fun processTrade(
        event: TakerTradeEvent,
        events: MutableList<MakerShadowLedgerEvent>,
    ) {
        val tradeId = event.tradeId ?: return
        val sequence = event.crossSequence ?: return
        val receivedAt = event.receivedAt ?: return
        if (!rememberTradeId(tradeId)) return
        if (!bookReady || state == MakerShadowState.HALTED_DATA_QUALITY) return
        if (eventDelay(event.capturedAt, receivedAt) > config.maxEventDelay) {
            cancelAllQuotes(events, event.capturedAt, receivedAt, "stale_trade_event", invalidated = true)
            state = if (inventoryQuantity == BigDecimal.ZERO) MakerShadowState.WAITING_FOR_BOOK else MakerShadowState.INVENTORY_OPEN
            return
        }

        val quoteSide = if (event.takerSide == Side.SELL) Side.BUY else Side.SELL
        val quote = activeQuotes[quoteSide] ?: return
        if (sequence <= quote.openedSequence || event.price.compareTo(quote.price) != 0) return

        var availableQuantity = event.quantity
        if (quote.queueAhead > BigDecimal.ZERO) {
            val consumed = minOf(quote.queueAhead, availableQuantity)
            quote.queueAhead -= consumed
            availableQuantity -= consumed
            emit(
                events = events,
                type = MakerShadowLedgerEventType.QUEUE_PROGRESS,
                eventAt = event.capturedAt,
                receivedAt = receivedAt,
                bookEpoch = quote.bookEpoch,
                sequence = sequence,
                quote = quote,
                tradeId = tradeId,
                quantity = consumed,
                queueAhead = quote.queueAhead,
            )
            if (quote.queueAhead == BigDecimal.ZERO) {
                emit(
                    events = events,
                    type = MakerShadowLedgerEventType.QUEUE_DEPLETED,
                    eventAt = event.capturedAt,
                    receivedAt = receivedAt,
                    bookEpoch = quote.bookEpoch,
                    sequence = sequence,
                    quote = quote,
                    tradeId = tradeId,
                    queueAhead = BigDecimal.ZERO,
                )
            }
        }
        if (availableQuantity <= BigDecimal.ZERO) return

        val fillQuantity = minOf(availableQuantity, quote.remainingQuantity)
        if (fillQuantity <= BigDecimal.ZERO) return
        applyMakerFill(
            quote = quote,
            tradeId = tradeId,
            sequence = sequence,
            fillQuantity = fillQuantity,
            eventAt = event.capturedAt,
            receivedAt = receivedAt,
            events = events,
        )
    }

    private fun applyMakerFill(
        quote: MutableShadowQuote,
        tradeId: String,
        sequence: Long,
        fillQuantity: BigDecimal,
        eventAt: Instant,
        receivedAt: Instant,
        events: MutableList<MakerShadowLedgerEvent>,
    ) {
        val wasRemaining = quote.remainingQuantity
        quote.remainingQuantity -= fillQuantity
        val fee = quote.price.multiply(fillQuantity).multiply(config.makerFeeRate)
        val previousInventory = inventoryQuantity
        applyCashAndInventory(quote.side, quote.price, fillQuantity, fee)
        totalMakerFees += fee
        nextFillNumber += 1
        pendingMarkOuts +=
            PendingMarkOut(
                fillId = "${config.sessionId}-f-$nextFillNumber",
                side = quote.side,
                price = quote.price,
                quantity = fillQuantity,
                filledAt = eventAt,
                remainingHorizons = MARK_OUT_HORIZONS.toMutableSet(),
            )

        emit(
            events = events,
            type =
                if (fillQuantity < wasRemaining) {
                    MakerShadowLedgerEventType.PARTIAL_FILL
                } else {
                    MakerShadowLedgerEventType.FILL
                },
            eventAt = eventAt,
            receivedAt = receivedAt,
            bookEpoch = quote.bookEpoch,
            sequence = sequence,
            quote = quote,
            tradeId = tradeId,
            price = quote.price,
            quantity = fillQuantity,
            fee = fee,
        )

        if (previousInventory == BigDecimal.ZERO && inventoryQuantity != BigDecimal.ZERO) {
            positionOpenedAt = receivedAt
            emit(
                events = events,
                type = MakerShadowLedgerEventType.POSITION_OPENED,
                eventAt = eventAt,
                receivedAt = receivedAt,
                bookEpoch = quote.bookEpoch,
                sequence = sequence,
                quote = quote,
                tradeId = tradeId,
                price = quote.price,
                quantity = fillQuantity,
                fee = fee,
            )
        } else if (previousInventory != BigDecimal.ZERO && inventoryQuantity == BigDecimal.ZERO) {
            positionOpenedAt = null
            emit(
                events = events,
                type = MakerShadowLedgerEventType.POSITION_CLOSED,
                eventAt = eventAt,
                receivedAt = receivedAt,
                bookEpoch = quote.bookEpoch,
                sequence = sequence,
                quote = quote,
                tradeId = tradeId,
                price = quote.price,
                quantity = fillQuantity,
                fee = fee,
                reason = "maker_fill",
            )
        }

        if (quote.remainingQuantity == BigDecimal.ZERO) activeQuotes.remove(quote.side)
        cancelAllQuotes(events, eventAt, receivedAt, "inventory_changed", invalidated = false)
        updateState()
    }

    private fun reconcileQuotes(
        book: ShadowBook,
        events: MutableList<MakerShadowLedgerEvent>,
    ) {
        activeQuotes.values.toList().forEach { quote ->
            val expectedPrice = if (quote.side == Side.BUY) book.bestBidPrice else book.bestAskPrice
            val expired = elapsed(quote.openedAt, book.receivedAt) >= config.maxQuoteAge
            if (quote.bookEpoch != book.epoch || quote.price.compareTo(expectedPrice) != 0 || expired) {
                cancelQuote(
                    quote = quote,
                    events = events,
                    eventAt = book.eventAt,
                    receivedAt = book.receivedAt,
                    reason = if (expired) "quote_expired" else "best_price_changed",
                    invalidated = quote.bookEpoch != book.epoch,
                )
            }
        }
    }

    private fun openRequiredQuotes(
        book: ShadowBook,
        events: MutableList<MakerShadowLedgerEvent>,
    ) {
        when {
            inventoryQuantity > BigDecimal.ZERO -> openQuote(Side.SELL, inventoryQuantity, book, events)
            inventoryQuantity < BigDecimal.ZERO -> openQuote(Side.BUY, inventoryQuantity.abs(), book, events)
            else -> {
                if (book.bestBidPrice.multiply(config.orderQuantity) > config.maxNotional ||
                    book.bestAskPrice.multiply(config.orderQuantity) > config.maxNotional
                ) {
                    return
                }
                openQuote(Side.BUY, config.orderQuantity, book, events)
                openQuote(Side.SELL, config.orderQuantity, book, events)
            }
        }
    }

    private fun openQuote(
        side: Side,
        quantity: BigDecimal,
        book: ShadowBook,
        events: MutableList<MakerShadowLedgerEvent>,
    ) {
        val price = if (side == Side.BUY) book.bestBidPrice else book.bestAskPrice
        val displayedQuantity = if (side == Side.BUY) book.bestBidQuantity else book.bestAskQuantity
        if (price.multiply(quantity) > config.maxNotional && inventoryQuantity == BigDecimal.ZERO) return

        nextQuoteNumber += 1
        val quote =
            MutableShadowQuote(
                quoteId = "${config.sessionId}-q-$nextQuoteNumber",
                side = side,
                price = price,
                originalQuantity = quantity,
                remainingQuantity = quantity,
                queueAhead = displayedQuantity.multiply(config.queueMultiplier) + config.queueBufferQuantity,
                bookEpoch = book.epoch,
                openedSequence = book.sequence,
                openedAt = book.receivedAt,
            )
        activeQuotes[side] = quote
        emit(
            events = events,
            type = MakerShadowLedgerEventType.QUOTE_OPENED,
            eventAt = book.eventAt,
            receivedAt = book.receivedAt,
            bookEpoch = book.epoch,
            sequence = book.sequence,
            quote = quote,
            price = price,
            quantity = quantity,
            queueAhead = quote.queueAhead,
        )
    }

    private fun forceTakerExit(
        book: ShadowBook,
        events: MutableList<MakerShadowLedgerEvent>,
        reason: String,
    ) {
        if (inventoryQuantity == BigDecimal.ZERO) return
        cancelAllQuotes(events, book.eventAt, book.receivedAt, reason, invalidated = false)
        val side = if (inventoryQuantity > BigDecimal.ZERO) Side.SELL else Side.BUY
        val quantity = inventoryQuantity.abs()
        val slippageRate = config.takerExitSlippageBps.divide(BPS_DIVISOR, DECIMAL_CONTEXT)
        val price =
            if (side == Side.SELL) {
                book.bestBidPrice.multiply(BigDecimal.ONE - slippageRate)
            } else {
                book.bestAskPrice.multiply(BigDecimal.ONE + slippageRate)
            }
        val fee = price.multiply(quantity).multiply(config.takerFeeRate)
        applyCashAndInventory(side, price, quantity, fee)
        totalTakerFees += fee
        positionOpenedAt = null
        emit(
            events = events,
            type = MakerShadowLedgerEventType.FORCED_TAKER_EXIT,
            eventAt = book.eventAt,
            receivedAt = book.receivedAt,
            bookEpoch = book.epoch,
            sequence = book.sequence,
            side = side,
            price = price,
            quantity = quantity,
            fee = fee,
            reason = reason,
        )
        emit(
            events = events,
            type = MakerShadowLedgerEventType.POSITION_CLOSED,
            eventAt = book.eventAt,
            receivedAt = book.receivedAt,
            bookEpoch = book.epoch,
            sequence = book.sequence,
            side = side,
            price = price,
            quantity = quantity,
            fee = fee,
            reason = reason,
        )
        state = MakerShadowState.WAITING_FOR_BOOK
    }

    private fun settleMarkOuts(
        book: ShadowBook,
        events: MutableList<MakerShadowLedgerEvent>,
    ) {
        pendingMarkOuts.toList().forEach { pending ->
            pending.remainingHorizons
                .filter { horizon -> !book.eventAt.isBefore(pending.filledAt.plus(horizon)) }
                .sorted()
                .forEach { horizon ->
                    val direction = if (pending.side == Side.BUY) BigDecimal.ONE else BigDecimal.ONE.negate()
                    val markOutBps =
                        book.midpoint
                            .subtract(pending.price)
                            .divide(pending.price, DECIMAL_CONTEXT)
                            .multiply(BPS_DIVISOR)
                            .multiply(direction)
                    emit(
                        events = events,
                        type = horizon.toMarkOutType(),
                        eventAt = book.eventAt,
                        receivedAt = book.receivedAt,
                        bookEpoch = book.epoch,
                        sequence = book.sequence,
                        side = pending.side,
                        price = pending.price,
                        quantity = pending.quantity,
                        markOutBps = markOutBps,
                        reason = pending.fillId,
                    )
                    pending.remainingHorizons.remove(horizon)
                }
            if (pending.remainingHorizons.isEmpty()) pendingMarkOuts.remove(pending)
        }
    }

    private fun rejectBook(
        event: OrderBookDepthSnapshot,
        events: MutableList<MakerShadowLedgerEvent>,
        reason: String,
    ) {
        val receivedAt = event.receivedAt ?: event.capturedAt
        cancelAllQuotes(events, event.capturedAt, receivedAt, reason, invalidated = true)
        bookReady = false
        currentBook = null
        state = MakerShadowState.WAITING_FOR_BOOK
        emit(
            events = events,
            type = MakerShadowLedgerEventType.BOOK_REJECTED,
            eventAt = event.capturedAt,
            receivedAt = receivedAt,
            bookEpoch = event.bookEpoch,
            sequence = event.crossSequence,
            reason = reason,
        )
    }

    private fun haltForDataQuality(
        events: MutableList<MakerShadowLedgerEvent>,
        eventAt: Instant,
        receivedAt: Instant,
        reason: String,
        bookEpoch: Long?,
        sequence: Long?,
    ) {
        cancelAllQuotes(events, eventAt, receivedAt, reason, invalidated = true)
        bookReady = false
        currentBook = null
        lastBookEpoch = null
        lastBookSequence = null
        state = MakerShadowState.HALTED_DATA_QUALITY
        emit(
            events = events,
            type = MakerShadowLedgerEventType.BOOK_REJECTED,
            eventAt = eventAt,
            receivedAt = receivedAt,
            bookEpoch = bookEpoch,
            sequence = sequence,
            reason = reason,
        )
        emit(
            events = events,
            type = MakerShadowLedgerEventType.SHADOW_HALTED,
            eventAt = eventAt,
            receivedAt = receivedAt,
            bookEpoch = bookEpoch,
            sequence = sequence,
            reason = reason,
        )
    }

    private fun cancelAllQuotes(
        events: MutableList<MakerShadowLedgerEvent>,
        eventAt: Instant,
        receivedAt: Instant,
        reason: String,
        invalidated: Boolean,
    ) {
        activeQuotes.values.toList().forEach { quote ->
            cancelQuote(quote, events, eventAt, receivedAt, reason, invalidated)
        }
    }

    private fun cancelQuote(
        quote: MutableShadowQuote,
        events: MutableList<MakerShadowLedgerEvent>,
        eventAt: Instant,
        receivedAt: Instant,
        reason: String,
        invalidated: Boolean,
    ) {
        if (activeQuotes.remove(quote.side) == null) return
        emit(
            events = events,
            type =
                if (invalidated) {
                    MakerShadowLedgerEventType.QUOTE_INVALIDATED
                } else {
                    MakerShadowLedgerEventType.QUOTE_CANCELLED
                },
            eventAt = eventAt,
            receivedAt = receivedAt,
            bookEpoch = quote.bookEpoch,
            sequence = quote.openedSequence,
            quote = quote,
            price = quote.price,
            quantity = quote.remainingQuantity,
            queueAhead = quote.queueAhead,
            reason = reason,
        )
    }

    private fun applyCashAndInventory(
        side: Side,
        price: BigDecimal,
        quantity: BigDecimal,
        fee: BigDecimal,
    ) {
        val notional = price.multiply(quantity)
        if (side == Side.BUY) {
            cash = cash - notional - fee
            inventoryQuantity += quantity
        } else {
            cash = cash + notional - fee
            inventoryQuantity -= quantity
        }
        if (inventoryQuantity.abs() < BigDecimal("0.0000000000001")) inventoryQuantity = BigDecimal.ZERO
        require(inventoryQuantity.abs() <= config.orderQuantity) {
            "Maker shadow inventory exceeded the configured order quantity."
        }
    }

    private fun rememberTradeId(tradeId: String): Boolean {
        if (!processedTradeIds.add(tradeId)) return false
        while (processedTradeIds.size > config.tradeIdCacheSize) {
            val oldest = processedTradeIds.first()
            processedTradeIds.remove(oldest)
        }
        return true
    }

    private fun updateState() {
        state =
            when {
                !bookReady -> MakerShadowState.WAITING_FOR_BOOK
                inventoryQuantity != BigDecimal.ZERO -> MakerShadowState.INVENTORY_OPEN
                activeQuotes.isNotEmpty() -> MakerShadowState.QUOTING
                else -> MakerShadowState.WAITING_FOR_BOOK
            }
    }

    private fun equity(): BigDecimal = cash + inventoryQuantity.multiply(lastValuationMidpoint ?: BigDecimal.ZERO)

    private fun emit(
        events: MutableList<MakerShadowLedgerEvent>,
        type: MakerShadowLedgerEventType,
        eventAt: Instant,
        receivedAt: Instant,
        bookEpoch: Long? = null,
        sequence: Long? = null,
        quote: MutableShadowQuote? = null,
        tradeId: String? = null,
        side: Side? = quote?.side,
        price: BigDecimal? = null,
        quantity: BigDecimal? = null,
        fee: BigDecimal? = null,
        queueAhead: BigDecimal? = null,
        markOutBps: BigDecimal? = null,
        reason: String? = null,
    ) {
        nextEventNumber += 1
        events +=
            MakerShadowLedgerEvent(
                eventId = "${config.sessionId}-e-$nextEventNumber",
                sessionId = config.sessionId,
                engineVersion = MAKER_SHADOW_ENGINE_VERSION,
                configFingerprint = config.fingerprint,
                type = type,
                symbol = config.symbol,
                eventAt = eventAt,
                receivedAt = receivedAt,
                bookEpoch = bookEpoch,
                crossSequence = sequence,
                quoteId = quote?.quoteId,
                tradeId = tradeId,
                side = side,
                price = price,
                quantity = quantity,
                fee = fee,
                queueAhead = queueAhead,
                inventoryQuantity = inventoryQuantity,
                cash = cash,
                equity = equity(),
                markOutBps = markOutBps,
                reason = reason,
            )
    }
}

private data class ShadowBook(
    val eventAt: Instant,
    val receivedAt: Instant,
    val bestBidPrice: BigDecimal,
    val bestBidQuantity: BigDecimal,
    val bestAskPrice: BigDecimal,
    val bestAskQuantity: BigDecimal,
    val spreadBps: BigDecimal,
    val epoch: Long,
    val sequence: Long,
) {
    val midpoint: BigDecimal = (bestBidPrice + bestAskPrice).divide(BigDecimal("2"), DECIMAL_CONTEXT)
}

private data class MutableShadowQuote(
    val quoteId: String,
    val side: Side,
    val price: BigDecimal,
    val originalQuantity: BigDecimal,
    var remainingQuantity: BigDecimal,
    var queueAhead: BigDecimal,
    val bookEpoch: Long,
    val openedSequence: Long,
    val openedAt: Instant,
) {
    fun snapshot(): MakerShadowQuoteSnapshot =
        MakerShadowQuoteSnapshot(
            quoteId = quoteId,
            side = side,
            price = price,
            originalQuantity = originalQuantity,
            remainingQuantity = remainingQuantity,
            queueAhead = queueAhead,
            bookEpoch = bookEpoch,
            openedSequence = openedSequence,
            openedAt = openedAt,
        )
}

private data class PendingMarkOut(
    val fillId: String,
    val side: Side,
    val price: BigDecimal,
    val quantity: BigDecimal,
    val filledAt: Instant,
    val remainingHorizons: MutableSet<Duration>,
)

private fun OrderBookDepthSnapshot.toShadowBookOrNull(): ShadowBook? {
    val bidPrice = bestBidPrice ?: return null
    val bidQuantity = bestBidQuantity ?: return null
    val askPrice = bestAskPrice ?: return null
    val askQuantity = bestAskQuantity ?: return null
    val epoch = bookEpoch ?: return null
    val sequence = crossSequence ?: return null
    val received = receivedAt ?: return null
    return ShadowBook(
        eventAt = capturedAt,
        receivedAt = received,
        bestBidPrice = bidPrice,
        bestBidQuantity = bidQuantity,
        bestAskPrice = askPrice,
        bestAskQuantity = askQuantity,
        spreadBps = spreadBps,
        epoch = epoch,
        sequence = sequence,
    )
}

private fun Duration.isPositive(): Boolean = !isZero && !isNegative

private fun eventDelay(
    eventAt: Instant,
    receivedAt: Instant,
): Duration = Duration.between(eventAt, receivedAt).let { if (it.isNegative) Duration.ZERO else it }

private fun elapsed(
    start: Instant,
    end: Instant,
): Duration = Duration.between(start, end).let { if (it.isNegative) Duration.ZERO else it }

private fun Duration.toMarkOutType(): MakerShadowLedgerEventType =
    when (seconds) {
        1L -> MakerShadowLedgerEventType.MARK_OUT_1S
        5L -> MakerShadowLedgerEventType.MARK_OUT_5S
        30L -> MakerShadowLedgerEventType.MARK_OUT_30S
        else -> error("Unsupported maker shadow mark-out horizon: $this")
    }

private fun BigDecimal.canonical(): String = stripTrailingZeros().toPlainString()

private fun String.sha256(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
