package com.smokelog.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smokelog.SmokeLogApp
import com.smokelog.ai.CookPatch
import com.smokelog.data.Cook
import com.smokelog.data.CookEvent
import com.smokelog.data.EventType
import com.smokelog.data.CookGroup
import com.smokelog.data.CookQuery
import com.smokelog.data.CookSection
import com.smokelog.data.CookSort
import com.smokelog.data.Settings
import com.smokelog.data.Tags
import com.smokelog.data.TempReading
import com.smokelog.data.WrapMethod
import com.smokelog.traeger.CookMonitorService
import com.smokelog.traeger.Grill
import com.smokelog.traeger.TraegerClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val app get() = SmokeLogApp.instance
private val dao get() = app.db.cookDao()

/** What the list shows, plus the categories and tags in use (with counts) to filter by. */
data class CookListUi(
    val loaded: Boolean = false,
    val total: Int = 0,
    val sections: List<CookSection> = emptyList(),
    val categories: List<Pair<String, Int>> = emptyList(),
    val tags: List<Pair<String, Int>> = emptyList(),
)

class CookListViewModel : ViewModel() {
    val monitoring = app.monitoringCookId
    private val prefs = app.getSharedPreferences("cook_list", android.content.Context.MODE_PRIVATE)

    /** Compose state so the search box updates synchronously while typing. Sort and grouping are remembered. */
    var query by mutableStateOf(CookQuery(
        sort = CookSort.entries.firstOrNull { it.name == prefs.getString("sort", null) } ?: CookSort.NEWEST,
        group = CookGroup.entries.firstOrNull { it.name == prefs.getString("group", null) } ?: CookGroup.NONE,
    ))
        private set

    val ui: StateFlow<CookListUi> = combine(
        dao.observeCooks(), dao.observeAllEvents(), dao.observeAllPhotos(), snapshotFlow { query },
    ) { cooks, events, photos, q ->
        CookListUi(
            loaded = true,
            total = cooks.size,
            sections = q.run(cooks, events.groupBy { it.cookId }, photos.groupBy { it.cookId }),
            categories = cooks.groupingBy { it.effectiveCategory }.eachCount().toList().sortedBy { CookQuery.categoryOrder(it.first) },
            tags = cooks.flatMap { it.tagList }.groupBy { it.lowercase() }.map { (_, v) -> v.first() to v.size }
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() }),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CookListUi())

    fun update(change: (CookQuery) -> CookQuery) {
        query = change(query)
        prefs.edit().putString("sort", query.sort.name).putString("group", query.group.name).apply()
    }

    fun clearFilters() = update { it.copy(text = "", categories = emptySet(), tags = emptySet()) }

    fun newCook(onCreated: (Long) -> Unit) = viewModelScope.launch {
        onCreated(dao.insertCook(Cook()))
    }
}

/** Shared state for an AI request in flight. */
data class AiState(
    val busy: Boolean = false, val error: String? = null, val result: String? = null,
    /** A prompt was handed to the Claude app; waiting for the user to paste the reply back. */
    val awaitingPaste: Boolean = false,
)

class CookEditViewModel(private val cookId: Long) : ViewModel() {
    // Compose state (not StateFlow) so text fields update synchronously while typing.
    var draft by mutableStateOf<Cook?>(null)
        private set
    /** Text in the tag box that hasn't become a tag yet; saved with the cook so it isn't lost. */
    var pendingTag by mutableStateOf("")
        private set
    var knownTags by mutableStateOf<List<String>>(emptyList())
        private set

    fun typeTag(s: String) { pendingTag = s }
    fun addTag(s: String) { update { it.copy(tags = Tags.add(it.tags, s, knownTags)) }; pendingTag = "" }
    fun removeTag(s: String) = update { it.copy(tags = Tags.remove(it.tags, s)) }

    private val _ai = MutableStateFlow(AiState())
    val ai: StateFlow<AiState> = _ai.asStateFlow()
    val aiEnabled get() = app.settings.state.value.hasAi

    /** Next-cook notes from earlier cooks of the selected meat. */
    var pastNotes by mutableStateOf<List<Cook>>(emptyList())
        private set

    fun loadPastNotes(meat: String) = viewModelScope.launch {
        pastNotes = if (meat.isBlank()) emptyList() else dao.nextCookNotesFor(meat.trim(), cookId)
    }

    private var pendingEvents: List<CookEvent> = emptyList()
    private var pendingReadings: List<TempReading> = emptyList()

    init {
        viewModelScope.launch {
            draft = dao.getCook(cookId)
            knownTags = dao.allTagStrings().flatMap { Tags.parse(it) }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
        }
    }

    fun update(f: (Cook) -> Cook) { draft = draft?.let(f) }

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        if (pendingTag.isNotBlank()) addTag(pendingTag)
        draft?.let { dao.updateCook(it) }
        if (pendingEvents.isNotEmpty()) dao.insertEvents(pendingEvents)
        if (pendingReadings.isNotEmpty()) dao.insertReadings(pendingReadings)
        pendingEvents = emptyList(); pendingReadings = emptyList()
        onDone()
    }

    val usesClaudeApp get() = app.settings.state.value.usesClaudeApp

    /** Sends notes to Claude (API), merges the extracted fields into the draft (user still reviews and saves). */
    fun fillFromNotes(notes: String) = viewModelScope.launch {
        val current = draft ?: return@launch
        _ai.value = AiState(busy = true)
        try {
            applyPatch(app.ai.parseNotes(notes, current), current)
        } catch (e: Exception) {
            _ai.value = AiState(error = e.message ?: "AI request failed")
        }
    }

    /** Opens the Claude app with the notes; the reply comes back via [applyPastedReply]. */
    fun openInClaudeApp(context: android.content.Context, notes: String) {
        val current = draft ?: return
        _ai.value = if (com.smokelog.ai.ClaudeApp.open(context, app.ai.parseRequestForClaudeApp(notes, current)))
            AiState(awaitingPaste = true)
        else AiState(error = "The Claude app isn't installed. Install it from the Play Store, or use an API key in Settings.")
    }

    fun applyPastedReply(text: String?) {
        val current = draft ?: return
        if (text.isNullOrBlank()) { _ai.value = _ai.value.copy(error = "Clipboard is empty – copy Claude's reply first"); return }
        try {
            applyPatch(app.ai.parseReply(text), current)
        } catch (e: Exception) {
            _ai.value = _ai.value.copy(error = e.message)
        }
    }

    private fun applyPatch(patch: CookPatch, current: Cook) {
        val (cook, events, readings) = app.ai.apply(patch, current)
        draft = cook
        pendingEvents = pendingEvents + events
        pendingReadings = pendingReadings + readings
        val extras = listOfNotNull(
            events.size.takeIf { it > 0 }?.let { "$it timeline events" },
            readings.size.takeIf { it > 0 }?.let { "$it temp readings" },
        ).joinToString(" and ")
        _ai.value = AiState(result = (patch.summary ?: "Fields updated.") +
            (if (extras.isNotEmpty()) " Will also add $extras." else "") + " Review, then Save.")
    }

    fun clearAi() { _ai.value = AiState() }
}

class CookDetailViewModel(val cookId: Long) : ViewModel() {
    val cook = dao.observeCook(cookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val readings = dao.observeReadings(cookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val events = dao.observeEvents(cookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val photos = dao.observePhotos(cookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val photoStore get() = app.photos

    private val _photoError = MutableStateFlow<String?>(null)
    val photoError: StateFlow<String?> = _photoError.asStateFlow()

    /** A temp file for the camera app to write into: (path to remember across process death, content Uri). */
    fun newCaptureTarget(): Pair<String, android.net.Uri> =
        app.photos.newCameraTarget().let { (f, uri) -> f.absolutePath to uri }

    fun onCaptured(success: Boolean, path: String?) = viewModelScope.launch {
        val f = path?.let { java.io.File(it) } ?: return@launch
        if (success && f.length() > 0) addPhoto(android.net.Uri.fromFile(f))
        f.delete()
    }

    fun addPhotos(uris: List<android.net.Uri>) = viewModelScope.launch { uris.forEach { addPhoto(it) } }

    private suspend fun addPhoto(uri: android.net.Uri) {
        try {
            val name = app.photos.importImage(uri)
            dao.insertPhoto(com.smokelog.data.CookPhoto(cookId = cookId, time = System.currentTimeMillis(), fileName = name))
            _photoError.value = null
        } catch (e: Exception) {
            _photoError.value = "Couldn't add that photo: ${e.message ?: "unknown error"}"
        }
    }

    fun updateCaption(p: com.smokelog.data.CookPhoto, caption: String) = viewModelScope.launch {
        dao.updatePhoto(p.copy(caption = caption.trim()))
    }

    fun deletePhoto(p: com.smokelog.data.CookPhoto) = viewModelScope.launch {
        dao.deletePhoto(p)
        app.photos.delete(p.fileName)
    }

    fun clearPhotoError() { _photoError.value = null }
    val live = CookMonitorService.live
    val monitoringId = app.monitoringCookId
    val settings: StateFlow<Settings.Snapshot> = app.settings.state

    private val _ai = MutableStateFlow(AiState())
    val ai: StateFlow<AiState> = _ai.asStateFlow()

    var pastNotes by mutableStateOf<List<Cook>>(emptyList())
        private set

    fun loadPastNotes(meat: String) = viewModelScope.launch {
        pastNotes = if (meat.isBlank()) emptyList() else dao.nextCookNotesFor(meat.trim(), cookId)
    }

    private val _grills = MutableStateFlow<GrillsState>(GrillsState.Idle)
    val grills: StateFlow<GrillsState> = _grills.asStateFlow()

    sealed interface GrillsState {
        data object Idle : GrillsState
        data object Loading : GrillsState
        data class Loaded(val grills: List<Grill>, val error: String? = null) : GrillsState
    }

    private val demoGrill = Grill(TraegerClient.DEMO_THING, "Demo grill (simulated)")

    fun loadGrills() = viewModelScope.launch {
        _grills.value = GrillsState.Loading
        _grills.value = if (!app.settings.state.value.hasTraeger) {
            GrillsState.Loaded(listOf(demoGrill), "Add your Traeger login in Settings to see your grills.")
        } else try {
            GrillsState.Loaded(app.traeger.listGrills() + demoGrill)
        } catch (e: Exception) {
            GrillsState.Loaded(listOf(demoGrill), e.message)
        }
    }

    fun dismissGrills() { _grills.value = GrillsState.Idle }

    fun startMonitoring(grill: Grill) = viewModelScope.launch {
        val c = cook.value ?: return@launch
        dao.updateCook(c.copy(traegerThing = grill.thingName, startTime = c.startTime ?: System.currentTimeMillis()))
        CookMonitorService.start(app, cookId, grill.thingName)
        _grills.value = GrillsState.Idle
    }

    fun stopMonitoring() = CookMonitorService.stop(app)

    private suspend fun latestProbe(): Int? =
        (live.value.takeIf { it.cookId == cookId }?.status?.probeF ?: dao.latestReading(cookId)?.mainProbeF)?.roundToInt()

    /** One-tap milestones: stamp the time and grab the current probe temp when we have one. */
    fun markNow(type: EventType) = viewModelScope.launch {
        val c = cook.value ?: return@launch
        val now = System.currentTimeMillis()
        val probe = latestProbe()
        val updated = when (type) {
            EventType.WRAPPED -> c.copy(wrapTime = now, wrapInternalF = probe ?: c.wrapInternalF,
                wrapMethod = c.wrapMethod?.takeIf { it != WrapMethod.NONE } ?: WrapMethod.BUTCHER_PAPER)
            EventType.PULLED -> c.copy(offTime = now, offInternalF = probe ?: c.offInternalF)
            EventType.REST_END -> c.copy(restEndTime = now)
            else -> c
        }
        if (updated != c) dao.updateCook(updated)
        // Recording keeps going after "Taken off" (probe temps only) and stops when the rest is done.
        if (type == EventType.REST_END && monitoringId.value == cookId) stopMonitoring()
        dao.insertEvent(CookEvent(cookId = cookId, time = now, type = type, note = probe?.let { "Probe ${it}°F" } ?: ""))
    }

    fun startCookNow() = viewModelScope.launch {
        val c = cook.value ?: return@launch
        dao.updateCook(c.copy(startTime = System.currentTimeMillis()))
    }

    fun addEvent(type: EventType, note: String, time: Long = System.currentTimeMillis()) = viewModelScope.launch {
        dao.insertEvent(CookEvent(cookId = cookId, time = time, type = type, note = note))
    }

    fun deleteEvent(e: CookEvent) = viewModelScope.launch { dao.deleteEvent(e) }

    fun addReading(grillF: Double?, probeF: Double?, time: Long = System.currentTimeMillis()) = viewModelScope.launch {
        if (grillF == null && probeF == null) return@launch
        dao.insertReading(TempReading(cookId = cookId, time = time, grillF = grillF, probeF = probeF))
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        if (monitoringId.value == cookId) stopMonitoring()
        dao.getPhotos(cookId).forEach { app.photos.delete(it.fileName) }
        cook.value?.let { dao.deleteCook(it) }
        onDone()
    }

    fun analyze(question: String?) = viewModelScope.launch {
        val c = cook.value ?: return@launch
        _ai.value = AiState(busy = true)
        try {
            val past = if (c.meat.isBlank()) emptyList() else dao.similarCooks("%${c.meat.trim()}%", c.id)
            val text = app.ai.analyze(c, dao.getReadings(cookId), dao.getEvents(cookId), past, question)
            if (question.isNullOrBlank()) dao.updateCook(c.copy(aiAnalysis = text))
            _ai.value = AiState(result = text)
        } catch (e: Exception) {
            _ai.value = AiState(error = e.message ?: "AI request failed")
        }
    }

    /** Opens the Claude app with the cook data; the reply comes back via [savePastedAnalysis]. */
    fun openInClaudeApp(context: android.content.Context, question: String?) = viewModelScope.launch {
        val c = cook.value ?: return@launch
        val past = if (c.meat.isBlank()) emptyList() else dao.similarCooks("%${c.meat.trim()}%", c.id)
        val prompt = app.ai.analyzeRequestForClaudeApp(c, dao.getReadings(cookId), dao.getEvents(cookId), past, question)
        _ai.value = if (com.smokelog.ai.ClaudeApp.open(context, prompt)) AiState(awaitingPaste = true)
        else AiState(error = "The Claude app isn't installed. Install it from the Play Store, or use an API key in Settings.")
    }

    fun savePastedAnalysis(text: String?) = viewModelScope.launch {
        val c = cook.value ?: return@launch
        if (text.isNullOrBlank()) { _ai.value = _ai.value.copy(error = "Clipboard is empty – copy Claude's reply first"); return@launch }
        dao.updateCook(c.copy(aiAnalysis = text.trim()))
        _ai.value = AiState(result = text.trim())
    }

    fun clearAi() { _ai.value = AiState() }
}

class SettingsViewModel : ViewModel() {
    val state = app.settings.state
    private val _test = MutableStateFlow<String?>(null)
    val test: StateFlow<String?> = _test.asStateFlow()

    fun save(s: Settings.Snapshot) = app.settings.update(s)

    fun testTraeger(s: Settings.Snapshot) = viewModelScope.launch {
        app.settings.update(s)
        _test.value = "Signing in…"
        _test.value = try {
            val grills = app.traeger.listGrills()
            if (grills.isEmpty()) "Signed in, but no grills are on this account."
            else "Connected: " + grills.joinToString { it.name }
        } catch (e: Exception) { e.message ?: "Failed" }
    }
}
