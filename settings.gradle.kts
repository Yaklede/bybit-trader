pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "bybit-trader"

include(":modules:bot-domain")
include(":modules:bot-strategy")
include(":modules:bot-exchange-bybit")
include(":modules:bot-ledger")
include(":modules:bot-engine")
include(":modules:bot-api")
include(":modules:bot-alerts")
include(":modules:bot-app")
