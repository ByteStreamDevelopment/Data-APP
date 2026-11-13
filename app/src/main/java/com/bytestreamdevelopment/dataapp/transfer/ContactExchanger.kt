package com.bytestreamdevelopment.dataapp.transfer

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.ContactsContract
import android.util.Log
import com.bytestreamdevelopment.dataapp.storage.AppPrefs
import com.bytestreamdevelopment.dataapp.storage.CardPhotos
import com.bytestreamdevelopment.dataapp.storage.Proximity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Initiator-Seite des Kontaktaustauschs: sucht im Hintergrund nach Data-App-Geräten und tauscht Visitenkarten,
 * sobald ein Gerät mit eingeschaltetem Kontaktaustausch aneinandergehalten wird. Die annehmende Seite
 * steckt in [ReceiverService.handleContact].
 */
@SuppressLint("MissingPermission") // ReceiverService prüft die Berechtigungen vor dem Start
class ContactExchanger(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: AppPrefs,
    private val instanceId: Int,
    private val trigger: ContactTrigger,
    private val onReceived: (card: ContactCard, photo: ByteArray?, from: String) -> Unit,
) {
    private var job: Job? = null

    /** true, solange irgendeine Übertragung läuft (auch eingehende) – dann wird nichts gestartet. */
    @Volatile
    var busy = false

    fun start() {
        if (job?.isActive == true || !prefs.contactExchangeActive) return
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val target = try {
                findTarget()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Hintergrundsuche fehlgeschlagen, neuer Versuch in 30 s", e)
                delay(30_000)
                continue
            }
            exchange(target)
        }
    }

    /** Scannt, bis ein Gerät nah genug ist; danach endet der Scan (wichtig für einen stabilen Verbindungsaufbau). */
    private suspend fun findTarget(): FoundReceiver {
        val tracker = ProximityTracker()
        return Sender.scan(context).first { r ->
            val meta = r.meta ?: return@first false
            val key = r.device.address
            tracker.update(key, r.rssi)
            if (busy || ReceiverState.shareActive.value) return@first false
            val now = SystemClock.elapsedRealtime()
            val close = tracker.isClose(key, thresholdDbm())
            synchronized(trigger) { trigger.shouldInitiate(instanceId, meta, close, now, lastTransferAt()) }
        }
    }

    private suspend fun exchange(target: FoundReceiver) {
        val peerId = target.meta?.instanceId ?: return
        busy = true
        try {
            withContext(Dispatchers.IO) {
                Sender.connectWithRetry(target.device, target.psm).use { s ->
                    val channel = Handshake.initiate(s.inputStream, s.outputStream)
                    val out = channel.output
                    val inp = channel.input
                    Proto.writeContactRequest(out, instanceId, prefs.myCard, CardPhotos.loadMine(context))
                    if (inp.readUnsignedByte() == Proto.ACCEPT) {
                        val payload = Proto.readCard(inp)
                        out.writeByte(Proto.DONE)
                        out.flush()
                        onReceived(payload.card, payload.photo, target.name)
                        runCatching { inp.read() } // warten, bis die Gegenseite schließt
                    } else {
                        Log.i(TAG, "Kontaktaustausch von ${target.name} abgelehnt")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Kontaktaustausch mit ${target.name} fehlgeschlagen", e)
        } finally {
            // Auch bei Fehlern/Ablehnung merken, damit nicht in Dauerschleife neu verbunden wird.
            synchronized(trigger) { trigger.markExchanged(peerId, SystemClock.elapsedRealtime()) }
            busy = false
        }
    }

    /** Kontakte nie „im ganzen Raum“ tauschen: höchstens bis „Sehr nah“. */
    private fun thresholdDbm(): Int {
        val chosen = prefs.proximity.thresholdDbm ?: Proximity.TOUCH.thresholdDbm!!
        return maxOf(chosen, Proximity.NEAR.thresholdDbm!!)
    }

    companion object {
        private const val TAG = "ContactExchanger"

        fun lastTransferAt(): Long? = ReceiverState.lastTransferAt.takeIf { it > 0 }

        /** Öffnet die Kontakte-App mit vorausgefülltem Formular zum Speichern. */
        fun insertIntent(card: ContactCard, photo: ByteArray? = null): Intent =
            Intent(ContactsContract.Intents.Insert.ACTION).apply {
                type = ContactsContract.RawContacts.CONTENT_TYPE
                if (card.name.isNotBlank()) putExtra(ContactsContract.Intents.Insert.NAME, card.name)
                if (card.phone.isNotBlank()) putExtra(ContactsContract.Intents.Insert.PHONE, card.phone)
                if (card.email.isNotBlank()) putExtra(ContactsContract.Intents.Insert.EMAIL, card.email)
                if (card.company.isNotBlank()) putExtra(ContactsContract.Intents.Insert.COMPANY, card.company)
                val rows = ArrayList<ContentValues>()
                if (card.website.isNotBlank()) {
                    rows += ContentValues().apply {
                        put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE)
                        put(ContactsContract.CommonDataKinds.Website.URL, card.website)
                    }
                }
                if (photo != null) {
                    rows += ContentValues().apply {
                        put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                        put(ContactsContract.CommonDataKinds.Photo.PHOTO, photo)
                    }
                }
                if (rows.isNotEmpty()) putParcelableArrayListExtra(ContactsContract.Intents.Insert.DATA, rows)
            }
    }
}
