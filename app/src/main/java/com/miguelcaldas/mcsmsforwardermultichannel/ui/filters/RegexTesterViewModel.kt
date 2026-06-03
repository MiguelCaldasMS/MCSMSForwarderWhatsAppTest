package com.miguelcaldas.mcsmsforwardermultichannel.ui.filters

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.miguelcaldas.mcsmsforwardermultichannel.util.ForwardTemplate
import com.miguelcaldas.mcsmsforwardermultichannel.util.LogUtils
import com.miguelcaldas.mcsmsforwardermultichannel.util.SenderMatcher
import com.miguelcaldas.mcsmsforwardermultichannel.util.SmsConfig
import com.miguelcaldas.mcsmsforwardermultichannel.util.TelegramConfig
import com.miguelcaldas.mcsmsforwardermultichannel.util.TextNormalizer
import com.miguelcaldas.mcsmsforwardermultichannel.util.WhatsAppConfig

class RegexTesterViewModel(application: Application) : AndroidViewModel(application) {

    enum class Tone { NEUTRAL, POSITIVE, ERROR }

    data class TestOutcome(val text: String, val tone: Tone)

    private val prefs = application.getSharedPreferences("mc_sms_fwd_wa", Context.MODE_PRIVATE)

    // Dry-run mirror of SmsReceiver's pipeline. Keep this in lockstep with the live receiver:
    // a channel only fires when it is *operational* (toggle on AND credentials complete).
    // The allowed senders and forwarding template are passed in from the Filters screen's
    // current (possibly unsaved) draft, so the test reflects what's on screen, not what's saved.
    fun runTest(
        senderRaw: String,
        messageRaw: String,
        patternRaw: String,
        allowedSendersRaw: List<String>,
        template: String,
    ): TestOutcome {
        val context = getApplication<Application>()
        val sender = senderRaw.trim()
        val message = messageRaw

        if (sender.isEmpty() || message.isEmpty() || patternRaw.isEmpty()) {
            return TestOutcome("Fill sender, message, and pattern to test.", Tone.NEUTRAL)
        }

        val regex = runCatching { Regex(patternRaw) }.getOrElse {
            return TestOutcome("Invalid regex: ${it.message}", Tone.ERROR)
        }

        val allowedSenders = allowedSendersRaw.filter { it.isNotBlank() }
        val iso = SenderMatcher.deviceCountryIso(context)
        val senderAllowed = allowedSenders.isNotEmpty() && SenderMatcher.matches(allowedSenders, sender, iso)
        val normalized = TextNormalizer.normalizeForMatching(message)
        val patternMatches = regex.containsMatchIn(normalized)

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

        val pipelineWouldSend = senderAllowed && patternMatches && operationalChannels.isNotEmpty()

        val builder = StringBuilder()
        builder.append("Sender allowed: ").append(if (senderAllowed) "yes" else "no").append(" (against ").append(allowedSenders.size).append(" entries)\n")
        builder.append("Pattern matches: ").append(if (patternMatches) "yes" else "no").append('\n')
        builder.append("Operational channels: ").append(if (operationalChannels.isEmpty()) "none" else operationalChannels.joinToString(", ")).append('\n')
        builder.append('\n')
        if (pipelineWouldSend) {
            builder.append("Would forward to ").append(operationalChannels.joinToString(", ")).append(":\n")
            builder.append('"').append(outgoingBody).append('"')
        } else {
            builder.append("Would not forward.")
        }

        if (pipelineWouldSend) {
            LogUtils.addToLog(context, "FAKE SEND \u2192 to ${operationalChannels.joinToString(", ")}: \"$outgoingBody\"")
        }

        return TestOutcome(builder.toString(), if (pipelineWouldSend) Tone.POSITIVE else Tone.NEUTRAL)
    }
}
