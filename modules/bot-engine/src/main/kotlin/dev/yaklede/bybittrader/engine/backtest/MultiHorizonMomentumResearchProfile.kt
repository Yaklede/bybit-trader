package dev.yaklede.bybittrader.engine.backtest

import dev.yaklede.bybittrader.strategy.MultiHorizonMomentumParameters
import dev.yaklede.bybittrader.strategy.MultiHorizonMomentumStrategy

const val MULTI_HORIZON_MOMENTUM_PROFILE_ID = "multi-horizon-momentum-development-v1"
const val MULTI_HORIZON_MOMENTUM_EXECUTION_CONTRACT = "causal-next-contiguous-open-v1"

/** The research profile is intentionally not wired into live execution. */
data class MultiHorizonMomentumResearchProfile(
    val profileId: String = MULTI_HORIZON_MOMENTUM_PROFILE_ID,
    val executionContract: String = MULTI_HORIZON_MOMENTUM_EXECUTION_CONTRACT,
    val validationStatus: StrategyValidationStatus = StrategyValidationStatus.UNVERIFIED,
    val automaticExecutionAllowed: Boolean = false,
    val parameters: MultiHorizonMomentumParameters = MultiHorizonMomentumParameters(),
) {
    fun strategy(): MultiHorizonMomentumStrategy = MultiHorizonMomentumStrategy(parameters)

    fun backtestConfig(): BacktestConfig =
        BacktestConfig(
            riskFraction = 0.01,
            feeRate = 0.0006,
            slippageRate = 0.0002,
            partialTakeProfitFraction = 0.0,
            breakevenAfterPartialTakeProfit = false,
            atrTrailingPeriod = 20,
            atrTrailingMultiplier = 16.0,
            maxHoldCandles = 4_032,
        )
}

object MultiHorizonMomentumResearchProfiles {
    fun current(): MultiHorizonMomentumResearchProfile = MultiHorizonMomentumResearchProfile()
}
