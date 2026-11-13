package com.bytestreamdevelopment.dataapp.transfer

import com.bytestreamdevelopment.dataapp.storage.ReceivedContact
import com.bytestreamdevelopment.dataapp.storage.ReceivedFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ArrayBlockingQueue

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
        val viaWifi: Boolean = false,
    ) : ReceiverStatus
    data class Error(val message: String) : ReceiverStatus
}

/** Anfrage eines unbekannten Geräts, die der Nutzer bestätigen muss. */
data class IncomingRequest(
    val id: Long,
    val senderName: String,
    val deviceId: String,
    val fileCount: Int,
    val totalBytes: Long,
    val code: String,
)

enum class Decision { ACCEPT, ACCEPT_AND_TRUST, REJECT }

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

    private val _pending = MutableStateFlow<IncomingRequest?>(null)
    /** Offene Empfangsanfrage (für Dialog und Benachrichtigung). */
    val pending: StateFlow<IncomingRequest?> = _pending.asStateFlow()

    @Volatile
    private var answers: ArrayBlockingQueue<Decision>? = null

    /** Stellt die Anfrage; der Aufrufer wartet an der zurückgegebenen Warteschlange auf die Antwort. */
    internal fun ask(request: IncomingRequest): ArrayBlockingQueue<Decision> {
        val queue = ArrayBlockingQueue<Decision>(1)
        answers = queue
        _pending.value = request
        return queue
    }

    fun answer(requestId: Long, decision: Decision) {
        if (_pending.value?.id == requestId) answers?.offer(decision)
    }

    /** Beantwortet eine eventuell offene Anfrage mit „Ablehnen“ (Abbruch, Dienst wird beendet). */
    internal fun rejectPending() {
        answers?.offer(Decision.REJECT)
    }

    internal fun clearPending() {
        answers = null
        _pending.value = null
    }

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
