package com.smokelog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.smokelog.data.MeatCatalog
import com.smokelog.data.Tags

/**
 * Tags as removable chips plus a box to type more. [pending] is text not yet turned into a tag;
 * it's owned by the caller so saving the form keeps it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagEditor(
    tags: String, known: List<String>, pending: String,
    onPending: (String) -> Unit, onAdd: (String) -> Unit, onRemove: (String) -> Unit,
) {
    val current = Tags.parse(tags)
    if (current.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        current.forEach { t ->
            InputChip(selected = true, onClick = { onRemove(t) }, label = { Text(t) },
                trailingIcon = { Icon(Icons.Default.Close, "Remove tag $t", Modifier.size(18.dp)) })
        }
    }
    OutlinedTextField(
        value = pending,
        onValueChange = { if (it.endsWith(",")) onAdd(it) else onPending(it) },
        label = { Text("Add a tag") }, placeholder = { Text("Competition, Thanksgiving, experiment…") },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onAdd(pending) }),
        trailingIcon = { if (pending.isNotBlank()) IconButton(onClick = { onAdd(pending) }) { Icon(Icons.Default.Add, "Add tag") } },
    )
    val suggestions = known
        .filter { k -> current.none { it.equals(k, ignoreCase = true) } && k.contains(pending.trim(), ignoreCase = true) }.take(8)
    if (suggestions.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        suggestions.forEach { t -> SuggestionChip(onClick = { onAdd(t) }, label = { Text(t) }) }
    }
}

/** Category: worked out from the meat unless one is chosen here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryRow(meat: String, category: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val automatic = "Automatic (${MeatCatalog.categoryFor(meat)})"
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = category.ifBlank { automatic }, onValueChange = {}, readOnly = true, label = { Text("Category") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(automatic) }, onClick = { onChange(""); open = false })
            MeatCatalog.categoryNames.forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { onChange(c); open = false }) }
        }
    }
}
