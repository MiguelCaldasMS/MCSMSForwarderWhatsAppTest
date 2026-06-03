package com.miguelcaldas.mcsmsforwardermultichannel.ui.filters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.miguelcaldas.mcsmsforwardermultichannel.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FiltersScreen(onBack: () -> Unit, viewModel: FiltersViewModel = viewModel()) {
    val senders by viewModel.senders.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val template by viewModel.template.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Seed the test inputs once: message from the last test (blank otherwise), sender from the
    // last test or the first phone in the current senders list.
    val initialTestSender = remember { viewModel.defaultTestSender() }
    val initialTestMessage = remember { viewModel.lastTestMessage() }

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Filters") },
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
            SendersCard(
                senders = senders,
                onAdd = { raw ->
                    val error = viewModel.addSender(raw)
                    if (error != null) {
                        scope.launch {
                            snackbarHostState.showSnackbar(error)
                        }
                    }
                    error == null
                },
                onRemove = { value ->
                    viewModel.removeSender(value)
                },
            )

            RulesCard(
                rules = rules,
                onAdd = { raw ->
                    val error = viewModel.addRule(raw)
                    if (error != null) {
                        scope.launch {
                            snackbarHostState.showSnackbar(error)
                        }
                    }
                    error == null
                },
                onRemove = { value ->
                    viewModel.removeRule(value)
                },
            )

            TemplateCard(
                template = template,
                onChange = { value ->
                    viewModel.setTemplate(value)
                },
            )

            TestCard(
                initialSender = initialTestSender,
                initialMessage = initialTestMessage,
                onRunTest = { sender, message ->
                    viewModel.runTest(sender, message)
                },
            )

            Button(
                onClick = {
                    viewModel.save()
                    scope.launch {
                        snackbarHostState.showSnackbar("Filters saved")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendersCard(senders: List<String>, onAdd: (String) -> Boolean, onRemove: (String) -> Unit) {
    var newSender by rememberSaveable { mutableStateOf("") }

    fun commit() {
        if (onAdd(newSender)) {
            newSender = ""
        }
    }

    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Allowed senders", style = MaterialTheme.typography.titleMedium)
            Text(
                "Phone numbers (with country code, e.g. +35191XXXXXX) or short names (e.g. AMAZON, MB WAY). Only messages from these senders are forwarded.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (senders.isEmpty()) {
                Text(
                    "No senders yet — nothing will be forwarded until you add at least one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    senders.forEach { sender ->
                        InputChip(
                            selected = false,
                            onClick = {
                                onRemove(sender)
                            },
                            label = { Text(sender, style = MaterialTheme.typography.bodyLarge) },
                            trailingIcon = {
                                Icon(painterResource(R.drawable.ic_close_24), contentDescription = "Remove $sender")
                            },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newSender,
                    onValueChange = { newSender = it },
                    label = { Text("Add sender") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commit() }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { commit() }) {
                    Text("Add")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RulesCard(rules: List<String>, onAdd: (String) -> Boolean, onRemove: (String) -> Unit) {
    var newRule by rememberSaveable { mutableStateOf("") }

    fun commit() {
        if (onAdd(newRule)) {
            newRule = ""
        }
    }

    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Message format rules", style = MaterialTheme.typography.titleMedium)
            Text(
                "Regular expressions matched against the message body (accent- and case-insensitive). A message forwards if it matches any rule. With no rules, nothing is forwarded.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (rules.isEmpty()) {
                Text(
                    "No rules yet — nothing will be forwarded until you add at least one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rules.forEach { rule ->
                        InputChip(
                            selected = false,
                            onClick = {
                                onRemove(rule)
                            },
                            label = { Text(rule, style = MaterialTheme.typography.bodyLarge) },
                            trailingIcon = {
                                Icon(painterResource(R.drawable.ic_close_24), contentDescription = "Remove $rule")
                            },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newRule,
                    onValueChange = { newRule = it },
                    label = { Text("Add rule") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commit() }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { commit() }) {
                    Text("Add")
                }
            }
        }
    }
}

@Composable
private fun TemplateCard(template: String, onChange: (String) -> Unit) {
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Forwarding template", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = template,
                onValueChange = { value ->
                    onChange(value)
                },
                label = { Text("Template (optional)") },
                supportingText = { Text("%s = source, %t = time (hh:mm:ss), %m = original message. Empty = forward as-is.") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun TestCard(
    initialSender: String,
    initialMessage: String,
    onRunTest: (String, String) -> FiltersViewModel.TestOutcome,
) {
    var sender by rememberSaveable { mutableStateOf(initialSender) }
    var message by rememberSaveable { mutableStateOf(initialMessage) }
    var outcome by remember { mutableStateOf<FiltersViewModel.TestOutcome?>(null) }

    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Test a message", style = MaterialTheme.typography.titleMedium)
            Text(
                "Dry-run a sample sender and message against the filters shown above. This mirrors the live pipeline and never sends anything.",
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
            Button(
                onClick = {
                    outcome = onRunTest(sender, message)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Test")
            }
            outcome?.let { result ->
                SelectionContainer {
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

@Composable
private fun toneColor(tone: FiltersViewModel.Tone): Color {
    return when (tone) {
        FiltersViewModel.Tone.POSITIVE -> MaterialTheme.colorScheme.primary
        FiltersViewModel.Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
    }
}
