package dev.yaklede.bybittrader.app

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class VolumeConfirmedTrendShadowAlertPolicyTest :
    StringSpec({
        "suppresses an unchanged shadow failure inside the repeat interval" {
            val policy =
                VolumeConfirmedTrendShadowAlertPolicy(
                    repeatInterval = Duration.ofHours(1),
                    clock = Clock.fixed(Instant.parse("2026-08-07T00:00:00Z"), ZoneOffset.UTC),
                )
            val error = IllegalStateException("missing funding")

            policy.shouldAlert(error) shouldBe true
            policy.shouldAlert(error) shouldBe false
            policy.shouldAlert(IllegalStateException("candle gap")) shouldBe true
        }

        "allows the same shadow failure after a successful evaluation" {
            val policy =
                VolumeConfirmedTrendShadowAlertPolicy(
                    clock = Clock.fixed(Instant.parse("2026-08-07T00:00:00Z"), ZoneOffset.UTC),
                )
            val error = IllegalStateException("missing funding")
            policy.shouldAlert(error)

            policy.recordSuccess()

            policy.shouldAlert(error) shouldBe true
        }
    })
