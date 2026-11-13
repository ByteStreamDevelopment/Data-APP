package com.bytestreamdevelopment.dataapp.transfer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.io.Closeable

/**
 * Beschleunigt eine bestehende BLE-Verbindung: Der L2CAP-Socket selbst bietet dafür keine API, aber eine
 * zusätzliche GATT-Verbindung teilt sich dieselbe Funkverbindung. Darüber werden 2M-PHY (doppelte Datenrate)
 * und ein kurzes Verbindungsintervall angefordert. Reine Optimierung – Fehler werden ignoriert.
 */
@SuppressLint("MissingPermission") // Aufrufer prüft Bluetooth.hasPermissions()
class LinkBooster(private val context: Context, private val device: BluetoothDevice) : Closeable {
    private var gatt: BluetoothGatt? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState != BluetoothProfile.STATE_CONNECTED) return
            runCatching {
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                if (Bluetooth.adapter(context)?.isLe2MPhySupported == true) {
                    gatt.setPreferredPhy(
                        BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED,
                    )
                }
            }.onFailure { Log.w(TAG, "Beschleunigung nicht möglich", it) }
        }

        override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            Log.i(TAG, "PHY jetzt tx=$txPhy rx=$rxPhy (Status $status)")
        }
    }

    fun start() {
        runCatching {
            gatt = device.connectGatt(
                context, false, callback, BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_2M_MASK,
            )
        }.onFailure { Log.w(TAG, "GATT-Verbindung für Beschleunigung fehlgeschlagen", it) }
    }

    override fun close() {
        runCatching { gatt?.close() }
        gatt = null
    }

    private companion object {
        const val TAG = "LinkBooster"
    }
}
