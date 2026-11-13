package com.bytestreamdevelopment.dataapp.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.bytestreamdevelopment.dataapp.storage.CardPhotos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.bytestreamdevelopment.dataapp.storage.AppPrefs
import com.bytestreamdevelopment.dataapp.storage.ReceivedContact
import com.bytestreamdevelopment.dataapp.transfer.ContactCard
import com.bytestreamdevelopment.dataapp.transfer.ContactExchanger
import com.bytestreamdevelopment.dataapp.transfer.ReceiverService
import java.text.DateFormat
import java.util.Date

/** „Meine Visitenkarte“: Anzeige, Bearbeiten und Schalter für den Kontaktaustausch. */
@Composable
fun MyCardCard(prefs: AppPrefs) {
    val ctx = LocalContext.current
    var card by remember { mutableStateOf(prefs.myCard) }
    var photo by remember { mutableStateOf(CardPhotos.loadMine(ctx)) }
    var enabled by remember { mutableStateOf(prefs.contactExchange) }
    var editing by rememberSaveable { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, contentDescription = null)
                Text(
                    "Meine Visitenkarte", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        prefs.contactExchange = it
                        ReceiverService.refresh(ctx)
                    },
                )
            }
            if (card.isEmpty) {
                Text(
                    "Noch nicht eingerichtet. Trage deine Daten ein – dann werden sie automatisch ausgetauscht, " +
                        "wenn du dein Handy ohne Datei an ein anderes hältst.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(photo, 48.dp)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(card.title, style = MaterialTheme.typography.bodyLarge)
                        if (card.details.isNotBlank()) {
                            Text(
                                card.details, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    if (enabled) "Wird beim Aneinanderhalten (ohne Datei) mit anderen Data-App-Handys getauscht."
                    else "Kontaktaustausch ist ausgeschaltet.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = { editing = true }) { Text(if (card.isEmpty) "Einrichten" else "Bearbeiten") }
        }
    }

    if (editing) {
        CardEditDialog(
            initial = card,
            initialPhoto = photo,
            onDismiss = { editing = false },
            onSave = { newCard, newPhoto ->
                card = newCard
                photo = newPhoto
                prefs.myCard = newCard
                CardPhotos.saveMine(ctx, newPhoto)
                ReceiverService.refresh(ctx)
                editing = false
            },
        )
    }
}

@Composable
private fun CardEditDialog(
    initial: ContactCard,
    initialPhoto: ByteArray?,
    onDismiss: () -> Unit,
    onSave: (ContactCard, ByteArray?) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var photo by remember { mutableStateOf(initialPhoto) }
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var phone by rememberSaveable { mutableStateOf(initial.phone) }
    var email by rememberSaveable { mutableStateOf(initial.email) }
    var company by rememberSaveable { mutableStateOf(initial.company) }
    var website by rememberSaveable { mutableStateOf(initial.website) }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { CardPhotos.fromUri(ctx, uri) }
                if (bytes != null) photo = bytes else Toast.makeText(ctx, "Bild konnte nicht geladen werden", Toast.LENGTH_SHORT).show()
            }
        }
    }
    // Möglichkeit: eigene Daten aus einem Eintrag im Adressbuch übernehmen (braucht dafür die Kontakte-Berechtigung).
    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) { CardPhotos.readContact(ctx, uri) }
                if (result == null) {
                    Toast.makeText(ctx, "Kontakt konnte nicht gelesen werden", Toast.LENGTH_SHORT).show()
                } else {
                    val (c, p) = result
                    name = c.name; phone = c.phone; email = c.email; company = c.company; website = c.website
                    if (p != null) photo = p
                }
            }
        }
    }
    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            runCatching { pickContact.launch(null) }
        } else {
            Toast.makeText(ctx, "Ohne Berechtigung können die Kontaktdaten nicht gelesen werden.", Toast.LENGTH_LONG).show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Meine Visitenkarte") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Nur ausgefüllte Felder werden weitergegeben.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Avatar(photo, 56.dp)
                    TextButton(onClick = {
                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text(if (photo == null) "Foto wählen" else "Foto ändern") }
                    if (photo != null) TextButton(onClick = { photo = null }) { Text("Entfernen") }
                }
                OutlinedButton(
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) ==
                            PackageManager.PERMISSION_GRANTED
                        if (granted) runCatching { pickContact.launch(null) }
                        else contactsPermission.launch(Manifest.permission.READ_CONTACTS)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Aus meinen Kontakten übernehmen") }
                CardField("Name", name, KeyboardType.Text, KeyboardCapitalization.Words) { name = it }
                CardField("Telefon", phone, KeyboardType.Phone) { phone = it }
                CardField("E-Mail", email, KeyboardType.Email) { email = it }
                CardField("Firma", company, KeyboardType.Text, KeyboardCapitalization.Words) { company = it }
                CardField("Website", website, KeyboardType.Uri) { website = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(ContactCard(name.trim(), phone.trim(), email.trim(), company.trim(), website.trim()), photo)
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun CardField(
    label: String,
    value: String,
    type: KeyboardType,
    caps: KeyboardCapitalization = KeyboardCapitalization.None,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(200)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = caps, keyboardType = type, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun ReceivedContactRow(entry: ReceivedContact, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.time))
    val sub = listOf(entry.card.details, time).filter { it.isNotBlank() }.joinToString(" · ")
    val photo = remember(entry.photo) { CardPhotos.loadReceived(ctx, entry.photo) }
    ListItem(
        leadingContent = { Avatar(photo, 40.dp) },
        headlineContent = { Text(entry.card.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(sub, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Row {
                IconButton(onClick = { saveToContacts(ctx, entry.card, photo) }) {
                    Icon(Icons.Default.Add, contentDescription = "In Kontakten speichern")
                }
                IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = "Aus Liste entfernen") }
            }
        },
    )
}

/** Rundes Kontaktfoto oder Personensymbol. */
@Composable
fun Avatar(photo: ByteArray?, size: Dp) {
    val image = remember(photo) { CardPhotos.decode(photo)?.asImageBitmap() }
    if (image != null) {
        Image(
            image, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape),
        )
    } else {
        Box(
            Modifier.size(size).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

private fun saveToContacts(ctx: Context, card: ContactCard, photo: ByteArray?) {
    try {
        ctx.startActivity(ContactExchanger.insertIntent(card, photo))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "Keine Kontakte-App gefunden", Toast.LENGTH_SHORT).show()
    }
}
