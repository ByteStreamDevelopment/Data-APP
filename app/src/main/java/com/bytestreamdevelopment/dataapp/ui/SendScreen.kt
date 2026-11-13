package com.bytestreamdevelopment.dataapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.bytestreamdevelopment.dataapp.storage.Proximity
import com.bytestreamdevelopment.dataapp.transfer.Proto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(vm: SendViewModel, onClose: () -> Unit) {
    val phase by vm.phase.collectAsState()
    val items by vm.items.collectAsState()
    val nearby by vm.nearby.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Senden") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Schließen") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (items.isNotEmpty()) {
                FilesCard(items.map { it.name }, items.sumOf { it.size }, isText = items.all { it.mimeType == Proto.MIME_TEXT })
            }

            BluetoothGate {
                LaunchedEffect(phase) { if (phase == SendPhase.Searching) vm.startScanning() }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.startScanning() }
                LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.pauseScanning() }
                PhaseContent(
                    phase, nearby, vm.proximity,
                    onSend = vm::sendTo, onRetry = vm::retry, onCancel = vm::cancel, onClose = onClose,
                )
            }
        }
    }
}

@Composable
private fun FilesCard(names: List<String>, total: Long, isText: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                when {
                    isText -> "Wird in die Zwischenablage des anderen Handys gelegt"
                    names.size == 1 -> "1 Datei · ${formatSize(total)}"
                    else -> "${names.size} Dateien · ${formatSize(total)}"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            names.take(4).forEach { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) }
            if (names.size > 4) Text("… und ${names.size - 4} weitere", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PhaseContent(
    phase: SendPhase,
    nearby: List<NearbyReceiver>,
    proximity: Proximity,
    onSend: (String) -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (phase) {
            SendPhase.Preparing -> {
                CircularProgressIndicator()
                Text("Dateien werden vorbereitet …")
            }
            SendPhase.Searching -> {
                Pulse(Icons.Default.Share, active = true, size = 200.dp)
                Text(
                    if (proximity == Proximity.MANUAL) "Wähle das Handy, an das gesendet werden soll"
                    else "Halte dein Handy jetzt an das andere Handy",
                    style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
                )
                Text(
                    "Auf dem anderen Handy muss die Data-App geöffnet und empfangsbereit sein.",
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (nearby.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Suche nach Handys in der Nähe …", modifier = Modifier.padding(start = 8.dp))
                    }
                } else {
                    SectionTitle("In der Nähe")
                    nearby.forEach { NearbyRow(it, onSend) }
                }
            }
            is SendPhase.Connecting -> {
                CircularProgressIndicator(Modifier.size(64.dp))
                Text(
                    if (phase.wifi) "Baue schnelle WLAN-Verbindung zu ${phase.to} auf …" else "Verbinde mit ${phase.to} …",
                    style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                )
                OutlinedButton(onClick = onCancel) { Text("Abbrechen") }
            }
            is SendPhase.Waiting -> {
                CircularProgressIndicator(Modifier.size(64.dp))
                Text(
                    "Warte auf Bestätigung von ${phase.to} …",
                    style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                )
                Text("Code: ${phase.code}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Auf dem anderen Handy muss derselbe Code stehen. Bei vertrauten Geräten geht es ohne Nachfrage weiter.",
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onCancel) { Text("Abbrechen") }
            }
            is SendPhase.Sending -> {
                Pulse(Icons.Default.Share, active = false, size = 140.dp)
                Text(
                    if (phase.viaWifi) "Sende an ${phase.to} (per WLAN)" else "Sende an ${phase.to}",
                    style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                )
                Text(
                    "${phase.fileName} (${phase.index + 1}/${phase.count})",
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = { if (phase.total > 0) phase.sent.toFloat() / phase.total else 1f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${formatSize(phase.sent)} von ${formatSize(phase.total)} · ${formatSize(phase.bytesPerSecond)}/s",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Die Handys dürfen jetzt wieder auseinander – bleib aber in der Nähe.",
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onCancel) { Text("Abbrechen") }
            }
            is SendPhase.Done -> {
                Icon(
                    Icons.Default.CheckCircle, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(96.dp),
                )
                Text(
                    if (phase.count == 1) "Gesendet an ${phase.to}" else "${phase.count} Dateien an ${phase.to} gesendet",
                    style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                )
                Button(onClick = onClose) { Text("Fertig") }
            }
            is SendPhase.Failed -> {
                Icon(
                    Icons.Default.Warning, contentDescription = null,
                    tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(72.dp),
                )
                Text(phase.message, textAlign = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (phase.canRetry) Button(onClick = onRetry) { Text("Erneut versuchen") }
                    OutlinedButton(onClick = onClose) { Text("Schließen") }
                }
            }
        }
    }
}

@Composable
private fun NearbyRow(r: NearbyReceiver, onSend: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Phone, contentDescription = null)
            Column(
                Modifier
                    .padding(horizontal = 12.dp)
                    .weight(1f),
            ) {
                Text(r.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(progress = { signalLevel(r.rssi) }, modifier = Modifier.width(80.dp))
                    Text(
                        if (r.close) " ganz nah" else " ${r.rssi} dBm",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            FilledTonalButton(onClick = { onSend(r.address) }) { Text("Senden") }
        }
    }
}
