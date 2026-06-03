package com.miguelcaldas.mcsmsforwardermultichannel.ui.filters

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import com.miguelcaldas.mcsmsforwardermultichannel.util.ForwardTemplate
import com.miguelcaldas.mcsmsforwardermultichannel.util.RegexListStore
import com.miguelcaldas.mcsmsforwardermultichannel.util.SenderListStore
import com.miguelcaldas.mcsmsforwardermultichannel.util.SenderMatcher
import com.miguelcaldas.mcsmsforwardermultichannel.util.SmsConfig
import com.miguelcaldas.mcsmsforwardermultichannel.util.TelegramConfig
import com.miguelcaldas.mcsmsforwardermultichannel.util.TextNormalizer
import com.miguelcaldas.mcsmsforwardermultichannel.util.WhatsAppConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FiltersViewModel(application: Application) : AndroidViewModel(application) {

    enum class Tone { NEUTRAL, POSITIVE }

    data class TestOutcome(val text: String, val tone: Tone)

    private val prefs = application.getSharedPreferences("mc_sms_fwd_wa", Context.MODE_PRIVATE)

    private val _senders = MutableStateFlow(SenderListStore.load(prefs))
    val senders: StateFlow<List<String>> = _senders.asStateFlow()

    private val _rules = MutableStateFlow(RegexListStore.load(prefs))
    val rules: StateFlow<List<String>> = _rules.asStateFlow()

    private val _template = MutableStateFlow(prefs.getString(KEY_TEMPLATE, "").orEmpty())
    val template: StateFlow<String> = _template.asStateFlow()

    // Edits mutate in-memory draft state only; nothing is persisted until save() is called,
    // mirroring the explicit Save button on the channel detail screens.
    fun addSender(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) {
            return "Enter a sender first"
        }
        if (_senders.value.any { it.equals(value, ignoreCase = true) }) {
            return "Already in the list"
        }
        _senders.value = _senders.value + value
        return null
    }

    fun removeSender(value: String) {
        _senders.value = _senders.value.filterNot { it == value }
    }

    fun addRule(raw: String): String? {
        // RegexListStore does not trim — leading/trailing whitespace can be part of a pattern,
        // so only an entirely empty entry is rejected.
        if (raw.isEmpty()) {
            return "Enter a rule first"
        }
        if (_rules.value.any { it == raw }) {
            return "Already in the list"
        }
        if (runCatching { Regex(raw) }.isFailure) {
            return "Not a valid pattern"
        }
        _rules.value = _rules.value + raw
        return null
    }

    fun removeRule(value: String) {
        _rules.value = _rules.value.filterNot { it == value }
    }

    fun setTemplate(value: String) {
        _template.value = value
    }

    // The message has no default — it starts blank unless a previous test was run, in which
    // case the last-tested message is restored.
    fun lastTestMessage(): String {
        return prefs.getString(KEY_LAST_TEST_MESSAGE, "").orEmpty()
    }

    // The sender defaults to the first phone-like entry in the (draft) senders list, unless a
    // previous test already used a sender, in which case that saved value wins.
    fun defaultTestSender(): String {
        val saved = prefs.getString(KEY_LAST_TEST_SENDER, null)
        if (!saved.isNullOrBlank()) {
            return saved
        }
        val senders = _senders.value.filter { it.isNotBlank() }
        return senders.firstOrNull { looksLikePhone(it) } ?: senders.firstOrNull() ?: ""
    }

    private fun looksLikePhone(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.startsWith("+") || trimmed.firstOrNull()?.isDigit() == true
    }

    // Dry-run mirror of SmsReceiver's pipeline, evaluated against the *currently displayed*
    // (possibly unsaved) draft filters. Keep this in lockstep with the live receiver: the
    // sender must be allowed, the message must match at least one rule, and at least one
    // channel must be operational (toggle on AND credentials complete). Nothing is sent.
    fun runTest(senderRaw: String, messageRaw: String): TestOutcome {
        val context = getApplication<Application>()
        val sender = senderRaw.trim()
        val message = messageRaw

        if (sender.isEmpty() || message.isEmpty()) {
            return TestOutcome("Enter a sender and a message to test.", Tone.NEUTRAL)
        }

        // Remember the inputs so the next test pre-fills with what was last used.
        prefs.edit {
            putString(KEY_LAST_TEST_SENDER, sender)
            putString(KEY_LAST_TEST_MESSAGE, message)
        }

        val allowedSenders = _senders.value.filter { it.isNotBlank() }
        val rules = _rules.value.filter { it.isNotBlank() }
        val template = _template.value

        val iso = SenderMatcher.deviceCountryIso(context)
        val senderAllowed = allowedSenders.isNotEmpty() && SenderMatcher.matches(allowedSenders, sender, iso)

        val normalized = TextNormalizer.normalizeForMatching(message)
        // Same as the receiver: compile each rule at most once, silently skip invalid ones, match any.
        val ruleMatches = rules.isNotEmpty() && rules.asSequence()
            .mapNotNull { runCatching { Regex(it) }.getOrNull() }
            .any { it.containsMatchIn(normalized) }

        val waConfig = WhatsAppConfig.load(context)
        val tgConfig = TelegramConfig.load(context)
        val smsConfig = SmsConfig.load(prefs)
        val operationalChannels = buildList {
            if (waConfig.isOperational) {
                add("WhatsApp ${waConfig.recipient}")
            }
            if (tgConfig.isOperational) {
                add("Telegram chat ${tgConfig.chatId}")
            }
            if (smsConfig.isOperational) {
                add("SMS ${smsConfig.destination}")
            }
        }

        val outgoingBody = if (template.isEmpty()) message else ForwardTemplate.apply(template, sender, System.currentTimeMillis(), message)
        val wouldSend = senderAllowed && ruleMatches && operationalChannels.isNotEmpty()

        val builder = StringBuilder()
        builder.append("Sender allowed: ").append(if (senderAllowed) "yes" else "no").append(" (against ").append(allowedSenders.size).append(" entries)\n")
        builder.append("Message matches a rule: ").append(if (ruleMatches) "yes" else "no").append(" (against ").append(rules.size).append(" rules)\n")
        builder.append("Operational channels: ").append(if (operationalChannels.isEmpty()) "none" else operationalChannels.joinToString(", ")).append('\n')
        builder.append('\n')
        if (wouldSend) {
            builder.append("Would forward to ").append(operationalChannels.joinToString(", ")).append(":\n")
            builder.append('"').append(outgoingBody).append('"')
        } else {
            builder.append("Would not forward.")
        }

        return TestOutcome(builder.toString(), if (wouldSend) Tone.POSITIVE else Tone.NEUTRAL)
    }

    fun save() {
        SenderListStore.save(prefs, _senders.value)
        RegexListStore.save(prefs, _rules.value)
        prefs.edit {
            putString(KEY_TEMPLATE, _template.value)
        }
    }

    private companion object {
        const val KEY_TEMPLATE = "forwardTemplate"
        const val KEY_LAST_TEST_SENDER = "lastTestSender"
        const val KEY_LAST_TEST_MESSAGE = "lastTestMessage"
    }
}
