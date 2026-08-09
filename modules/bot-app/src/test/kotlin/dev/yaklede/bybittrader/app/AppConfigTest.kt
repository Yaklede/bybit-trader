package dev.yaklede.bybittrader.app

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Duration

class AppConfigTest :
    StringSpec({
        "paper mode starts without private exchange credentials" {
            val config = AppConfig.fromEnvironment(emptyMap())

            config.runtimeMode shouldBe RuntimeMode.PAPER
            config.marketData.symbol.value shouldBe "BTCUSDT"
            config.marketData.timeframes.map { it.name } shouldBe listOf("M1", "M5", "M15")
            config.forwardMarketCapture.enabled shouldBe false
            config.forwardMarketCapture.orderBookDepth shouldBe 50
            config.forwardMarketCapture.rawArchiveEnabled shouldBe false
            config.forwardMarketCapture.rawArchivePath shouldBe "data/market-events"
            config.makerShadow.enabled shouldBe false
            config.makerShadow.initialEquity.toPlainString() shouldBe "100"
            config.makerShadow.orderQuantity.toPlainString() shouldBe "0.001"
            config.makerShadow.queueMultiplier.toPlainString() shouldBe "1.5"
            config.volumeConfirmedTrendShadow.enabled shouldBe false
            config.volumeConfirmedTrendShadow.protocolPath shouldBe "config/volume-confirmed-trend-ensemble-v1.json"
            config.volumeConfirmedTrendShadow.bootstrapPath shouldBe
                "config/volume-confirmed-trend-ensemble-v1-bootstrap.json"
            config.volumeConfirmedTrendShadow.initialEquity.toPlainString() shouldBe "660"
            config.volumeConfirmedTrendShadow.maximumObservationDelay.seconds shouldBe 1200
            config.volumeConfirmedTrendShadow.boundaryDelay.seconds shouldBe 10
            config.volumeConfirmedTrendShadow.failureRetryDelay.seconds shouldBe 60
            config.volumeConfirmedTrendLive.enabled shouldBe false
            config.volumeConfirmedTrendLive.approvalExportDirectory shouldBe "data/trend-approval"
            config.volumeConfirmedTrendLive.approvalReceiptPath shouldBe
                "config/volume-confirmed-trend-live-approval.json"
            config.volumeConfirmedTrendLive.shadowEvidencePath shouldBe
                "data/trend-approval/pending/shadow-evidence.json"
            config.volumeConfirmedTrendLive.approvalReportPath shouldBe
                "data/trend-approval/pending/approval-report.json"
            config.volumeConfirmedTrendLive.reconciliationInterval.seconds shouldBe 15
            config.volumeConfirmedTrendLive.maximumSignalAge.seconds shouldBe 1200
            config.bybitPrivate.credentialsAvailable shouldBe false
            config.bybitPrivate.baseUrl shouldBe "https://api-testnet.bybit.com"
            config.bybitPrivate.privateWebSocketUrl shouldBe "wss://stream.bybit.com/v5/private"
            config.bybitPrivate.privateExecutionStreamEnabled shouldBe false
            config.bybitPrivate.accountType shouldBe "UNIFIED"
            config.api.host shouldBe "127.0.0.1"
            config.api.port shouldBe 8080
            config.paperLoop.enabled shouldBe false
            config.paperLoop.strategy shouldBe PaperStrategyKind.MULTI_HORIZON_MOMENTUM
            config.paperLoop.timeframe.name shouldBe "M5"
            config.paperLoop.candleLimit shouldBe 12000
            config.paperLoop.syncLimit shouldBe 1000
            config.paperLoop.intervalSeconds shouldBe 300
            config.paperTrading.initialEquity.toPlainString() shouldBe "1000000"
            config.paperTrading.riskFraction.toPlainString() shouldBe "0.01"
            config.execution.enabled shouldBe false
            config.execution.allowUnverifiedProfile shouldBe false
            config.execution.useLiveAccountEquity shouldBe false
            config.execution.leverage shouldBe null
            config.execution.minimumNetRiskReward.toPlainString() shouldBe "1.0"
            config.execution.circuitBreakerEnabled shouldBe true
            config.execution.maximumDailyLossFraction.toPlainString() shouldBe "0.03"
            config.execution.maximumAccountDrawdownFraction.toPlainString() shouldBe "0.20"
            config.execution.maximumConsecutiveLosses shouldBe 3
            config.execution.riskStateMaximumAge.seconds shouldBe 120
            config.execution.walletReconciliationEnabled shouldBe true
            config.execution.walletReconciliationTolerance.toPlainString() shouldBe "0.01"
            config.execution.walletReconciliationMaximumAge.seconds shouldBe 180
            config.execution.walletReconciliationConfirmedMismatchCount shouldBe 3
            config.executionLoop.enabled shouldBe false
            config.executionReconciliation.enabled shouldBe false
            config.executionReconciliation.alertBatchLimit shouldBe 100
            config.executionReconciliation.intervalSeconds shouldBe 60
            config.volumeFlowComposite.currentConfigPath shouldBe "config/volume-flow-composite-current.json"
            config.strategyProfiles.statePath shouldBe "data/strategy-profile-current.txt"
        }

        "testnet mode requires private exchange credentials" {
            shouldThrow<IllegalArgumentException> {
                AppConfig.fromEnvironment(mapOf("BOT_MODE" to "TESTNET"))
            }
        }

        "trend live executor is fail-closed and isolated from legacy execution" {
            shouldThrow<IllegalArgumentException> {
                AppConfig.fromEnvironment(
                    mapOf(
                        "BOT_VOLUME_CONFIRMED_TREND_LIVE_ENABLED" to "true",
                        "BOT_VOLUME_CONFIRMED_TREND_APPROVAL_EXPORT_DIR" to "/approval/exports",
                    ),
                )
            }
            shouldThrow<IllegalArgumentException> {
                AppConfig.fromEnvironment(
                    mapOf(
                        "BOT_MODE" to "LIVE",
                        "BYBIT_API_KEY" to "test-key",
                        "BYBIT_API_SECRET" to "test-secret",
                        "BOT_VOLUME_CONFIRMED_TREND_LIVE_ENABLED" to "true",
                    ),
                )
            }
            shouldThrow<IllegalArgumentException> {
                AppConfig.fromEnvironment(
                    mapOf(
                        "BOT_MODE" to "LIVE",
                        "BYBIT_API_KEY" to "test-key",
                        "BYBIT_API_SECRET" to "test-secret",
                        "BOT_VOLUME_CONFIRMED_TREND_SHADOW_ENABLED" to "true",
                        "BOT_VOLUME_CONFIRMED_TREND_LIVE_ENABLED" to "true",
                        "BOT_PRIVATE_EXECUTION_ENABLED" to "true",
                    ),
                )
            }
            shouldThrow<IllegalArgumentException> {
                AppConfig.fromEnvironment(
                    mapOf(
                        "BOT_MODE" to "LIVE",
                        "BYBIT_API_KEY" to "test-key",
                        "BYBIT_API_SECRET" to "test-secret",
                        "BOT_VOLUME_CONFIRMED_TREND_SHADOW_ENABLED" to "true",
                        "BOT_VOLUME_CONFIRMED_TREND_LIVE_ENABLED" to "true",
                        "BOT_PAPER_LOOP_ENABLED" to "true",
                    ),
                )
            }

            val config =
                AppConfig.fromEnvironment(
                    mapOf(
                        "BOT_MODE" to "LIVE",
                        "BYBIT_API_KEY" to "test-key",
                        "BYBIT_API_SECRET" to "test-secret",
                        "BOT_VOLUME_CONFIRMED_TREND_SHADOW_ENABLED" to "true",
                        "BOT_VOLUME_CONFIRMED_TREND_LIVE_ENABLED" to "true",
                        "BOT_VOLUME_CONFIRMED_TREND_APPROVAL_EXPORT_DIR" to "/approval/exports",
                        "BOT_VOLUME_CONFIRMED_TREND_LIVE_APPROVAL_PATH" to "/approval/receipt.json",
                        "BOT_VOLUME_CONFIRMED_TREND_SHADOW_EVIDENCE_PATH" to "/approval/shadow.json",
                        "BOT_VOLUME_CONFIRMED_TREND_APPROVAL_REPORT_PATH" to "/approval/report.json",
                        "BOT_VOLUME_CONFIRMED_TREND_LIVE_RECONCILIATION_SECONDS" to "20",
                        "BOT_VOLUME_CONFIRMED_TREND_LIVE_MAX_SIGNAL_AGE_SECONDS" to "900",
                    ),
                )

            config.volumeConfirmedTrendLive.enabled shouldBe true
            config.volumeConfirmedTrendLive.approvalExportDirectory shouldBe "/approval/exports"
            config.volumeConfirmedTrendLive.approvalReceiptPath shouldBe "/approval/receipt.json"
            config.volumeConfirmedTrendLive.shadowEvidencePath shouldBe "/approval/shadow.json"
            config.volumeConfirmedTrendLive.approvalReportPath shouldBe "/approval/report.json"
            config.volumeConfirmedTrendLive.reconciliationInterval.seconds shouldBe 20
            config.volumeConfirmedTrendLive.maximumSignalAge.seconds shouldBe 900
            config.execution.enabled shouldBe false
            config.executionLoop.enabled shouldBe false
            config.executionReconciliation.enabled shouldBe false
            config.bybitPrivate.privateExecutionStreamEnabled shouldBe false
        }

        "trend live A policy disables daily and consecutive limits while retaining operational guards" {
            val looseSettings =
                AppConfig
                    .fromEnvironment(emptyMap())
                    .execution
                    .copy(
                        maximumDailyLossFraction = BigDecimal("0.08"),
                        maximumAccountDrawdownFraction = BigDecimal("0.50"),
                        maximumConsecutiveLosses = 8,
                        riskStateMaximumAge = Duration.ofMinutes(15),
                        walletReconciliationTolerance = BigDecimal("0.03"),
                        walletReconciliationMaximumAge = Duration.ofMinutes(15),
                        walletReconciliationConfirmedMismatchCount = 9,
                    )

            val frozenPolicy =
                looseSettings.toVolumeConfirmedTrendLiveRiskPolicy(
                    approvalMaximumDrawdownFraction = BigDecimal("0.35"),
                )

            frozenPolicy.maximumDailyLossFraction shouldBe null
            frozenPolicy.maximumAccountDrawdownFraction.toPlainString() shouldBe "0.35"
            frozenPolicy.maximumConsecutiveLosses shouldBe null
            frozenPolicy.riskStateMaximumAge shouldBe Duration.ofMinutes(10)
            frozenPolicy.walletReconciliationMaximumAge shouldBe Duration.ofMinutes(10)
            frozenPolicy.walletReconciliationConfirmedMismatchCount shouldBe 2
            frozenPolicy.matchesFrozenApprovalPolicy(frozenPolicy) shouldBe true
            verifiedVolumeConfirmedTrendLiveRiskPolicyParity(
                artifactPassed = false,
                runtime = frozenPolicy,
                frozen = frozenPolicy,
            ) shouldBe false
            verifiedVolumeConfirmedTrendLiveRiskPolicyParity(
                artifactPassed = true,
                runtime = frozenPolicy,
                frozen = frozenPolicy,
            ) shouldBe true
            looseSettings.volumeConfirmedTrendLiveWalletTolerance().toPlainString() shouldBe "0.01"

            val stricterSettings =
                looseSettings.copy(
                    maximumDailyLossFraction = BigDecimal("0.02"),
                    maximumAccountDrawdownFraction = BigDecimal("0.18"),
                    maximumConsecutiveLosses = 2,
                    riskStateMaximumAge = Duration.ofMinutes(2),
                    walletReconciliationTolerance = BigDecimal("0.005"),
                    walletReconciliationMaximumAge = Duration.ofMinutes(3),
                    walletReconciliationConfirmedMismatchCount = 1,
                )
            val stricterPolicy =
                stricterSettings.toVolumeConfirmedTrendLiveRiskPolicy(
                    approvalMaximumDrawdownFraction = BigDecimal("0.15"),
                )

            stricterPolicy.maximumDailyLossFraction shouldBe null
            stricterPolicy.maximumAccountDrawdownFraction.toPlainString() shouldBe "0.15"
            stricterPolicy.maximumConsecutiveLosses shouldBe null
            stricterPolicy.riskStateMaximumAge shouldBe Duration.ofMinutes(2)
            stricterPolicy.walletReconciliationMaximumAge shouldBe Duration.ofMinutes(3)
            stricterPolicy.walletReconciliationConfirmedMismatchCount shouldBe 1
            stricterPolicy.matchesFrozenApprovalPolicy(frozenPolicy) shouldBe false
            verifiedVolumeConfirmedTrendLiveRiskPolicyParity(
                artifactPassed = true,
                runtime = stricterPolicy,
                frozen = frozenPolicy,
            ) shouldBe false
            stricterSettings.volumeConfirmedTrendLiveWalletTolerance().toPlainString() shouldBe "0.005"
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
                        "BYBIT_API_KEY" to "test-key",
                        "BYBIT_API_SECRET" to "test-secret",
                        "BYBIT_PRIVATE_BASE_URL" to "https://api-testnet.bybit.com",
                        "BYBIT_RECV_WINDOW_MILLIS" to "7000",
                        "BYBIT_POSITION_IDX" to "0",
                        "BYBIT_ACCOUNT_TYPE" to "UNIFIED",
                        "BOT_EXECUTION_ACCOUNT_EQUITY" to "2000000",
                        "BOT_EXECUTION_USE_LIVE_EQUITY" to "true",
                        "BOT_EXECUTION_RISK_FRACTION" to "0.03",
                        "BOT_EXECUTION_QTY_STEP" to "0.01",
                        "BOT_EXECUTION_MIN_QTY" to "0.01",
                        "BOT_EXECUTION_MAX_QTY" to "5",
                        "BOT_EXECUTION_MAX_NOTIONAL" to "100000",
                        "BOT_EXECUTION_LEVERAGE" to "15",
                        "BOT_EXECUTION_LIQUIDATION_BUFFER_PCT" to "0.8",
                        "BOT_EXECUTION_MIN_NET_RR" to "1.25",
                        "BOT_EXECUTION_PRICE_TICK" to "0.5",
                        "BOT_EXECUTION_PROTECTION_GRACE_SECONDS" to "45",
                        "BOT_EXECUTION_MAX_ENTRY_DELAY_SECONDS" to "20",
                        "BOT_EXECUTION_MAX_ACTUAL_RISK_OVERRUN_FRACTION" to "0.08",
                        "BOT_EXECUTION_CIRCUIT_BREAKER_ENABLED" to "true",
                        "BOT_EXECUTION_MAX_DAILY_LOSS_FRACTION" to "0.04",
                        "BOT_EXECUTION_MAX_ACCOUNT_DRAWDOWN_FRACTION" to "0.25",
                        "BOT_EXECUTION_MAX_CONSECUTIVE_LOSSES" to "4",
                        "BOT_EXECUTION_RISK_STATE_MAX_AGE_SECONDS" to "180",
                        "BOT_EXECUTION_WALLET_RECONCILIATION_ENABLED" to "true",
                        "BOT_EXECUTION_WALLET_RECONCILIATION_TOLERANCE" to "0.02",
                        "BOT_EXECUTION_WALLET_RECONCILIATION_MAX_AGE_SECONDS" to "240",
                        "BOT_EXECUTION_WALLET_RECONCILIATION_CONFIRMED_MISMATCHES" to "5",
                    ),
                )

            config.runtimeMode shouldBe RuntimeMode.TESTNET
            config.bybitPrivate.credentialsAvailable shouldBe true
            config.bybitPrivate.privateWebSocketUrl shouldBe "wss://stream-testnet.bybit.com/v5/private"
            config.bybitPrivate.privateExecutionStreamEnabled shouldBe true
            config.bybitPrivate.recvWindowMillis shouldBe 7000
            config.bybitPrivate.accountType shouldBe "UNIFIED"
            config.execution.enabled shouldBe true
            config.execution.allowUnverifiedProfile shouldBe false
            config.executionLoop.enabled shouldBe false
            config.executionReconciliation.enabled shouldBe true
            config.executionReconciliation.alertBatchLimit shouldBe 100
            config.executionReconciliation.intervalSeconds shouldBe 60
            config.execution.accountEquity.toPlainString() shouldBe "2000000"
            config.execution.useLiveAccountEquity shouldBe true
            config.execution.riskFraction.toPlainString() shouldBe "0.03"
            config.execution.quantityStep.toPlainString() shouldBe "0.01"
            config.execution.priceTick.toPlainString() shouldBe "0.5"
            config.execution.protectionGracePeriod.seconds shouldBe 45
            config.execution.maximumEntryDelay.seconds shouldBe 20
            config.execution.maximumActualRiskOverrunFraction.toPlainString() shouldBe "0.08"
            config.execution.circuitBreakerEnabled shouldBe true
            config.execution.maximumDailyLossFraction.toPlainString() shouldBe "0.04"
            config.execution.maximumAccountDrawdownFraction.toPlainString() shouldBe "0.25"
            config.execution.maximumConsecutiveLosses shouldBe 4
            config.execution.riskStateMaximumAge.seconds shouldBe 180
            config.execution.walletReconciliationEnabled shouldBe true
            config.execution.walletReconciliationTolerance.toPlainString() shouldBe "0.02"
            config.execution.walletReconciliationMaximumAge.seconds shouldBe 240
            config.execution.walletReconciliationConfirmedMismatchCount shouldBe 5
            config.execution.maxQuantity?.toPlainString() shouldBe "5"
            config.execution.maxNotional?.toPlainString() shouldBe "100000"
            config.execution.leverage?.toPlainString() shouldBe "15"
            config.execution.liquidationBufferPct.toPlainString() shouldBe "0.8"
            config.execution.minimumNetRiskReward.toPlainString() shouldBe "1.25"
        }

        "execution reconciliation settings can be configured independently of automatic entries" {
            val config =
                AppConfig.fromEnvironment(
                    mapOf(
                        "BOT_MODE" to "TESTNET",
                        "BOT_PRIVATE_EXECUTION_ENABLED" to "true",
                        "BOT_EXECUTION_RECONCILIATION_ENABLED" to "true",
                        "BOT_EXECUTION_RECONCILIATION_INTERVAL_SECONDS" to "90",
                        "BOT_EXECUTION_ALERT_BATCH_LIMIT" to "25",
                        "BYBIT_API_KEY" to "test-key",
                        "BYBIT_API_SECRET" to "test-secret",
                    ),
                )

            config.executionLoop.enabled shouldBe false
            config.executionReconciliation.enabled shouldBe true
            config.executionReconciliation.intervalSeconds shouldBe 90
            config.executionReconciliation.alertBatchLimit shouldBe 25
        }

        "private execution stream can be explicitly disabled" {
            val config =
                AppConfig.fromEnvironment(
                    mapOf(
                        "BOT_MODE" to "TESTNET",
                        "BOT_PRIVATE_EXECUTION_ENABLED" to "true",
                        "BOT_PRIVATE_EXECUTION_STREAM_ENABLED" to "false",
                        "BYBIT_API_KEY" to "test-key",
                        "BYBIT_API_SECRET" to "test-secret",
                    ),
                )

            config.bybitPrivate.privateExecutionStreamEnabled shouldBe false
        }

        "execution reconciliation requires private execution" {
            shouldThrow<IllegalArgumentException> {
                AppConfig.fromEnvironment(
                    mapOf(
                        "BOT_EXECUTION_RECONCILIATION_ENABLED" to "true",
                    ),
                )
            }
        }

        "execution loop rejects the failed runtime profile" {
            shouldThrow<IllegalArgumentException> {
                AppConfig.fromEnvironment(
                    mapOf(
                        "BOT_MODE" to "LIVE",
                        "BOT_PRIVATE_EXECUTION_ENABLED" to "true",
                        "BOT_EXECUTION_LOOP_ENABLED" to "true",
                        "BOT_EXECUTION_MAX_NOTIONAL" to "100",
                        "BYBIT_API_KEY" to "test-key",
                        "BYBIT_API_SECRET" to "test-secret",
                    ),
                )
            }
        }

        "rejected profile cannot be enabled with the legacy override" {
            shouldThrow<IllegalArgumentException> {
                AppConfig.fromEnvironment(
                    mapOf(
                        "BOT_MODE" to "LIVE",
                        "BOT_PRIVATE_EXECUTION_ENABLED" to "true",
                        "BOT_EXECUTION_LOOP_ENABLED" to "true",
                        "BOT_EXECUTION_ALLOW_UNVERIFIED_PROFILE" to "true",
                        "BOT_EXECUTION_MAX_NOTIONAL" to "100",
                        "BYBIT_API_KEY" to "test-key",
                        "BYBIT_API_SECRET" to "test-secret",
                    ),
                )
            }
        }

        "rejected profile cannot run automatically on testnet" {
            shouldThrow<IllegalArgumentException> {
                AppConfig.fromEnvironment(
                    mapOf(
                        "BOT_MODE" to "TESTNET",
                        "BOT_PRIVATE_EXECUTION_ENABLED" to "true",
                        "BOT_EXECUTION_LOOP_ENABLED" to "true",
                        "BOT_EXECUTION_ALLOW_UNVERIFIED_PROFILE" to "true",
                        "BOT_EXECUTION_MAX_NOTIONAL" to "100",
                        "BYBIT_API_KEY" to "test-key",
                        "BYBIT_API_SECRET" to "test-secret",
                    ),
                )
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

        "forward market capture settings are opt-in and validate their order book depth" {
            val config =
                AppConfig.fromEnvironment(
                    mapOf(
                        "BOT_FORWARD_MARKET_CAPTURE_ENABLED" to "true",
                        "BYBIT_PUBLIC_WEBSOCKET_URL" to "wss://stream.bybit.test/v5/public/linear",
                        "BOT_FORWARD_ORDER_BOOK_DEPTH" to "25",
                        "BOT_FORWARD_RAW_ARCHIVE_PATH" to "/data/raw-market-test",
                    ),
                )

            config.forwardMarketCapture.enabled shouldBe true
            config.forwardMarketCapture.orderBookDepth shouldBe 25
            config.forwardMarketCapture.rawArchiveEnabled shouldBe true
            config.forwardMarketCapture.rawArchivePath shouldBe "/data/raw-market-test"

            AppConfig
                .fromEnvironment(
                    mapOf(
                        "BOT_MODE" to "TESTNET",
                        "BYBIT_PUBLIC_WEBSOCKET_URL" to "",
                        "BYBIT_API_KEY" to "test-key",
                        "BYBIT_API_SECRET" to "test-secret",
                    ),
                ).forwardMarketCapture.publicWebSocketUrl shouldBe "wss://stream-testnet.bybit.com/v5/public/linear"

            shouldThrow<IllegalArgumentException> {
                AppConfig.fromEnvironment(mapOf("BOT_FORWARD_ORDER_BOOK_DEPTH" to "51"))
            }
            shouldThrow<IllegalArgumentException> {
                AppConfig.fromEnvironment(mapOf("BOT_FORWARD_RAW_ARCHIVE_ENABLED" to "true"))
            }
        }

        "maker shadow is opt-in and requires raw forward evidence" {
            val config =
                AppConfig.fromEnvironment(
                    mapOf(
                        "BOT_FORWARD_MARKET_CAPTURE_ENABLED" to "true",
                        "BOT_MAKER_SHADOW_ENABLED" to "true",
                        "BOT_MAKER_SHADOW_SESSION_ID" to "shadow-config-test",
                        "BOT_MAKER_SHADOW_INITIAL_EQUITY" to "660",
                        "BOT_MAKER_SHADOW_ORDER_QUANTITY" to "0.001",
                        "BOT_MAKER_SHADOW_MAX_NOTIONAL" to "100",
                        "BOT_MAKER_SHADOW_QUEUE_MULTIPLIER" to "2",
                        "BOT_MAKER_SHADOW_QUEUE_BUFFER_QUANTITY" to "0.0005",
                        "BOT_MAKER_SHADOW_MIN_SPREAD_BPS" to "0.02",
                        "BOT_MAKER_SHADOW_MAKER_FEE_RATE" to "0.0001",
                        "BOT_MAKER_SHADOW_TAKER_FEE_RATE" to "0.0006",
                        "BOT_MAKER_SHADOW_TAKER_EXIT_SLIPPAGE_BPS" to "3",
                        "BOT_MAKER_SHADOW_MAX_QUOTE_AGE_MILLIS" to "3000",
                        "BOT_MAKER_SHADOW_MAX_HOLDING_SECONDS" to "90",
                        "BOT_MAKER_SHADOW_MAX_EVENT_DELAY_MILLIS" to "500",
                    ),
                )

            config.makerShadow.enabled shouldBe true
            config.makerShadow.sessionId shouldBe "shadow-config-test"
            config.makerShadow.initialEquity.toPlainString() shouldBe "660"
            config.makerShadow.queueMultiplier.toPlainString() shouldBe "2"
            config.makerShadow.queueBufferQuantity.toPlainString() shouldBe "0.0005"
            config.makerShadow.minSpreadBps.toPlainString() shouldBe "0.02"
            config.makerShadow.makerFeeRate.toPlainString() shouldBe "0.0001"
            config.makerShadow.takerFeeRate.toPlainString() shouldBe "0.0006"
            config.makerShadow.maxQuoteAge.toMillis() shouldBe 3000
            config.makerShadow.maxHoldingDuration.seconds shouldBe 90
            config.makerShadow.maxEventDelay.toMillis() shouldBe 500

            shouldThrow<IllegalArgumentException> {
                AppConfig.fromEnvironment(mapOf("BOT_MAKER_SHADOW_ENABLED" to "true"))
            }
            shouldThrow<IllegalArgumentException> {
                AppConfig.fromEnvironment(
                    mapOf(
                        "BOT_FORWARD_MARKET_CAPTURE_ENABLED" to "true",
                        "BOT_FORWARD_RAW_ARCHIVE_ENABLED" to "false",
                        "BOT_MAKER_SHADOW_ENABLED" to "true",
                    ),
                )
            }
            shouldThrow<IllegalArgumentException> {
                AppConfig.fromEnvironment(
                    mapOf(
                        "BOT_FORWARD_MARKET_CAPTURE_ENABLED" to "true",
                        "BOT_MAKER_SHADOW_ENABLED" to "true",
                        "BOT_MAKER_SHADOW_QUEUE_MULTIPLIER" to "0.5",
                    ),
                )
            }
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
            config.paperLoop.strategy shouldBe PaperStrategyKind.MULTI_HORIZON_MOMENTUM
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
                        "BOT_STRATEGY_PROFILE_STATE_PATH" to "/tmp/strategy-profile.txt",
                    ),
                )

            config.volumeFlowComposite.currentConfigPath shouldBe "/tmp/current-volume-flow.json"
            config.strategyProfiles.statePath shouldBe "/tmp/strategy-profile.txt"
        }
    })
