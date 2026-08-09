package dev.yaklede.bybittrader.app

import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveRiskPolicy
import java.math.BigDecimal

internal fun ExecutionSettings.toVolumeConfirmedTrendLiveRiskPolicy(
    approvalMaximumDrawdownFraction: BigDecimal,
): VolumeConfirmedTrendLiveRiskPolicy {
    require(
        approvalMaximumDrawdownFraction > BigDecimal.ZERO &&
            approvalMaximumDrawdownFraction <= BigDecimal.ONE,
    ) {
        "Trend live approval maximum drawdown fraction must be in (0, 1]."
    }
    val frozenCeiling = VolumeConfirmedTrendLiveRiskPolicy()
    return VolumeConfirmedTrendLiveRiskPolicy(
        maximumDailyLossFraction = null,
        maximumAccountDrawdownFraction =
            minOf(maximumAccountDrawdownFraction, approvalMaximumDrawdownFraction),
        maximumConsecutiveLosses = null,
        riskStateMaximumAge = minOf(riskStateMaximumAge, frozenCeiling.riskStateMaximumAge),
        walletReconciliationMaximumAge =
            minOf(walletReconciliationMaximumAge, frozenCeiling.walletReconciliationMaximumAge),
        walletReconciliationConfirmedMismatchCount =
            minOf(
                walletReconciliationConfirmedMismatchCount,
                frozenCeiling.walletReconciliationConfirmedMismatchCount,
            ),
    )
}

internal fun ExecutionSettings.volumeConfirmedTrendLiveWalletTolerance(): BigDecimal =
    minOf(walletReconciliationTolerance, BigDecimal("0.01"))

internal fun VolumeConfirmedTrendLiveRiskPolicy.matchesFrozenApprovalPolicy(frozen: VolumeConfirmedTrendLiveRiskPolicy): Boolean =
    maximumDailyLossFraction.numericallyEquals(frozen.maximumDailyLossFraction) &&
        maximumAccountDrawdownFraction.compareTo(frozen.maximumAccountDrawdownFraction) == 0 &&
        maximumConsecutiveLosses == frozen.maximumConsecutiveLosses &&
        riskStateMaximumAge == frozen.riskStateMaximumAge &&
        walletReconciliationMaximumAge == frozen.walletReconciliationMaximumAge &&
        walletReconciliationConfirmedMismatchCount == frozen.walletReconciliationConfirmedMismatchCount

internal fun verifiedVolumeConfirmedTrendLiveRiskPolicyParity(
    artifactPassed: Boolean,
    runtime: VolumeConfirmedTrendLiveRiskPolicy,
    frozen: VolumeConfirmedTrendLiveRiskPolicy,
): Boolean = artifactPassed && runtime.matchesFrozenApprovalPolicy(frozen)

private fun BigDecimal?.numericallyEquals(other: BigDecimal?): Boolean =
    when {
        this == null || other == null -> this == null && other == null
        else -> compareTo(other) == 0
    }
