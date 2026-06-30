package dev.yaklede.bybittrader.app

data class AppConfig(
    val runtimeMode: RuntimeMode,
    val api: ApiConfig,
    val database: DatabaseConfig,
    val alerts: AlertsConfig,
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

            return AppConfig(
                runtimeMode = runtimeMode,
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
            )
        }
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
