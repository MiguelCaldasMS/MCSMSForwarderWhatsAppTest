package com.miguelcaldas.mcsmsforwardermultichannel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.miguelcaldas.mcsmsforwardermultichannel.ui.log.LogScreen
import com.miguelcaldas.mcsmsforwardermultichannel.ui.theme.MCSmsForwarderTheme

class LogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MCSmsForwarderTheme {
                LogScreen(onBack = { finish() })
            }
        }
    }
}
