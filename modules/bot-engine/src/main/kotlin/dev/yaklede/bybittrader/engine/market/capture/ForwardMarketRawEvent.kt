package dev.yaklede.bybittrader.engine.market.capture

import dev.yaklede.bybittrader.domain.Symbol
import java.time.Instant

const val FORWARD_MARKET_RAW_SCHEMA_VERSION: Int = 1

enum class ForwardMarketEventKind {
    ORDER_BOOK,
    PUBLIC_TRADE,
    LIQUIDATION,
}

enum class ForwardMarketMessageType {
    SNAPSHOT,
    DELTA,
}

enum class ForwardMarketDataQuality(
    val gapDetected: Boolean,
    val requiresReconnect: Boolean,
) {
    VALID(gapDetected = false, requiresReconnect = false),
    SNAPSHOT_RESET(gapDetected = false, requiresReconnect = false),
    DELTA_BEFORE_SNAPSHOT(gapDetected = true, requiresReconnect = true),
    MISSING_UPDATE_ID(gapDetected = true, requiresReconnect = true),
    NON_MONOTONIC_UPDATE_ID(gapDetected = true, requiresReconnect = true),
    EMPTY_ORDER_BOOK(gapDetected = true, requiresReconnect = true),
    CROSSED_ORDER_BOOK(gapDetected = true, requiresReconnect = true),
}

data class ForwardMarketRawEvent(
    val localConnectionId: String,
    val topic: String,
    val symbol: Symbol,
    val eventKind: ForwardMarketEventKind,
    val messageType: ForwardMarketMessageType,
    val exchangeTimestamp: Instant?,
    val matchingEngineTimestamp: Instant?,
    val receivedAt: Instant,
    val sequenceStart: Long?,
    val sequenceEnd: Long?,
    val updateId: Long?,
    val bookEpoch: Long?,
    val quality: ForwardMarketDataQuality,
    val rawPayload: String,
    val schemaVersion: Int = FORWARD_MARKET_RAW_SCHEMA_VERSION,
) {
    init {
        require(localConnectionId.isNotBlank()) { "Forward market local connection ID must not be blank." }
        require(topic.isNotBlank()) { "Forward market topic must not be blank." }
        require(rawPayload.isNotBlank()) { "Forward market raw payload must not be blank." }
        require(schemaVersion > 0) { "Forward market raw schema version must be positive." }
        require(sequenceStart == null || sequenceEnd == null || sequenceStart <= sequenceEnd) {
            "Forward market sequence range must be ordered."
        }
        require(bookEpoch == null || bookEpoch > 0) { "Forward market order-book epoch must be positive." }
    }

    val gapDetected: Boolean
        get() = quality.gapDetected
}

data class ForwardMarketCaptureBatch(
    val rawEvent: ForwardMarketRawEvent,
    val normalizedEvents: List<ForwardMarketCaptureEvent>,
) {
    init {
        require(normalizedEvents.all { it.symbol == rawEvent.symbol }) {
            "Forward market raw and normalized event symbols must match."
        }
    }
}

interface ForwardMarketRawEventArchive : AutoCloseable {
    fun append(event: ForwardMarketRawEvent)

    fun flush(now: Instant)

    fun status(): ForwardMarketRawArchiveStatus
}

data class ForwardMarketRawArchiveStatus(
    val enabled: Boolean,
    val archiveSessionId: String?,
    val archivedEventCount: Long,
    val gapEventCount: Long,
    val latestReceivedAt: Instant?,
    val activeSegment: String?,
    val lastCompletedSegment: String?,
) {
    init {
        require(archivedEventCount >= 0) { "Forward market archived event count must not be negative." }
        require(gapEventCount in 0..archivedEventCount) {
            "Forward market gap event count must fit the archived event count."
        }
    }

    companion object {
        val DISABLED =
            ForwardMarketRawArchiveStatus(
                enabled = false,
                archiveSessionId = null,
                archivedEventCount = 0,
                gapEventCount = 0,
                latestReceivedAt = null,
                activeSegment = null,
                lastCompletedSegment = null,
            )
    }
}
