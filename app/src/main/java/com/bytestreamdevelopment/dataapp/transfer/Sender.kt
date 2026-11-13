package com.bytestreamdevelopment.dataapp.transfer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.bytestreamdevelopment.dataapp.storage.AppPrefs
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.Closeable
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

/** Rückmeldungen während des Sendens (werden auf dem IO-Thread aufgerufen). */
class SendCallbacks(
    /** Offene Verbindung – zum Abbrechen einfach schließen. */
    val onCloseable: (Closeable) -> Unit,
    /** Anfrage ist raus; [code] muss mit der Anzeige auf dem anderen Handy übereinstimmen. */
    val onWaiting: (code: String) -> Unit,
    /** Empfänger hat angenommen; true = WLAN-Direct wird aufgebaut. */
    val onAccepted: (wifi: Boolean) -> Unit,
    val onProgress: (index: Int, sentBytes: Long, totalBytes: Long, viaWifi: Boolean) -> Unit,
)

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
     * Baut die verschlüsselte Verbindung auf, wartet auf die Annahme und überträgt alle Dateien –
     * bei großen Mengen über WLAN-Direct, sonst (oder wenn das scheitert) über Bluetooth.
     * Blockierend → auf Dispatchers.IO aufrufen.
     */
    suspend fun send(
        context: Context,
        prefs: AppPrefs,
        device: BluetoothDevice,
        psm: Int,
        senderName: String,
        items: List<OutgoingItem>,
        cb: SendCallbacks,
    ) {
        // Jeder Socket wird schon vor dem Verbinden gemeldet, damit „Abbrechen“ auch den Verbindungsaufbau beendet.
        val socket = connectWithRetry(device, psm, cb.onCloseable)
        currentCoroutineContext().ensureActive() // abgebrochen, während verbunden wurde → nichts mehr senden
        var booster: LinkBooster? = null
        var wifi: WifiDirect.Client? = null
        try {
            socket.use { s ->
                val channel = Handshake.initiate(s.inputStream, s.outputStream)
                val out = channel.output
                val inp = channel.input
                val total = items.sumOf { it.size }
                val offerWifi = total >= WifiDirect.MIN_BYTES && WifiDirect.isAvailable(context, prefs)
                Proto.writeHeader(out, Proto.Header(senderName, items.size, total, offerWifi))
                Proto.writeAuth(out, prefs.identity, channel.keys.transcript)
                out.flush()
                cb.onWaiting(channel.keys.code)

                when (inp.readUnsignedByte()) {
                    Proto.ACCEPT -> Unit
                    Proto.REJECT -> throw IOException("Das andere Handy hat die Übertragung abgelehnt")
                    else -> throw IOException("Unerwartete Antwort vom anderen Handy")
                }

                var data = channel
                var viaWifi = false
                if (inp.readUnsignedByte() == Proto.TRANSPORT_WIFI) {
                    val info = Proto.readWifiInfo(inp)
                    cb.onAccepted(true)
                    val client = WifiDirect.Client(context).also {
                        wifi = it
                        cb.onCloseable(it)
                    }
                    val tcp = try {
                        client.connect(info)
                    } catch (e: Exception) {
                        Log.w(TAG, "WLAN-Direct fehlgeschlagen, weiter über Bluetooth", e)
                        null
                    }
                    if (tcp != null) {
                        cb.onCloseable(tcp)
                        data = Handshake.wrap(
                            tcp.getInputStream(), tcp.getOutputStream(), channel.keys,
                            SessionKeys.WIFI_I2R, SessionKeys.WIFI_R2I,
                        )
                        viaWifi = true
                    } else {
                        client.close()
                    }
                    out.writeByte(if (viaWifi) 1 else 0)
                    out.flush()
                } else {
                    cb.onAccepted(false)
                }
                if (!viaWifi && prefs.fastBluetooth) booster = LinkBooster(context, device).also { it.start() }

                var sent = 0L
                items.forEachIndexed { index, item ->
                    cb.onProgress(index, sent, total, viaWifi)
                    Proto.writeFileHeader(data.output, Proto.FileHeader(item.name, item.mimeType, item.size))
                    item.open().use { src ->
                        // Genau die angekündigte Größe senden, sonst gerät das Protokoll aus dem Takt.
                        Proto.copyExactly(src, data.output, item.size) { n ->
                            sent += n
                            cb.onProgress(index, sent, total, viaWifi)
                        }
                    }
                }
                data.output.flush()
                if (data.input.readUnsignedByte() != Proto.DONE) throw IOException("Übertragung wurde nicht bestätigt")
            }
        } finally {
            booster?.close()
            wifi?.close()
        }
    }

    /** Der erste L2CAP-Verbindungsaufbau schlägt auf manchen Geräten sporadisch fehl → bis zu drei Versuche. */
    suspend fun connectWithRetry(
        device: BluetoothDevice,
        psm: Int,
        onSocket: (Closeable) -> Unit = {},
    ): BluetoothSocket {
        var last: IOException? = null
        repeat(3) { attempt ->
            currentCoroutineContext().ensureActive()
            val socket = device.createInsecureL2capChannel(psm)
            onSocket(socket)
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
