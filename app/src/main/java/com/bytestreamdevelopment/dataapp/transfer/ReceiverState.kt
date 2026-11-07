package com.bytestreamdevelopment.dataapp.transfer

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

    internal fun set(status: ReceiverStatus) {
        _status.value = status
    }

    internal fun setHistory(list: List<ReceivedFile>) {
        _history.value = list
    }
}
