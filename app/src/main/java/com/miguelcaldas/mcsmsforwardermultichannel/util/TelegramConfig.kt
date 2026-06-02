package com.miguelcaldas.mcsmsforwardermultichannel.util

import android.content.Context

/**
 * Immutable snapshot of the Telegram bot credentials persisted in SharedPreferences.
 * Telegram is the second outbound channel and is opt-in: it ships disabled.
 */
data class TelegramConfig(
    val enabled: Boolean,
    val botToken: String,
    val chatId: String,
) {
    val hasCredentials: Boolean
        get() = botToken.isNotBlank() && chatId.isNotBlank()

    val isOperational: Boolean
        get() = enabled && hasCredentials

    companion object {
        const val PREFS_NAME = "mc_sms_fwd_wa"

        const val KEY_ENABLED = "tgEnabled"
        const val KEY_CHAT_ID = "tgChatId"

        fun load(context: Context): TelegramConfig {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return TelegramConfig(
                enabled = prefs.getBoolean(KEY_ENABLED, false),
                // The bot token is held encrypted-at-rest, not in the plaintext prefs.
                botToken = SecureStore.read(context, SecureStore.KEY_TG_BOT_TOKEN),
                chatId = prefs.getString(KEY_CHAT_ID, "").orEmpty().trim(),
            )
        }
    }
}
