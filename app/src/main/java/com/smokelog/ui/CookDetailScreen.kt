package com.smokelog.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smokelog.data.Cook
import com.smokelog.data.CookEvent
import com.smokelog.data.EventType
import com.smokelog.data.CookForecast
import com.smokelog.data.Fmt
import com.smokelog.data.Probes
import com.smokelog.data.WrapMethod
import com.smokelog.share.CookShare
import com.smokelog.traeger.CookMonitorService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CookDetailScreen(vm: CookDetailViewModel, onBack: () -> Unit, onEdit: () -> Unit) {
    val cook by vm.cook.collectAsState()
    val readings by vm.readings.collectAsState()
    val events by vm.events.collectAsState()
    val photos by vm.photos.collectAsState()
    val photoError by vm.photoError.collectAsState()
    val live by vm.live.collectAsState()
    val monitoringId by vm.monitoringId.collectAsState()
    val grills by vm.grills.collectAsState()
    val ai by vm.ai.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var menu by remember { mutableStateOf(false) }
    var shareMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var eventDialog by remember { mutableStateOf(false) }
    var readingDialog by remember { mutableStateOf(false) }

    val c = cook
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(c?.displayTitle ?: "") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit details") }
                    IconButton(onClick = { shareMenu = true }) { Icon(Icons.Default.Share, "Share") }
                    DropdownMenu(shareMenu, { shareMenu = false }) {
                        DropdownMenuItem(text = { Text("Share summary (text)") }, onClick = {
                            shareMenu = false; c?.let { CookShare.shareText(context, it, readings, events, photos, vm.photoStore) }
                        })
                        DropdownMenuItem(text = { Text("Share full cook file") }, onClick = {
                            shareMenu = false; c?.let { scope.launch { CookShare.shareFile(context, it, readings, events, photos, vm.photoStore) } }
                        })
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Delete cook") }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { menu = false; confirmDelete = true })
                    }
                },
            )
        },
    ) { pad ->
        if (c == null) return@Scaffold
        val recordingThis = monitoringId == c.id
        val liveTargets = if (recordingThis) live.status?.probes?.map { it?.targetF } else null
        val forecasts = remember(readings, liveTargets, c.meat, c.cut) { CookForecast.forProbes(c.meat, c.cut, readings, liveTargets) }
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LaunchedEffect(c.meat) { vm.loadPastNotes(c.meat) }
            PastNotesCard(c.meat, vm.pastNotes)

            // ---- Live grill / recording
            Section("Grill") {
                if (recordingThis) {
                    val s = live.status
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BigStat("Pit", Fmt.temp(s?.grillF), s?.setF?.let { "set ${Fmt.temp(it)}" })
                        val shown = s?.probes.orEmpty().withIndex().filter { it.value?.tempF != null }
                        if (shown.isEmpty()) BigStat("Probe", Fmt.temp(null), null)
                        shown.forEach { (i, p) ->
                            BigStat(Probes.labels[i], Fmt.temp(p!!.tempF), p.targetF?.let { "target ${Fmt.temp(it)}" })
                        }
                    }
                    live.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (c.offTime == null) ForecastBlock(forecasts, c.meat)
                    OutlinedButton(onClick = vm::stopMonitoring) { Text("Stop recording") }
                } else {
                    Text(
                        if (monitoringId != null) "Another cook is recording. Stop it to record this one."
                        else "Connect to your Traeger to plot pit and probe temperatures automatically.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = vm::loadGrills, enabled = monitoringId == null) { Text("Record from grill") }
                    // Manual readings: forecast while the cook is still going and readings are recent.
                    val fresh = readings.lastOrNull()?.let { System.currentTimeMillis() - it.time < 30 * 60_000 } == true
                    if (c.offTime == null && fresh) ForecastBlock(forecasts, c.meat)
                }
            }

            // ---- Milestones
            Section("Milestones") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (c.startTime == null) AssistChip(onClick = vm::startCookNow, label = { Text("Meat on now") })
                    if (c.wrapTime == null && c.wrapMethod != WrapMethod.NONE) AssistChip(onClick = { vm.markNow(EventType.WRAPPED) }, label = { Text("Wrapped now") })
                    if (c.offTime == null) AssistChip(onClick = { vm.markNow(EventType.PULLED) }, label = { Text("Taken off now") })
                    if (c.offTime != null && c.restEndTime == null && c.restMinutes == null) AssistChip(onClick = { vm.markNow(EventType.REST_END) }, label = { Text("Rest done now") })
                    AssistChip(onClick = { vm.markNow(EventType.SPRITZ) }, label = { Text("Spritzed") })
                    AssistChip(onClick = { eventDialog = true }, label = { Text("Add event…") })
                    AssistChip(onClick = { readingDialog = true }, label = { Text("Add reading…") })
                }
                KeyValue("Meat on", c.startTime?.let { Fmt.dateTime(it) + (c.startInternalF?.let { t -> " · ${t}°F" } ?: "") })
                KeyValue("Wrapped", c.wrapTime?.let {
                    listOfNotNull(Fmt.time(it), c.wrapInternalF?.let { t -> "${t}°F" }, c.wrapElapsedMinutes?.let { m -> "at ${Fmt.duration(m)}" }, c.wrapMethod?.label).joinToString(" · ")
                } ?: c.wrapMethod?.takeIf { it == WrapMethod.NONE }?.let { "Not wrapped" })
                KeyValue("Taken off", c.offTime?.let { Fmt.time(it) + (c.offInternalF?.let { t -> " · ${t}°F" } ?: "") })
                KeyValue("Total cook", c.cookMinutes?.let { Fmt.duration(it) + (c.weightLb?.let { w -> " · ${"%.1f".format(it / 60.0 / w)} h/lb" } ?: "") })
                KeyValue("Rest", listOfNotNull(c.effectiveRestMinutes?.let { Fmt.duration(it.toLong()) }, c.restLocation?.label).joinToString(" · "))
            }

            // ---- Chart
            Section("Temperatures") {
                TempChart(c, readings, forecast = forecasts.firstOrNull { it.forecast.kind == CookForecast.Kind.ON_TRACK }?.takeIf { c.offTime == null }?.let { it.slot to it.forecast })
            }

            // ---- Photos
            PhotosSection(
                photos = photos, store = vm.photoStore, startTime = c.startTime, error = photoError,
                newCaptureTarget = vm::newCaptureTarget, onCaptured = vm::onCaptured, onPicked = vm::addPhotos,
                onCaption = vm::updateCaption, onDelete = vm::deletePhoto,
            )

            // ---- Setup & prep
            Section("Setup & prep") { DetailsBlock(c) }

            // ---- Timeline
            if (events.isNotEmpty()) Section("Timeline") {
                events.forEach { e -> EventRow(e, c.startTime) { vm.deleteEvent(e) } }
            }

            // ---- AI
            val aiSettings = vm.settings.collectAsState().value
            AiAnalysisCard(
                c, ai, aiSettings.hasAi, aiSettings.usesClaudeApp,
                onAsk = { q -> if (aiSettings.usesClaudeApp) vm.openInClaudeApp(context, q) else vm.analyze(q) },
                onPaste = vm::savePastedAnalysis, onDismiss = vm::clearAi,
            )

            if (c.resultNotes.isNotBlank() || c.rating != null) Section("Result") {
                c.rating?.let { Text("★".repeat(it) + "☆".repeat(5 - it), color = MaterialTheme.colorScheme.primary) }
                if (c.resultNotes.isNotBlank()) Text(c.resultNotes)
            }

            Section("Notes for next cook") {
                if (c.nextCookNotes.isNotBlank()) Text(c.nextCookNotes)
                else Text("Nothing yet. Tap edit to leave yourself notes for the next ${c.meat.ifBlank { "cook" }.lowercase()}.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    // ---- Dialogs
    when (val g = grills) {
        CookDetailViewModel.GrillsState.Idle -> {}
        CookDetailViewModel.GrillsState.Loading -> AlertDialog(
            onDismissRequest = vm::dismissGrills, confirmButton = {},
            title = { Text("Finding grills…") }, text = { CircularProgressIndicator() },
        )
        is CookDetailViewModel.GrillsState.Loaded -> AlertDialog(
            onDismissRequest = vm::dismissGrills,
            confirmButton = { TextButton(onClick = vm::dismissGrills) { Text("Cancel") } },
            title = { Text("Choose a grill") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    g.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    g.grills.forEach { grill ->
                        Text(grill.name, Modifier.fillMaxWidth().clickable { vm.startMonitoring(grill) }.padding(vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge)
                    }
                }
            },
        )
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this cook?") },
        text = { Text("This removes the cook and all of its temperature readings. It can't be undone.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(onBack) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
    if (eventDialog) EventDialog(onDismiss = { eventDialog = false }) { type, note -> vm.addEvent(type, note); eventDialog = false }
    if (readingDialog) ReadingDialog(onDismiss = { readingDialog = false }) { pit, probe -> vm.addReading(pit, probe); readingDialog = false }
}

@Composable
private fun BigStat(label: String, value: String, sub: String?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun DetailsBlock(c: Cook) {
    fun hrs(h: Double?, suffix: String) = h?.let { " (${Fmt.num(it)} h$suffix)" } ?: ""
    KeyValue("Meat", listOf(c.meat, c.cut).filter { it.isNotBlank() }.joinToString(" – ") + (c.weightLb?.let { " · ${Fmt.num(it)} lb" } ?: ""))
    KeyValue("Category", c.effectiveCategory)
    KeyValue("Tags", c.tagList.joinToString(", "))
    KeyValue("Smoker", listOf(c.smoker, c.smokerTempF?.let { "${it}°F" } ?: "").filter { it.isNotBlank() }.joinToString(" @ "))
    KeyValue("Pellets / wood", c.fuel)
    KeyValue("Placement", listOfNotNull(c.placement?.label, c.placementNotes.ifBlank { null }).joinToString(" – "))
    KeyValue("Fat cap", c.fatCap?.label)
    KeyValue("Water pan", c.waterPan?.let { if (it) "Yes" + (c.waterPanNotes.takeIf { n -> n.isNotBlank() }?.let { n -> " – $n" } ?: "") else "No" })
    KeyValue("Trim", c.trimNotes)
    KeyValue("Binder", c.binder)
    KeyValue("Seasoning", c.seasoning.ifBlank { null }?.plus(hrs(c.seasonedHoursBefore, " before")))
    KeyValue("Dry brine", c.dryBrine.ifBlank { null }?.plus(hrs(c.dryBrineHours, "")))
    KeyValue("Wet brine", c.wetBrine.ifBlank { null }?.plus(hrs(c.wetBrineHours, "")))
    KeyValue("Injection", c.injection.ifBlank { null }?.plus(hrs(c.injectionHoursBefore, " before")))
    KeyValue("Prep notes", c.prepNotes)
    KeyValue("Wrap notes", c.wrapNotes)
    KeyValue("Rest notes", c.restNotes)
}

@Composable
private fun EventRow(e: CookEvent, start: Long?, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${Fmt.time(e.time)}${start?.let { " · ${Fmt.duration((e.time - it) / 60_000)} in" } ?: ""}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(e.type.label + if (e.note.isNotBlank()) " – ${e.note}" else "", style = MaterialTheme.typography.bodyMedium)
        }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete event") }
    }
    HorizontalDivider()
}

@Composable
private fun AiAnalysisCard(
    c: Cook, ai: AiState, enabled: Boolean, useClaudeApp: Boolean,
    onAsk: (String?) -> Unit, onPaste: (String?) -> Unit, onDismiss: () -> Unit,
) {
    var question by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null)
                Text("  AI pitmaster", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (!enabled) {
                Text("Add an Anthropic API key in Settings, or switch the AI assistant to the Claude app, to get a debrief of this cook compared with your past cooks.",
                    style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            val shown = ai.result ?: c.aiAnalysis.ifBlank { null }
            shown?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodyMedium) } }
            ai.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(
                value = question, onValueChange = { question = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Ask about this cook (optional)… e.g. “Why did it stall so long?”") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onAsk(question.ifBlank { null }) }, enabled = !ai.busy) {
                    Text(when {
                        useClaudeApp -> if (question.isBlank()) "Debrief in Claude" else "Ask Claude"
                        question.isNotBlank() -> "Ask"
                        shown == null -> "Analyze cook"
                        else -> "Re-analyze"
                    })
                }
                if (ai.result != null) TextButton(onClick = { question = ""; onDismiss() }) { Text("Clear") }
                if (ai.busy) CircularProgressIndicator()
            }
            if (useClaudeApp && ai.awaitingPaste) {
                Text("In Claude, pick New session and send. Then copy the reply (long-press → Copy) and paste it here to keep it with this cook.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { onPaste(clipboard.getText()?.text) }) {
                    Icon(Icons.Default.ContentPaste, null); Text(" Paste Claude's reply")
                }
            }
        }
    }
}

@Composable
private fun EventDialog(onDismiss: () -> Unit, onAdd: (EventType, String) -> Unit) {
    var type by remember { mutableStateOf(EventType.NOTE) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add event") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EnumRow("Type", type, EventType.entries, { it.label }) { type = it ?: EventType.NOTE }
                TextRow("Note", note, { note = it }, singleLine = false)
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(type, note) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ReadingDialog(onDismiss: () -> Unit, onAdd: (Double?, Double?) -> Unit) {
    var pit by remember { mutableStateOf<Double?>(null) }
    var probe by remember { mutableStateOf<Double?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add reading (now)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberRow("Pit temp", pit, { pit = it }, suffix = "°F")
                NumberRow("Probe / internal", probe, { probe = it }, suffix = "°F")
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(pit, probe) }, enabled = pit != null || probe != null) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
