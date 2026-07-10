package dev.yaklede.bybittrader.exchange.bybit

import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.market.flow.TakerFlowBar
import java.io.Reader
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit

enum class MalformedArchiveRowPolicy {
    REJECT,
    SKIP,
}

object BybitTakerFlowArchiveParser {
    fun parse(
        reader: Reader,
        symbol: Symbol,
        malformedRowPolicy: MalformedArchiveRowPolicy = MalformedArchiveRowPolicy.REJECT,
    ): List<TakerFlowBar> =
        reader.useLines { lines ->
            parse(lines, symbol, malformedRowPolicy)
        }

    fun parse(
        lines: Sequence<String>,
        symbol: Symbol,
        malformedRowPolicy: MalformedArchiveRowPolicy = MalformedArchiveRowPolicy.REJECT,
    ): List<TakerFlowBar> {
        val iterator = lines.filter { line -> line.isNotBlank() }.iterator()
        if (!iterator.hasNext()) return emptyList()

        val header = parseCsvLine(iterator.next()).map(::normalizeHeader)
        val columnMap = header.withIndex().associate { (index, value) -> value to index }
        val timestampColumn = columnMap.firstExisting("timestamp", "time", "tradedatems", "executedatms", "createdtime")
        val sideColumn = columnMap.firstExisting("side")
        val quantityColumn = columnMap.firstExisting("size", "qty", "quantity", "execqty")
        val priceColumn = columnMap.firstExisting("price", "execprice")
        val symbolColumn = columnMap.firstExistingOrNull("symbol")

        val buckets = linkedMapOf<Instant, MutableTakerFlowBucket>()
        var lineNumber = 1
        while (iterator.hasNext()) {
            lineNumber += 1
            val row = parseCsvLine(iterator.next())
            try {
                val rowSymbol = symbolColumn?.let { row.getRequired(it, lineNumber) }?.takeIf(String::isNotBlank)
                if (rowSymbol != null && rowSymbol != symbol.value) {
                    throw BybitMarketDataException(
                        "Archive CSV row $lineNumber had symbol $rowSymbol, expected ${symbol.value}.",
                    )
                }
                val openedAt = row.getRequired(timestampColumn, lineNumber).toInstant(lineNumber).truncatedTo(ChronoUnit.MINUTES)
                val side = row.getRequired(sideColumn, lineNumber).trim()
                val quantity = BigDecimal(row.getRequired(quantityColumn, lineNumber))
                val price = BigDecimal(row.getRequired(priceColumn, lineNumber))
                if (quantity < BigDecimal.ZERO || price < BigDecimal.ZERO) {
                    throw BybitMarketDataException("Archive CSV row $lineNumber had negative quantity or price.")
                }
                val notional = quantity.multiply(price)
                val bucket = buckets.getOrPut(openedAt) { MutableTakerFlowBucket(symbol = symbol, openedAt = openedAt) }
                when {
                    side.equals("Buy", ignoreCase = true) -> bucket.addBuy(quantity, notional)
                    side.equals("Sell", ignoreCase = true) -> bucket.addSell(quantity, notional)
                    else -> throw BybitMarketDataException("Archive CSV row $lineNumber had unsupported side $side.")
                }
            } catch (error: RuntimeException) {
                if (malformedRowPolicy == MalformedArchiveRowPolicy.REJECT) {
                    throw if (error is BybitMarketDataException) {
                        error
                    } else {
                        BybitMarketDataException("Archive CSV row $lineNumber was malformed: ${error.message}")
                    }
                }
            }
        }

        return buckets.values
            .map(MutableTakerFlowBucket::toBar)
            .sortedBy { bar -> bar.openedAt }
    }
}

private data class MutableTakerFlowBucket(
    val symbol: Symbol,
    val openedAt: Instant,
    var takerBuyBase: BigDecimal = BigDecimal.ZERO,
    var takerBuyNotional: BigDecimal = BigDecimal.ZERO,
    var takerSellBase: BigDecimal = BigDecimal.ZERO,
    var takerSellNotional: BigDecimal = BigDecimal.ZERO,
    var buyTradeCount: Int = 0,
    var sellTradeCount: Int = 0,
) {
    fun addBuy(
        quantity: BigDecimal,
        notional: BigDecimal,
    ) {
        takerBuyBase += quantity
        takerBuyNotional += notional
        buyTradeCount += 1
    }

    fun addSell(
        quantity: BigDecimal,
        notional: BigDecimal,
    ) {
        takerSellBase += quantity
        takerSellNotional += notional
        sellTradeCount += 1
    }

    fun toBar(): TakerFlowBar =
        TakerFlowBar(
            symbol = symbol,
            openedAt = openedAt,
            takerBuyBase = takerBuyBase.stripTrailingZerosSafe(),
            takerBuyNotional = takerBuyNotional.stripTrailingZerosSafe(),
            takerSellBase = takerSellBase.stripTrailingZerosSafe(),
            takerSellNotional = takerSellNotional.stripTrailingZerosSafe(),
            buyTradeCount = buyTradeCount,
            sellTradeCount = sellTradeCount,
        )
}

private fun BigDecimal.stripTrailingZerosSafe(): BigDecimal =
    stripTrailingZeros().setScale(this.scale().coerceAtLeast(0), RoundingMode.UNNECESSARY)

private fun Map<String, Int>.firstExisting(vararg names: String): Int =
    firstExistingOrNull(*names) ?: throw BybitMarketDataException(
        "Archive CSV header must include one of: ${names.joinToString()}",
    )

private fun Map<String, Int>.firstExistingOrNull(vararg names: String): Int? =
    names.firstNotNullOfOrNull { name -> this[normalizeHeader(name)] }

private fun List<String>.getRequired(
    index: Int,
    lineNumber: Int,
): String =
    getOrNull(index)?.trim()
        ?: throw BybitMarketDataException("Archive CSV row $lineNumber did not contain required column $index.")

private fun String.toInstant(lineNumber: Int): Instant {
    val value = trim()
    return value.toBigDecimalOrNull()?.let { epoch ->
        if (epoch >= EPOCH_SECONDS_THRESHOLD) {
            Instant.ofEpochMilli(epoch.setScale(0, RoundingMode.DOWN).longValueExact())
        } else {
            val seconds = epoch.setScale(0, RoundingMode.FLOOR).longValueExact()
            val nanos =
                epoch
                    .subtract(BigDecimal.valueOf(seconds))
                    .movePointRight(9)
                    .setScale(0, RoundingMode.DOWN)
                    .longValueExact()
            Instant.ofEpochSecond(seconds, nanos)
        }
    } ?: runCatching { Instant.parse(value) }.getOrElse {
        throw BybitMarketDataException("Archive CSV row $lineNumber had invalid timestamp $value.")
    }
}

private fun normalizeHeader(value: String): String =
    value
        .trim()
        .lowercase()
        .filter(Char::isLetterOrDigit)

private fun parseCsvLine(line: String): List<String> {
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    var index = 0
    while (index < line.length) {
        val char = line[index]
        when {
            char == '"' && inQuotes && index + 1 < line.length && line[index + 1] == '"' -> {
                current.append('"')
                index += 1
            }
            char == '"' -> inQuotes = !inQuotes
            char == ',' && !inQuotes -> {
                cells += current.toString()
                current.clear()
            }
            else -> current.append(char)
        }
        index += 1
    }
    cells += current.toString()
    if (inQuotes) {
        throw BybitMarketDataException("Archive CSV row had unterminated quoted field.")
    }
    return cells
}

private val EPOCH_SECONDS_THRESHOLD = BigDecimal("10000000000")
