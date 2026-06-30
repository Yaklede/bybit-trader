package dev.yaklede.bybittrader.app

import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe

data class AppConfig(
    val runtimeMode: RuntimeMode,
    val marketData: MarketDataConfig,
    val api: ApiConfig,
    val database: DatabaseConfig,
    val alerts: AlertsConfig,
    val paperLoop: PaperLoopSettings,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): AppConfig {
            val runtimeMode =
                environment["BOT_MODE"]
                    ?.uppercase()
                    ?.let(RuntimeMode::valueOf)
                    ?: RuntimeMode.PAPER

            if (runtimeMode.requiresPrivateExchangeAccess()) {
                require(!environment["BYBIT_API_KEY"].isNullOrBlank()) {
                    "Bybit key is required outside paper mode."
                }
                require(!environment["BYBIT_API_SECRET"].isNullOrBlank()) {
                    "Bybit secret is required outside paper mode."
                }
            }

            val marketData = MarketDataConfig.fromEnvironment(environment)
            return AppConfig(
                runtimeMode = runtimeMode,
                marketData = marketData,
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
                paperLoop = PaperLoopSettings.fromEnvironment(environment, marketData.timeframes.first()),
            )
        }
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
                        ?: listOf(Timeframe.M15, Timeframe.H1),
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
    val timeframe: Timeframe,
    val candleLimit: Int,
    val intervalSeconds: Long,
) {
    init {
        require(candleLimit in 20..1000) { "Paper loop candle limit must be between 20 and 1000." }
        require(intervalSeconds in 10..86_400) { "Paper loop interval seconds must be between 10 and 86400." }
    }

    companion object {
        fun fromEnvironment(
            environment: Map<String, String>,
            defaultTimeframe: Timeframe,
        ): PaperLoopSettings =
            PaperLoopSettings(
                enabled = environment["BOT_PAPER_LOOP_ENABLED"].toBooleanStrictOrFalse(),
                timeframe =
                    environment["BOT_PAPER_TIMEFRAME"]
                        ?.trim()
                        ?.uppercase()
                        ?.let(Timeframe::valueOf)
                        ?: defaultTimeframe,
                candleLimit = environment["BOT_PAPER_CANDLE_LIMIT"]?.toIntOrNull() ?: 200,
                intervalSeconds = environment["BOT_PAPER_INTERVAL_SECONDS"]?.toLongOrNull() ?: 900,
            )
    }
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
