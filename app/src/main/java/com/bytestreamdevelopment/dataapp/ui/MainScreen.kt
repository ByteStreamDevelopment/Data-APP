package com.bytestreamdevelopment.dataapp.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bytestreamdevelopment.dataapp.storage.AppPrefs
import com.bytestreamdevelopment.dataapp.storage.Proximity
import com.bytestreamdevelopment.dataapp.storage.ReceivedFile
import com.bytestreamdevelopment.dataapp.transfer.Decision
import com.bytestreamdevelopment.dataapp.transfer.IncomingRequest
import com.bytestreamdevelopment.dataapp.transfer.Proto
import com.bytestreamdevelopment.dataapp.transfer.ReceiverService
import com.bytestreamdevelopment.dataapp.transfer.ReceiverState
import com.bytestreamdevelopment.dataapp.transfer.ReceiverStatus
import com.bytestreamdevelopment.dataapp.transfer.WifiDirect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val prefs = remember { AppPrefs(ctx) }
    val status by ReceiverState.status.collectAsState()
    val history by ReceiverState.history.collectAsState()
    val contacts by ReceiverState.contacts.collectAsState()
    val pending by ReceiverState.pending.collectAsState()
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
            item { MyCardCard(prefs) }
            val contactList = contacts.orEmpty()
            if (contactList.isNotEmpty()) {
                item { SectionTitle("Empfangene Kontakte") }
                items(contactList, key = { "c#${it.time}#${it.card.hashCode()}" }) { entry ->
                    ReceivedContactRow(entry, onRemove = { ReceiverService.removeContact(ctx, entry) })
                    HorizontalDivider()
                }
            }
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

    pending?.let { RequestDialog(it) }
    if (showSettings) SettingsDialog(prefs) { showSettings = false }
}

/** Nachfrage, bevor von einem unbekannten Gerät empfangen wird. */
@Composable
private fun RequestDialog(request: IncomingRequest) {
    var trust by remember(request.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {}, // bewusst nur über die Knöpfe schließbar
        title = { Text("Empfang bestätigen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${request.senderName} möchte dir " +
                        (if (request.fileCount == 1) "1 Element" else "${request.fileCount} Elemente") +
                        " senden (${formatSize(request.totalBytes)}).",
                )
                Text("Code: ${request.code}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Nimm nur an, wenn auf dem sendenden Handy derselbe Code steht.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth().clickable { trust = !trust },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = trust, onCheckedChange = null)
                    Text("Diesem Gerät künftig vertrauen", modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                ReceiverState.answer(request.id, if (trust) Decision.ACCEPT_AND_TRUST else Decision.ACCEPT)
            }) { Text("Annehmen") }
        },
        dismissButton = {
            TextButton(onClick = { ReceiverState.answer(request.id, Decision.REJECT) }) { Text("Ablehnen") }
        },
    )
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
                    Text(
                        "Empfange von ${status.from}" + if (status.viaWifi) " (per WLAN)" else "",
                        style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                    )
                    Text(
                        "${status.fileName} (${status.fileIndex + 1}/${status.fileCount})",
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    LinearProgressIndicator(
                        progress = { if (status.totalBytes > 0) status.bytes.toFloat() / status.totalBytes else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${formatSize(status.bytes)} von ${formatSize(status.totalBytes)}", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { ReceiverService.cancelTransfer(ctx) }) { Text("Abbrechen") }
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
            Text("1. Foto, Datei oder Link öffnen und auf „Teilen“ tippen.")
            Text("2. „Data-App“ auswählen.")
            Text("3. Handys aneinanderhalten – die Übertragung startet automatisch.")
            Text("Ohne Datei: Handys einfach aneinanderhalten, um eure Visitenkarten auszutauschen.")
            Text(
                "Auf dem anderen Handy muss die Data-App installiert und empfangsbereit sein.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReceivedRow(file: ReceivedFile, onOpen: () -> Unit, onRemove: () -> Unit) {
    val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(file.time))
    val isLink = file.text?.let(Proto::asLink) != null
    val detail = when {
        isLink -> "Link von ${file.from} · $time"
        file.text != null -> "Text von ${file.from} · $time"
        else -> "von ${file.from} · ${formatSize(file.size)} · $time"
    }
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        leadingContent = {
            when {
                file.text != null -> Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(40.dp).padding(8.dp))
                file.mimeType.startsWith("image/") -> Thumbnail(file.uri, Icons.Default.Check)
                file.mimeType.startsWith("video/") -> Thumbnail(file.uri, Icons.Default.PlayArrow)
                file.mimeType.startsWith("audio/") -> Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(40.dp).padding(8.dp))
                else -> Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(40.dp).padding(8.dp))
            }
        },
        headlineContent = { Text(file.name, maxLines = if (file.text != null) 2 else 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = "Aus Liste entfernen") }
        },
    )
}

/** Vorschaubild eines empfangenen Fotos/Videos; solange keins da ist, das Ersatzsymbol. */
@Composable
private fun Thumbnail(uri: Uri, fallback: ImageVector) {
    val ctx = LocalContext.current
    var image by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        image = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.loadThumbnail(uri, Size(160, 160), null).asImageBitmap() }.getOrNull()
        }
    }
    val bitmap = image
    if (bitmap != null) {
        Image(
            bitmap, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)),
        )
    } else {
        Icon(fallback, contentDescription = null, modifier = Modifier.size(40.dp).padding(8.dp))
    }
}

private fun open(ctx: Context, file: ReceivedFile) {
    val text = file.text
    try {
        if (text != null) {
            val link = Proto.asLink(text)
            if (link != null) {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Data-App", text))
                Toast.makeText(ctx, "In die Zwischenablage kopiert", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(file.uri, file.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "Keine App zum Öffnen gefunden", Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Toast.makeText(ctx, "Datei ist nicht mehr vorhanden", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun SettingsDialog(prefs: AppPrefs, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var proximity by remember { mutableStateOf(prefs.proximity) }
    var autoReceive by remember { mutableStateOf(prefs.autoReceive) }
    var autoBoot by remember { mutableStateOf(prefs.autoStartOnBoot) }
    var ask by remember { mutableStateOf(prefs.askBeforeReceive) }
    var trustedCount by remember { mutableIntStateOf(prefs.trusted.size) }
    var feedback by remember { mutableStateOf(prefs.feedback) }
    var fastBt by remember { mutableStateOf(prefs.fastBluetooth) }
    var wifi by remember { mutableStateOf(prefs.wifiDirect && WifiDirect.hasPermission(ctx)) }
    val wifiPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        wifi = WifiDirect.hasPermission(ctx)
        prefs.wifiDirect = wifi
        if (!wifi) Toast.makeText(ctx, "Ohne die Berechtigung bleibt es bei Bluetooth.", Toast.LENGTH_LONG).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } },
        title = { Text("Einstellungen") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
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
                Text("Sicherheit", style = MaterialTheme.typography.labelLarge)
                SettingSwitch(
                    "Vor dem Empfang nachfragen",
                    "Unbekannte Geräte brauchen deine Bestätigung. Die Übertragung ist immer verschlüsselt.",
                    ask,
                ) { ask = it; prefs.askBeforeReceive = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (trustedCount == 1) "1 vertrautes Gerät" else "$trustedCount vertraute Geräte",
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(enabled = trustedCount > 0, onClick = { prefs.trusted = emptyMap(); trustedCount = 0 }) {
                        Text("Alle entfernen")
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("Geschwindigkeit", style = MaterialTheme.typography.labelLarge)
                SettingSwitch(
                    "Große Übertragungen per WLAN-Direct",
                    if (!WifiDirect.isSupported(ctx)) "Dieses Handy unterstützt kein WLAN-Direct."
                    else "Ab 5 MB, wenn beide Handys es erlauben und WLAN eingeschaltet ist. Sonst Bluetooth.",
                    wifi, enabled = WifiDirect.isSupported(ctx),
                ) {
                    if (it && !WifiDirect.hasPermission(ctx)) {
                        val perms = if (android.os.Build.VERSION.SDK_INT >= 33) {
                            arrayOf(WifiDirect.permission)
                        } else {
                            arrayOf(WifiDirect.permission, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                        }
                        wifiPermission.launch(perms)
                    } else {
                        wifi = it
                        prefs.wifiDirect = it
                    }
                }
                SettingSwitch(
                    "Schnelleres Bluetooth (2M-PHY)",
                    "Fordert eine schnellere Funkverbindung an, wenn beide Handys sie unterstützen.",
                    fastBt,
                ) { fastBt = it; prefs.fastBluetooth = it }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("Allgemein", style = MaterialTheme.typography.labelLarge)
                SettingSwitch("Vibration und Ton", "Beim Erkennen des anderen Handys und am Ende der Übertragung.", feedback) {
                    feedback = it; prefs.feedback = it
                }
                SettingSwitch("Beim Öffnen automatisch empfangsbereit", null, autoReceive) {
                    autoReceive = it; prefs.autoReceive = it
                }
                SettingSwitch("Nach Neustart des Handys empfangsbereit", null, autoBoot) {
                    autoBoot = it; prefs.autoStartOnBoot = it
                }
            }
        },
    )
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
