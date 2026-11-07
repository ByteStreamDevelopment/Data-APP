package com.bytestreamdevelopment.dataapp.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.bytestreamdevelopment.dataapp.transfer.Bluetooth
import java.util.Locale

/** Zeigt [content] erst, wenn Bluetooth-Berechtigungen erteilt sind und Bluetooth eingeschaltet ist. */
@Composable
fun BluetoothGate(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(Bluetooth.hasPermissions(ctx)) }
    var enabled by remember { mutableStateOf(Bluetooth.isEnabled(ctx)) }
    var asked by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = Bluetooth.hasPermissions(ctx)
        enabled = Bluetooth.isEnabled(ctx)
        asked = true
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        enabled = Bluetooth.isEnabled(ctx)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = Bluetooth.hasPermissions(ctx)
        enabled = Bluetooth.isEnabled(ctx)
    }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                enabled = Bluetooth.isEnabled(context)
            }
        }
        ContextCompat.registerReceiver(
            ctx, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }

    when {
        Bluetooth.adapter(ctx) == null -> NoticeCard(
            title = "Kein Bluetooth",
            text = "Dieses Gerät hat kein Bluetooth – Data-App kann hier leider nicht funktionieren.",
        )
        !granted -> NoticeCard(
            title = "Bluetooth-Zugriff erlauben",
            text = if (Build.VERSION.SDK_INT >= 31) {
                "Data-App braucht die Berechtigung „Geräte in der Nähe“, um andere Handys zu finden und Dateien zu übertragen."
            } else {
                "Auf Android 10/11 setzt die Bluetooth-Suche die Standortberechtigung voraus. Data-App nutzt deinen Standort nicht."
            },
            primary = "Erlauben" to {
                val perms = Bluetooth.requiredPermissions.toMutableList()
                if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
                permLauncher.launch(perms.toTypedArray())
            },
            secondary = if (asked) {
                "App-Einstellungen" to {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            } else {
                null
            },
        )
        !enabled -> NoticeCard(
            title = "Bluetooth ist aus",
            text = "Schalte Bluetooth ein, um Dateien zu senden oder zu empfangen.",
            primary = "Einschalten" to {
                runCatching { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                    .onFailure { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            },
        )
        else -> content()
    }
}

@Composable
fun NoticeCard(
    title: String,
    text: String,
    icon: ImageVector = Icons.Default.Warning,
    primary: Pair<String, () -> Unit>? = null,
    secondary: Pair<String, () -> Unit>? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null)
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (primary != null || secondary != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    primary?.let { (label, action) -> Button(onClick = action) { Text(label) } }
                    secondary?.let { (label, action) -> OutlinedButton(onClick = action) { Text(label) } }
                }
            }
        }
    }
}

/** Pulsierender Kreis mit Symbol – signalisiert „sucht / wartet“. */
@Composable
fun Pulse(icon: ImageVector, active: Boolean, size: Dp = 160.dp) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val t by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "t",
    )
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (active) {
            Box(
                Modifier
                    .size(size)
                    .scale(0.5f + 0.5f * t)
                    .alpha(1f - t)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
        Box(
            Modifier
                .size(size * 0.5f)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(size * 0.25f),
            )
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp),
    )
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return String.format(Locale.GERMANY, if (v < 10) "%.1f %s" else "%.0f %s", v, units[i])
}

/** Grobe Einordnung der Signalstärke für die Anzeige (0..1). */
fun signalLevel(rssi: Int): Float = ((rssi + 100) / 70f).coerceIn(0f, 1f)
