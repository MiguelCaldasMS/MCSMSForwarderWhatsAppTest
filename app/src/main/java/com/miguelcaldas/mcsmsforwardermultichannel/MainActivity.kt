package com.miguelcaldas.mcsmsforwardermultichannel

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.miguelcaldas.mcsmsforwardermultichannel.util.ForwardStatsStore
import com.miguelcaldas.mcsmsforwardermultichannel.util.RegexListStore
import com.miguelcaldas.mcsmsforwardermultichannel.util.SenderListStore
import com.miguelcaldas.mcsmsforwardermultichannel.util.SmsConfig
import com.miguelcaldas.mcsmsforwardermultichannel.util.TelegramConfig
import com.miguelcaldas.mcsmsforwardermultichannel.util.WhatsAppConfig
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var rootContainer: View
    private lateinit var statCount: TextView
    private lateinit var statFirst: TextView
    private lateinit var statLast: TextView
    private lateinit var masterSwitch: MaterialSwitch
    private lateinit var masterSwitchSubtitle: TextView
    private lateinit var readinessContainer: LinearLayout

    // Refresh the stat views live when a forward is recorded while this screen is visible.
    private val statsChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in STAT_KEYS) refreshStats()
        }

    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val requestMultiplePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionsMap ->
            refreshReadiness()
            val denied = permissionsMap.filterValues { !it }.keys
            if (denied.isEmpty()) return@registerForActivityResult

            val permanentlyDenied = denied.any { !shouldShowRequestPermissionRationale(it) }
            if (permanentlyDenied) {
                Snackbar.make(
                    rootContainer,
                    "Some permissions were permanently denied. Enable them in app settings.",
                    Snackbar.LENGTH_LONG
                ).setAction("Settings") { openAppSettings() }.show()
            } else {
                val names = denied.joinToString { it.substringAfterLast(".") }
                Snackbar.make(rootContainer, "Needed permissions: $names", Snackbar.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootContainer = findViewById(R.id.rootContainer)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        val contentScroll = findViewById<View>(R.id.contentScroll)
        ViewCompat.setOnApplyWindowInsetsListener(contentScroll) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }

        prefs = getSharedPreferences("mc_sms_fwd_wa", Context.MODE_PRIVATE)

        statCount = findViewById(R.id.statCount)
        statFirst = findViewById(R.id.statFirst)
        statLast = findViewById(R.id.statLast)
        masterSwitch = findViewById(R.id.masterSwitch)
        masterSwitchSubtitle = findViewById(R.id.masterSwitchSubtitle)
        readinessContainer = findViewById(R.id.readinessContainer)

        masterSwitch.isChecked = prefs.getBoolean("master_enabled", true)
        updateMasterSubtitle(masterSwitch.isChecked)
        masterSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit { putBoolean("master_enabled", checked) }
            updateMasterSubtitle(checked)
        }

        findViewById<MaterialButton>(R.id.openSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.openLog).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.resetStats).setOnClickListener {
            confirmResetStats()
        }

        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        // Re-sync the switch in case the Quick Settings tile flipped it while we were paused.
        masterSwitch.isChecked = prefs.getBoolean("master_enabled", true)
        updateMasterSubtitle(masterSwitch.isChecked)
        refreshStats()
        refreshReadiness()
        prefs.registerOnSharedPreferenceChangeListener(statsChangeListener)
    }

    override fun onPause() {
        super.onPause()
        prefs.unregisterOnSharedPreferenceChangeListener(statsChangeListener)
    }

    private fun updateMasterSubtitle(enabled: Boolean) {
        masterSwitchSubtitle.text = if (enabled)
            "Matching messages from allowed senders are forwarded to the enabled channels."
        else
            "Paused. Incoming SMS will be ignored."
    }

    private fun confirmResetStats() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Reset stats?")
            .setMessage("Counter and first/last timestamps will be cleared. Activity log is unaffected.")
            .setPositiveButton("Reset") { _, _ ->
                ForwardStatsStore.reset(this)
                refreshStats()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshStats() {
        val stats = ForwardStatsStore.load(this)
        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val now = System.currentTimeMillis()
        statCount.text = stats.count.toString()
        statFirst.text = if (stats.hasAny) formatAbsoluteAndRelative(fmt, stats.firstMillis, now) else "—"
        statLast.text = if (stats.hasAny) formatAbsoluteAndRelative(fmt, stats.lastMillis, now) else "—"
    }

    private fun formatAbsoluteAndRelative(fmt: DateFormat, millis: Long, now: Long): String {
        val absolute = fmt.format(Date(millis))
        val relative = DateUtils.getRelativeTimeSpanString(
            millis, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE
        )
        return "$absolute  ·  $relative"
    }

    private fun refreshReadiness() {
        readinessContainer.removeAllViews()
        val powerManager = getSystemService(PowerManager::class.java)

        addReadinessRow(
            "Receive SMS permission",
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) ==
                PackageManager.PERMISSION_GRANTED,
            fixLabel = "Grant",
            onFix = { requestMultiplePermissionsLauncher.launch(REQUIRED_PERMISSIONS) }
        )
        addReadinessRow(
            "Notifications permission",
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
            fixLabel = "Grant",
            onFix = { requestMultiplePermissionsLauncher.launch(REQUIRED_PERMISSIONS) }
        )
        addReadinessRow(
            "Battery optimization exempt",
            powerManager?.isIgnoringBatteryOptimizations(packageName) == true,
            fixLabel = "Settings",
            onFix = { requestIgnoreBatteryOptimizations() }
        )

        val waConfig = WhatsAppConfig.load(this)
        val tgConfig = TelegramConfig.load(this)
        val smsConfig = SmsConfig.load(prefs)
        addReadinessRow(
            "At least one channel enabled",
            waConfig.enabled || tgConfig.enabled || smsConfig.enabled,
            fixLabel = "Open",
            onFix = { startActivity(Intent(this, SettingsActivity::class.java)) }
        )
        if (waConfig.enabled) {
            addReadinessRow(
                "WhatsApp Phone Number ID",
                waConfig.phoneNumberId.isNotEmpty(),
                fixLabel = "Open",
                onFix = { startActivity(Intent(this, SettingsActivity::class.java)) }
            )
            addReadinessRow(
                "WhatsApp access token",
                waConfig.accessToken.isNotEmpty(),
                fixLabel = "Open",
                onFix = { startActivity(Intent(this, SettingsActivity::class.java)) }
            )
            addReadinessRow(
                "WhatsApp recipient (E.164)",
                waConfig.recipient.isNotEmpty(),
                fixLabel = "Open",
                onFix = { startActivity(Intent(this, SettingsActivity::class.java)) }
            )
            if (waConfig.useTemplate) {
                addReadinessRow(
                    "WhatsApp template",
                    waConfig.templateName.isNotBlank() && waConfig.templateLanguage.isNotBlank(),
                    fixLabel = "Open",
                    onFix = { startActivity(Intent(this, SettingsActivity::class.java)) }
                )
            }
        }
        if (tgConfig.enabled) {
            addReadinessRow(
                "Telegram bot token",
                tgConfig.botToken.isNotEmpty(),
                fixLabel = "Open",
                onFix = { startActivity(Intent(this, SettingsActivity::class.java)) }
            )
            addReadinessRow(
                "Telegram chat ID",
                tgConfig.chatId.isNotEmpty(),
                fixLabel = "Open",
                onFix = { startActivity(Intent(this, SettingsActivity::class.java)) }
            )
        }
        if (smsConfig.enabled) {
            addReadinessRow(
                "Send SMS permission",
                ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) ==
                    PackageManager.PERMISSION_GRANTED,
                fixLabel = "Grant",
                onFix = { requestMultiplePermissionsLauncher.launch(REQUIRED_PERMISSIONS) }
            )
            addReadinessRow(
                "SMS destination (E.164)",
                smsConfig.destination.isNotEmpty(),
                fixLabel = "Open",
                onFix = { startActivity(Intent(this, SettingsActivity::class.java)) }
            )
        }
        addReadinessRow(
            "At least one allowed sender",
            SenderListStore.load(prefs).any { it.isNotBlank() },
            fixLabel = "Open",
            onFix = { startActivity(Intent(this, SettingsActivity::class.java)) }
        )
        addReadinessRow(
            "At least one regex",
            RegexListStore.load(prefs).isNotEmpty(),
            fixLabel = "Open",
            onFix = { startActivity(Intent(this, SettingsActivity::class.java)) }
        )
    }

    private fun addReadinessRow(
        label: String,
        ok: Boolean,
        fixLabel: String,
        onFix: () -> Unit,
    ) {
        val row = LayoutInflater.from(this)
            .inflate(R.layout.item_readiness, readinessContainer, false) as ViewGroup
        val icon = row.findViewById<ImageView>(R.id.readinessIcon)
        icon.setImageResource(if (ok) R.drawable.ic_check_24 else R.drawable.ic_warning_24)
        icon.setColorFilter(
            MaterialColors.getColor(
                icon,
                if (ok) androidx.appcompat.R.attr.colorPrimary
                else androidx.appcompat.R.attr.colorError
            )
        )
        row.findViewById<TextView>(R.id.readinessLabel).text = label
        val fix = row.findViewById<MaterialButton>(R.id.readinessFix)
        if (ok) {
            fix.visibility = View.GONE
        } else {
            fix.visibility = View.VISIBLE
            fix.text = fixLabel
            fix.setOnClickListener { onFix() }
        }
        readinessContainer.addView(row)
    }

    private fun checkAndRequestPermissions() {
        val toRequest = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) {
            requestMultiplePermissionsLauncher.launch(REQUIRED_PERMISSIONS)
        }
        checkAndRequestBatteryOptimizationExemption()
    }

    private fun checkAndRequestBatteryOptimizationExemption() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return

        MaterialAlertDialogBuilder(this)
            .setTitle("Battery optimization")
            .setMessage(
                "Exempt this app from battery optimization for reliable forwarding? " +
                    "This may increase battery usage."
            )
            .setPositiveButton("Settings") { _, _ -> requestIgnoreBatteryOptimizations() }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun requestIgnoreBatteryOptimizations() {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            "package:$packageName".toUri()
        )
        if (packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0)) != null) {
            startActivity(intent)
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                "package:$packageName".toUri()
            )
        )
    }

    private companion object {
        val STAT_KEYS = setOf("fwd_count", "fwd_first_ts", "fwd_last_ts")
    }
}
