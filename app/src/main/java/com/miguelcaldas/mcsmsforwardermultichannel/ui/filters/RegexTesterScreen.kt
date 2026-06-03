package com.miguelcaldas.mcsmsforwardermultichannel.ui.filters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.miguelcaldas.mcsmsforwardermultichannel.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegexTesterScreen(
    filtersViewModel: FiltersViewModel,
    onBack: () -> Unit,
    viewModel: RegexTesterViewModel = viewModel(),
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val senders by filtersViewModel.senders.collectAsStateWithLifecycle()
    val template by filtersViewModel.template.collectAsStateWithLifecycle()

    var sender by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    var pattern by rememberSaveable { mutableStateOf("") }
    var outcome by remember { mutableStateOf<RegexTesterViewModel.TestOutcome?>(null) }

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Regex tester") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back_24), contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Dry-run a sample message against your filters. This evaluates the same pipeline the live receiver uses — it never sends anything.",
                style = MaterialTheme.typography.bodyMedium,
            )

            OutlinedTextField(
                value = sender,
                onValueChange = { sender = it },
                label = { Text("Sample sender") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                label = { Text("Sample message") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pattern,
                onValueChange = { pattern = it },
                label = { Text("Pattern to test") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        val result = filtersViewModel.addPattern(pattern)
                        scope.launch {
                            snackbarHostState.showSnackbar(result)
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Save pattern")
                }
                Button(
                    onClick = {
                        outcome = viewModel.runTest(sender, message, pattern, senders, template)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Run test")
                }
            }

            outcome?.let { result ->
                Card {
                    SelectionContainer(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                        Text(
                            result.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = toneColor(result.tone),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun toneColor(tone: RegexTesterViewModel.Tone): Color {
    return when (tone) {
        RegexTesterViewModel.Tone.POSITIVE -> MaterialTheme.colorScheme.primary
        RegexTesterViewModel.Tone.ERROR -> MaterialTheme.colorScheme.error
        RegexTesterViewModel.Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
    }
}
