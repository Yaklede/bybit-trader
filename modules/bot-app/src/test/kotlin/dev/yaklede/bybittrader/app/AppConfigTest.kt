package dev.yaklede.bybittrader.app

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class AppConfigTest :
    StringSpec({
        "paper mode starts without private exchange credentials" {
            val config = AppConfig.fromEnvironment(emptyMap())

            config.runtimeMode shouldBe RuntimeMode.PAPER
            config.marketData.symbol.value shouldBe "BTCUSDT"
            config.marketData.timeframes.map { it.name } shouldBe listOf("M15", "H1")
            config.api.host shouldBe "127.0.0.1"
            config.api.port shouldBe 8080
        }

        "testnet mode requires private exchange credentials" {
            shouldThrow<IllegalArgumentException> {
                AppConfig.fromEnvironment(mapOf("BOT_MODE" to "TESTNET"))
            }
        }

        "enabled telegram alerts require telegram environment values" {
            shouldThrow<IllegalArgumentException> {
                AppConfig.fromEnvironment(mapOf("TELEGRAM_ALERTS_ENABLED" to "true"))
            }
        }

        "enabled discord alerts require webhook environment value" {
            shouldThrow<IllegalArgumentException> {
                AppConfig.fromEnvironment(mapOf("DISCORD_ALERTS_ENABLED" to "true"))
            }
        }

        "market data settings can be read from environment" {
            val config =
                AppConfig.fromEnvironment(
                    mapOf(
                        "BOT_SYMBOL" to "ETHUSDT",
                        "BOT_TIMEFRAMES" to "M15",
                        "BYBIT_PUBLIC_BASE_URL" to "https://api-testnet.bybit.com",
                    ),
                )

            config.marketData.symbol.value shouldBe "ETHUSDT"
            config.marketData.timeframes.map { it.name } shouldBe listOf("M15")
            config.marketData.bybitPublicBaseUrl shouldBe "https://api-testnet.bybit.com"
        }
    })
