package com.smokelog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smokelog.ai.ClaudeApp
import com.smokelog.data.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val saved by vm.state.collectAsState()
    val test by vm.test.collectAsState()
    var s by remember { mutableStateOf(saved) }
    val note = MaterialTheme.typography.bodySmall
    val noteColor = MaterialTheme.colorScheme.onSurfaceVariant
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { TextButton(onClick = { vm.save(s); onBack() }) { Text("Save") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Traeger account") {
                Text(
                    "Uses your Traeger app (WiFIRE) login to read pit and probe temperatures. Traeger has no official " +
                        "public API, so this uses the same cloud service as the Traeger app and could stop working if Traeger changes it.",
                    style = note, color = noteColor,
                )
                TextRow("Email", s.traegerEmail, { s = s.copy(traegerEmail = it) })
                SecretRow("Password", s.traegerPassword) { s = s.copy(traegerPassword = it) }
                IntRow("Save a reading every", s.readingIntervalSec, { s = s.copy(readingIntervalSec = it ?: 60) }, suffix = "sec")
                OutlinedButton(onClick = { vm.testTraeger(s) }, enabled = s.hasTraeger) { Text("Test connection") }
                test?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }

            Section("AI assistant") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = s.usesClaudeApp, onClick = { s = s.copy(aiProvider = Settings.PROVIDER_CLAUDE_APP) },
                        label = { Text("Claude app on this phone") })
                    FilterChip(selected = !s.usesClaudeApp, onClick = { s = s.copy(aiProvider = Settings.PROVIDER_API) },
                        label = { Text("API key") })
                }
                if (s.usesClaudeApp) {
                    val installed = remember { ClaudeApp.isInstalled(context) }
                    Text(
                        "Uses the Claude app and your Claude subscription, no API key needed. SmokeLog opens Claude with " +
                            "your notes or cook data; you copy Claude's reply and paste it back into SmokeLog.",
                        style = note, color = noteColor,
                    )
                    Text(
                        if (installed) "✓ Claude app found" else "Claude app not found. Install it from the Play Store.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        "Fully automatic, using an API key from console.anthropic.com (billed separately from a Claude " +
                            "subscription). Your notes and cook data are sent to Anthropic only when you tap an AI button.",
                        style = note, color = noteColor,
                    )
                    SecretRow("Anthropic API key", s.anthropicKey) { s = s.copy(anthropicKey = it) }
                    TextRow("Model", s.aiModel, { s = s.copy(aiModel = it) })
                }
            }

            Text("Credentials are stored encrypted on this device.", style = note, color = noteColor)
        }
    }
}
