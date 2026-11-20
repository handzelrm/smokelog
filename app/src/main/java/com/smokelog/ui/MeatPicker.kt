package com.smokelog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smokelog.data.Cook
import com.smokelog.data.Fmt
import com.smokelog.data.MeatCatalog

private const val OTHER = "Other (type your own)"

/** Meat dropdown grouped by category, then a cut dropdown for the chosen meat. Both allow free text. */
@Composable
fun MeatPicker(meat: String, cut: String, onMeat: (String) -> Unit, onCut: (String) -> Unit) {
    var customMeat by remember { mutableStateOf(false) }
    val catalogMeat = MeatCatalog.find(meat)
    val meatIsCustom = customMeat || (meat.isNotBlank() && catalogMeat == null)

    GroupedDropdown(
        label = "Meat",
        value = when { meatIsCustom -> "Other"; catalogMeat != null -> catalogMeat.name; else -> "" },
        groups = MeatCatalog.categories.map { it.name to it.meats.map { m -> m.name } } + (null to listOf(OTHER)),
    ) { picked ->
        if (picked == OTHER) {
            customMeat = true
            if (catalogMeat != null) onMeat("")
            onCut("")
        } else {
            customMeat = false
            if (!picked.equals(meat, ignoreCase = true)) {
                onMeat(picked)
                if (MeatCatalog.find(picked)?.cuts?.contains(cut) != true) onCut("")
            }
        }
    }
    if (meatIsCustom) TextRow("Meat (your own)", meat, onMeat, placeholder = "e.g. Elk roast")

    val cuts = if (meatIsCustom) emptyList() else catalogMeat?.cuts.orEmpty()
    var customCut by remember { mutableStateOf(false) }
    val cutIsCustom = customCut || (cut.isNotBlank() && cut !in cuts)
    if (cuts.isNotEmpty()) {
        GroupedDropdown(
            label = "Cut / style",
            value = if (cutIsCustom) "Other" else cut,
            groups = listOf(null to cuts + OTHER),
        ) { picked ->
            if (picked == OTHER) { customCut = true; if (cut in cuts) onCut("") }
            else { customCut = false; onCut(picked) }
        }
        if (cutIsCustom) TextRow("Cut / style (your own)", cut, onCut)
    } else if (meat.isNotBlank()) {
        TextRow("Cut / style (optional)", cut, onCut)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupedDropdown(label: String, value: String, groups: List<Pair<String?, List<String>>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            groups.forEachIndexed { i, (header, items) ->
                if (i > 0) HorizontalDivider()
                if (header != null) DropdownMenuItem(
                    text = { Text(header.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
                    onClick = {}, enabled = false,
                )
                items.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item, fontWeight = if (item == value) FontWeight.SemiBold else null) },
                        onClick = { onPick(item); open = false },
                    )
                }
            }
        }
    }
}

/** "Notes for next cook" left on earlier cooks of the same meat. */
@Composable
fun PastNotesCard(meat: String, past: List<Cook>, modifier: Modifier = Modifier) {
    if (past.isEmpty()) return
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lightbulb, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Text("  Notes from your last ${meat.lowercase()} cook${if (past.size > 1) "s" else ""}",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            past.forEach { p ->
                Column {
                    Text(
                        listOfNotNull(Fmt.date(p.startTime ?: p.createdAt), p.cut.ifBlank { null }, p.rating?.let { "★".repeat(it) }).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Text(p.nextCookNotes.trim(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
    }
}
