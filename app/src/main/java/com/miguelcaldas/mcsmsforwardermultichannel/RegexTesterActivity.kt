package com.miguelcaldas.mcsmsforwardermultichannel

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import com.miguelcaldas.mcsmsforwardermultichannel.util.ForwardTemplate
import com.miguelcaldas.mcsmsforwardermultichannel.util.LogUtils
import com.miguelcaldas.mcsmsforwardermultichannel.util.RegexListStore
import com.miguelcaldas.mcsmsforwardermultichannel.util.SenderListStore
import com.miguelcaldas.mcsmsforwardermultichannel.util.SenderMatcher
import com.miguelcaldas.mcsmsforwardermultichannel.util.SmsConfig
import com.miguelcaldas.mcsmsforwardermultichannel.util.TelegramConfig
import com.miguelcaldas.mcsmsforwardermultichannel.util.TextNormalizer
import com.miguelcaldas.mcsmsforwardermultichannel.util.WhatsAppConfig

class RegexTesterActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var rootContainer: View
    private lateinit var sampleSender: EditText
    private lateinit var sampleMessage: EditText
    private lateinit var testPattern: EditText
    private lateinit var testResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_regex_tester)

        rootContainer = findViewById(R.id.rootContainer)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val contentScroll = findViewById<View>(R.id.contentScroll)
        ViewCompat.setOnApplyWindowInsetsListener(contentScroll) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }

        prefs = getSharedPreferences("mc_sms_fwd_wa", Context.MODE_PRIVATE)
        sampleSender = findViewById(R.id.sampleSender)
        sampleMessage = findViewById(R.id.sampleMessage)
        testPattern = findViewById(R.id.testPattern)
        testResult = findViewById(R.id.testResult)

        findViewById<MaterialButton>(R.id.testButton).setOnClickListener { runTest() }
        findViewById<MaterialButton>(R.id.savePatternButton).setOnClickListener { saveCurrentPattern() }
    }

    private fun runTest() {
        val sender = sampleSender.text?.toString()?.trim().orEmpty()
        val message = sampleMessage.text?.toString().orEmpty()
        val pattern = testPattern.text?.toString().orEmpty()

        if (sender.isEmpty() || message.isEmpty() || pattern.isEmpty()) {
            testResult.text = "Fill sender, message, and pattern to test."
            tintResult(ResultTone.NEUTRAL)
            return
        }

        val regex = runCatching { Regex(pattern) }.getOrElse {
            testResult.text = "Invalid regex: ${it.message}"
            tintResult(ResultTone.ERROR)
            return
        }

        val allowedSenders = SenderListStore.load(prefs).filter { it.isNotBlank() }
        val iso = SenderMatcher.deviceCountryIso(this)
        val senderAllowed = allowedSenders.isNotEmpty() &&
            SenderMatcher.matches(allowedSenders, sender, iso)
        val normalized = TextNormalizer.normalizeForMatching(message)
        val patternMatches = regex.containsMatchIn(normalized)

        // Mirror the live pipeline: a channel only fires when it is *operational*
        // (its toggle is on AND its credentials are complete). Evaluate all three so
        // the dry-run never disagrees with SmsReceiver.
        val waConfig = WhatsAppConfig.load(this)
        val tgConfig = TelegramConfig.load(this)
        val smsConfig = SmsConfig.load(prefs)
        val operationalChannels = buildList {
            if (waConfig.isOperational) add("WhatsApp ${waConfig.recipient}")
            if (tgConfig.isOperational) add("Telegram chat ${tgConfig.chatId}")
            if (smsConfig.isOperational) add("SMS ${smsConfig.destination}")
        }

        val template = prefs.getString("forwardTemplate", "").orEmpty()
        val outgoingBody = if (template.isEmpty()) message
        else ForwardTemplate.apply(template, sender, System.currentTimeMillis(), message)

        val pipelineWouldSend = senderAllowed && patternMatches && operationalChannels.isNotEmpty()

        val builder = StringBuilder()
        builder.append("Sender allowed: ").append(if (senderAllowed) "yes" else "no")
            .append(" (against ").append(allowedSenders.size).append(" entries)\n")
        builder.append("Pattern matches: ").append(if (patternMatches) "yes" else "no").append('\n')
        builder.append("Operational channels: ")
            .append(if (operationalChannels.isEmpty()) "none" else operationalChannels.joinToString(", "))
            .append('\n')
        builder.append('\n')
        if (pipelineWouldSend) {
            builder.append("Would forward to ").append(operationalChannels.joinToString(", ")).append(":\n")
            builder.append('"').append(outgoingBody).append('"')
        } else {
            builder.append("Would not forward.")
        }

        testResult.text = builder.toString()
        tintResult(if (pipelineWouldSend) ResultTone.POSITIVE else ResultTone.NEUTRAL)

        if (pipelineWouldSend) {
            LogUtils.addToLog(
                this,
                "FAKE SEND \u2192 to ${operationalChannels.joinToString(", ")}: \"$outgoingBody\""
            )
        }
    }

    private enum class ResultTone { NEUTRAL, POSITIVE, ERROR }

    private fun tintResult(tone: ResultTone) {
        val attr = when (tone) {
            ResultTone.POSITIVE -> androidx.appcompat.R.attr.colorPrimary
            ResultTone.ERROR -> androidx.appcompat.R.attr.colorError
            ResultTone.NEUTRAL -> com.google.android.material.R.attr.colorOnSurface
        }
        testResult.setTextColor(MaterialColors.getColor(testResult, attr))
    }

    private fun saveCurrentPattern() {
        val pattern = testPattern.text?.toString().orEmpty()
        if (pattern.isBlank()) {
            Snackbar.make(rootContainer, "Enter a pattern first", Snackbar.LENGTH_SHORT).show()
            return
        }
        val invalid = runCatching { Regex(pattern) }.exceptionOrNull()
        if (invalid != null) {
            Snackbar.make(
                rootContainer,
                "Invalid regex: ${invalid.message}",
                Snackbar.LENGTH_LONG
            ).show()
            return
        }
        val current = RegexListStore.load(prefs).toMutableList()
        if (current.any { it == pattern }) {
            Snackbar.make(rootContainer, "Pattern already saved", Snackbar.LENGTH_SHORT).show()
            return
        }
        current.add(pattern)
        RegexListStore.save(prefs, current)
        Snackbar.make(rootContainer, "Pattern saved", Snackbar.LENGTH_SHORT).show()
    }
}
