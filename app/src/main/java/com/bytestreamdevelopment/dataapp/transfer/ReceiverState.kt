package com.bytestreamdevelopment.dataapp.transfer

import com.bytestreamdevelopment.dataapp.storage.ReceivedContact
import com.bytestreamdevelopment.dataapp.storage.ReceivedFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ReceiverStatus {
    data object Off : ReceiverStatus
    data object Waiting : ReceiverStatus
    data class Receiving(
        val from: String,
        val fileIndex: Int,
        val fileCount: Int,
        val fileName: String,
        val bytes: Long,
        val totalBytes: Long,
    ) : ReceiverStatus
    data class Error(val message: String) : ReceiverStatus
}

/** Gemeinsamer Zustand zwischen [ReceiverService] und Oberfläche. */
object ReceiverState {
    private val _status = MutableStateFlow<ReceiverStatus>(ReceiverStatus.Off)
    val status: StateFlow<ReceiverStatus> = _status.asStateFlow()

    private val _history = MutableStateFlow<List<ReceivedFile>?>(null)
    /** null = noch nicht geladen. */
    val history: StateFlow<List<ReceivedFile>?> = _history.asStateFlow()

    private val _contacts = MutableStateFlow<List<ReceivedContact>?>(null)
    /** Per Aneinanderhalten empfangene Kontakte; null = noch nicht geladen. */
    val contacts: StateFlow<List<ReceivedContact>?> = _contacts.asStateFlow()

    private val _shareActive = MutableStateFlow(false)
    /** true, solange der Teilen-Bildschirm offen ist → Hintergrund-Kontaktaustausch und Werbung pausieren. */
    val shareActive: StateFlow<Boolean> = _shareActive.asStateFlow()

    /** Zeitpunkt (elapsedRealtime) der letzten Dateiübertragung, egal in welche Richtung; 0 = noch keine. */
    @Volatile
    var lastTransferAt: Long = 0L

    fun setShareActive(active: Boolean) {
        _shareActive.value = active
    }

    internal fun setContacts(list: List<ReceivedContact>) {
        _contacts.value = list
    }

    internal fun set(status: ReceiverStatus) {
        _status.value = status
    }

    internal fun setHistory(list: List<ReceivedFile>) {
        _history.value = list
    }
}
