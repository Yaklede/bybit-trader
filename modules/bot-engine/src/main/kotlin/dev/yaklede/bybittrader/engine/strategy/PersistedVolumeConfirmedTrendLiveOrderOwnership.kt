package dev.yaklede.bybittrader.engine.strategy

import dev.yaklede.bybittrader.domain.Symbol

class PersistedVolumeConfirmedTrendLiveOrderOwnership(
    private val store: VolumeConfirmedTrendLiveStore,
    private val protocolId: String,
    private val protocolSha256: String,
    private val symbol: Symbol,
    private val eventLimit: Int = 100_000,
) {
    init {
        require(protocolId.isNotBlank()) { "Trend live ownership protocol ID must not be blank." }
        require(protocolSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Trend live ownership protocol fingerprint must be a lowercase SHA-256."
        }
        require(eventLimit in 1..100_000) { "Trend live ownership event limit must be valid." }
    }

    suspend fun clientOrderIds(): Set<String> =
        store
            .trendLiveEvents(protocolId, symbol, eventLimit)
            .asSequence()
            .filter { event ->
                event.protocolId == protocolId &&
                    event.protocolSha256 == protocolSha256 &&
                    event.symbol == symbol &&
                    event.type in ORDER_OWNERSHIP_EVENT_TYPES
            }.mapNotNull(VolumeConfirmedTrendLiveEvent::clientOrderId)
            .toSet()

    private companion object {
        val ORDER_OWNERSHIP_EVENT_TYPES =
            setOf(
                VolumeConfirmedTrendLiveEventType.ENTRY_INTENT_RECORDED,
                VolumeConfirmedTrendLiveEventType.ENTRY_SUBMITTED,
                VolumeConfirmedTrendLiveEventType.EXIT_INTENT_RECORDED,
                VolumeConfirmedTrendLiveEventType.EXIT_SUBMITTED,
            )
    }
}
