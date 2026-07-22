package dev.yaklede.bybittrader.ledger

import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketDataQuality
import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketEventKind
import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketMessageType
import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketRawEvent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import java.util.zip.GZIPInputStream

class GzipNdjsonForwardMarketRawEventArchiveTest :
    StringSpec({
        "archives public messages in sealed minute gzip segments with quality metadata" {
            val directory = Files.createTempDirectory("forward-market-archive-test")
            try {
                val archive =
                    GzipNdjsonForwardMarketRawEventArchive(
                        rootDirectory = directory,
                        archiveSessionId = "session-test",
                        flushEveryEvents = 1,
                    )
                archive.append(rawEvent("2026-07-10T00:00:01Z", 100, ForwardMarketDataQuality.SNAPSHOT_RESET))
                archive.append(rawEvent("2026-07-10T00:00:02Z", 99, ForwardMarketDataQuality.NON_MONOTONIC_UPDATE_ID))

                val activeStatus = archive.status()
                activeStatus.archivedEventCount shouldBe 2L
                activeStatus.gapEventCount shouldBe 1L
                activeStatus.activeSegment?.endsWith(".part") shouldBe true
                activeStatus.lastCompletedSegment shouldBe null

                archive.flush(Instant.parse("2026-07-10T00:01:00Z"))

                val sealedStatus = archive.status()
                sealedStatus.activeSegment shouldBe null
                sealedStatus.lastCompletedSegment?.endsWith(".ndjson.gz") shouldBe true
                val segment = directory.resolve(requireNotNull(sealedStatus.lastCompletedSegment))
                Files.exists(segment) shouldBe true
                val lines = readGzipLines(segment)
                lines.size shouldBe 2
                lines.first().contains("\"quality\":\"SNAPSHOT_RESET\"") shouldBe true
                lines.last().contains("\"gapDetected\":true") shouldBe true
                lines.last().contains("\"rawPayload\":\"{\\\"topic\\\":\\\"orderbook.50.BTCUSDT\\\"}\"") shouldBe true

                archive.close()
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

        "closing the archive seals its active segment and is idempotent" {
            val directory = Files.createTempDirectory("forward-market-close-test")
            try {
                val archive =
                    GzipNdjsonForwardMarketRawEventArchive(
                        rootDirectory = directory,
                        archiveSessionId = "session-close",
                    )
                archive.append(rawEvent("2026-07-10T00:00:01Z", 100, ForwardMarketDataQuality.SNAPSHOT_RESET))

                archive.close()
                archive.close()

                archive.status().lastCompletedSegment?.endsWith(".ndjson.gz") shouldBe true
                archive.status().activeSegment shouldBe null
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
    })

private fun rawEvent(
    receivedAt: String,
    updateId: Long,
    quality: ForwardMarketDataQuality,
): ForwardMarketRawEvent =
    ForwardMarketRawEvent(
        localConnectionId = "connection-test",
        topic = "orderbook.50.BTCUSDT",
        symbol = Symbol("BTCUSDT"),
        eventKind = ForwardMarketEventKind.ORDER_BOOK,
        messageType = ForwardMarketMessageType.SNAPSHOT,
        exchangeTimestamp = Instant.parse(receivedAt),
        matchingEngineTimestamp = Instant.parse(receivedAt),
        receivedAt = Instant.parse(receivedAt),
        sequenceStart = updateId * 10,
        sequenceEnd = updateId * 10,
        updateId = updateId,
        bookEpoch = 1,
        quality = quality,
        rawPayload = "{\"topic\":\"orderbook.50.BTCUSDT\"}",
    )

private fun readGzipLines(path: java.nio.file.Path): List<String> =
    GZIPInputStream(Files.newInputStream(path))
        .bufferedReader(StandardCharsets.UTF_8)
        .use { reader -> reader.readLines() }
