package dev.yaklede.bybittrader.ledger

import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketRawArchiveStatus
import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketRawEvent
import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketRawEventArchive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.zip.GZIPOutputStream

private const val DEFAULT_FLUSH_EVERY_EVENTS = 250
private const val GZIP_BUFFER_BYTES = 64 * 1024
private val SEGMENT_NAME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm'Z'").withZone(ZoneOffset.UTC)

class GzipNdjsonForwardMarketRawEventArchive(
    private val rootDirectory: Path,
    private val archiveSessionId: String = UUID.randomUUID().toString(),
    private val flushEveryEvents: Int = DEFAULT_FLUSH_EVERY_EVENTS,
) : ForwardMarketRawEventArchive {
    private val lock = Any()
    private var active: ActiveSegment? = null
    private var closed = false
    private var segmentSequence = 0L
    private var archivedEventCount = 0L
    private var gapEventCount = 0L
    private var latestReceivedAt: Instant? = null
    private var lastCompletedSegment: String? = null

    init {
        require(rootDirectory.toString().isNotBlank()) { "Forward market raw archive path must not be blank." }
        require(archiveSessionId.matches(Regex("[A-Za-z0-9_-]+"))) {
            "Forward market raw archive session ID contains unsupported characters."
        }
        require(flushEveryEvents > 0) { "Forward market raw archive flush event count must be positive." }
        Files.createDirectories(rootDirectory)
    }

    override fun append(event: ForwardMarketRawEvent) {
        synchronized(lock) {
            check(!closed) { "Forward market raw archive is closed." }
            val minute = event.receivedAt.truncatedTo(ChronoUnit.MINUTES)
            if (active?.minute != minute) {
                sealActiveSegment()
                active = openSegment(event, minute)
            }
            val segment = requireNotNull(active)
            segment.writer.write(event.toArchiveJson().toString())
            segment.writer.newLine()
            segment.eventCount += 1
            archivedEventCount += 1
            if (event.gapDetected) gapEventCount += 1
            latestReceivedAt = maxOf(latestReceivedAt ?: event.receivedAt, event.receivedAt)
            if (segment.eventCount % flushEveryEvents == 0L) segment.writer.flush()
        }
    }

    override fun flush(now: Instant) {
        synchronized(lock) {
            if (closed) return
            active?.writer?.flush()
            if (active?.minute?.isBefore(now.truncatedTo(ChronoUnit.MINUTES)) == true) {
                sealActiveSegment()
            }
        }
    }

    override fun status(): ForwardMarketRawArchiveStatus =
        synchronized(lock) {
            ForwardMarketRawArchiveStatus(
                enabled = true,
                archiveSessionId = archiveSessionId,
                archivedEventCount = archivedEventCount,
                gapEventCount = gapEventCount,
                latestReceivedAt = latestReceivedAt,
                activeSegment = active?.partPath?.relativeToArchiveRoot(),
                lastCompletedSegment = lastCompletedSegment,
            )
        }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            sealActiveSegment()
            closed = true
        }
    }

    private fun openSegment(
        event: ForwardMarketRawEvent,
        minute: Instant,
    ): ActiveSegment {
        val date = minute.atZone(ZoneOffset.UTC).toLocalDate()
        val directory =
            rootDirectory
                .resolve(date.year.toString())
                .resolve("%02d".format(date.monthValue))
                .resolve("%02d".format(date.dayOfMonth))
        Files.createDirectories(directory)
        segmentSequence += 1
        val symbol = event.symbol.value.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val baseName =
            "$symbol-${SEGMENT_NAME_FORMATTER.format(minute)}-$archiveSessionId-$segmentSequence.ndjson.gz"
        val finalPath = directory.resolve(baseName)
        val partPath = directory.resolve("$baseName.part")
        val output =
            Files.newOutputStream(
                partPath,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
        val gzip = GZIPOutputStream(output, GZIP_BUFFER_BYTES, true)
        return ActiveSegment(
            minute = minute,
            partPath = partPath,
            finalPath = finalPath,
            writer = BufferedWriter(OutputStreamWriter(gzip, StandardCharsets.UTF_8), GZIP_BUFFER_BYTES),
        )
    }

    private fun sealActiveSegment() {
        val segment = active ?: return
        active = null
        segment.writer.close()
        try {
            Files.move(segment.partPath, segment.finalPath, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(segment.partPath, segment.finalPath)
        }
        lastCompletedSegment = segment.finalPath.relativeToArchiveRoot()
    }

    private fun Path.relativeToArchiveRoot(): String =
        rootDirectory
            .toAbsolutePath()
            .normalize()
            .relativize(toAbsolutePath().normalize())
            .toString()
}

private data class ActiveSegment(
    val minute: Instant,
    val partPath: Path,
    val finalPath: Path,
    val writer: BufferedWriter,
    var eventCount: Long = 0,
)

private fun ForwardMarketRawEvent.toArchiveJson(): JsonObject =
    buildJsonObject {
        put("schemaVersion", schemaVersion)
        put("localConnectionId", localConnectionId)
        put("topic", topic)
        put("symbol", symbol.value)
        put("eventKind", eventKind.name)
        put("messageType", messageType.name)
        putNullable("exchangeTimestamp", exchangeTimestamp?.toString())
        putNullable("matchingEngineTimestamp", matchingEngineTimestamp?.toString())
        put("receivedAt", receivedAt.toString())
        putNullable("sequenceStart", sequenceStart)
        putNullable("sequenceEnd", sequenceEnd)
        putNullable("updateId", updateId)
        putNullable("bookEpoch", bookEpoch)
        put("quality", quality.name)
        put("gapDetected", gapDetected)
        put("rawPayload", rawPayload)
    }

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
    key: String,
    value: String?,
) {
    put(key, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
    key: String,
    value: Long?,
) {
    put(key, value?.let(::JsonPrimitive) ?: JsonNull)
}
