package com.miguelcaldas.mcsmsforwardermultichannel

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar
import com.miguelcaldas.mcsmsforwardermultichannel.util.LogUtils

class LogActivity : AppCompatActivity() {

    private lateinit var logText: TextView
    private lateinit var rootContainer: View
    private lateinit var filterChips: ChipGroup
    private lateinit var prefs: SharedPreferences
    private var currentFilter: Filter = Filter.All

    private enum class Filter { All, SendOk, SendFailed, FakeSend, Boot }

    // Refresh the log view live when a new entry is written while this screen is visible.
    private val logChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == LOGS_KEY) refreshLogs()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        rootContainer = findViewById(R.id.rootContainer)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        prefs = getSharedPreferences("mc_sms_fwd_wa", MODE_PRIVATE)

        val contentScroll = findViewById<View>(R.id.contentScroll)
        ViewCompat.setOnApplyWindowInsetsListener(contentScroll) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }

        logText = findViewById(R.id.logText)
        filterChips = findViewById(R.id.filterChips)
        filterChips.setOnCheckedStateChangeListener { _, ids ->
            currentFilter = when (ids.firstOrNull()) {
                R.id.chipSendOk -> Filter.SendOk
                R.id.chipSendFailed -> Filter.SendFailed
                R.id.chipFakeSend -> Filter.FakeSend
                R.id.chipBoot -> Filter.Boot
                else -> Filter.All
            }
            refreshLogs()
        }

        findViewById<MaterialButton>(R.id.clearLogs).setOnClickListener {
            LogUtils.clearLogs(this)
            refreshLogs()
        }
        findViewById<MaterialButton>(R.id.shareLogs).setOnClickListener { shareLogs() }
    }

    override fun onResume() {
        super.onResume()
        refreshLogs()
        prefs.registerOnSharedPreferenceChangeListener(logChangeListener)
    }

    override fun onPause() {
        super.onPause()
        prefs.unregisterOnSharedPreferenceChangeListener(logChangeListener)
    }

    private fun matchesFilter(entry: String): Boolean = when (currentFilter) {
        Filter.All -> true
        Filter.SendOk -> entry.contains("SEND OK") || entry.contains("REAL SEND \u2192")
        Filter.SendFailed -> entry.contains("FAILED")
        Filter.FakeSend -> entry.contains("FAKE SEND")
        Filter.Boot -> entry.contains("BOOT \u2192") || entry.contains("TILE \u2192")
    }

    private fun refreshLogs() {
        val logs = LogUtils.getLogs(this).filter { matchesFilter(it) }
        if (logs.isEmpty()) {
            logText.text = if (currentFilter == Filter.All) "No logs yet."
            else "No entries match this filter."
            return
        }
        val builder = SpannableStringBuilder()
        logs.forEach { entry ->
            val start = builder.length
            builder.append(entry).append("\n\n")
            val end = builder.length
            // Order matters: FAILED is checked before generic success so
            // "SEND FAILED" is colored red, not green.
            when {
                entry.contains("FAILED") -> builder.setSpan(
                    ForegroundColorSpan("#C62828".toColorInt()),
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                entry.contains("REAL SEND") || entry.contains("SEND OK") -> builder.setSpan(
                    ForegroundColorSpan("#2E7D32".toColorInt()),
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                entry.contains("FAKE SEND") -> builder.setSpan(
                    ForegroundColorSpan("#1565C0".toColorInt()),
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        logText.text = builder
    }

    private fun shareLogs() {
        val logs = LogUtils.getLogs(this).filter { matchesFilter(it) }
        if (logs.isEmpty()) {
            Snackbar.make(rootContainer, "Nothing to share", Snackbar.LENGTH_SHORT).show()
            return
        }
        val payload = logs.joinToString("\n")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "MC SMS Forwarder log")
            putExtra(Intent.EXTRA_TEXT, payload)
        }
        startActivity(Intent.createChooser(send, "Share log"))
    }

    private companion object {
        const val LOGS_KEY = "logs_v2"
    }
}
