package com.bytestreamdevelopment.dataapp.ui

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bytestreamdevelopment.dataapp.storage.AppPrefs
import com.bytestreamdevelopment.dataapp.transfer.Bluetooth
import com.bytestreamdevelopment.dataapp.transfer.FoundReceiver
import com.bytestreamdevelopment.dataapp.transfer.OutgoingItem
import com.bytestreamdevelopment.dataapp.transfer.ProximityTracker
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

sealed interface SendPhase {
    data object Preparing : SendPhase
    data object Searching : SendPhase
    data class Connecting(val to: String) : SendPhase
    data class Sending(
        val to: String,
        val index: Int,
        val count: Int,
        val fileName: String,
        val sent: Long,
        val total: Long,
        val bytesPerSecond: Long,
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
    private val found = HashMap<String, FoundReceiver>()
    private val lastSeen = HashMap<String, Long>()
    private val smoothed = HashMap<String, Int>()

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
        _phase.value = SendPhase.Connecting(target.name)
        viewModelScope.launch {
            try {
                val start = SystemClock.elapsedRealtime()
                var lastUi = 0L
                withContext(Dispatchers.IO) {
                    delay(200) // Scanner kurz zur Ruhe kommen lassen, verbessert den Verbindungsaufbau
                    Sender.send(target.device, target.psm, Bluetooth.deviceName(getApplication()), items) { index, sent, total ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastUi >= 200 || sent == total) {
                            lastUi = now
                            val secs = (now - start).coerceAtLeast(1) / 1000.0
                            _phase.value = SendPhase.Sending(
                                target.name, index, items.size, items[index].name, sent, total, (sent / secs).toLong(),
                            )
                        }
                    }
                }
                _phase.value = SendPhase.Done(target.name, items.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Senden fehlgeschlagen", e)
                _phase.value = SendPhase.Failed(
                    "Senden an ${target.name} fehlgeschlagen: ${e.message ?: "Verbindung unterbrochen"}", canRetry = true,
                )
            }
        }
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

    companion object {
        private const val TAG = "SendViewModel"
    }
}
