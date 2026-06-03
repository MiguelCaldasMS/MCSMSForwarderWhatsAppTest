package com.miguelcaldas.mcsmsforwardermultichannel.ui.filters

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import com.miguelcaldas.mcsmsforwardermultichannel.util.RegexListStore
import com.miguelcaldas.mcsmsforwardermultichannel.util.SenderListStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FiltersViewModel(application: Application) : AndroidViewModel(application) {

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

    fun addRule() {
        _rules.value = _rules.value + ""
    }

    fun updateRule(index: Int, value: String) {
        val updated = _rules.value.toMutableList()
        if (index in updated.indices) {
            // RegexListStore does not trim — leading/trailing whitespace can be part of a pattern.
            updated[index] = value
            _rules.value = updated
        }
    }

    fun removeRule(index: Int) {
        val updated = _rules.value.toMutableList()
        if (index in updated.indices) {
            updated.removeAt(index)
            _rules.value = updated
        }
    }

    fun setTemplate(value: String) {
        _template.value = value
    }

    // Adds a validated pattern to the draft rules (used by the regex tester's "Save pattern").
    // Like every other edit here it stays in the draft until save() is called.
    fun addPattern(pattern: String): String {
        if (pattern.isBlank()) {
            return "Enter a pattern first"
        }
        val invalid = runCatching { Regex(pattern) }.exceptionOrNull()
        if (invalid != null) {
            return "Invalid regex: ${invalid.message}"
        }
        if (_rules.value.any { it == pattern }) {
            return "Pattern already added"
        }
        _rules.value = _rules.value + pattern
        return "Pattern added — Save to keep it"
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
    }
}
