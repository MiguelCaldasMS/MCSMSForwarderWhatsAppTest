package com.miguelcaldas.mcsmsforwardermultichannel.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Posts forwarded SMS bodies to the WhatsApp Cloud API ("Graph") on a single
 * background thread so SmsReceiver can finish its onReceive promptly.
 *
 * Stats are incremented only when the API returns 2xx. The access token never
 * appears in log entries — only the HTTP status code and (if present) Meta's
 * `error.code` / `error.message` summary, with any echoed copy of the token
 * redacted before logging.
 */
object WhatsAppCloudChannel {
    private const val GRAPH_BASE = "https://graph.facebook.com/v21.0"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000

    // Single-thread executor mirrors LogUtils: serialises sends so we never
    // open two simultaneous HTTPS connections for the same incoming SMS, and
    // keeps onReceive return time short on the main thread.
    private val sendExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wa-sender").apply {
            isDaemon = true
        }
    }

    fun send(context: Context, config: WhatsAppConfig, body: String, onComplete: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        if (!config.hasCredentials) {
            LogUtils.addToLog(app, "SEND FAILED [WhatsApp] → missing config")
            onComplete(false)
            return
        }
        sendExecutor.execute {
            var success = false
            try {
                val outcome = runCatching { postSync(config, body) }
                val result = outcome.getOrNull()
                when {
                    result != null && result.success -> {
                        success = true
                        LogUtils.addToLog(app, "SEND OK [WhatsApp] → ${config.recipient} (HTTP ${result.statusCode})")
                    }
                    result != null -> {
                        // Meta echoes a malformed/invalid token back in error.message (code 190),
                        // so redact the access token from the summary before logging.
                        val detail = result.errorSummary?.takeIf { it.isNotBlank() }?.let { " ${redactSecret(it, config.accessToken)}" }.orEmpty()
                        LogUtils.addToLog(app, "SEND FAILED [WhatsApp] → ${config.recipient} (HTTP ${result.statusCode})$detail")
                    }
                    else -> {
                        val msg = redactSecret(outcome.exceptionOrNull()?.message.orEmpty(), config.accessToken)
                        LogUtils.addToLog(app, "SEND FAILED [WhatsApp] → ${config.recipient} (transport) $msg".trimEnd())
                    }
                }
            } finally {
                onComplete(success)
            }
        }
    }

    private data class Outcome(val statusCode: Int, val success: Boolean, val errorSummary: String?)

    /**
     * Removes the access token from a string that is about to be logged. Meta's error.message
     * echoes a malformed token verbatim (code 190) and transport exceptions can embed request
     * details, so any occurrence of the secret is replaced with a fixed placeholder.
     */
    private fun redactSecret(text: String, secret: String): String =
        if (secret.isBlank()) text else text.replace(secret, "[redacted]")

    private fun postSync(config: WhatsAppConfig, body: String): Outcome {
        val url = URL("$GRAPH_BASE/${config.phoneNumberId}/messages")
        val payload = buildPayload(config, body).toString().toByteArray(Charsets.UTF_8)
        val conn = (url.openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            conn.useCaches = false
            conn.setRequestProperty("Authorization", "Bearer ${config.accessToken}")
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            val success = code in 200..299
            val summary = if (success) null else {
                val stream = conn.errorStream ?: conn.inputStream
                val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                summarizeError(raw)
            }
            return Outcome(code, success, summary)
        } finally {
            conn.disconnect()
        }
    }

    private fun buildPayload(config: WhatsAppConfig, body: String): JSONObject {
        // Meta accepts E.164 with or without the leading '+'.
        val recipient = config.recipient.trimStart('+')
        val root = JSONObject()
            .put("messaging_product", "whatsapp")
            .put("recipient_type", "individual")
            .put("to", recipient)
        return if (config.useTemplate) {
            root.put("type", "template")
                .put(
                    "template",
                    JSONObject()
                        .put("name", config.templateName)
                        .put(
                            "language",
                            JSONObject().put("code", config.templateLanguage)
                        )
                        .put(
                            "components",
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "body")
                                    .put(
                                        "parameters",
                                        JSONArray().put(
                                            JSONObject()
                                                .put("type", "text")
                                                .put("text", body)
                                        )
                                    )
                            )
                        )
                )
        } else {
            root.put("type", "text")
                .put(
                    "text",
                    JSONObject()
                        .put("preview_url", false)
                        .put("body", body)
                )
        }
    }

    private fun summarizeError(raw: String): String {
        if (raw.isBlank()) {
            return ""
        }
        return runCatching {
            val err = JSONObject(raw).optJSONObject("error") ?: return raw.take(180)
            val code = err.optInt("code", -1)
            val type = err.optString("type")
            val msg = err.optString("message")
            buildString {
                if (code != -1) {
                    append("code=").append(code).append(' ')
                }
                if (type.isNotEmpty()) {
                    append("type=").append(type).append(' ')
                }
                if (msg.isNotEmpty()) {
                    append("msg=").append(msg)
                }
            }.trim().take(240)
        }.getOrElse { raw.take(180) }
    }
}
