package dev.yaklede.bybittrader.api.strategy

import dev.yaklede.bybittrader.api.security.configureControlAuthentication
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.execution.ExchangeAccountExecutionProfile
import dev.yaklede.bybittrader.engine.execution.ExchangeAccountMode
import dev.yaklede.bybittrader.engine.execution.ExchangeInstrumentRules
import dev.yaklede.bybittrader.engine.execution.ExchangeMarginMode
import dev.yaklede.bybittrader.engine.execution.ExchangePositionExecutionProfile
import dev.yaklede.bybittrader.engine.execution.ExchangePositionMode
import dev.yaklede.bybittrader.engine.execution.ExchangeSpotHedgingStatus
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendExchangeContractSnapshot
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendExchangeContractValidator
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import java.time.Instant

class VolumeConfirmedTrendExchangeContractRoutesTest :
    StringSpec({
        "exchange contract inspection requires operator authentication" {
            testApplication {
                applicationWithExchangeContractProvider { validSnapshot() }

                client.get(EXCHANGE_CONTRACT_PATH).status shouldBe HttpStatusCode.Unauthorized
            }
        }

        "missing private exchange configuration returns an explicit unavailable result" {
            testApplication {
                applicationWithExchangeContractProvider(null)

                val response = client.get(EXCHANGE_CONTRACT_PATH) { bearerAuth(CREDENTIAL) }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe
                    """{"available":false,"valid":false,"checkedAt":null,"failures":["PRIVATE_EXCHANGE_UNAVAILABLE"],"account":null,"position":null,"instrument":null}"""
            }
        }

        "exchange contract inspection exposes the frozen account and instrument requirements" {
            testApplication {
                applicationWithExchangeContractProvider { validSnapshot() }

                val response = client.get(EXCHANGE_CONTRACT_PATH) { bearerAuth(CREDENTIAL) }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText().also { body ->
                    body shouldContain "\"available\":true"
                    body shouldContain "\"valid\":true"
                    body shouldContain "\"accountMode\":\"UNIFIED_2\""
                    body shouldContain "\"marginMode\":\"CROSS\""
                    body shouldContain "\"spotHedgingStatus\":\"OFF\""
                    body shouldContain "\"positionMode\":\"ONE_WAY\""
                    body shouldContain "\"buyLeverage\":\"1\""
                    body shouldContain "\"minimumOrderQuantity\":\"0.001\""
                    body shouldContain "\"quantityStep\":\"0.001\""
                    body shouldContain "\"contractType\":\"LinearPerpetual\""
                }
            }
        }

        "exchange contract inspection explains every incompatible account setting" {
            testApplication {
                applicationWithExchangeContractProvider { invalidSnapshot() }

                val response = client.get(EXCHANGE_CONTRACT_PATH) { bearerAuth(CREDENTIAL) }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText().also { body ->
                    body shouldContain "\"valid\":false"
                    body shouldContain "\"MARGIN_MODE_NOT_CROSS\""
                    body shouldContain "\"POSITION_MODE_NOT_ONE_WAY\""
                    body shouldContain "\"BUY_LEVERAGE_NOT_ONE\""
                    body shouldContain "\"SELL_LEVERAGE_NOT_ONE\""
                }
            }
        }
    })

private fun io.ktor.server.testing.ApplicationTestBuilder.applicationWithExchangeContractProvider(
    provider: VolumeConfirmedTrendExchangeContractProvider?,
) {
    application {
        install(ContentNegotiation) { json() }
        configureControlAuthentication(CREDENTIAL)
        routing {
            configureVolumeConfirmedTrendShadowRoutes(
                reportProvider = null,
                exchangeContractProvider = provider,
            )
        }
    }
}

private fun validSnapshot(): VolumeConfirmedTrendExchangeContractSnapshot = snapshot(account(), position())

private fun invalidSnapshot(): VolumeConfirmedTrendExchangeContractSnapshot =
    snapshot(
        account = account().copy(marginMode = ExchangeMarginMode.ISOLATED),
        position =
            position().copy(
                positionMode = ExchangePositionMode.HEDGE,
                buyLeverage = BigDecimal("15"),
                sellLeverage = BigDecimal("15"),
            ),
    )

private fun snapshot(
    account: ExchangeAccountExecutionProfile,
    position: ExchangePositionExecutionProfile,
): VolumeConfirmedTrendExchangeContractSnapshot {
    val instrument = instrument()
    return VolumeConfirmedTrendExchangeContractSnapshot(
        checkedAt = CHECKED_AT,
        account = account,
        position = position,
        instrument = instrument,
        validation =
            VolumeConfirmedTrendExchangeContractValidator.validate(
                account = account,
                position = position,
                instrument = instrument,
            ),
    )
}

private fun account(): ExchangeAccountExecutionProfile =
    ExchangeAccountExecutionProfile(
        accountType = "UNIFIED",
        accountMode = ExchangeAccountMode.UNIFIED_2,
        unifiedMarginStatus = 5,
        marginMode = ExchangeMarginMode.CROSS,
        spotHedgingStatus = ExchangeSpotHedgingStatus.OFF,
        updatedAt = CHECKED_AT.minusSeconds(1),
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

private const val CREDENTIAL = "contract-test-token"
private const val EXCHANGE_CONTRACT_PATH = "/strategy/volume-confirmed-trend/exchange-contract"
private val CHECKED_AT: Instant = Instant.parse("2026-08-08T00:00:00Z")
