package com.bytestreamdevelopment.dataapp.transfer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream

data class FoundReceiver(
    val device: BluetoothDevice,
    val psm: Int,
    val name: String,
    val rssi: Int,
    val meta: Proto.Meta?,
)

/** Eine zu sendende Datei; [open] liefert jedes Mal einen frischen Stream. */
class OutgoingItem(val name: String, val mimeType: String, val size: Long, val open: () -> InputStream)

@SuppressLint("MissingPermission") // Aufrufer prüft Bluetooth.hasPermissions()
object Sender {
    private const val TAG = "Sender"

    /** Liefert laufend empfangsbereite Data-App-Geräte in der Nähe samt Signalstärke. */
    fun scan(context: Context, scanMode: Int = ScanSettings.SCAN_MODE_LOW_LATENCY): Flow<FoundReceiver> = callbackFlow {
        val scanner = Bluetooth.adapter(context)?.bluetoothLeScanner
            ?: throw IOException("Bluetooth ist ausgeschaltet")
        val uuid = ParcelUuid(Proto.SERVICE_UUID)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val advert = Proto.decodeAdvert(result.scanRecord?.getServiceData(uuid)) ?: return
                val meta = Proto.decodeMeta(result.scanRecord?.getManufacturerSpecificData(Proto.MANUFACTURER_ID))
                trySend(
                    FoundReceiver(result.device, advert.psm, advert.name.ifBlank { "Unbekanntes Handy" }, result.rssi, meta)
                )
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IOException("Bluetooth-Suche fehlgeschlagen (Fehler $errorCode)"))
            }
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(uuid).build())
        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        scanner.startScan(filters, settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    /**
     * Baut die Verbindung auf (mit Wiederholungen – der erste L2CAP-Verbindungsaufbau schlägt
     * auf manchen Geräten sporadisch fehl) und überträgt alle Dateien. Blockierend → auf Dispatchers.IO aufrufen.
     */
    suspend fun send(
        device: BluetoothDevice,
        psm: Int,
        senderName: String,
        items: List<OutgoingItem>,
        onProgress: (index: Int, sentBytes: Long, totalBytes: Long) -> Unit,
    ) {
        val socket = connectWithRetry(device, psm)
        socket.use { s ->
            val out = DataOutputStream(BufferedOutputStream(s.outputStream, 64 * 1024))
            val inp = DataInputStream(s.inputStream)
            val total = items.sumOf { it.size }
            Proto.writeHeader(out, Proto.Header(senderName, items.size, total))
            when (inp.readUnsignedByte()) {
                Proto.ACCEPT -> Unit
                Proto.REJECT -> throw IOException("Das andere Handy hat die Übertragung abgelehnt")
                else -> throw IOException("Unerwartete Antwort vom anderen Handy")
            }
            var sent = 0L
            items.forEachIndexed { index, item ->
                onProgress(index, sent, total)
                Proto.writeFileHeader(out, Proto.FileHeader(item.name, item.mimeType, item.size))
                item.open().use { src ->
                    // Genau die angekündigte Größe senden, sonst gerät das Protokoll aus dem Takt.
                    Proto.copyExactly(src, out, item.size) { n ->
                        sent += n
                        onProgress(index, sent, total)
                    }
                }
            }
            out.flush()
            if (inp.readUnsignedByte() != Proto.DONE) throw IOException("Übertragung wurde nicht bestätigt")
        }
    }

    suspend fun connectWithRetry(device: BluetoothDevice, psm: Int): android.bluetooth.BluetoothSocket {
        var last: IOException? = null
        repeat(3) { attempt ->
            val socket = device.createInsecureL2capChannel(psm)
            try {
                socket.connect()
                return socket
            } catch (e: IOException) {
                Log.w(TAG, "Verbindungsversuch ${attempt + 1} fehlgeschlagen", e)
                runCatching { socket.close() }
                last = e
                delay(400L * (attempt + 1))
            }
        }
        throw IOException("Verbindung zum anderen Handy fehlgeschlagen", last)
    }
}
