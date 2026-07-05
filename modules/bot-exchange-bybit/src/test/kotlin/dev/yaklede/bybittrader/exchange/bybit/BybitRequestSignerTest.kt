package dev.yaklede.bybittrader.exchange.bybit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class BybitRequestSignerTest :
    StringSpec({
        "signGet follows Bybit documented GET prehash rule" {
            val signer =
                BybitRequestSigner(
                    keyId = "XXXXXXXXXX",
                    signingCredential = "XXXXXXXXXX",
                    recvWindowMillis = 5_000,
                )

            val signature =
                signer
                    .signForTest(
                        timestampMillis = "1658384314791",
                        payload = "category=option&symbol=BTC-29JUL22-25000-C",
                    ).signature

            signature shouldBe "c00720f96c5934ca7057ac28ae65b823f83b8b67a8fe784e7795ca0fa3c148ec"
        }

        "signPost follows Bybit documented POST prehash rule" {
            val signer =
                BybitRequestSigner(
                    keyId = "XXXXXXXXXX",
                    signingCredential = "XXXXXXXXXX",
                    recvWindowMillis = 5_000,
                )

            val signature =
                signer
                    .signForTest(
                        timestampMillis = "1658385579423",
                        payload = """{"category": "option"}""",
                    ).signature

            signature shouldBe "fbb4648b585b3e62de2c4eaecc8f3b6066aa18e98cc300eaff4d87f15b55724a"
        }
    })
