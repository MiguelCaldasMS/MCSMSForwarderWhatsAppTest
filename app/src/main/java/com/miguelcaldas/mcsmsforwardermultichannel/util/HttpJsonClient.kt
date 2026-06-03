package com.miguelcaldas.mcsmsforwardermultichannel.util

import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal shared HTTP-JSON POST plumbing for the WhatsApp and Telegram channels.
 *
 * It only owns the transport mechanics (connection setup, timeouts, writing the
 * body, reading the status and error stream). Each channel keeps its own payload
 * building, provider-specific error summarizing, and secret redaction so this stays
 * free of any credential handling.
 */
object HttpJsonClient {

    /**
     * Result of a POST. [errorBody] holds the raw response body **only when the request
     * failed** (non-2xx); on success it is empty. The caller decides how to summarize it.
     */
    data class Result(val statusCode: Int, val success: Boolean, val errorBody: String)

    /**
     * POSTs [payload] as `application/json` to [url] and returns the outcome. Throws on
     * transport failure (DNS, TLS, timeout) so the caller can distinguish a transport
     * error from an HTTP error response.
     */
    fun postJson(url: String, payload: ByteArray, connectTimeoutMs: Int, readTimeoutMs: Int, headers: Map<String, String> = emptyMap()): Result {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.useCaches = false
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (name, value) ->
                conn.setRequestProperty(name, value)
            }
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            val success = code in 200..299
            val errorBody = if (success) {
                ""
            } else {
                val stream = conn.errorStream ?: conn.inputStream
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            }
            return Result(code, success, errorBody)
        } finally {
            conn.disconnect()
        }
    }
}
