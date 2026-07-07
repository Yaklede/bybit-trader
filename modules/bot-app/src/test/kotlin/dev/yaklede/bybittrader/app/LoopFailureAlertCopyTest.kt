package dev.yaklede.bybittrader.app

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class LoopFailureAlertCopyTest :
    StringSpec({
        "loop failure alert includes actionable exception detail" {
            val body =
                loopFailureAlertBody(
                    loopName = "실거래",
                    error =
                        IllegalArgumentException(
                            "At least 17281 candles are required for volume-flow-aggressive-absa_final_us_v1.",
                        ),
                )

            body shouldContain "오류: IllegalArgumentException"
            body shouldContain "At least 17281 candles are required"
            body shouldContain "히스토리 캔들 동기화"
        }

        "loop failure alert redacts credential related detail" {
            val body =
                loopFailureAlertBody(
                    loopName = "실거래",
                    error = IllegalArgumentException("api secret invalid. bearer abc.def.ghi"),
                )

            body shouldContain "[redacted]"
            body shouldNotContain "secret"
            body shouldNotContain "abc.def.ghi"
        }
    })
