package dev.yaklede.bybittrader.engine.strategy

import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import java.math.BigDecimal
import java.time.Instant

enum class VolumeConfirmedTrendLiveStatus {
    DISABLED,
    FLAT,
    ENTRY_INTENT_RECORDED,
    ENTRY_SUBMITTED,
    OPEN,
    EXIT_INTENT_RECORDED,
    EXIT_SUBMITTED,
    HALTED,
}

data class VolumeConfirmedTrendLiveState(
    val protocolId: String,
    val candidateId: String,
    val protocolSha256: String,
    val symbol: Symbol,
    val status: VolumeConfirmedTrendLiveStatus,
    val approvalId: String?,
    val activeDecisionKey: String?,
    val pendingTargetSide: Side?,
    val clientOrderId: String?,
    val exchangeOrderId: String?,
    val observedPositionSide: Side?,
    val observedPositionQuantity: BigDecimal?,
    val lastExecutionId: String?,
    val haltedReasonCode: String?,
    val updatedAt: Instant,
) {
    init {
        require(protocolId.isNotBlank() && candidateId.isNotBlank()) {
            "Trend live protocol and candidate IDs must not be blank."
        }
        require(protocolSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Trend live protocol fingerprint must be a lowercase SHA-256."
        }
        require(approvalId == null || approvalId.isNotBlank()) { "Trend live approval ID must not be blank." }
        require(activeDecisionKey == null || activeDecisionKey.isNotBlank()) {
            "Trend live decision key must not be blank."
        }
        require(clientOrderId == null || clientOrderId.isNotBlank()) {
            "Trend live client order ID must not be blank."
        }
        require(exchangeOrderId == null || exchangeOrderId.isNotBlank()) {
            "Trend live exchange order ID must not be blank."
        }
        require(lastExecutionId == null || lastExecutionId.isNotBlank()) {
            "Trend live execution ID must not be blank."
        }
        require(haltedReasonCode == null || haltedReasonCode.isNotBlank()) {
            "Trend live halt reason must not be blank."
        }
        require((observedPositionSide == null) == (observedPositionQuantity == null)) {
            "Trend live observed position side and quantity must be present together."
        }
        require(observedPositionQuantity == null || observedPositionQuantity > BigDecimal.ZERO) {
            "Trend live observed position quantity must be positive."
        }
        require(status != VolumeConfirmedTrendLiveStatus.HALTED || haltedReasonCode != null) {
            "A halted trend live state requires a reason."
        }
        require(status == VolumeConfirmedTrendLiveStatus.HALTED || haltedReasonCode == null) {
            "Only a halted trend live state may retain a halt reason."
        }
        require(status !in ORDER_INTENT_STATES || activeDecisionKey != null) {
            "A trend live order lifecycle state requires a decision key."
        }
        require(status !in ORDER_INTENT_STATES || pendingTargetSide != null) {
            "A trend live order lifecycle state requires a pending target side."
        }
        require(status !in SUBMITTED_STATES || clientOrderId != null) {
            "A submitted trend live state requires a client order ID."
        }
        require(status != VolumeConfirmedTrendLiveStatus.OPEN || observedPositionSide != null) {
            "An open trend live state requires an observed exchange position."
        }
    }

    private companion object {
        val ORDER_INTENT_STATES =
            setOf(
                VolumeConfirmedTrendLiveStatus.ENTRY_INTENT_RECORDED,
                VolumeConfirmedTrendLiveStatus.ENTRY_SUBMITTED,
                VolumeConfirmedTrendLiveStatus.EXIT_INTENT_RECORDED,
                VolumeConfirmedTrendLiveStatus.EXIT_SUBMITTED,
            )
        val SUBMITTED_STATES =
            setOf(
                VolumeConfirmedTrendLiveStatus.ENTRY_SUBMITTED,
                VolumeConfirmedTrendLiveStatus.EXIT_SUBMITTED,
            )
    }
}

enum class VolumeConfirmedTrendLiveEventType {
    INITIALIZED,
    ENTRY_INTENT_RECORDED,
    ENTRY_SUBMITTED,
    ENTRY_FILL_OBSERVED,
    EXIT_INTENT_RECORDED,
    EXIT_SUBMITTED,
    EXIT_FILL_OBSERVED,
    RECONCILED,
    HALTED,
    RESUMED,
}

data class VolumeConfirmedTrendLiveEvent(
    val eventId: String,
    val protocolId: String,
    val protocolSha256: String,
    val symbol: Symbol,
    val decisionKey: String?,
    val type: VolumeConfirmedTrendLiveEventType,
    val targetSide: Side?,
    val orderSide: Side?,
    val orderQuantity: BigDecimal?,
    val referencePrice: BigDecimal?,
    val limitPrice: BigDecimal?,
    val clientOrderId: String?,
    val exchangeOrderId: String?,
    val executionId: String?,
    val reasonCode: String,
    val occurredAt: Instant,
) {
    init {
        require(eventId.isNotBlank() && protocolId.isNotBlank()) {
            "Trend live event and protocol IDs must not be blank."
        }
        require(protocolSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Trend live event protocol fingerprint must be a lowercase SHA-256."
        }
        require(decisionKey == null || decisionKey.isNotBlank()) { "Trend live event decision key must not be blank." }
        require(orderQuantity == null || orderQuantity > BigDecimal.ZERO) {
            "Trend live event order quantity must be positive."
        }
        require(referencePrice == null || referencePrice > BigDecimal.ZERO) {
            "Trend live event reference price must be positive."
        }
        require(limitPrice == null || limitPrice > BigDecimal.ZERO) {
            "Trend live event limit price must be positive."
        }
        require(clientOrderId == null || clientOrderId.isNotBlank()) {
            "Trend live event client order ID must not be blank."
        }
        require(exchangeOrderId == null || exchangeOrderId.isNotBlank()) {
            "Trend live event exchange order ID must not be blank."
        }
        require(executionId == null || executionId.isNotBlank()) {
            "Trend live event execution ID must not be blank."
        }
        require(reasonCode.isNotBlank()) { "Trend live event reason code must not be blank." }
    }
}

interface VolumeConfirmedTrendLiveStore {
    suspend fun trendLiveState(
        protocolId: String,
        symbol: Symbol,
    ): VolumeConfirmedTrendLiveState?

    suspend fun commitTrendLive(
        state: VolumeConfirmedTrendLiveState,
        events: List<VolumeConfirmedTrendLiveEvent>,
    )

    suspend fun trendLiveEvents(
        protocolId: String,
        symbol: Symbol,
        limit: Int,
    ): List<VolumeConfirmedTrendLiveEvent>
}
