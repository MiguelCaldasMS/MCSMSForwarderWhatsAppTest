package com.miguelcaldas.mcsmsforwardermultichannel.util

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import java.util.Locale

object SenderMatcher {

    // Single source of truth for sender allow-list matching. Used by SmsReceiver
    // (live pipeline) and RegexTesterActivity (dry-run) so the two cannot drift.
    fun matches(allowedSenders: List<String>, sender: String, countryIso: String): Boolean =
        allowedSenders.any { entry ->
            entry.equals(sender, ignoreCase = true) || PhoneNumberUtils.areSamePhoneNumber(entry, sender, countryIso)
        }

    fun deviceCountryIso(context: Context): String {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val iso = tm?.networkCountryIso?.takeIf { it.isNotEmpty() } ?: tm?.simCountryIso?.takeIf { it.isNotEmpty() } ?: Locale.getDefault().country
        return iso.lowercase(Locale.ROOT)
    }
}
