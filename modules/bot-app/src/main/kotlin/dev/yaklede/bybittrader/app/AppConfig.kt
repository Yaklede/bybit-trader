package dev.yaklede.bybittrader.app

import dev.yaklede.bybittrader.domain.ResearchCandleLimits
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import java.math.BigDecimal

data class AppConfig(
    val runtimeMode: RuntimeMode,
    val marketData: MarketDataConfig,
    val bybitPrivate: BybitPrivateSettings,
    val api: ApiConfig,
    val database: DatabaseConfig,
    val alerts: AlertsConfig,
    val paperLoop: PaperLoopSettings,
    val paperTrading: PaperTradingSettings,
    val executionLoop: ExecutionLoopSettings,
    val execution: ExecutionSettings,
    val volumeFlowComposite: VolumeFlowCompositeSettings,
    val strategyProfiles: StrategyProfileSettings,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): AppConfig {
            val runtimeMode =
                environment["BOT_MODE"]
                    ?.uppercase()
                    ?.let(RuntimeMode::valueOf)
                    ?: RuntimeMode.PAPER

            val privateExecutionEnabled = environment["BOT_PRIVATE_EXECUTION_ENABLED"].toBooleanStrictOrFalse()
            val executionLoopEnabled = environment["BOT_EXECUTION_LOOP_ENABLED"].toBooleanStrictOrFalse()
            if (runtimeMode.requiresPrivateExchangeAccess() || privateExecutionEnabled || executionLoopEnabled) {
                require(!environment["BYBIT_API_KEY"].isNullOrBlank()) {
                    "Bybit key is required for private exchange access."
                }
                require(!environment["BYBIT_API_SECRET"].isNullOrBlank()) {
                    "Bybit secret is required for private exchange access."
                }
            }

            val marketData = MarketDataConfig.fromEnvironment(environment)
            val paperStrategy = PaperStrategyKind.fromEnvironment(environment)
            val execution = ExecutionSettings.fromEnvironment(environment)
            val executionLoop = ExecutionLoopSettings.fromEnvironment(environment, Timeframe.M5)
            require(!execution.enabled || runtimeMode != RuntimeMode.PAPER) {
                "BOT_MODE=TESTNET or LIVE is required when BOT_PRIVATE_EXECUTION_ENABLED=true."
            }
            require(!executionLoop.enabled || execution.enabled) {
                "BOT_PRIVATE_EXECUTION_ENABLED=true is required when BOT_EXECUTION_LOOP_ENABLED=true."
            }
            require(runtimeMode != RuntimeMode.LIVE || !executionLoop.enabled || execution.maxNotional != null) {
                "BOT_EXECUTION_MAX_NOTIONAL is required for the unverified live execution loop."
            }
            return AppConfig(
                runtimeMode = runtimeMode,
                marketData = marketData,
                bybitPrivate = BybitPrivateSettings.fromEnvironment(environment, runtimeMode),
                api =
                    ApiConfig(
                        host = environment["BOT_API_HOST"] ?: "127.0.0.1",
                        port = environment["BOT_API_PORT"]?.toIntOrNull() ?: 8080,
                        controlCredential = environment["BOT_CONTROL_TOKEN"]?.takeIf { it.isNotBlank() },
                    ),
                database =
                    DatabaseConfig(
                        path = environment["BOT_DATABASE_PATH"] ?: "data/bybit-trader.sqlite",
                    ),
                alerts = AlertsConfig.fromEnvironment(environment),
                paperLoop = PaperLoopSettings.fromEnvironment(environment, marketData.timeframes.first(), paperStrategy),
                paperTrading = PaperTradingSettings.fromEnvironment(environment, paperStrategy),
                executionLoop = executionLoop,
                execution = execution,
                volumeFlowComposite = VolumeFlowCompositeSettings.fromEnvironment(environment),
                strategyProfiles = StrategyProfileSettings.fromEnvironment(environment),
            )
        }
    }
}

data class BybitPrivateSettings(
    val keyId: String?,
    val signingCredential: String?,
    val baseUrl: String,
    val recvWindowMillis: Long,
    val category: String,
    val positionIdx: Int,
    val accountType: String,
) {
    init {
        require(baseUrl.isNotBlank()) { "Bybit private base URL must not be blank." }
        require(recvWindowMillis in 1_000..60_000) { "Bybit recv window must be between 1000 and 60000 ms." }
        require(category == "linear") { "Only Bybit linear category is supported for execution." }
        require(positionIdx in 0..2) { "Bybit position index must be 0, 1, or 2." }
        require(accountType in setOf("UNIFIED", "CONTRACT", "SPOT")) {
            "Bybit account type must be UNIFIED, CONTRACT, or SPOT."
        }
    }

    val credentialsAvailable: Boolean
        get() = !keyId.isNullOrBlank() && !signingCredential.isNullOrBlank()

    companion object {
        fun fromEnvironment(
            environment: Map<String, String>,
            runtimeMode: RuntimeMode,
        ): BybitPrivateSettings =
            BybitPrivateSettings(
                keyId = environment["BYBIT_API_KEY"]?.takeIf { it.isNotBlank() },
                signingCredential = environment["BYBIT_API_SECRET"]?.takeIf { it.isNotBlank() },
                baseUrl =
                    environment["BYBIT_PRIVATE_BASE_URL"]
                        ?: when (runtimeMode) {
                            RuntimeMode.TESTNET -> "https://api-testnet.bybit.com"
                            RuntimeMode.LIVE -> "https://api.bybit.com"
                            RuntimeMode.PAPER -> "https://api-testnet.bybit.com"
                        },
                recvWindowMillis = environment["BYBIT_RECV_WINDOW_MILLIS"]?.toLongOrNull() ?: 5_000,
                category = environment["BYBIT_PRIVATE_CATEGORY"]?.trim()?.lowercase() ?: "linear",
                positionIdx = environment["BYBIT_POSITION_IDX"]?.toIntOrNull() ?: 0,
                accountType = environment["BYBIT_ACCOUNT_TYPE"]?.trim()?.uppercase() ?: "UNIFIED",
            )
    }
}

data class VolumeFlowCompositeSettings(
    val currentConfigPath: String,
) {
    init {
        require(currentConfigPath.isNotBlank()) { "Volume-flow composite current config path must not be blank." }
    }

    companion object {
        fun fromEnvironment(environment: Map<String, String>): VolumeFlowCompositeSettings =
            VolumeFlowCompositeSettings(
                currentConfigPath = environment["BOT_VOLUME_FLOW_COMPOSITE_CONFIG_PATH"] ?: "config/volume-flow-composite-current.json",
            )
    }
}

data class StrategyProfileSettings(
    val statePath: String,
) {
    init {
        require(statePath.isNotBlank()) { "Strategy profile state path must not be blank." }
    }

    companion object {
        fun fromEnvironment(environment: Map<String, String>): StrategyProfileSettings =
            StrategyProfileSettings(
                statePath = environment["BOT_STRATEGY_PROFILE_STATE_PATH"] ?: "data/strategy-profile-current.txt",
            )
    }
}

data class MarketDataConfig(
    val symbol: Symbol,
    val timeframes: List<Timeframe>,
    val bybitPublicBaseUrl: String,
) {
    init {
        require(timeframes.isNotEmpty()) { "At least one market data timeframe is required." }
        require(bybitPublicBaseUrl.isNotBlank()) { "Bybit public base URL must not be blank." }
    }

    companion object {
        fun fromEnvironment(environment: Map<String, String>): MarketDataConfig =
            MarketDataConfig(
                symbol = Symbol(environment["BOT_SYMBOL"] ?: "BTCUSDT"),
                timeframes =
                    environment["BOT_TIMEFRAMES"]
                        ?.split(",")
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?.map(Timeframe::valueOf)
                        ?: listOf(Timeframe.M1, Timeframe.M5, Timeframe.M15),
                bybitPublicBaseUrl = environment["BYBIT_PUBLIC_BASE_URL"] ?: "https://api.bybit.com",
            )
    }
}

data class ApiConfig(
    val host: String,
    val port: Int,
    val controlCredential: String?,
) {
    init {
        require(host.isNotBlank()) { "API host must not be blank." }
        require(port in 1..65535) { "API port must be between 1 and 65535." }
    }
}

data class DatabaseConfig(
    val path: String,
) {
    init {
        require(path.isNotBlank()) { "Database path must not be blank." }
    }
}

data class PaperLoopSettings(
    val enabled: Boolean,
    val strategy: PaperStrategyKind,
    val timeframe: Timeframe,
    val candleLimit: Int,
    val syncLimit: Int,
    val intervalSeconds: Long,
) {
    init {
        require(candleLimit in 20..ResearchCandleLimits.MAX_M5_REPLAY_CANDLES) {
            "Paper loop candle limit must be between 20 and ${ResearchCandleLimits.MAX_M5_REPLAY_CANDLES}."
        }
        require(syncLimit in 1..1000) { "Paper loop sync limit must be between 1 and 1000." }
        require(intervalSeconds in 10..86_400) { "Paper loop interval seconds must be between 10 and 86400." }
    }

    companion object {
        fun fromEnvironment(
            environment: Map<String, String>,
            defaultTimeframe: Timeframe,
            strategy: PaperStrategyKind,
        ): PaperLoopSettings =
            PaperLoopSettings(
                enabled = environment["BOT_PAPER_LOOP_ENABLED"].toBooleanStrictOrFalse(),
                strategy = strategy,
                timeframe =
                    environment["BOT_PAPER_TIMEFRAME"]
                        ?.trim()
                        ?.uppercase()
                        ?.let(Timeframe::valueOf)
                        ?: strategy.defaultTimeframe(defaultTimeframe),
                candleLimit = environment["BOT_PAPER_CANDLE_LIMIT"]?.toIntOrNull() ?: strategy.defaultCandleLimit,
                syncLimit = environment["BOT_PAPER_SYNC_LIMIT"]?.toIntOrNull() ?: 1000,
                intervalSeconds = environment["BOT_PAPER_INTERVAL_SECONDS"]?.toLongOrNull() ?: strategy.defaultIntervalSeconds,
            )
    }
}

enum class PaperStrategyKind(
    val configValue: String,
    val defaultCandleLimit: Int,
    val defaultIntervalSeconds: Long,
) {
    VOLUME_FLOW_AGGRESSIVE(
        configValue = "volume-flow-aggressive",
        defaultCandleLimit = 18_000,
        defaultIntervalSeconds = 300,
    ),
    MEAN_REVERSION(
        configValue = "mean-reversion",
        defaultCandleLimit = 200,
        defaultIntervalSeconds = 900,
    ),
    ;

    fun defaultTimeframe(fallback: Timeframe): Timeframe =
        when (this) {
            VOLUME_FLOW_AGGRESSIVE -> Timeframe.M5
            MEAN_REVERSION -> fallback
        }

    companion object {
        fun fromEnvironment(environment: Map<String, String>): PaperStrategyKind =
            environment["BOT_PAPER_STRATEGY"]
                ?.trim()
                ?.lowercase()
                ?.let { value ->
                    entries.firstOrNull { it.configValue == value || it.name.lowercase() == value }
                        ?: throw IllegalArgumentException("Unsupported paper strategy: $value.")
                }
                ?: VOLUME_FLOW_AGGRESSIVE
    }
}

data class PaperTradingSettings(
    val initialEquity: BigDecimal,
    val riskFraction: BigDecimal,
    val feeRate: BigDecimal,
) {
    init {
        require(initialEquity > BigDecimal.ZERO) { "Paper initial equity must be positive." }
        require(riskFraction > BigDecimal.ZERO && riskFraction <= BigDecimal("0.20")) {
            "Paper risk fraction must be between 0 and 0.20."
        }
        require(feeRate >= BigDecimal.ZERO && feeRate <= BigDecimal("0.01")) {
            "Paper fee rate must be between 0 and 0.01."
        }
    }

    companion object {
        fun fromEnvironment(
            environment: Map<String, String>,
            strategy: PaperStrategyKind,
        ): PaperTradingSettings =
            PaperTradingSettings(
                initialEquity = environment["BOT_PAPER_INITIAL_EQUITY"]?.let(::BigDecimal) ?: BigDecimal("1000000"),
                riskFraction =
                    environment["BOT_PAPER_RISK_FRACTION"]?.let(::BigDecimal)
                        ?: strategy.defaultRiskFraction(),
                feeRate = environment["BOT_PAPER_FEE_RATE"]?.let(::BigDecimal) ?: BigDecimal("0.0006"),
            )
    }
}

data class ExecutionLoopSettings(
    val enabled: Boolean,
    val timeframe: Timeframe,
    val candleLimit: Int,
    val syncLimit: Int,
    val alertBatchLimit: Int,
    val intervalSeconds: Long,
) {
    init {
        require(candleLimit in 20..ResearchCandleLimits.MAX_M5_REPLAY_CANDLES) {
            "Execution loop candle limit must be between 20 and ${ResearchCandleLimits.MAX_M5_REPLAY_CANDLES}."
        }
        require(syncLimit in 1..1000) { "Execution loop sync limit must be between 1 and 1000." }
        require(alertBatchLimit in 1..1000) { "Execution alert batch limit must be between 1 and 1000." }
        require(intervalSeconds in 10..86_400) { "Execution loop interval seconds must be between 10 and 86400." }
    }

    companion object {
        fun fromEnvironment(
            environment: Map<String, String>,
            defaultTimeframe: Timeframe,
        ): ExecutionLoopSettings =
            ExecutionLoopSettings(
                enabled = environment["BOT_EXECUTION_LOOP_ENABLED"].toBooleanStrictOrFalse(),
                timeframe =
                    environment["BOT_EXECUTION_TIMEFRAME"]
                        ?.trim()
                        ?.uppercase()
                        ?.let(Timeframe::valueOf)
                        ?: defaultTimeframe,
                candleLimit = environment["BOT_EXECUTION_CANDLE_LIMIT"]?.toIntOrNull() ?: 18_000,
                syncLimit = environment["BOT_EXECUTION_SYNC_LIMIT"]?.toIntOrNull() ?: 1000,
                alertBatchLimit = environment["BOT_EXECUTION_ALERT_BATCH_LIMIT"]?.toIntOrNull() ?: 100,
                intervalSeconds = environment["BOT_EXECUTION_INTERVAL_SECONDS"]?.toLongOrNull() ?: 300,
            )
    }
}

data class ExecutionSettings(
    val enabled: Boolean,
    val accountEquity: BigDecimal,
    val useLiveAccountEquity: Boolean,
    val riskFraction: BigDecimal,
    val feeRate: BigDecimal,
    val slippageBufferRate: BigDecimal,
    val quantityStep: BigDecimal,
    val minQuantity: BigDecimal,
    val maxQuantity: BigDecimal?,
    val maxNotional: BigDecimal?,
    val leverage: BigDecimal?,
) {
    init {
        require(accountEquity > BigDecimal.ZERO) { "Execution account equity must be positive." }
        require(riskFraction > BigDecimal.ZERO && riskFraction <= BigDecimal("0.20")) {
            "Execution risk fraction must be between 0 and 0.20."
        }
        require(feeRate >= BigDecimal.ZERO && feeRate <= BigDecimal("0.01")) {
            "Execution fee rate must be between 0 and 0.01."
        }
        require(slippageBufferRate >= BigDecimal.ZERO && slippageBufferRate <= BigDecimal("0.01")) {
            "Execution slippage buffer rate must be between 0 and 0.01."
        }
        require(quantityStep > BigDecimal.ZERO) { "Execution quantity step must be positive." }
        require(minQuantity > BigDecimal.ZERO) { "Execution min quantity must be positive." }
        require(maxQuantity == null || maxQuantity >= minQuantity) {
            "Execution max quantity must be greater than or equal to min quantity."
        }
        require(maxNotional == null || maxNotional > BigDecimal.ZERO) { "Execution max notional must be positive." }
        require(leverage == null || leverage > BigDecimal.ONE) { "Execution leverage must be greater than 1." }
    }

    companion object {
        fun fromEnvironment(environment: Map<String, String>): ExecutionSettings =
            ExecutionSettings(
                enabled = environment["BOT_PRIVATE_EXECUTION_ENABLED"].toBooleanStrictOrFalse(),
                accountEquity = environment["BOT_EXECUTION_ACCOUNT_EQUITY"]?.let(::BigDecimal) ?: BigDecimal("1000000"),
                useLiveAccountEquity = environment["BOT_EXECUTION_USE_LIVE_EQUITY"].toBooleanStrictOrFalse(),
                riskFraction = environment["BOT_EXECUTION_RISK_FRACTION"]?.let(::BigDecimal) ?: BigDecimal("0.055"),
                feeRate = environment["BOT_EXECUTION_FEE_RATE"]?.let(::BigDecimal) ?: BigDecimal("0.0006"),
                slippageBufferRate =
                    environment["BOT_EXECUTION_SLIPPAGE_BUFFER_RATE"]?.let(::BigDecimal) ?: BigDecimal("0.0002"),
                quantityStep = environment["BOT_EXECUTION_QTY_STEP"]?.let(::BigDecimal) ?: BigDecimal("0.001"),
                minQuantity = environment["BOT_EXECUTION_MIN_QTY"]?.let(::BigDecimal) ?: BigDecimal("0.001"),
                maxQuantity = environment["BOT_EXECUTION_MAX_QTY"]?.takeIf { it.isNotBlank() }?.let(::BigDecimal),
                maxNotional = environment["BOT_EXECUTION_MAX_NOTIONAL"]?.takeIf { it.isNotBlank() }?.let(::BigDecimal),
                leverage = environment["BOT_EXECUTION_LEVERAGE"]?.takeIf { it.isNotBlank() }?.let(::BigDecimal),
            )
    }
}

private fun PaperStrategyKind.defaultRiskFraction(): BigDecimal =
    when (this) {
        PaperStrategyKind.VOLUME_FLOW_AGGRESSIVE -> BigDecimal("0.055")
        PaperStrategyKind.MEAN_REVERSION -> BigDecimal("0.005")
    }

enum class RuntimeMode {
    PAPER,
    TESTNET,
    LIVE,
}

private fun RuntimeMode.requiresPrivateExchangeAccess(): Boolean = this != RuntimeMode.PAPER

data class AlertsConfig(
    val telegram: TelegramAlertConfig?,
    val discord: DiscordAlertConfig?,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String>): AlertsConfig {
            val telegramEnabled = environment["TELEGRAM_ALERTS_ENABLED"].toBooleanStrictOrFalse()
            val discordEnabled = environment["DISCORD_ALERTS_ENABLED"].toBooleanStrictOrFalse()
            return AlertsConfig(
                telegram =
                    if (telegramEnabled) {
                        TelegramAlertConfig(
                            botCredential = requireNotBlank(environment["TELEGRAM_BOT_TOKEN"], "Telegram bot credential"),
                            chatId = requireNotBlank(environment["TELEGRAM_CHAT_ID"], "Telegram chat id"),
                        )
                    } else {
                        null
                    },
                discord =
                    if (discordEnabled) {
                        DiscordAlertConfig(
                            webhookUrl = requireNotBlank(environment["DISCORD_WEBHOOK_URL"], "Discord webhook url"),
                        )
                    } else {
                        null
                    },
            )
        }
    }
}

data class TelegramAlertConfig(
    val botCredential: String,
    val chatId: String,
)

data class DiscordAlertConfig(
    val webhookUrl: String,
)

private fun String?.toBooleanStrictOrFalse(): Boolean = this?.equals("true", ignoreCase = true) == true

private fun requireNotBlank(
    value: String?,
    label: String,
): String {
    require(!value.isNullOrBlank()) { "$label is required when enabled." }
    return value
}
