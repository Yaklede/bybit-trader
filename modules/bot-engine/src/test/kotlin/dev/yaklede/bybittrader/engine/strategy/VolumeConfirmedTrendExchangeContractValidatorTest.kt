package dev.yaklede.bybittrader.engine.strategy

import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.execution.ExchangeAccountExecutionProfile
import dev.yaklede.bybittrader.engine.execution.ExchangeAccountMode
import dev.yaklede.bybittrader.engine.execution.ExchangeInstrumentRules
import dev.yaklede.bybittrader.engine.execution.ExchangeMarginMode
import dev.yaklede.bybittrader.engine.execution.ExchangePositionExecutionProfile
import dev.yaklede.bybittrader.engine.execution.ExchangePositionMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class VolumeConfirmedTrendExchangeContractValidatorTest :
    StringSpec({
        "frozen Bybit execution contract passes" {
            val validation =
                VolumeConfirmedTrendExchangeContractValidator.validate(
                    account = account(),
                    position = position(),
                    instrument = instrument(),
                )

            validation.valid shouldBe true
            validation.failures shouldBe emptyList()
        }

        "hedge isolated leveraged account fails closed" {
            val validation =
                VolumeConfirmedTrendExchangeContractValidator.validate(
                    account = account().copy(marginMode = ExchangeMarginMode.ISOLATED),
                    position =
                        position().copy(
                            positionMode = ExchangePositionMode.HEDGE,
                            buyLeverage = BigDecimal("15"),
                            sellLeverage = BigDecimal("15"),
                        ),
                    instrument = instrument(),
                )

            validation.valid shouldBe false
            validation.failures shouldBe
                listOf(
                    VolumeConfirmedTrendExchangeContractFailure.MARGIN_MODE_NOT_CROSS,
                    VolumeConfirmedTrendExchangeContractFailure.POSITION_MODE_NOT_ONE_WAY,
                    VolumeConfirmedTrendExchangeContractFailure.BUY_LEVERAGE_NOT_ONE,
                    VolumeConfirmedTrendExchangeContractFailure.SELL_LEVERAGE_NOT_ONE,
                )
        }

        "changed exchange quantity rules fail instead of silently changing strategy" {
            val validation =
                VolumeConfirmedTrendExchangeContractValidator.validate(
                    account = account(),
                    position = position(),
                    instrument =
                        instrument().copy(
                            minimumOrderQuantity = BigDecimal("0.0001"),
                            quantityStep = BigDecimal("0.0001"),
                        ),
                )

            validation.valid shouldBe false
            validation.failures shouldBe
                listOf(
                    VolumeConfirmedTrendExchangeContractFailure.MINIMUM_QUANTITY_MISMATCH,
                    VolumeConfirmedTrendExchangeContractFailure.QUANTITY_STEP_MISMATCH,
                )
        }
    })

private fun account(): ExchangeAccountExecutionProfile =
    ExchangeAccountExecutionProfile(
        accountType = "UNIFIED",
        accountMode = ExchangeAccountMode.UNIFIED_2,
        unifiedMarginStatus = 5,
        marginMode = ExchangeMarginMode.CROSS,
        updatedAt = Instant.parse("2026-08-07T00:00:00Z"),
    )

private fun position(): ExchangePositionExecutionProfile =
    ExchangePositionExecutionProfile(
        symbol = Symbol("BTCUSDT"),
        positionMode = ExchangePositionMode.ONE_WAY,
        buyLeverage = BigDecimal.ONE,
        sellLeverage = BigDecimal.ONE,
        observedPositionIndices = setOf(0),
        reduceOnlyRestricted = false,
    )

private fun instrument(): ExchangeInstrumentRules =
    ExchangeInstrumentRules(
        symbol = Symbol("BTCUSDT"),
        status = "Trading",
        contractType = "LinearPerpetual",
        baseCoin = "BTC",
        quoteCoin = "USDT",
        settleCoin = "USDT",
        unifiedMarginTrade = true,
        minimumOrderQuantity = BigDecimal("0.001"),
        quantityStep = BigDecimal("0.001"),
        minimumNotional = BigDecimal("5"),
        priceTick = BigDecimal("0.1"),
        minimumLeverage = BigDecimal.ONE,
        maximumLeverage = BigDecimal("100"),
        leverageStep = BigDecimal("0.01"),
    )
