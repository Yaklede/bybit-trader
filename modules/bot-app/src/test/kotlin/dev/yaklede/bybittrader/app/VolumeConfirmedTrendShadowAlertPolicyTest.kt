package dev.yaklede.bybittrader.app

import dev.yaklede.bybittrader.alerts.AlertSeverity
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class VolumeConfirmedTrendShadowAlertPolicyTest :
    StringSpec({
        "suppresses an unchanged shadow failure only after delivery" {
            val policy =
                VolumeConfirmedTrendShadowAlertPolicy(
                    repeatInterval = Duration.ofHours(1),
                    clock = Clock.fixed(Instant.parse("2026-08-07T00:00:00Z"), ZoneOffset.UTC),
                )
            val error = IllegalStateException("missing funding")

            policy.shouldAlert(error) shouldBe true
            policy.shouldAlert(error) shouldBe true
            policy.recordDelivered(error)
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
            policy.recordDelivered(error)

            policy.recordSuccess()

            policy.shouldAlert(error) shouldBe true
        }

        "failure alert explains that the loop will retry instead of claiming it stopped" {
            val alert = IllegalStateException("missing funding").toVolumeConfirmedTrendShadowFailureAlert(Duration.ofSeconds(60))

            alert.severity shouldBe AlertSeverity.WARNING
            alert.body shouldContain "60초 후 자동으로 다시 시도"
            alert.body shouldContain "missing funding"
            alert.body shouldNotContain "중단"
        }
    })
