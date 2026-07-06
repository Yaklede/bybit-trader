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
            config.marketData.timeframes.map { it.name } shouldBe listOf("M1", "M5", "M15")
            config.bybitPrivate.credentialsAvailable shouldBe false
            config.bybitPrivate.baseUrl shouldBe "https://api-testnet.bybit.com"
            config.bybitPrivate.accountType shouldBe "UNIFIED"
            config.api.host shouldBe "127.0.0.1"
            config.api.port shouldBe 8080
            config.paperLoop.enabled shouldBe false
            config.paperLoop.strategy shouldBe PaperStrategyKind.VOLUME_FLOW_AGGRESSIVE
            config.paperLoop.timeframe.name shouldBe "M5"
            config.paperLoop.candleLimit shouldBe 18000
            config.paperLoop.syncLimit shouldBe 1000
            config.paperLoop.intervalSeconds shouldBe 300
            config.paperTrading.initialEquity.toPlainString() shouldBe "1000000"
            config.paperTrading.riskFraction.toPlainString() shouldBe "0.055"
            config.execution.enabled shouldBe false
            config.executionLoop.enabled shouldBe false
            config.volumeFlowComposite.currentConfigPath shouldBe "config/volume-flow-composite-current.json"
        }

        "testnet mode requires private exchange credentials" {
            shouldThrow<IllegalArgumentException> {
                AppConfig.fromEnvironment(mapOf("BOT_MODE" to "TESTNET"))
            }
        }

        "private execution requires testnet or live mode" {
            shouldThrow<IllegalArgumentException> {
                AppConfig.fromEnvironment(
                    mapOf(
                        "BOT_PRIVATE_EXECUTION_ENABLED" to "true",
                        "BYBIT_API_KEY" to "test-key",
                        "BYBIT_API_SECRET" to "test-secret",
                    ),
                )
            }
        }

        "testnet private execution settings can be read from environment" {
            val config =
                AppConfig.fromEnvironment(
                    mapOf(
                        "BOT_MODE" to "TESTNET",
                        "BOT_PRIVATE_EXECUTION_ENABLED" to "true",
                        "BOT_EXECUTION_LOOP_ENABLED" to "true",
                        "BYBIT_API_KEY" to "test-key",
                        "BYBIT_API_SECRET" to "test-secret",
                        "BYBIT_PRIVATE_BASE_URL" to "https://api-testnet.bybit.com",
                        "BYBIT_RECV_WINDOW_MILLIS" to "7000",
                        "BYBIT_POSITION_IDX" to "0",
                        "BYBIT_ACCOUNT_TYPE" to "UNIFIED",
                        "BOT_EXECUTION_ACCOUNT_EQUITY" to "2000000",
                        "BOT_EXECUTION_RISK_FRACTION" to "0.03",
                        "BOT_EXECUTION_QTY_STEP" to "0.01",
                        "BOT_EXECUTION_MIN_QTY" to "0.01",
                        "BOT_EXECUTION_MAX_QTY" to "5",
                        "BOT_EXECUTION_MAX_NOTIONAL" to "100000",
                    ),
                )

            config.runtimeMode shouldBe RuntimeMode.TESTNET
            config.bybitPrivate.credentialsAvailable shouldBe true
            config.bybitPrivate.recvWindowMillis shouldBe 7000
            config.bybitPrivate.accountType shouldBe "UNIFIED"
            config.execution.enabled shouldBe true
            config.executionLoop.enabled shouldBe true
            config.execution.accountEquity.toPlainString() shouldBe "2000000"
            config.execution.riskFraction.toPlainString() shouldBe "0.03"
            config.execution.quantityStep.toPlainString() shouldBe "0.01"
            config.execution.maxQuantity?.toPlainString() shouldBe "5"
            config.execution.maxNotional?.toPlainString() shouldBe "100000"
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

        "paper loop settings can be read from environment" {
            val config =
                AppConfig.fromEnvironment(
                    mapOf(
                        "BOT_PAPER_LOOP_ENABLED" to "true",
                        "BOT_PAPER_TIMEFRAME" to "H1",
                        "BOT_PAPER_CANDLE_LIMIT" to "300",
                        "BOT_PAPER_SYNC_LIMIT" to "400",
                        "BOT_PAPER_INTERVAL_SECONDS" to "1800",
                        "BOT_PAPER_RISK_FRACTION" to "0.02",
                    ),
                )

            config.paperLoop.enabled shouldBe true
            config.paperLoop.strategy shouldBe PaperStrategyKind.VOLUME_FLOW_AGGRESSIVE
            config.paperLoop.timeframe.name shouldBe "H1"
            config.paperLoop.candleLimit shouldBe 300
            config.paperLoop.syncLimit shouldBe 400
            config.paperLoop.intervalSeconds shouldBe 1800
            config.paperTrading.riskFraction.toPlainString() shouldBe "0.02"
        }

        "paper strategy can be switched back to mean reversion" {
            val config =
                AppConfig.fromEnvironment(
                    mapOf(
                        "BOT_PAPER_STRATEGY" to "mean-reversion",
                    ),
                )

            config.paperLoop.strategy shouldBe PaperStrategyKind.MEAN_REVERSION
            config.paperLoop.timeframe.name shouldBe "M1"
            config.paperLoop.candleLimit shouldBe 200
            config.paperLoop.intervalSeconds shouldBe 900
            config.paperTrading.riskFraction.toPlainString() shouldBe "0.005"
        }

        "volume-flow composite current config path can be read from environment" {
            val config =
                AppConfig.fromEnvironment(
                    mapOf(
                        "BOT_VOLUME_FLOW_COMPOSITE_CONFIG_PATH" to "/tmp/current-volume-flow.json",
                    ),
                )

            config.volumeFlowComposite.currentConfigPath shouldBe "/tmp/current-volume-flow.json"
        }
    })
