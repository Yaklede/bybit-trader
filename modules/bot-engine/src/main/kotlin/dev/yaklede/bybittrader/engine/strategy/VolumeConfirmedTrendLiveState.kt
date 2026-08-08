package dev.yaklede.bybittrader.engine.strategy

import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.execution.ExecutionRiskState
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

enum class VolumeConfirmedTrendLiveStatus {
    DISABLED,
    FLAT,
    ENTRY_INTENT_RECORDED,
    ENTRY_SUBMITTED,
    ENTRY_NOT_FILLED,
    OPEN,
    EXIT_INTENT_RECORDED,
    EXIT_SUBMITTED,
    EXIT_NOT_FILLED,
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
    val riskState: ExecutionRiskState? = null,
    val riskReasonCodes: List<String> = emptyList(),
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
        require(riskReasonCodes.all(String::isNotBlank) && riskReasonCodes.distinct().size == riskReasonCodes.size) {
            "Trend live risk reason codes must be non-blank and unique."
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
        require(status !in ORDER_LIFECYCLE_STATES || activeDecisionKey != null) {
            "A trend live order lifecycle state requires a decision key."
        }
        require(status !in ORDER_LIFECYCLE_STATES || pendingTargetSide != null) {
            "A trend live order lifecycle state requires a pending target side."
        }
        require(status !in ORDER_LIFECYCLE_STATES || clientOrderId != null) {
            "A trend live order lifecycle state requires a client order ID."
        }
        require(status !in POSITION_STATES || observedPositionSide != null) {
            "A trend live position state requires an observed exchange position."
        }
        require(status != VolumeConfirmedTrendLiveStatus.ENTRY_NOT_FILLED || observedPositionSide == null) {
            "An unfilled trend entry cannot retain an observed exchange position."
        }
    }

    private companion object {
        val ORDER_LIFECYCLE_STATES =
            setOf(
                VolumeConfirmedTrendLiveStatus.ENTRY_INTENT_RECORDED,
                VolumeConfirmedTrendLiveStatus.ENTRY_SUBMITTED,
                VolumeConfirmedTrendLiveStatus.ENTRY_NOT_FILLED,
                VolumeConfirmedTrendLiveStatus.EXIT_INTENT_RECORDED,
                VolumeConfirmedTrendLiveStatus.EXIT_SUBMITTED,
                VolumeConfirmedTrendLiveStatus.EXIT_NOT_FILLED,
            )
        val POSITION_STATES =
            setOf(
                VolumeConfirmedTrendLiveStatus.OPEN,
                VolumeConfirmedTrendLiveStatus.EXIT_NOT_FILLED,
            )
    }
}

enum class VolumeConfirmedTrendLiveEventType {
    INITIALIZED,
    ENTRY_INTENT_RECORDED,
    ENTRY_SUBMITTED,
    ENTRY_FILL_OBSERVED,
    ENTRY_NOT_FILLED,
    EXIT_INTENT_RECORDED,
    EXIT_SUBMITTED,
    EXIT_FILL_OBSERVED,
    EXIT_NOT_FILLED,
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

data class VolumeConfirmedTrendLiveConfig(
    val protocolId: String,
    val candidateId: String,
    val protocolSha256: String,
    val symbol: Symbol,
    val recoveryRetryDelay: Duration = Duration.ofSeconds(10),
    val recoveryHistoryOverlap: Duration = Duration.ofMinutes(5),
    val approvalRevocationExitRetryDelay: Duration = Duration.ofMinutes(1),
    val recoveryEventLimit: Int = 100,
    val riskPolicy: VolumeConfirmedTrendLiveRiskPolicy = VolumeConfirmedTrendLiveRiskPolicy(),
) {
    init {
        require(protocolId.isNotBlank() && candidateId.isNotBlank()) { "Trend live config identities must not be blank." }
        require(protocolSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Trend live config protocol fingerprint must be a lowercase SHA-256."
        }
        require(symbol.value == "BTCUSDT") { "The frozen trend live service supports BTCUSDT only." }
        require(!recoveryRetryDelay.isNegative && !recoveryRetryDelay.isZero) {
            "Trend live recovery retry delay must be positive."
        }
        require(!recoveryHistoryOverlap.isNegative && !recoveryHistoryOverlap.isZero) {
            "Trend live recovery history overlap must be positive."
        }
        require(!approvalRevocationExitRetryDelay.isNegative && !approvalRevocationExitRetryDelay.isZero) {
            "Trend live approval-revocation exit retry delay must be positive."
        }
        require(recoveryEventLimit in 1..100_000) { "Trend live recovery event limit must be valid." }
    }
}

data class VolumeConfirmedTrendLiveRiskPolicy(
    val maximumDailyLossFraction: BigDecimal = BigDecimal("0.03"),
    val maximumAccountDrawdownFraction: BigDecimal = BigDecimal("0.35"),
    val maximumConsecutiveLosses: Int = 3,
    val riskStateMaximumAge: Duration = Duration.ofMinutes(10),
    val walletReconciliationMaximumAge: Duration = Duration.ofMinutes(10),
    val walletReconciliationConfirmedMismatchCount: Int = 2,
) {
    init {
        require(maximumDailyLossFraction > BigDecimal.ZERO && maximumDailyLossFraction <= BigDecimal.ONE) {
            "Trend live maximum daily loss fraction must be in (0, 1]."
        }
        require(maximumAccountDrawdownFraction > BigDecimal.ZERO && maximumAccountDrawdownFraction <= BigDecimal.ONE) {
            "Trend live maximum account drawdown fraction must be in (0, 1]."
        }
        require(maximumConsecutiveLosses in 1..100) {
            "Trend live maximum consecutive losses must be between 1 and 100."
        }
        require(!riskStateMaximumAge.isNegative && !riskStateMaximumAge.isZero) {
            "Trend live risk state maximum age must be positive."
        }
        require(!walletReconciliationMaximumAge.isNegative && !walletReconciliationMaximumAge.isZero) {
            "Trend live wallet reconciliation maximum age must be positive."
        }
        require(walletReconciliationConfirmedMismatchCount in 1..100) {
            "Trend live wallet mismatch confirmation count must be between 1 and 100."
        }
    }
}

enum class VolumeConfirmedTrendLiveEvaluationStatus {
    APPROVAL_BLOCKED,
    HALTED,
    NO_ACTION,
    NO_TRADE,
    RISK_BLOCKED,
    ORDER_SUBMITTED,
    ORDER_NOT_FILLED,
    RECOVERED,
    RECOVERY_PENDING,
    RECONCILED,
}

data class VolumeConfirmedTrendLiveEvaluationResult(
    val status: VolumeConfirmedTrendLiveEvaluationStatus,
    val state: VolumeConfirmedTrendLiveState,
    val plan: VolumeConfirmedTrendTargetPlan?,
    val approvalFailures: List<VolumeConfirmedTrendLiveApprovalFailure> = emptyList(),
    val contractFailures: List<VolumeConfirmedTrendExchangeContractFailure> = emptyList(),
    val riskReasonCodes: List<String> = emptyList(),
    val recoveryReasonCode: String? = null,
)

interface VolumeConfirmedTrendLiveExecutor {
    suspend fun evaluate(
        signal: VolumeConfirmedTrendExecutionSignal,
        referencePrice: BigDecimal,
    ): VolumeConfirmedTrendLiveEvaluationResult

    suspend fun reconcile(): VolumeConfirmedTrendLiveEvaluationResult

    suspend fun haltForSafety(reasonCode: String): VolumeConfirmedTrendLiveEvaluationResult
}
