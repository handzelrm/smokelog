package com.smokelog.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.smokelog.data.Cook
import com.smokelog.data.FatCap
import com.smokelog.data.Placement
import com.smokelog.data.RestLocation
import com.smokelog.data.WrapMethod

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookEditScreen(vm: CookEditViewModel, onDone: () -> Unit) {
    val draft = vm.draft
    val ai by vm.ai.collectAsState()
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit cook") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back without saving") } },
                actions = { TextButton(onClick = { vm.save(onDone) }) { Text("Save") } },
            )
        },
    ) { pad ->
        val c = draft ?: return@Scaffold
        val set = vm::update
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AiNotesCard(
                enabled = vm.aiEnabled, useClaudeApp = vm.usesClaudeApp, ai = ai,
                onSubmit = vm::fillFromNotes, onOpenClaude = { notes -> vm.openInClaudeApp(context, notes) },
                onPaste = vm::applyPastedReply, onDismiss = vm::clearAi,
            )

            Section("Meat") {
                TextRow("Title (optional)", c.title, { v -> set { it.copy(title = v) } })
                MeatPicker(c.meat, c.cut, onMeat = { v -> set { it.copy(meat = v) } }, onCut = { v -> set { it.copy(cut = v) } })
                NumberRow("Weight", c.weightLb, { v -> set { it.copy(weightLb = v) } }, suffix = "lb")
            }

            Section("Organize") {
                CategoryRow(c.meat, c.category) { v -> set { it.copy(category = v) } }
                TagEditor(c.tags, vm.knownTags, vm.pendingTag, onPending = vm::typeTag, onAdd = vm::addTag, onRemove = vm::removeTag)
            }

            LaunchedEffect(c.meat) { vm.loadPastNotes(c.meat) }
            PastNotesCard(c.meat, vm.pastNotes)

            Section("Preparation") {
                TextRow("Trim", c.trimNotes, { v -> set { it.copy(trimNotes = v) } }, singleLine = false)
                TextRow("Binder", c.binder, { v -> set { it.copy(binder = v) } }, placeholder = "Mustard, hot sauce, oil…")
                TextRow("Seasoning / rub", c.seasoning, { v -> set { it.copy(seasoning = v) } }, singleLine = false)
                NumberRow("Seasoned how long before", c.seasonedHoursBefore, { v -> set { it.copy(seasonedHoursBefore = v) } }, suffix = "h")
                TextRow("Dry brine", c.dryBrine, { v -> set { it.copy(dryBrine = v) } })
                NumberRow("Dry brine duration", c.dryBrineHours, { v -> set { it.copy(dryBrineHours = v) } }, suffix = "h")
                TextRow("Wet brine", c.wetBrine, { v -> set { it.copy(wetBrine = v) } }, singleLine = false)
                NumberRow("Wet brine duration", c.wetBrineHours, { v -> set { it.copy(wetBrineHours = v) } }, suffix = "h")
                TextRow("Injection", c.injection, { v -> set { it.copy(injection = v) } }, singleLine = false)
                NumberRow("Injected how long before", c.injectionHoursBefore, { v -> set { it.copy(injectionHoursBefore = v) } }, suffix = "h")
                TextRow("Other prep notes", c.prepNotes, { v -> set { it.copy(prepNotes = v) } }, singleLine = false)
            }

            Section("Smoker setup") {
                TextRow("Smoker", c.smoker, { v -> set { it.copy(smoker = v) } }, placeholder = "Traeger Ironwood 885")
                TextRow("Pellets / wood", c.fuel, { v -> set { it.copy(fuel = v) } })
                IntRow("Smoker temperature", c.smokerTempF, { v -> set { it.copy(smokerTempF = v) } }, suffix = "°F")
                EnumRow("Placement on smoker", c.placement, Placement.entries, { it.label }) { v -> set { it.copy(placement = v) } }
                TextRow("Placement notes", c.placementNotes, { v -> set { it.copy(placementNotes = v) } }, placeholder = "Point toward fire pot…")
                EnumRow("Fat cap", c.fatCap, FatCap.entries, { it.label }) { v -> set { it.copy(fatCap = v) } }
                YesNoRow("Water pan", c.waterPan) { v -> set { it.copy(waterPan = v) } }
                if (c.waterPan == true) TextRow("Water pan notes", c.waterPanNotes, { v -> set { it.copy(waterPanNotes = v) } })
            }

            Section("Cook") {
                DateTimeRow("Meat on", c.startTime) { v -> set { it.copy(startTime = v) } }
                IntRow("Internal temp when put on", c.startInternalF, { v -> set { it.copy(startInternalF = v) } }, suffix = "°F")
            }

            Section("Wrap") {
                EnumRow("Wrap method", c.wrapMethod, WrapMethod.entries, { it.label }) { v -> set { it.copy(wrapMethod = v) } }
                if (c.wrapMethod != null && c.wrapMethod != WrapMethod.NONE) {
                    DateTimeRow("Wrapped at", c.wrapTime) { v -> set { it.copy(wrapTime = v) } }
                    IntRow("Internal temp at wrap", c.wrapInternalF, { v -> set { it.copy(wrapInternalF = v) } }, suffix = "°F")
                    TextRow("Wrap notes", c.wrapNotes, { v -> set { it.copy(wrapNotes = v) } }, singleLine = false,
                        placeholder = "Tallow, butter, spritz before wrapping…")
                }
            }

            Section("Off & rest") {
                DateTimeRow("Taken off at", c.offTime) { v -> set { it.copy(offTime = v) } }
                IntRow("Internal temp when taken off", c.offInternalF, { v -> set { it.copy(offInternalF = v) } }, suffix = "°F")
                EnumRow("Rested in", c.restLocation, RestLocation.entries, { it.label }) { v -> set { it.copy(restLocation = v) } }
                DateTimeRow("Rest ended / sliced at", c.restEndTime) { v -> set { it.copy(restEndTime = v) } }
                IntRow("Rest length (if no end time)", c.restMinutes, { v -> set { it.copy(restMinutes = v) } }, suffix = "min")
                TextRow("Rest notes", c.restNotes, { v -> set { it.copy(restNotes = v) } }, placeholder = "Oven at 170°F, wrapped in towels…")
            }

            Section("Result") {
                RatingRow(c) { v -> set { it.copy(rating = v) } }
                TextRow("How did it turn out?", c.resultNotes, { v -> set { it.copy(resultNotes = v) } }, singleLine = false)
            }

            Section("Notes for next cook") {
                Text("Shown when you start your next ${c.meat.ifBlank { "cook of this meat" }.lowercase()}.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextRow("What to change or remember", c.nextCookNotes, { v -> set { it.copy(nextCookNotes = v) } }, singleLine = false,
                    placeholder = "e.g. Wrap at 170 instead of 165, rest at least 2 h, less salt in the rub")
            }

            Button(onClick = { vm.save(onDone) }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        }
    }
}

@Composable
private fun RatingRow(c: Cook, onChange: (Int?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Rating", Modifier.weight(1f))
        (1..5).forEach { n ->
            IconButton(onClick = { onChange(if (c.rating == n) null else n) }) {
                Icon(if ((c.rating ?: 0) >= n) Icons.Default.Star else Icons.Default.StarBorder, "$n stars",
                    tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Type or dictate notes; Claude fills in the matching fields (via the API, or the Claude app + paste back). */
@Composable
private fun AiNotesCard(
    enabled: Boolean, useClaudeApp: Boolean, ai: AiState,
    onSubmit: (String) -> Unit, onOpenClaude: (String) -> Unit, onPaste: (String?) -> Unit, onDismiss: () -> Unit,
) {
    var notes by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                notes = if (notes.isBlank()) it else "$notes $it"
            }
        }
    }
    Section("Quick fill with AI") {
        if (!enabled) {
            Text("Add an Anthropic API key in Settings, or switch the AI assistant to the Claude app, to fill this form from typed or spoken notes.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Section
        }
        Text(
            if (useClaudeApp) "Describe the cook in your own words. Claude (the app on this phone) turns it into fields: send it, copy Claude's reply, then come back and paste it."
            else "Describe the cook in your own words — the assistant fills in the fields below.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = notes, onValueChange = { notes = it }, minLines = 3, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("“14 lb prime packer, dry brined overnight, SPG on 1 h before. On at 6am at 250, fat side down, water pan. Wrapped in butcher paper with tallow at 165 around 1pm…”") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Describe your cook"))
            }) { Icon(Icons.Default.Mic, null); Text(" Dictate") }
            if (useClaudeApp) {
                Button(onClick = { onOpenClaude(notes) }, enabled = notes.isNotBlank()) {
                    Icon(Icons.Default.AutoAwesome, null); Text(" Ask Claude")
                }
            } else {
                Button(onClick = { onSubmit(notes) }, enabled = notes.isNotBlank() && !ai.busy) {
                    Icon(Icons.Default.AutoAwesome, null); Text(" Fill fields")
                }
                if (ai.busy) CircularProgressIndicator(Modifier.padding(start = 4.dp))
            }
        }
        if (useClaudeApp && (ai.awaitingPaste || ai.error != null)) {
            Text("In Claude, pick New session and send. Then long-press the reply, tap Copy, and come back here.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { onPaste(clipboard.getText()?.text) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.ContentPaste, null); Text(" Paste Claude's reply")
            }
        }
        ai.result?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { notes = ""; onDismiss() }) { Text("Clear notes") }
        }
        ai.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
}
