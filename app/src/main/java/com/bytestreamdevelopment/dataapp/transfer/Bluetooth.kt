package com.bytestreamdevelopment.dataapp.transfer

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

object Bluetooth {
    /** Laufzeit-Berechtigungen, ohne die weder gesendet noch empfangen werden kann. */
    val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            // Android 10/11: BLE-Scan setzt Standortberechtigung voraus.
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }

    /** Erteilte Berechtigungen prüfen (bei Standort genügt die genaue). */
    fun hasPermissions(context: Context): Boolean = requiredPermissions.filterNot {
        it == Manifest.permission.ACCESS_COARSE_LOCATION
    }.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun adapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    fun isEnabled(context: Context): Boolean = adapter(context)?.isEnabled == true

    /** Gerätename für die Anzeige beim Gegenüber (ohne BLUETOOTH_CONNECT abrufbar). */
    fun deviceName(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
}
