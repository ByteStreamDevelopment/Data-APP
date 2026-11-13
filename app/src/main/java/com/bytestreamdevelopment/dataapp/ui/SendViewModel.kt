package com.bytestreamdevelopment.dataapp.ui

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bytestreamdevelopment.dataapp.storage.AppPrefs
import com.bytestreamdevelopment.dataapp.transfer.Bluetooth
import com.bytestreamdevelopment.dataapp.transfer.Feedback
import com.bytestreamdevelopment.dataapp.transfer.FoundReceiver
import com.bytestreamdevelopment.dataapp.transfer.OutgoingItem
import com.bytestreamdevelopment.dataapp.transfer.ProximityTracker
import com.bytestreamdevelopment.dataapp.transfer.ReceiverState
import com.bytestreamdevelopment.dataapp.transfer.SendCallbacks
import com.bytestreamdevelopment.dataapp.transfer.Sender
import com.bytestreamdevelopment.dataapp.transfer.ShareParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

sealed interface SendPhase {
    data object Preparing : SendPhase
    data object Searching : SendPhase
    data class Connecting(val to: String, val wifi: Boolean = false) : SendPhase

    /** Anfrage gesendet; das andere Handy muss annehmen. [code] steht auch dort auf dem Bildschirm. */
    data class Waiting(val to: String, val code: String) : SendPhase
    data class Sending(
        val to: String,
        val index: Int,
        val count: Int,
        val fileName: String,
        val sent: Long,
        val total: Long,
        val bytesPerSecond: Long,
        val viaWifi: Boolean,
    ) : SendPhase
    data class Done(val to: String, val count: Int) : SendPhase
    data class Failed(val message: String, val canRetry: Boolean) : SendPhase
}

data class NearbyReceiver(val address: String, val name: String, val rssi: Int, val close: Boolean)

class SendViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = AppPrefs(app)

    private val _phase = MutableStateFlow<SendPhase>(SendPhase.Preparing)
    val phase: StateFlow<SendPhase> = _phase.asStateFlow()

    private val _items = MutableStateFlow<List<OutgoingItem>>(emptyList())
    val items: StateFlow<List<OutgoingItem>> = _items.asStateFlow()

    private val _nearby = MutableStateFlow<List<NearbyReceiver>>(emptyList())
    val nearby: StateFlow<List<NearbyReceiver>> = _nearby.asStateFlow()

    val proximity get() = prefs.proximity

    private var loaded = false
    private var scanJob: Job? = null
    private var sendJob: Job? = null
    private val found = HashMap<String, FoundReceiver>()
    private val lastSeen = HashMap<String, Long>()
    private val smoothed = HashMap<String, Int>()

    /** Offene Verbindungen des laufenden Versands; Schließen bricht blockierende Aufrufe ab. */
    private val closeables = CopyOnWriteArrayList<Closeable>()

    /** Kennzeichnet den aktuellen Versand, damit ein abgebrochener keine Anzeige mehr überschreibt. */
    @Volatile
    private var token: Any? = null

    fun load(intent: Intent) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { ShareParser.parse(getApplication(), intent) }
                _items.value = list
                _phase.value = if (list.isEmpty()) {
                    SendPhase.Failed("Es wurde nichts zum Senden übergeben.", canRetry = false)
                } else {
                    SendPhase.Searching
                }
            } catch (e: Exception) {
                Log.w(TAG, "Teilen-Intent nicht lesbar", e)
                _phase.value = SendPhase.Failed("Die Dateien konnten nicht gelesen werden: ${e.message}", canRetry = false)
            }
        }
    }

    /** Startet die Suche; wird von der Oberfläche aufgerufen, sobald Berechtigungen und Bluetooth bereit sind. */
    fun startScanning() {
        if (_phase.value != SendPhase.Searching || scanJob?.isActive == true) return
        found.clear(); lastSeen.clear(); smoothed.clear()
        _nearby.value = emptyList()
        val tracker = ProximityTracker()
        scanJob = viewModelScope.launch {
            // Geräte entfernen, die seit 4 s nicht mehr gesehen wurden.
            launch {
                while (true) {
                    delay(1000)
                    val now = SystemClock.elapsedRealtime()
                    val stale = lastSeen.filterValues { now - it > 4000 }.keys
                    stale.forEach { found.remove(it); lastSeen.remove(it); smoothed.remove(it); tracker.remove(it) }
                    if (stale.isNotEmpty()) publishNearby(tracker)
                }
            }
            try {
                Sender.scan(getApplication()).collect { r ->
                    val key = r.device.address
                    found[key] = r
                    lastSeen[key] = SystemClock.elapsedRealtime()
                    smoothed[key] = tracker.update(key, r.rssi)
                    publishNearby(tracker)
                    val threshold = prefs.proximity.thresholdDbm
                    if (threshold != null && tracker.isClose(key, threshold)) sendTo(key)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Scan fehlgeschlagen", e)
                _phase.value = SendPhase.Failed(e.message ?: "Bluetooth-Suche fehlgeschlagen", canRetry = true)
            }
        }
    }

    private fun publishNearby(tracker: ProximityTracker) {
        val threshold = prefs.proximity.thresholdDbm
        _nearby.value = found.values
            .map {
                val addr = it.device.address
                NearbyReceiver(addr, it.name, smoothed[addr] ?: it.rssi, threshold != null && tracker.isClose(addr, threshold))
            }
            .sortedByDescending { it.rssi }
    }

    fun sendTo(address: String) {
        val target = found[address] ?: return
        if (_phase.value != SendPhase.Searching) return
        scanJob?.cancel()
        scanJob = null
        val items = _items.value
        val app = getApplication<Application>()
        val mine = Any()
        token = mine
        fun show(phase: SendPhase) {
            if (token === mine) _phase.value = phase
        }
        show(SendPhase.Connecting(target.name))
        Feedback.detected(app, prefs)
        sendJob = viewModelScope.launch {
            try {
                var start = SystemClock.elapsedRealtime()
                var lastUi = 0L
                var started = false
                val callbacks = SendCallbacks(
                    onCloseable = { closeables += it },
                    onWaiting = { code -> show(SendPhase.Waiting(target.name, code)) },
                    onAccepted = { wifi -> show(SendPhase.Connecting(target.name, wifi)) },
                    onProgress = { index, sent, total, viaWifi ->
                        val now = SystemClock.elapsedRealtime()
                        if (!started) {
                            started = true
                            start = now // Wartezeit auf die Annahme nicht in die Geschwindigkeit einrechnen
                        }
                        if (now - lastUi >= 200 || sent == total) {
                            lastUi = now
                            val secs = (now - start).coerceAtLeast(1) / 1000.0
                            show(
                                SendPhase.Sending(
                                    target.name, index, items.size, items[index].name, sent, total, (sent / secs).toLong(), viaWifi,
                                )
                            )
                        }
                    },
                )
                withContext(Dispatchers.IO) {
                    delay(200) // Scanner kurz zur Ruhe kommen lassen, verbessert den Verbindungsaufbau
                    Sender.send(app, prefs, target.device, target.psm, Bluetooth.deviceName(app), items, callbacks)
                }
                ReceiverState.lastTransferAt = SystemClock.elapsedRealtime()
                if (token === mine) Feedback.success(app, prefs)
                show(SendPhase.Done(target.name, items.size))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Senden fehlgeschlagen", e)
                ReceiverState.lastTransferAt = SystemClock.elapsedRealtime()
                if (token === mine) Feedback.failure(app, prefs)
                show(
                    SendPhase.Failed(
                        "Senden an ${target.name} fehlgeschlagen: ${e.message ?: "Verbindung unterbrochen"}", canRetry = true,
                    )
                )
            } finally {
                closeAll()
            }
        }
    }

    /** Bricht Verbindungsaufbau, Warten oder Übertragung ab. */
    fun cancel() {
        val phase = _phase.value
        if (phase !is SendPhase.Connecting && phase !is SendPhase.Waiting && phase !is SendPhase.Sending) return
        token = null
        _phase.value = SendPhase.Failed("Übertragung abgebrochen.", canRetry = true)
        sendJob?.cancel()
        closeAll()
    }

    private fun closeAll() {
        val open = closeables.toList()
        closeables.clear()
        // Schließen kann kurz blockieren (WLAN-Gruppe abbauen) → nicht auf dem Hauptthread.
        Thread { open.forEach { runCatching { it.close() } } }.start()
    }

    fun retry() {
        if (_items.value.isEmpty()) return
        _phase.value = SendPhase.Searching
        startScanning()
    }

    /** Suche anhalten, solange die App im Hintergrund ist (spart Akku). */
    fun pauseScanning() {
        if (_phase.value == SendPhase.Searching) {
            scanJob?.cancel()
            scanJob = null
        }
    }

    override fun onCleared() {
        token = null
        closeAll()
    }

    companion object {
        private const val TAG = "SendViewModel"
    }
}
