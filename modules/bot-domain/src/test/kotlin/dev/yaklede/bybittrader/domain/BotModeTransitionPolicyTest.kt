package dev.yaklede.bybittrader.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class BotModeTransitionPolicyTest :
    StringSpec({
        val policy = BotModeTransitionPolicy()

        "pause new entries keeps exit management mode separate from full pause" {
            policy.nextMode(BotMode.RUNNING, ControlAction.PAUSE_NEW_ENTRIES) shouldBe
                BotMode.PAUSE_NEW_ENTRIES
        }

        "pause all blocks strategy-created order flow" {
            policy.nextMode(BotMode.RUNNING, ControlAction.PAUSE_ALL) shouldBe BotMode.PAUSE_ALL
        }

        "resume returns to running state" {
            policy.nextMode(BotMode.PAUSE_ALL, ControlAction.RESUME) shouldBe
                BotMode.RUNNING
        }

        "emergency stop dominates pause commands" {
            policy.nextMode(BotMode.EMERGENCY_STOP, ControlAction.PAUSE_ALL) shouldBe
                BotMode.EMERGENCY_STOP
        }
    })
