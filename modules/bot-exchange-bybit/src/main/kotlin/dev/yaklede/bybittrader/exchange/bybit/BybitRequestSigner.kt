package dev.yaklede.bybittrader.exchange.bybit

import java.time.Clock
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class BybitRequestSigner(
    private val keyId: String,
    private val signingCredential: String,
    private val recvWindowMillis: Long = 5_000,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(keyId.isNotBlank()) { "Bybit API key must not be blank." }
        require(signingCredential.isNotBlank()) { "Bybit API secret must not be blank." }
        require(recvWindowMillis in 1_000..60_000) { "Bybit recv window must be between 1000 and 60000 ms." }
    }

    fun signGet(queryString: String): BybitAuthHeaders {
        val timestamp = clock.millis().toString()
        return sign(timestamp = timestamp, payload = queryString)
    }

    fun signPost(jsonBody: String): BybitAuthHeaders {
        val timestamp = clock.millis().toString()
        return sign(timestamp = timestamp, payload = jsonBody)
    }

    fun signForTest(
        timestampMillis: String,
        payload: String,
    ): BybitAuthHeaders = sign(timestamp = timestampMillis, payload = payload)

    private fun sign(
        timestamp: String,
        payload: String,
    ): BybitAuthHeaders {
        val recvWindow = recvWindowMillis.toString()
        val preHash = timestamp + keyId + recvWindow + payload
        return BybitAuthHeaders(
            keyId = keyId,
            timestampMillis = timestamp,
            recvWindowMillis = recvWindow,
            signature = hmacSha256Hex(preHash, signingCredential),
        )
    }
}

data class BybitAuthHeaders(
    val keyId: String,
    val timestampMillis: String,
    val recvWindowMillis: String,
    val signature: String,
)

private fun hmacSha256Hex(
    payload: String,
    signingCredential: String,
): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(signingCredential.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return mac
        .doFinal(payload.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
