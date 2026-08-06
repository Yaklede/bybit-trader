package dev.yaklede.bybittrader.app.research

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class MakerShadowReplayMainTest :
    StringSpec({
        "sealed raw evidence replays deterministically through queue depletion and a closed position" {
            val root = Files.createTempDirectory("maker-shadow-replay-test")
            try {
                val input = Files.createDirectories(root.resolve("input"))
                val definition = root.resolve("definition.json")
                Files.writeString(definition, replayDefinition())
                writeFixtureArchive(input.resolve("BTCUSDT-20260806T0000Z-test-1.ndjson.gz"))
                Files.writeString(input.resolve("BTCUSDT-20260806T0001Z-test-2.ndjson.gz.part"), "excluded")

                val options =
                    MakerShadowReplayOptions(
                        definitionPath = definition,
                        inputRoot = input,
                        outputPath = root.resolve("result.json"),
                    )
                val first = replayMakerShadow(options)
                val second = replayMakerShadow(options)

                first.status shouldBe "DEVELOPMENT_INSUFFICIENT_EVIDENCE"
                first.automaticExecutionAllowed shouldBe false
                first.input.sealedFileCount shouldBe 1
                first.input.excludedPartFileCount shouldBe 1
                first.input.rawEventCount shouldBe 4
                first.input.normalizedEventCount shouldBe 4
                first.input.gapEventCount shouldBe 0
                first.metrics.finalEquity shouldBe "100.0799"
                first.metrics.netPnl shouldBe "0.0799"
                first.metrics.netReturnPct shouldBe "0.0799"
                first.metrics.grossPnlBeforeFees shouldBe "0.1"
                first.metrics.totalMakerFees shouldBe "0.0201"
                first.metrics.totalTakerFees shouldBe "0"
                first.metrics.inventoryQuantity shouldBe "0"
                first.metrics.closedPositionCount shouldBe 1
                first.metrics.profitableClosedPositions shouldBe 1
                first.metrics.losingClosedPositions shouldBe 0
                first.gates.closedInventory shouldBe true
                first.gates.zeroGapEvents shouldBe true
                first.gates.queueStressComplete shouldBe false
                first.gates.costStressComplete shouldBe false
                first.metrics.ledgerEventSha256 shouldBe second.metrics.ledgerEventSha256
                first.sourceSnapshotSha256 shouldBe second.sourceSnapshotSha256
                first.replayFingerprint shouldBe second.replayFingerprint
            } finally {
                root.toFile().deleteRecursively()
            }
        }

        "stress matrix approves only a complete positive deterministic fixture" {
            val root = Files.createTempDirectory("maker-shadow-matrix-test")
            try {
                val input = Files.createDirectories(root.resolve("input"))
                val definition = root.resolve("definition.json")
                Files.writeString(definition, replayDefinition())
                writeFixtureArchive(input.resolve("BTCUSDT-20260806T0000Z-test-1.ndjson.gz"))
                val options =
                    MakerShadowReplayOptions(
                        definitionPath = definition,
                        inputRoot = input,
                        outputPath = root.resolve("matrix.json"),
                    )

                val first = replayMakerShadowMatrix(options)
                val second = replayMakerShadowMatrix(options)

                first.status shouldBe "DEVELOPMENT_STRESS_COMPLETE_FORWARD_VALIDATION_REQUIRED"
                first.automaticExecutionAllowed shouldBe false
                first.scenarioCount shouldBe 1
                first.scenarios
                    .single()
                    .metrics.netPnl shouldBe "0.0799"
                first.gates.allPassed shouldBe true
                first.matrixFingerprint shouldBe second.matrixFingerprint
                first.sourceSnapshotSha256 shouldBe second.sourceSnapshotSha256
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    })

private fun writeFixtureArchive(path: java.nio.file.Path) {
    val frames =
        listOf(
            fixtureFrame(
                receivedAt = "2026-08-06T00:00:00.010Z",
                quality = "SNAPSHOT_RESET",
                payload =
                    """
                    {"topic":"orderbook.50.BTCUSDT","type":"snapshot","ts":1785974400002,"data":{"s":"BTCUSDT","b":[["100","1"]],"a":[["101","1"]],"u":1,"seq":100},"cts":1785974400000}
                    """.trimIndent(),
            ),
            fixtureFrame(
                receivedAt = "2026-08-06T00:00:01.010Z",
                quality = "VALID",
                payload =
                    """
                    {"topic":"publicTrade.BTCUSDT","type":"snapshot","ts":1785974401000,"data":[{"T":1785974401000,"s":"BTCUSDT","S":"Sell","v":"1.1","p":"100","i":"trade-entry","seq":101}]}
                    """.trimIndent(),
            ),
            fixtureFrame(
                receivedAt = "2026-08-06T00:00:02.010Z",
                quality = "VALID",
                payload =
                    """
                    {"topic":"orderbook.50.BTCUSDT","type":"delta","ts":1785974402002,"data":{"s":"BTCUSDT","b":[],"a":[],"u":2,"seq":102},"cts":1785974402000}
                    """.trimIndent(),
            ),
            fixtureFrame(
                receivedAt = "2026-08-06T00:00:03.010Z",
                quality = "VALID",
                payload =
                    """
                    {"topic":"publicTrade.BTCUSDT","type":"snapshot","ts":1785974403000,"data":[{"T":1785974403000,"s":"BTCUSDT","S":"Buy","v":"1.1","p":"101","i":"trade-exit","seq":103}]}
                    """.trimIndent(),
            ),
        )
    GZIPOutputStream(Files.newOutputStream(path))
        .bufferedWriter(StandardCharsets.UTF_8)
        .use { writer ->
            frames.forEach { frame ->
                writer.write(frame)
                writer.newLine()
            }
        }
}

private fun fixtureFrame(
    receivedAt: String,
    quality: String,
    payload: String,
): String =
    buildJsonObject {
        put("schemaVersion", 1)
        put("localConnectionId", "fixture-connection")
        put("receivedAt", receivedAt)
        put("quality", quality)
        put("rawPayload", payload)
    }.toString()

private fun replayDefinition(): String =
    """
    {
      "schemaVersion": 1,
      "experimentId": "maker-shadow-replay-test",
      "candidateId": "fixture_candidate",
      "researchStage": "DEVELOPMENT_TEST",
      "automaticExecutionAllowed": false,
      "symbol": "BTCUSDT",
      "orderBookDepth": 50,
      "parameters": {
        "initialEquity": "100",
        "orderQuantity": "0.1",
        "maxNotional": "100",
        "queueMultiplier": "1",
        "queueBufferQuantity": "0",
        "minSpreadBps": "0",
        "makerFeeRate": "0.001",
        "takerFeeRate": "0.002",
        "takerExitSlippageBps": "2",
        "maxQuoteAgeMillis": 10000,
        "maxHoldingSeconds": 60,
        "maxEventDelayMillis": 1000,
        "tradeIdCacheSize": 1000
      },
      "evidencePolicy": {
        "source": "FORWARD_RAW_ARCHIVE",
        "allowPartFiles": false,
        "requireContiguousReceiveOrder": true,
        "minimumObservedHours": "0",
        "minimumClosedPositions": 1,
        "requiredQueueStressMultipliers": ["1"],
        "requiredCostStressMultipliers": ["1"]
      }
    }
    """.trimIndent()
