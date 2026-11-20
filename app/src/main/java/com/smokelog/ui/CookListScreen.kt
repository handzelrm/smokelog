package com.smokelog.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smokelog.data.Cook
import com.smokelog.data.Fmt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookListScreen(onOpen: (Long) -> Unit, onNew: (Long) -> Unit, onSettings: () -> Unit, vm: CookListViewModel = viewModel()) {
    val cooks by vm.cooks.collectAsState()
    val monitoring by vm.monitoring.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SmokeLog") },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.newCook(onNew) },
                icon = { Icon(Icons.Default.Add, null) }, text = { Text("New cook") },
            )
        },
    ) { pad ->
        if (cooks.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No cooks yet. Tap “New cook” to start logging your first smoke.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(cooks, key = { it.id }) { cook -> CookRow(cook, recording = monitoring == cook.id) { onOpen(cook.id) } }
        }
    }
}

@Composable
private fun CookRow(cook: Cook, recording: Boolean, onClick: () -> Unit) {
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
        }
    }
}
