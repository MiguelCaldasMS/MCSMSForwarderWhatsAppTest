package com.miguelcaldas.mcsmsforwardermultichannel.util

import android.content.Context

/**
 * In-memory snapshot of the WhatsApp Cloud API credentials persisted in
 * SharedPreferences. Loaded once per send so the receiver / settings UI can
 * pass an immutable value around.
 *
 * The message template is fixed in code (see [WhatsAppCloudChannel]); it is
 * deliberately not part of this config or the settings UI.
 */
data class WhatsAppConfig(
    val enabled: Boolean,
    val phoneNumberId: String,
    val accessToken: String,
    val recipient: String,
) {
    val hasCredentials: Boolean
        get() = phoneNumberId.isNotBlank() && accessToken.isNotBlank() && recipient.isNotBlank()

    val isOperational: Boolean
        get() = enabled && hasCredentials

    companion object {
        const val PREFS_NAME = "mc_sms_fwd_wa"

        const val KEY_ENABLED = "waEnabled"
        const val KEY_PHONE_NUMBER_ID = "waPhoneNumberId"
        const val KEY_RECIPIENT = "waRecipient"

        fun load(context: Context): WhatsAppConfig {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return WhatsAppConfig(
                // Default true so existing installs keep forwarding to WhatsApp after upgrade.
                enabled = prefs.getBoolean(KEY_ENABLED, true),
                phoneNumberId = prefs.getString(KEY_PHONE_NUMBER_ID, "").orEmpty().trim(),
                // The access token is held encrypted-at-rest, not in the plaintext prefs.
                accessToken = SecureStore.read(context, SecureStore.KEY_WA_ACCESS_TOKEN),
                recipient = prefs.getString(KEY_RECIPIENT, "").orEmpty().trim(),
            )
        }
    }
}
