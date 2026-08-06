package dev.yaklede.bybittrader.engine.strategy

import dev.yaklede.bybittrader.engine.execution.ExchangeAccountExecutionProfile
import dev.yaklede.bybittrader.engine.execution.ExchangeAccountMode
import dev.yaklede.bybittrader.engine.execution.ExchangeInstrumentRules
import dev.yaklede.bybittrader.engine.execution.ExchangeMarginMode
import dev.yaklede.bybittrader.engine.execution.ExchangePositionExecutionProfile
import dev.yaklede.bybittrader.engine.execution.ExchangePositionMode
import java.math.BigDecimal

enum class VolumeConfirmedTrendExchangeContractFailure {
    ACCOUNT_TYPE_NOT_UNIFIED,
    ACCOUNT_MODE_NOT_UNIFIED,
    MARGIN_MODE_NOT_CROSS,
    POSITION_MODE_NOT_ONE_WAY,
    BUY_LEVERAGE_NOT_ONE,
    SELL_LEVERAGE_NOT_ONE,
    POSITION_REDUCE_ONLY_RESTRICTED,
    SYMBOL_MISMATCH,
    INSTRUMENT_NOT_TRADING,
    INSTRUMENT_NOT_LINEAR_PERPETUAL,
    INSTRUMENT_CURRENCY_MISMATCH,
    INSTRUMENT_NOT_UNIFIED_MARGIN,
    MINIMUM_QUANTITY_MISMATCH,
    QUANTITY_STEP_MISMATCH,
    ONE_X_LEVERAGE_UNSUPPORTED,
}

data class VolumeConfirmedTrendExchangeContractValidation(
    val valid: Boolean,
    val failures: List<VolumeConfirmedTrendExchangeContractFailure>,
)

object VolumeConfirmedTrendExchangeContractValidator {
    fun validate(
        account: ExchangeAccountExecutionProfile,
        position: ExchangePositionExecutionProfile,
        instrument: ExchangeInstrumentRules,
        contract: VolumeConfirmedTrendExecutionContract = VolumeConfirmedTrendExecutionContract(),
    ): VolumeConfirmedTrendExchangeContractValidation {
        val failures = mutableListOf<VolumeConfirmedTrendExchangeContractFailure>()
        if (account.accountType != "UNIFIED") {
            failures += VolumeConfirmedTrendExchangeContractFailure.ACCOUNT_TYPE_NOT_UNIFIED
        }
        if (account.accountMode !in setOf(ExchangeAccountMode.UNIFIED_1, ExchangeAccountMode.UNIFIED_2)) {
            failures += VolumeConfirmedTrendExchangeContractFailure.ACCOUNT_MODE_NOT_UNIFIED
        }
        if (account.marginMode != ExchangeMarginMode.CROSS) {
            failures += VolumeConfirmedTrendExchangeContractFailure.MARGIN_MODE_NOT_CROSS
        }
        if (position.positionMode != ExchangePositionMode.ONE_WAY) {
            failures += VolumeConfirmedTrendExchangeContractFailure.POSITION_MODE_NOT_ONE_WAY
        }
        if (position.buyLeverage?.compareTo(BigDecimal.ONE) != 0) {
            failures += VolumeConfirmedTrendExchangeContractFailure.BUY_LEVERAGE_NOT_ONE
        }
        if (position.sellLeverage?.compareTo(BigDecimal.ONE) != 0) {
            failures += VolumeConfirmedTrendExchangeContractFailure.SELL_LEVERAGE_NOT_ONE
        }
        if (position.reduceOnlyRestricted) {
            failures += VolumeConfirmedTrendExchangeContractFailure.POSITION_REDUCE_ONLY_RESTRICTED
        }
        if (position.symbol != instrument.symbol || instrument.symbol.value != "BTCUSDT") {
            failures += VolumeConfirmedTrendExchangeContractFailure.SYMBOL_MISMATCH
        }
        if (instrument.status != "Trading") {
            failures += VolumeConfirmedTrendExchangeContractFailure.INSTRUMENT_NOT_TRADING
        }
        if (instrument.contractType != "LinearPerpetual") {
            failures += VolumeConfirmedTrendExchangeContractFailure.INSTRUMENT_NOT_LINEAR_PERPETUAL
        }
        if (instrument.baseCoin != "BTC" || instrument.quoteCoin != "USDT" || instrument.settleCoin != "USDT") {
            failures += VolumeConfirmedTrendExchangeContractFailure.INSTRUMENT_CURRENCY_MISMATCH
        }
        if (!instrument.unifiedMarginTrade) {
            failures += VolumeConfirmedTrendExchangeContractFailure.INSTRUMENT_NOT_UNIFIED_MARGIN
        }
        if (instrument.minimumOrderQuantity.compareTo(BigDecimal.valueOf(contract.minimumQuantityBtc)) != 0) {
            failures += VolumeConfirmedTrendExchangeContractFailure.MINIMUM_QUANTITY_MISMATCH
        }
        if (instrument.quantityStep.compareTo(BigDecimal.valueOf(contract.quantityStepBtc)) != 0) {
            failures += VolumeConfirmedTrendExchangeContractFailure.QUANTITY_STEP_MISMATCH
        }
        if (instrument.minimumLeverage > BigDecimal.ONE || instrument.maximumLeverage < BigDecimal.ONE) {
            failures += VolumeConfirmedTrendExchangeContractFailure.ONE_X_LEVERAGE_UNSUPPORTED
        }
        return VolumeConfirmedTrendExchangeContractValidation(
            valid = failures.isEmpty(),
            failures = failures,
        )
    }
}
