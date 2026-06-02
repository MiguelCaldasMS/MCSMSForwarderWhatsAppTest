package com.miguelcaldas.mcsmsforwardermultichannel.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted-at-rest storage for channel secrets (the WhatsApp access token and the
 * Telegram bot token). Backed by Jetpack Security's [EncryptedSharedPreferences] over
 * an Android Keystore master key, so the values are never written to the plaintext
 * "mc_sms_fwd_wa" prefs. A legacy plaintext value left there by an older build is
 * migrated into the encrypted store (and wiped from the plaintext file) on first read.
 */
object SecureStore {
    private const val FILE = "mc_sms_fwd_secure"
    private const val LEGACY_PLAIN_FILE = "mc_sms_fwd_wa"

    const val KEY_WA_ACCESS_TOKEN = "waAccessToken"
    const val KEY_TG_BOT_TOKEN = "tgBotToken"

    @Volatile
    private var cached: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: run {
                val app = context.applicationContext
                val masterKey = MasterKey.Builder(app)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    app,
                    FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                ).also { cached = it }
            }
        }
    }

    /** True when a non-empty secret is stored under [key]. */
    fun has(context: Context, key: String): Boolean = read(context, key).isNotEmpty()

    /**
     * Returns the stored secret, transparently migrating a legacy plaintext value out
     * of the unencrypted prefs file on first read. Returns an empty string when unset.
     */
    fun read(context: Context, key: String): String {
        val secure = prefs(context)
        secure.getString(key, null)?.let { return it }
        val legacy = context.applicationContext
            .getSharedPreferences(LEGACY_PLAIN_FILE, Context.MODE_PRIVATE)
        val plain = legacy.getString(key, "").orEmpty().trim()
        if (plain.isNotEmpty()) {
            secure.edit { putString(key, plain) }
            legacy.edit { remove(key) }
        }
        return plain
    }

    /** Stores [value] (trimmed), or removes the secret entirely when [value] is blank. */
    fun write(context: Context, key: String, value: String) {
        prefs(context).edit {
            if (value.isBlank()) remove(key) else putString(key, value.trim())
        }
    }
}
