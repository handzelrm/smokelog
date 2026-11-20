package com.smokelog.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.smokelog.data.Fmt
import java.util.Calendar

private val Ember = Color(0xFFB4441C)
private val Bark = Color(0xFF3A2418)

@Composable
fun SmokeLogTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFFFFB59A), onPrimary = Color(0xFF5A1B00),
        primaryContainer = Color(0xFF7E2C0A), onPrimaryContainer = Color(0xFFFFDBCF),
        secondary = Color(0xFFE7BDAD),
        tertiaryContainer = Color(0xFF5A4300), onTertiaryContainer = Color(0xFFFFE9B8),
        background = Color(0xFF1A1A19), surface = Color(0xFF1A1A19),
    ) else lightColorScheme(
        primary = Ember, onPrimary = Color.White,
        primaryContainer = Color(0xFFFFDBCF), onPrimaryContainer = Bark,
        secondary = Color(0xFF77574A),
        tertiaryContainer = Color(0xFFFFE9B8), onTertiaryContainer = Color(0xFF3A2A00),
        background = Color(0xFFFCFCFB), surface = Color(0xFFFCFCFB),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun TextRow(label: String, value: String, onChange: (String) -> Unit, singleLine: Boolean = true, placeholder: String? = null) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        placeholder = placeholder?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun SecretRow(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Numeric field that keeps its own text so partially typed values ("12.") aren't lost. */
@Composable
fun NumberRow(label: String, value: Double?, onChange: (Double?) -> Unit, suffix: String? = null, modifier: Modifier = Modifier.fillMaxWidth()) {
    var text by remember { mutableStateOf(Fmt.num(value)) }
    // Pick up external changes (e.g. AI-filled values) without clobbering in-progress typing.
    LaunchedEffect(value) { if (text.toDoubleOrNull() != value) text = Fmt.num(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t.filter { it.isDigit() || it == '.' }
            onChange(text.toDoubleOrNull())
        },
        label = { Text(label) }, singleLine = true,
        suffix = suffix?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
fun IntRow(label: String, value: Int?, onChange: (Int?) -> Unit, suffix: String? = null, modifier: Modifier = Modifier.fillMaxWidth()) =
    NumberRow(label, value?.toDouble(), { onChange(it?.toInt()) }, suffix, modifier)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <E : Enum<E>> EnumRow(label: String, value: E?, options: List<E>, text: (E) -> String, onChange: (E?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = value?.let(text) ?: "", onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("—") }, onClick = { onChange(null); open = false })
            options.forEach { o -> DropdownMenuItem(text = { Text(text(o)) }, onClick = { onChange(o); open = false }) }
        }
    }
}

/** Yes / No / unknown chips. */
@Composable
fun YesNoRow(label: String, value: Boolean?, onChange: (Boolean?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f))
        FilterChip(selected = value == true, onClick = { onChange(if (value == true) null else true) }, label = { Text("Yes") })
        FilterChip(selected = value == false, onClick = { onChange(if (value == false) null else false) }, label = { Text("No") })
    }
}

@Composable
fun DateTimeRow(label: String, value: Long?, onChange: (Long?) -> Unit) {
    val context = LocalContext.current
    fun pick() {
        val cal = Calendar.getInstance().apply { value?.let { timeInMillis = it } }
        DatePickerDialog(context, { _, y, m, d ->
            TimePickerDialog(context, { _, h, min ->
                cal.set(y, m, d, h, min, 0)
                onChange(cal.timeInMillis)
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }
    OutlinedTextField(
        value = value?.let { Fmt.dateTime(it) } ?: "", onValueChange = {}, readOnly = true,
        label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            Row {
                if (value != null) IconButton(onClick = { onChange(null) }) { Icon(Icons.Default.Clear, "Clear") }
                IconButton(onClick = { pick() }) { Icon(Icons.Default.Schedule, "Pick time") }
            }
        },
    )
}

@Composable
fun KeyValue(label: String, value: String?) {
    if (value.isNullOrBlank() || value == "–") return
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(0.42f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, Modifier.weight(0.58f), style = MaterialTheme.typography.bodyMedium)
    }
}
