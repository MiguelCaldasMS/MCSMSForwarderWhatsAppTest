package com.miguelcaldas.mcsmsforwardermultichannel.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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

    // The message template is fixed in code, not chosen in the UI. It points at the
    // approved "titled_forwarded_sms" template, whose body has two {{n}} parameters:
    // {{1}} is a fixed user (TEMPLATE_USER) and {{2}} is the forwarded SMS body.
    private const val TEMPLATE_NAME = "titled_forwarded_sms"
    private const val TEMPLATE_LANGUAGE = "en_US"
    private const val TEMPLATE_USER = "Miguel"

    // Single-thread executor: serialises sends so we never open two simultaneous
    // HTTPS connections for the same incoming SMS, and keeps onReceive return time
    // short on the main thread.
    private val sendExecutor = singleThreadDaemonExecutor("wa-sender")

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
        val url = "$GRAPH_BASE/${config.phoneNumberId}/messages"
        val payload = buildPayload(config, body).toString().toByteArray(Charsets.UTF_8)
        val headers = mapOf("Authorization" to "Bearer ${config.accessToken}")
        val result = HttpJsonClient.postJson(url, payload, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, headers)
        val summary = if (result.success) null else summarizeError(result.errorBody)
        return Outcome(result.statusCode, result.success, summary)
    }

    private fun buildPayload(config: WhatsAppConfig, body: String): JSONObject {
        // Meta accepts E.164 with or without the leading '+'.
        val recipient = config.recipient.trimStart('+')
        val parameters = JSONArray()
            .put(JSONObject().put("type", "text").put("text", TEMPLATE_USER))
            .put(JSONObject().put("type", "text").put("text", body))
        val template = JSONObject()
            .put("name", TEMPLATE_NAME)
            .put("language", JSONObject().put("code", TEMPLATE_LANGUAGE))
            .put("components", JSONArray().put(JSONObject().put("type", "body").put("parameters", parameters)))
        return JSONObject()
            .put("messaging_product", "whatsapp")
            .put("recipient_type", "individual")
            .put("to", recipient)
            .put("type", "template")
            .put("template", template)
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
