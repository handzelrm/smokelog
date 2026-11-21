package com.smokelog.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.smokelog.data.CookPhoto
import com.smokelog.data.Fmt
import com.smokelog.data.PhotoStore

/** Take or add pictures of the cook, shown as a strip of thumbnails; tap one to view, caption or delete it. */
@Composable
fun PhotosSection(
    photos: List<CookPhoto>,
    store: PhotoStore,
    startTime: Long?,
    error: String?,
    newCaptureTarget: () -> Pair<String, android.net.Uri>,
    onCaptured: (Boolean, String?) -> Unit,
    onPicked: (List<android.net.Uri>) -> Unit,
    onCaption: (CookPhoto, String) -> Unit,
    onDelete: (CookPhoto) -> Unit,
) {
    var capturePath by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var viewing by remember { mutableStateOf<CookPhoto?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        onCaptured(ok, capturePath); capturePath = null
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)) { uris ->
        if (uris.isNotEmpty()) onPicked(uris)
    }

    Section("Photos") {
        if (photos.isEmpty()) {
            Text("Bark, smoke ring, the slice… snap them as you go.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(photos, key = { it.id }) { p ->
                    Column(Modifier.size(width = 112.dp, height = 140.dp).clickable { viewing = p }) {
                        AsyncImage(
                            model = store.file(p.fileName), contentDescription = p.caption.ifBlank { "Cook photo" },
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(112.dp).clip(RoundedCornerShape(10.dp)),
                        )
                        Text(
                            p.caption.ifBlank { elapsed(p.time, startTime) }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val (path, uri) = newCaptureTarget()
                capturePath = path
                try { camera.launch(uri); cameraError = null }
                catch (e: ActivityNotFoundException) { cameraError = "No camera app found on this phone." }
            }) { Icon(Icons.Default.PhotoCamera, null); Text(" Take photo") }
            OutlinedButton(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Icon(Icons.Default.AddPhotoAlternate, null); Text(" Add photos") }
        }
        (cameraError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }

    viewing?.let { p ->
        PhotoViewer(
            photo = photos.firstOrNull { it.id == p.id } ?: p, store = store, startTime = startTime,
            onDismiss = { viewing = null },
            onCaption = { onCaption(p, it) },
            onDelete = { onDelete(p); viewing = null },
        )
    }
}

/** "5h 10m in" for photos taken during the cook; otherwise the date (or time, if today). */
private fun elapsed(time: Long, start: Long?): String = when {
    start != null && time >= start && time - start < 24 * 3_600_000L -> "${Fmt.duration((time - start) / 60_000)} in"
    Fmt.date(time) == Fmt.date(System.currentTimeMillis()) -> Fmt.time(time)
    else -> Fmt.date(time)
}

@Composable
private fun PhotoViewer(
    photo: CookPhoto, store: PhotoStore, startTime: Long?,
    onDismiss: () -> Unit, onCaption: (String) -> Unit, onDelete: () -> Unit,
) {
    var caption by remember(photo.id) { mutableStateOf(photo.caption) }
    var confirmDelete by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = { onCaption(caption); onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().imePadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Actions on top so they never sit under the navigation bar or keyboard.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (confirmDelete) {
                    TextButton(onClick = onDelete) { Text("Delete photo", color = MaterialTheme.colorScheme.error) }
                } else {
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = Color.White) }
                }
                Button(onClick = { onCaption(caption); onDismiss() }) { Text("Done") }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AsyncImage(model = store.file(photo.fileName), contentDescription = caption.ifBlank { "Cook photo" },
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            Text("${Fmt.dateTime(photo.time)}${startTime?.takeIf { photo.time >= it && photo.time - it < 24 * 3_600_000L }?.let { " · ${Fmt.duration((photo.time - it) / 60_000)} in" } ?: ""}",
                color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = caption, onValueChange = { caption = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Add a caption (e.g. bark after 6 h)") },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
            )
            Spacer(Modifier.height(48.dp))
        }
    }
}
