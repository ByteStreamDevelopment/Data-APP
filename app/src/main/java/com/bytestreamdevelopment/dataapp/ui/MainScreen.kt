package com.bytestreamdevelopment.dataapp.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bytestreamdevelopment.dataapp.storage.AppPrefs
import com.bytestreamdevelopment.dataapp.storage.Proximity
import com.bytestreamdevelopment.dataapp.storage.ReceivedFile
import com.bytestreamdevelopment.dataapp.transfer.ReceiverService
import com.bytestreamdevelopment.dataapp.transfer.ReceiverState
import com.bytestreamdevelopment.dataapp.transfer.ReceiverStatus
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val prefs = remember { AppPrefs(ctx) }
    val status by ReceiverState.status.collectAsState()
    val history by ReceiverState.history.collectAsState()
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { ReceiverService.ensureHistory(ctx) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Data-App") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Einstellungen")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                BluetoothGate {
                    var autoStarted by rememberSaveable { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        if (!autoStarted && prefs.autoReceive && status == ReceiverStatus.Off) {
                            ReceiverService.start(ctx)
                        }
                        autoStarted = true
                    }
                    ReceiverCard(status)
                }
            }
            item { HowToCard() }
            item { SectionTitle("Empfangen") }
            val list = history.orEmpty()
            if (list.isEmpty()) {
                item {
                    Text(
                        "Noch nichts empfangen.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(list, key = { "${it.uri}#${it.time}" }) { file ->
                    ReceivedRow(file, onOpen = { open(ctx, file) }, onRemove = { ReceiverService.removeFromHistory(ctx, file) })
                    HorizontalDivider()
                }
            }
        }
    }

    if (showSettings) SettingsDialog(prefs) { showSettings = false }
}

@Composable
private fun ReceiverCard(status: ReceiverStatus) {
    val ctx = LocalContext.current
    val on = status is ReceiverStatus.Waiting || status is ReceiverStatus.Receiving
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Pulse(Icons.Default.Phone, active = status is ReceiverStatus.Waiting)
            when (status) {
                ReceiverStatus.Off -> Text("Empfang ist aus", style = MaterialTheme.typography.titleLarge)
                ReceiverStatus.Waiting -> {
                    Text("Bereit zum Empfangen", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Halte das sendende Handy einfach an dieses.",
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is ReceiverStatus.Receiving -> {
                    Text("Empfange von ${status.from}", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Text(
                        "${status.fileName} (${status.fileIndex + 1}/${status.fileCount})",
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    LinearProgressIndicator(
                        progress = { if (status.totalBytes > 0) status.bytes.toFloat() / status.totalBytes else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${formatSize(status.bytes)} von ${formatSize(status.totalBytes)}", style = MaterialTheme.typography.bodySmall)
                }
                is ReceiverStatus.Error -> {
                    Text("Empfang gestoppt", style = MaterialTheme.typography.titleLarge)
                    Text(status.message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Empfangsbereit", modifier = Modifier.padding(end = 12.dp))
                Switch(
                    checked = on,
                    onCheckedChange = { if (it) ReceiverService.start(ctx) else ReceiverService.stop(ctx) },
                )
            }
        }
    }
}

@Composable
private fun HowToCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null)
                Text("So sendest du etwas", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
            }
            Text("1. Foto oder Datei öffnen (Galerie, Dateien …) und auf „Teilen“ tippen.")
            Text("2. „Data-App“ auswählen.")
            Text("3. Handys aneinanderhalten – die Übertragung startet automatisch.")
            Text(
                "Auf dem anderen Handy muss die Data-App installiert und empfangsbereit sein.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReceivedRow(file: ReceivedFile, onOpen: () -> Unit, onRemove: () -> Unit) {
    val icon = when {
        file.mimeType.startsWith("image/") -> Icons.Default.AccountBox
        file.mimeType.startsWith("video/") -> Icons.Default.PlayArrow
        file.mimeType.startsWith("audio/") -> Icons.Default.Star
        else -> Icons.Default.Check
    }
    val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(file.time))
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("von ${file.from} · ${formatSize(file.size)} · $time", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = "Aus Liste entfernen") }
        },
    )
}

private fun open(ctx: android.content.Context, file: ReceivedFile) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(file.uri, file.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "Keine App zum Öffnen gefunden", Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Toast.makeText(ctx, "Datei ist nicht mehr vorhanden", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun SettingsDialog(prefs: AppPrefs, onDismiss: () -> Unit) {
    var proximity by remember { mutableStateOf(prefs.proximity) }
    var autoReceive by remember { mutableStateOf(prefs.autoReceive) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } },
        title = { Text("Einstellungen") },
        text = {
            Column {
                Text("Automatisch senden, wenn die Handys …", style = MaterialTheme.typography.labelLarge)
                Proximity.entries.forEach { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = p == proximity, role = Role.RadioButton) {
                                proximity = p
                                prefs.proximity = p
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = p == proximity, onClick = null)
                        Text(p.label, modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 10.dp))
                    }
                }
                Text(
                    "Klappt das Aneinanderhalten bei deinen Handys nicht zuverlässig, wähle „Sehr nah“.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Beim Öffnen automatisch empfangsbereit", modifier = Modifier.weight(1f))
                    Switch(checked = autoReceive, onCheckedChange = { autoReceive = it; prefs.autoReceive = it })
                }
            }
        },
    )
}
