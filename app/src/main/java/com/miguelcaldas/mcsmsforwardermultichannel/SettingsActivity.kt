package com.miguelcaldas.mcsmsforwardermultichannel

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneNumberUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.addTextChangedListener
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import com.miguelcaldas.mcsmsforwardermultichannel.util.LogUtils
import com.miguelcaldas.mcsmsforwardermultichannel.util.RegexListStore
import com.miguelcaldas.mcsmsforwardermultichannel.util.SecureStore
import com.miguelcaldas.mcsmsforwardermultichannel.util.SenderListStore
import com.miguelcaldas.mcsmsforwardermultichannel.util.SenderMatcher
import com.miguelcaldas.mcsmsforwardermultichannel.util.SmsConfig
import com.miguelcaldas.mcsmsforwardermultichannel.util.SmsChannel
import com.miguelcaldas.mcsmsforwardermultichannel.util.TelegramChannel
import com.miguelcaldas.mcsmsforwardermultichannel.util.TelegramConfig
import com.miguelcaldas.mcsmsforwardermultichannel.util.WhatsAppCloudChannel
import com.miguelcaldas.mcsmsforwardermultichannel.util.WhatsAppConfig

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var rootContainer: View
    private lateinit var sendersContainer: LinearLayout
    private lateinit var regexesContainer: LinearLayout

    private lateinit var waEnabled: MaterialSwitch
    private lateinit var waPhoneNumberIdLayout: TextInputLayout
    private lateinit var waPhoneNumberId: EditText
    private lateinit var waAccessTokenLayout: TextInputLayout
    private lateinit var waAccessToken: EditText
    private lateinit var waRecipientLayout: TextInputLayout
    private lateinit var waRecipient: EditText
    private lateinit var waUseTemplate: MaterialSwitch
    private lateinit var waTemplateName: EditText
    private lateinit var waTemplateLanguage: EditText
    private lateinit var waTemplateNameLayout: TextInputLayout
    private lateinit var waTemplateLanguageLayout: TextInputLayout

    private lateinit var tgEnabled: MaterialSwitch
    private lateinit var tgBotTokenLayout: TextInputLayout
    private lateinit var tgBotToken: EditText
    private lateinit var tgChatId: EditText

    private lateinit var smsEnabled: MaterialSwitch
    private lateinit var smsDestinationLayout: TextInputLayout
    private lateinit var smsDestination: EditText

    private val mainHandler = Handler(Looper.getMainLooper())
    private val persistSendersRunnable = Runnable { persistSenders() }
    private val persistRegexesRunnable = Runnable { persistRegexes() }
    private val persistWaRunnable = Runnable { persistWhatsAppConfig() }
    private val persistTgRunnable = Runnable { persistTelegramConfig() }
    private val persistSmsRunnable = Runnable { persistSmsConfig() }
    private var pendingTemplateText: String = ""
    private val persistTemplateRunnable = Runnable {
        prefs.edit {
            putString("forwardTemplate", pendingTemplateText)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        rootContainer = findViewById(R.id.rootContainer)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            finish()
        }

        val contentScroll = findViewById<View>(R.id.contentScroll)
        ViewCompat.setOnApplyWindowInsetsListener(contentScroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }

        prefs = getSharedPreferences("mc_sms_fwd_wa", Context.MODE_PRIVATE)

        waEnabled = findViewById(R.id.waEnabled)
        waPhoneNumberIdLayout = findViewById(R.id.waPhoneNumberIdLayout)
        waPhoneNumberId = findViewById(R.id.waPhoneNumberId)
        waAccessTokenLayout = findViewById(R.id.waAccessTokenLayout)
        waAccessToken = findViewById(R.id.waAccessToken)
        waRecipientLayout = findViewById(R.id.waRecipientLayout)
        waRecipient = findViewById(R.id.waRecipient)
        waUseTemplate = findViewById(R.id.waUseTemplate)
        waTemplateName = findViewById(R.id.waTemplateName)
        waTemplateLanguage = findViewById(R.id.waTemplateLanguage)
        waTemplateNameLayout = findViewById(R.id.waTemplateNameLayout)
        waTemplateLanguageLayout = findViewById(R.id.waTemplateLanguageLayout)

        tgEnabled = findViewById(R.id.tgEnabled)
        tgBotTokenLayout = findViewById(R.id.tgBotTokenLayout)
        tgBotToken = findViewById(R.id.tgBotToken)
        tgChatId = findViewById(R.id.tgChatId)

        smsEnabled = findViewById(R.id.smsEnabled)
        smsDestinationLayout = findViewById(R.id.smsDestinationLayout)
        smsDestination = findViewById(R.id.smsDestination)

        val forwardTemplate = findViewById<EditText>(R.id.forwardTemplate)
        val openTester = findViewById<MaterialButton>(R.id.openTester)
        val addSenderButton = findViewById<MaterialButton>(R.id.addSenderButton)
        val addRegexButton = findViewById<MaterialButton>(R.id.addRegexButton)
        val sendTestButton = findViewById<MaterialButton>(R.id.sendTestButton)
        val sendTgTestButton = findViewById<MaterialButton>(R.id.sendTgTestButton)
        val sendSmsTestButton = findViewById<MaterialButton>(R.id.sendSmsTestButton)
        sendersContainer = findViewById(R.id.sendersContainer)
        regexesContainer = findViewById(R.id.regexesContainer)

        waEnabled.isChecked = prefs.getBoolean(WhatsAppConfig.KEY_ENABLED, true)
        waPhoneNumberId.setText(prefs.getString(WhatsAppConfig.KEY_PHONE_NUMBER_ID, ""))
        // Write-only: the stored token is never surfaced. Leave the field blank and show a
        // "saved" hint; typing replaces it, an empty field keeps the existing token.
        waAccessToken.setText("")
        applyTokenHelper(waAccessTokenLayout, SecureStore.has(this, SecureStore.KEY_WA_ACCESS_TOKEN), WA_TOKEN_HELP)
        waRecipient.setText(prefs.getString(WhatsAppConfig.KEY_RECIPIENT, ""))
        waTemplateName.setText(prefs.getString(WhatsAppConfig.KEY_TEMPLATE_NAME, ""))
        waTemplateLanguage.setText(prefs.getString(WhatsAppConfig.KEY_TEMPLATE_LANGUAGE, WhatsAppConfig.DEFAULT_TEMPLATE_LANGUAGE))
        val useTemplateInitial = prefs.getBoolean(WhatsAppConfig.KEY_USE_TEMPLATE, true)
        waUseTemplate.isChecked = useTemplateInitial
        updateTemplateFieldsEnabled(useTemplateInitial)
        validateRecipient(waRecipient.text?.toString().orEmpty())

        val waPersistWatcher: (CharSequence?) -> Unit = { _ ->
            mainHandler.removeCallbacks(persistWaRunnable)
            mainHandler.postDelayed(persistWaRunnable, PERSIST_DEBOUNCE_MS)
        }
        waPhoneNumberId.addTextChangedListener {
            waPersistWatcher(it)
        }
        waAccessToken.addTextChangedListener {
            waPersistWatcher(it)
        }
        waRecipient.addTextChangedListener {
            validateRecipient(it?.toString().orEmpty())
            waPersistWatcher(it)
        }
        waTemplateName.addTextChangedListener {
            waPersistWatcher(it)
        }
        waTemplateLanguage.addTextChangedListener {
            waPersistWatcher(it)
        }
        waEnabled.setOnCheckedChangeListener { _, _ ->
            mainHandler.removeCallbacks(persistWaRunnable)
            mainHandler.postDelayed(persistWaRunnable, PERSIST_DEBOUNCE_MS)
        }
        waUseTemplate.setOnCheckedChangeListener { _, checked ->
            updateTemplateFieldsEnabled(checked)
            mainHandler.removeCallbacks(persistWaRunnable)
            mainHandler.postDelayed(persistWaRunnable, PERSIST_DEBOUNCE_MS)
        }

        val tgConfig = TelegramConfig.load(this)
        tgEnabled.isChecked = tgConfig.enabled
        // Write-only: the stored bot token is never surfaced (see WhatsApp note above).
        tgBotToken.setText("")
        applyTokenHelper(tgBotTokenLayout, SecureStore.has(this, SecureStore.KEY_TG_BOT_TOKEN), TG_TOKEN_HELP)
        tgChatId.setText(tgConfig.chatId)
        val tgPersistWatcher: (CharSequence?) -> Unit = { _ ->
            mainHandler.removeCallbacks(persistTgRunnable)
            mainHandler.postDelayed(persistTgRunnable, PERSIST_DEBOUNCE_MS)
        }
        tgBotToken.addTextChangedListener {
            tgPersistWatcher(it)
        }
        tgChatId.addTextChangedListener {
            tgPersistWatcher(it)
        }
        tgEnabled.setOnCheckedChangeListener { _, _ ->
            mainHandler.removeCallbacks(persistTgRunnable)
            mainHandler.postDelayed(persistTgRunnable, PERSIST_DEBOUNCE_MS)
        }

        val smsConfig = SmsConfig.load(prefs)
        smsEnabled.isChecked = smsConfig.enabled
        smsDestination.setText(smsConfig.destination)
        validateSmsDestination(smsConfig.destination)
        val smsPersistWatcher: (CharSequence?) -> Unit = { _ ->
            mainHandler.removeCallbacks(persistSmsRunnable)
            mainHandler.postDelayed(persistSmsRunnable, PERSIST_DEBOUNCE_MS)
        }
        smsDestination.addTextChangedListener {
            validateSmsDestination(it?.toString().orEmpty())
            smsPersistWatcher(it)
        }
        smsEnabled.setOnCheckedChangeListener { _, _ ->
            mainHandler.removeCallbacks(persistSmsRunnable)
            mainHandler.postDelayed(persistSmsRunnable, PERSIST_DEBOUNCE_MS)
        }

        forwardTemplate.setText(prefs.getString("forwardTemplate", ""))
        forwardTemplate.addTextChangedListener { text ->
            pendingTemplateText = text?.toString().orEmpty()
            mainHandler.removeCallbacks(persistTemplateRunnable)
            mainHandler.postDelayed(persistTemplateRunnable, PERSIST_DEBOUNCE_MS)
        }

        SenderListStore.load(prefs).forEach {
            addSenderRow(it)
        }
        RegexListStore.load(prefs).forEach {
            addRegexRow(it)
        }

        addSenderButton.setOnClickListener {
            val row = addSenderRow("")
            row.findViewById<EditText>(R.id.senderEntry).requestFocus()
        }

        addRegexButton.setOnClickListener {
            val row = addRegexRow("")
            row.findViewById<EditText>(R.id.regexEntry).requestFocus()
        }

        openTester.setOnClickListener {
            startActivity(Intent(this, RegexTesterActivity::class.java))
        }

        sendTestButton.setOnClickListener {
            flushPendingWrites()
            sendWhatsAppTestMessage()
        }
        sendTgTestButton.setOnClickListener {
            flushPendingWrites()
            sendTelegramTestMessage()
        }
        sendSmsTestButton.setOnClickListener {
            flushPendingWrites()
            sendSmsTestMessage()
        }
    }

    override fun onPause() {
        super.onPause()
        // Flush any pending debounced writes so leaving the screen never loses input.
        flushPendingWrites()
    }

    private fun flushPendingWrites() {
        listOf(
            persistSendersRunnable,
            persistRegexesRunnable,
            persistWaRunnable,
            persistTgRunnable,
            persistSmsRunnable,
            persistTemplateRunnable,
        ).forEach {
            mainHandler.removeCallbacks(it)
            it.run()
        }
    }

    private fun persistWhatsAppConfig() {
        prefs.edit {
            putBoolean(WhatsAppConfig.KEY_ENABLED, waEnabled.isChecked)
            putString(WhatsAppConfig.KEY_PHONE_NUMBER_ID, waPhoneNumberId.text?.toString()?.trim().orEmpty())
            putString(WhatsAppConfig.KEY_RECIPIENT, waRecipient.text?.toString()?.trim().orEmpty())
            putBoolean(WhatsAppConfig.KEY_USE_TEMPLATE, waUseTemplate.isChecked)
            putString(WhatsAppConfig.KEY_TEMPLATE_NAME, waTemplateName.text?.toString()?.trim().orEmpty())
            putString(WhatsAppConfig.KEY_TEMPLATE_LANGUAGE, waTemplateLanguage.text?.toString()?.trim()?.ifEmpty { WhatsAppConfig.DEFAULT_TEMPLATE_LANGUAGE } ?: WhatsAppConfig.DEFAULT_TEMPLATE_LANGUAGE)
        }
        // Only overwrite the encrypted token when the user actually typed one; an empty
        // field means "keep the saved token".
        val typedToken = waAccessToken.text?.toString()?.trim().orEmpty()
        if (typedToken.isNotEmpty()) {
            SecureStore.write(this, SecureStore.KEY_WA_ACCESS_TOKEN, typedToken)
        }
    }

    private fun persistTelegramConfig() {
        prefs.edit {
            putBoolean(TelegramConfig.KEY_ENABLED, tgEnabled.isChecked)
            putString(TelegramConfig.KEY_CHAT_ID, tgChatId.text?.toString()?.trim().orEmpty())
        }
        // Only overwrite the encrypted token when the user actually typed one.
        val typedToken = tgBotToken.text?.toString()?.trim().orEmpty()
        if (typedToken.isNotEmpty()) {
            SecureStore.write(this, SecureStore.KEY_TG_BOT_TOKEN, typedToken)
        }
    }

    private fun persistSmsConfig() {
        prefs.edit {
            putBoolean(SmsConfig.KEY_ENABLED, smsEnabled.isChecked)
            putString(SmsConfig.KEY_DESTINATION, smsDestination.text?.toString()?.trim().orEmpty())
        }
        warnIfSmsDestinationIsAllowedSender()
    }

    private fun updateTemplateFieldsEnabled(enabled: Boolean) {
        waTemplateNameLayout.isEnabled = enabled
        waTemplateLanguageLayout.isEnabled = enabled
        waTemplateName.isEnabled = enabled
        waTemplateLanguage.isEnabled = enabled
    }

    /**
     * Reflects the write-only token state in the field's helper text: when a secret is
     * already saved we show the "saved" note instead of the default guidance, signalling
     * the user can leave it blank to keep it or type a new value to replace it.
     */
    private fun applyTokenHelper(layout: TextInputLayout, defined: Boolean, defaultHelp: String) {
        layout.helperText = if (defined) TOKEN_SAVED_HELP else defaultHelp
    }

    private fun validateRecipient(text: String) {
        waRecipientLayout.error = when {
            text.isEmpty() -> null
            !PhoneNumberUtils.isWellFormedSmsAddress(text) -> "Doesn't look like an E.164 number"
            else -> null
        }
    }

    private fun validateSmsDestination(text: String) {
        smsDestinationLayout.error = when {
            text.isEmpty() -> null
            !PhoneNumberUtils.isWellFormedSmsAddress(text) -> "Doesn't look like a valid SMS address"
            else -> null
        }
    }

    private fun warnIfSmsDestinationIsAllowedSender() {
        val config = SmsConfig.load(prefs)
        if (!config.enabled || config.destination.isEmpty()) {
            return
        }
        val allowed = SenderListStore.load(prefs).filter { it.isNotBlank() }
        if (allowed.isEmpty()) {
            return
        }
        val iso = SenderMatcher.deviceCountryIso(this)
        if (SenderMatcher.matches(allowed, config.destination, iso)) {
            Snackbar.make(rootContainer, "SMS destination is also an allowed sender. The loop guard will suppress its replies.", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun sendWhatsAppTestMessage() {
        val config = WhatsAppConfig.load(this)
        if (!config.hasCredentials) {
            Snackbar.make(rootContainer, "Set Phone Number ID, access token, and recipient first.", Snackbar.LENGTH_LONG).show()
            return
        }
        if (config.useTemplate && (config.templateName.isBlank() || config.templateLanguage.isBlank())) {
            Snackbar.make(rootContainer, "Template name and language are required when 'Use template' is on.", Snackbar.LENGTH_LONG).show()
            return
        }
        val body = "MC SMS\u2192WhatsApp Test \u2014 manual test send at ${System.currentTimeMillis()}"
        LogUtils.addToLog(this, "REAL SEND [WhatsApp] \u2192 To: ${config.recipient} | Msg: $body (manual test)")
        WhatsAppCloudChannel.send(this, config, body)
        Snackbar.make(rootContainer, "Sending test message\u2026 see Activity log.", Snackbar.LENGTH_SHORT).show()
    }

    private fun sendTelegramTestMessage() {
        val config = TelegramConfig.load(this)
        if (!config.hasCredentials) {
            Snackbar.make(rootContainer, "Set bot token and chat ID first.", Snackbar.LENGTH_LONG).show()
            return
        }
        val body = "MC SMS\u2192Telegram Test \u2014 manual test send at ${System.currentTimeMillis()}"
        LogUtils.addToLog(this, "REAL SEND [Telegram] \u2192 To: chat ${config.chatId} | Msg: $body (manual test)")
        TelegramChannel.send(this, config, body)
        Snackbar.make(rootContainer, "Sending Telegram test\u2026 see Activity log.", Snackbar.LENGTH_SHORT).show()
    }

    private fun sendSmsTestMessage() {
        val config = SmsConfig.load(prefs)
        if (!config.hasCredentials) {
            Snackbar.make(rootContainer, "Set the SMS destination number first.", Snackbar.LENGTH_LONG).show()
            return
        }
        val body = "MC SMS\u2192SMS Test \u2014 manual test send at ${System.currentTimeMillis()}"
        LogUtils.addToLog(this, "REAL SEND [SMS] \u2192 To: ${config.destination} | Msg: $body (manual test)")
        SmsChannel.send(this, config, body)
        Snackbar.make(rootContainer, "Sending SMS test\u2026 see Activity log.", Snackbar.LENGTH_SHORT).show()
    }

    private fun addSenderRow(initialValue: String): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_sender, sendersContainer, false)
        val entry = row.findViewById<EditText>(R.id.senderEntry)
        val delete = row.findViewById<MaterialButton>(R.id.deleteSender)
        val moveUp = row.findViewById<MaterialButton>(R.id.moveUpSender)
        val moveDown = row.findViewById<MaterialButton>(R.id.moveDownSender)

        entry.setText(initialValue)
        // All rows share R.id.senderEntry, so view-state restore would copy the
        // last-focused row's text onto every row on activity recreation.
        entry.isSaveEnabled = false
        entry.addTextChangedListener {
            schedulePersistSenders()
        }

        delete.setOnClickListener {
            sendersContainer.removeView(row)
            schedulePersistSenders()
        }
        moveUp.setOnClickListener {
            moveRow(sendersContainer, row, -1)
            schedulePersistSenders()
        }
        moveDown.setOnClickListener {
            moveRow(sendersContainer, row, +1)
            schedulePersistSenders()
        }

        sendersContainer.addView(row)
        return row
    }

    private fun schedulePersistSenders() {
        mainHandler.removeCallbacks(persistSendersRunnable)
        mainHandler.postDelayed(persistSendersRunnable, PERSIST_DEBOUNCE_MS)
    }

    private fun persistSenders() {
        val list = (0 until sendersContainer.childCount).map { i ->
            sendersContainer.getChildAt(i).findViewById<EditText>(R.id.senderEntry).text?.toString().orEmpty()
        }
        SenderListStore.save(prefs, list)
    }

    private fun addRegexRow(initialValue: String): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_regex, regexesContainer, false)
        val entry = row.findViewById<EditText>(R.id.regexEntry)
        val layout = row.findViewById<TextInputLayout>(R.id.regexEntryLayout)
        val delete = row.findViewById<MaterialButton>(R.id.deleteRegex)
        val moveUp = row.findViewById<MaterialButton>(R.id.moveUpRegex)
        val moveDown = row.findViewById<MaterialButton>(R.id.moveDownRegex)

        entry.setText(initialValue)
        entry.isSaveEnabled = false
        validateRegex(layout, initialValue)
        entry.addTextChangedListener { text ->
            validateRegex(layout, text?.toString().orEmpty())
            schedulePersistRegexes()
        }

        delete.setOnClickListener {
            regexesContainer.removeView(row)
            schedulePersistRegexes()
        }
        moveUp.setOnClickListener {
            moveRow(regexesContainer, row, -1)
            schedulePersistRegexes()
        }
        moveDown.setOnClickListener {
            moveRow(regexesContainer, row, +1)
            schedulePersistRegexes()
        }

        regexesContainer.addView(row)
        return row
    }

    private fun validateRegex(layout: TextInputLayout, pattern: String) {
        layout.error = when {
            pattern.isBlank() -> null
            else -> runCatching { Regex(pattern) }.exceptionOrNull()?.let { "Invalid regex: ${it.message}" }
        }
    }

    private fun schedulePersistRegexes() {
        mainHandler.removeCallbacks(persistRegexesRunnable)
        mainHandler.postDelayed(persistRegexesRunnable, PERSIST_DEBOUNCE_MS)
    }

    private fun persistRegexes() {
        val list = (0 until regexesContainer.childCount).map { i ->
            regexesContainer.getChildAt(i).findViewById<EditText>(R.id.regexEntry).text?.toString().orEmpty()
        }
        RegexListStore.save(prefs, list)
    }

    private fun moveRow(container: LinearLayout, row: View, delta: Int) {
        val index = container.indexOfChild(row)
        val target = index + delta
        if (index < 0 || target < 0 || target >= container.childCount) {
            return
        }
        container.removeViewAt(index)
        container.addView(row, target)
    }

    companion object {
        // 150ms swallows the bursts during typing while still feeling instant on save.
        private const val PERSIST_DEBOUNCE_MS = 150L

        // Default helper texts for the write-only token fields (mirrors the layout XML),
        // restored when no secret is saved yet.
        private const val WA_TOKEN_HELP =
            "System-user token recommended for production. Temporary tokens expire in 24h."
        private const val TG_TOKEN_HELP = "From @BotFather, e.g. 123456:ABCDEF..."
        private const val TOKEN_SAVED_HELP =
            "Saved (hidden). Leave blank to keep it, or type a new value to replace it."
    }
}
