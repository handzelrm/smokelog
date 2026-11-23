package com.smokelog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smokelog.data.CookGroup
import com.smokelog.data.CookHit
import com.smokelog.data.CookQuery
import com.smokelog.data.CookSort
import com.smokelog.data.Fmt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookListScreen(onOpen: (Long) -> Unit, onNew: (Long) -> Unit, onSettings: () -> Unit, vm: CookListViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val query = vm.query
    val monitoring by vm.monitoring.collectAsState()
    val listState = rememberLazyListState()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // Back to the top when the filters change, but keep the position when returning from a cook.
    var shownQuery by rememberSaveable { mutableStateOf(query.hashCode()) }
    LaunchedEffect(query) {
        if (shownQuery != query.hashCode()) { shownQuery = query.hashCode(); listState.scrollToItem(0) }
    }
    BackHandler(enabled = query.isFiltering) { focus.clearFocus(); vm.clearFilters() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SmokeLog") },
                actions = {
                    if (ui.total > 0) SortGroupMenu(query, vm::update)
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.newCook(onNew) },
                icon = { Icon(Icons.Default.Add, null) }, text = { Text("New cook") },
            )
        },
    ) { pad ->
        if (ui.total == 0) {
            if (ui.loaded) Box(Modifier.fillMaxSize().padding(pad).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No cooks yet. Tap “New cook” to start logging your first smoke.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(
                value = query.text, onValueChange = { t -> vm.update { it.copy(text = t) } },
                placeholder = { Text("Search cooks, rubs, wood, notes…") }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.text.isNotEmpty()) IconButton(onClick = { vm.update { it.copy(text = "") } }) { Icon(Icons.Default.Clear, "Clear search") }
                },
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // Hide rather than clear focus: with a hardware keyboard, Enter would go on to press the next button.
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            FilterChips(ui, query, vm::update)
            LazyColumn(
                Modifier.fillMaxSize(), state = listState,
                contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (query.isFiltering) item(key = "count") {
                    val shown = ui.sections.sumOf { it.hits.size }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (shown == 0) "No cooks match" else "$shown of ${ui.total} cooks", Modifier.weight(1f),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { focus.clearFocus(); vm.clearFilters() }) { Text("Clear") }
                    }
                }
                ui.sections.forEach { s ->
                    s.title?.let { title ->
                        item(key = "h:$title") {
                            Text("$title (${s.hits.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                    items(s.hits, key = { it.cook.id }) { hit -> CookRow(hit, recording = monitoring == hit.cook.id) { onOpen(hit.cook.id) } }
                }
            }
        }
    }
}

/** Categories and tags actually in use; categories widen the list, tags narrow it. */
@Composable
private fun FilterChips(ui: CookListUi, query: CookQuery, update: ((CookQuery) -> CookQuery) -> Unit) {
    if (ui.categories.size < 2 && ui.tags.isEmpty() && query.categories.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ui.categories.forEach { (name, n) ->
            val on = name in query.categories
            FilterChip(selected = on, label = { Text("$name $n") },
                leadingIcon = if (on) { { Icon(Icons.Default.Check, null) } } else null,
                onClick = { update { it.copy(categories = if (on) it.categories - name else it.categories + name) } })
        }
        ui.tags.forEach { (name, n) ->
            val on = query.tags.any { it.equals(name, ignoreCase = true) }
            FilterChip(selected = on, label = { Text("$name $n") },
                leadingIcon = { Icon(if (on) Icons.Default.Check else Icons.AutoMirrored.Filled.Label, if (on) null else "Tag") },
                onClick = { update { q -> q.copy(tags = if (on) q.tags.filterNot { it.equals(name, ignoreCase = true) }.toSet() else q.tags + name) } })
        }
    }
}

@Composable
private fun SortGroupMenu(query: CookQuery, update: ((CookQuery) -> CookQuery) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort and group") }
        DropdownMenu(open, { open = false }) {
            @Composable fun header(t: String) = DropdownMenuItem(enabled = false, onClick = {},
                text = { Text(t, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) })
            header("SORT BY")
            CookSort.entries.forEach { s ->
                DropdownMenuItem(text = { Text(s.label) }, onClick = { update { it.copy(sort = s) }; open = false },
                    trailingIcon = if (query.sort == s) { { Icon(Icons.Default.Check, "Selected") } } else null)
            }
            HorizontalDivider()
            header("GROUP BY")
            CookGroup.entries.forEach { g ->
                DropdownMenuItem(text = { Text(g.label) }, onClick = { update { it.copy(group = g) }; open = false },
                    trailingIcon = if (query.group == g) { { Icon(Icons.Default.Check, "Selected") } } else null)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CookRow(hit: CookHit, recording: Boolean, onClick: () -> Unit) {
    val cook = hit.cook
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(cook.displayTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (recording) {
                    Icon(Icons.Default.FiberManualRecord, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 4.dp))
                    Text("Recording", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            val meta = listOfNotNull(
                Fmt.date(cook.startTime ?: cook.createdAt),
                cook.weightLb?.let { "${Fmt.num(it)} lb" },
                cook.smokerTempF?.let { "${it}°F pit" },
                cook.cookMinutes?.let { Fmt.duration(it) },
            ).joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            cook.rating?.let { Text("★".repeat(it) + "☆".repeat(5 - it), color = MaterialTheme.colorScheme.primary) }
            if (cook.tagList.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                cook.tagList.forEach { Text("#$it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary) }
            }
            hit.snippet?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
