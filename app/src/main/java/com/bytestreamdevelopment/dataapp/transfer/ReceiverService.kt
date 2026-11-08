package com.bytestreamdevelopment.dataapp.transfer

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.bytestreamdevelopment.dataapp.MainActivity
import com.bytestreamdevelopment.dataapp.R
import com.bytestreamdevelopment.dataapp.storage.AppPrefs
import com.bytestreamdevelopment.dataapp.storage.FileSaver
import com.bytestreamdevelopment.dataapp.storage.ReceivedContact
import com.bytestreamdevelopment.dataapp.storage.ReceivedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Vordergrunddienst „Empfangsbereit“: bewirbt sich per BLE mit [Proto.SERVICE_UUID] und nimmt über einen
 * unverschlüsselten L2CAP-Kanal (keine Kopplung nötig) Dateien entgegen.
 */
@SuppressLint("MissingPermission") // Berechtigungen werden vor dem Start geprüft (siehe start()).
class ReceiverService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: BluetoothServerSocket? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var running = false
    private var advertising = false
    private var psm = 0
    private lateinit var prefs: AppPrefs

    /** Zufällige ID je Dienststart; das Gerät mit der kleineren ID startet den Kontaktaustausch. */
    private val instanceId = Random.nextInt(1, Int.MAX_VALUE)
    private val trigger = ContactTrigger()
    private lateinit var exchanger: ContactExchanger

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Hintergrundsuche nur bei eingeschaltetem Display – spart Akku.
            if (intent.action == Intent.ACTION_SCREEN_ON) updateExchanger() else exchanger.stop()
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "Advertising fehlgeschlagen: $errorCode")
            fail("Bluetooth-Werbung konnte nicht gestartet werden (Fehler $errorCode)")
        }
    }

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                fail("Bluetooth wurde ausgeschaltet")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        ensureHistory(this)
        createChannels(this)
        exchanger = ContactExchanger(this, scope, prefs, instanceId, trigger) { card, from ->
            saveContact(card, from)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REFRESH) {
            // Einstellungen/Visitenkarte geändert → Werbung (Kontakt-Flag) und Hintergrundsuche neu aufsetzen.
            if (running) {
                stopAdvertising()
                if (!ReceiverState.shareActive.value) startAdvertising()
                updateExchanger()
            } else {
                stopSelf()
            }
            return START_NOT_STICKY
        }
        // startForeground bei jedem startForegroundService-Aufruf, auch wenn der Dienst schon läuft.
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ONGOING, ongoingNotification("Halte ein anderes Handy an dieses, um Dateien zu empfangen."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: Exception) {
            Log.w(TAG, "startForeground fehlgeschlagen", e)
            ReceiverState.set(ReceiverStatus.Error("Empfang konnte nicht gestartet werden: ${e.message}"))
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) start()
        return START_NOT_STICKY
    }

    private fun start() {
        val adapter = Bluetooth.adapter(this)
        if (!Bluetooth.hasPermissions(this)) return fail("Bluetooth-Berechtigung fehlt")
        if (adapter == null || !adapter.isEnabled) return fail("Bluetooth ist ausgeschaltet")
        advertiser = adapter.bluetoothLeAdvertiser ?: return fail("Dieses Handy kann nicht per Bluetooth LE werben")

        try {
            val socket = adapter.listenUsingInsecureL2capChannel()
            server = socket
            psm = socket.psm
            running = true
            ContextCompat.registerReceiver(
                this, btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            ContextCompat.registerReceiver(
                this, screenReceiver,
                IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            ReceiverState.set(ReceiverStatus.Waiting)
            scope.launch { acceptLoop(socket) }
            // Solange auf diesem Handy der Teilen-Bildschirm offen ist, weder werben noch Kontakte tauschen.
            // Der erste Wert (false) startet Werbung und Hintergrundsuche.
            scope.launch(Dispatchers.Main) {
                ReceiverState.shareActive.collect { active ->
                    if (!running) return@collect
                    if (active) {
                        stopAdvertising()
                        exchanger.stop()
                    } else {
                        startAdvertising()
                        updateExchanger()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Start fehlgeschlagen", e)
            fail("Empfang konnte nicht gestartet werden: ${e.message}")
        }
    }

    private fun acceptLoop(socket: BluetoothServerSocket) {
        while (scope.isActive) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                break // Server geschlossen
            }
            exchanger.busy = true
            try {
                handle(client)
            } catch (e: Exception) {
                Log.w(TAG, "Übertragung fehlgeschlagen", e)
                notifyResult("Übertragung abgebrochen", e.message ?: "Verbindung unterbrochen", null)
            } finally {
                exchanger.busy = false
                runCatching { client.close() }
                if (running) {
                    ReceiverState.set(ReceiverStatus.Waiting)
                    updateOngoing("Halte ein anderes Handy an dieses, um Dateien zu empfangen.")
                }
            }
        }
    }

    private fun handle(client: BluetoothSocket) {
        val inp = DataInputStream(BufferedInputStream(client.inputStream, 64 * 1024))
        val out = DataOutputStream(client.outputStream)
        when (val magic = inp.readInt()) {
            Proto.CONTACT_MAGIC -> handleContact(inp, out)
            else -> handleFiles(inp, out, magic)
        }
    }

    /** Annehmende Seite des Kontaktaustauschs (Gegenstück zu [ContactExchanger]). */
    private fun handleContact(inp: DataInputStream, out: DataOutputStream) {
        val (peerId, card) = Proto.readContactRequest(inp)
        val now = SystemClock.elapsedRealtime()
        val allowed = prefs.contactExchangeActive && !ReceiverState.shareActive.value &&
            synchronized(trigger) { trigger.mayExchange(peerId, now, ContactExchanger.lastTransferAt()) }
        if (!allowed) {
            out.writeByte(Proto.REJECT)
            out.flush()
            return
        }
        synchronized(trigger) { trigger.markExchanged(peerId, now) }
        out.writeByte(Proto.ACCEPT)
        Proto.writeCard(out, prefs.myCard)
        out.flush()
        saveContact(card, from = "")
        runCatching { inp.readUnsignedByte() } // DONE des Initiators abwarten
    }

    private fun handleFiles(inp: DataInputStream, out: DataOutputStream, magic: Int) {
        ReceiverState.lastTransferAt = SystemClock.elapsedRealtime()
        val header = Proto.readHeader(inp, magic)
        out.writeByte(Proto.ACCEPT)
        out.flush()

        val saved = mutableListOf<ReceivedFile>()
        var done = 0L
        var lastUi = 0L
        repeat(header.fileCount) { index ->
            val fh = Proto.readFileHeader(inp)
            fun publish(force: Boolean) {
                val now = SystemClock.elapsedRealtime()
                if (!force && now - lastUi < 250) return
                lastUi = now
                ReceiverState.set(
                    ReceiverStatus.Receiving(header.senderName, index, header.fileCount, fh.name, done, header.totalBytes)
                )
                updateOngoing(
                    "Empfange ${fh.name} (${index + 1}/${header.fileCount}) von ${header.senderName}",
                    progress = if (header.totalBytes > 0) (done * 100 / header.totalBytes).toInt() else 0,
                )
            }
            publish(force = true)
            val uri = FileSaver.save(this, fh.name, fh.mimeType) { os ->
                Proto.copyExactly(inp, os, fh.size) { n ->
                    done += n
                    publish(force = false)
                }
            }
            val file = ReceivedFile(fh.name, fh.mimeType, fh.size, uri, header.senderName, System.currentTimeMillis())
            saved += file
            addToHistory(file)
        }
        out.writeByte(Proto.DONE)
        out.flush()
        // Warten, bis der Sender die Verbindung schließt (liest -1), damit DONE sicher ankommt.
        runCatching { inp.read() }
        ReceiverState.lastTransferAt = SystemClock.elapsedRealtime()

        val title = if (saved.size == 1) "Datei empfangen" else "${saved.size} Dateien empfangen"
        val text = if (saved.size == 1) {
            "${saved[0].name} von ${header.senderName} – gespeichert in ${FileSaver.folderDescription(saved[0].mimeType)}"
        } else {
            "Von ${header.senderName} – gespeichert im Ordner „${FileSaver.FOLDER}“"
        }
        notifyResult(title, text, saved.singleOrNull())
    }

    private fun addToHistory(file: ReceivedFile) {
        val list = listOf(file) + (ReceiverState.history.value ?: emptyList())
        ReceiverState.setHistory(list.take(AppPrefs.MAX_HISTORY))
        prefs.saveHistory(list)
    }

    private fun saveContact(card: ContactCard, from: String) {
        if (card.isEmpty) return
        val entry = ReceivedContact(card, from, System.currentTimeMillis())
        val list = listOf(entry) + (ReceiverState.contacts.value ?: prefs.loadContacts())
        ReceiverState.setContacts(list.take(AppPrefs.MAX_HISTORY))
        prefs.saveContacts(list)
        notifyContact(card)
    }

    private fun startAdvertising() {
        val adv = advertiser ?: return
        if (advertising || !running) return
        try {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .build()
            // Hauptpaket: Flags (3) + 128-Bit-UUID (18) + Herstellerdaten (4 + 5) = 30 von 31 Byte.
            val data = AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(Proto.SERVICE_UUID))
                .addManufacturerData(
                    Proto.MANUFACTURER_ID,
                    Proto.encodeMeta(Proto.Meta(instanceId, prefs.contactExchangeActive)),
                )
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .build()
            val scanResponse = AdvertiseData.Builder()
                .addServiceData(ParcelUuid(Proto.SERVICE_UUID), Proto.encodeAdvert(psm, Bluetooth.deviceName(this)))
                .setIncludeDeviceName(false)
                .build()
            adv.startAdvertising(settings, data, scanResponse, advertiseCallback)
            advertising = true
        } catch (e: Exception) {
            Log.w(TAG, "Advertising-Start fehlgeschlagen", e)
            fail("Bluetooth-Werbung konnte nicht gestartet werden: ${e.message}")
        }
    }

    private fun stopAdvertising() {
        if (!advertising) return
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        advertising = false
    }

    private fun updateExchanger() {
        val screenOn = getSystemService(PowerManager::class.java).isInteractive
        if (running && screenOn && prefs.contactExchangeActive && !ReceiverState.shareActive.value) {
            exchanger.start()
        } else {
            exchanger.stop()
        }
    }

    private fun fail(message: String) {
        ReceiverState.set(ReceiverStatus.Error(message))
        stopEverything(keepStatus = true)
    }

    private fun stopEverything(keepStatus: Boolean = false) {
        if (running) {
            runCatching { unregisterReceiver(btStateReceiver) }
            runCatching { unregisterReceiver(screenReceiver) }
        }
        running = false
        if (::exchanger.isInitialized) exchanger.stop()
        stopAdvertising()
        advertiser = null
        runCatching { server?.close() }
        server = null
        if (!keepStatus) ReceiverState.set(ReceiverStatus.Off)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) stopEverything()
        scope.cancel()
        super.onDestroy()
    }

    // --- Benachrichtigungen ---

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ongoingNotification(text: String, progress: Int? = null): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ReceiverService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Data-App ist empfangsbereit")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Beenden", stop)
            .apply { if (progress != null) setProgress(100, progress, false) }
            .build()
    }

    private fun updateOngoing(text: String, progress: Int? = null) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ONGOING, ongoingNotification(text, progress))
    }

    private fun notifyResult(title: String, text: String, single: ReceivedFile?) {
        val tap = if (single != null) {
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(single.uri, single.mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            PendingIntent.getActivity(this, 2, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else {
            openAppIntent()
        }
        val n = NotificationCompat.Builder(this, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_RESULT_BASE + (System.currentTimeMillis() % 1000).toInt(), n)
        }
    }

    private fun notifyContact(card: ContactCard) {
        val save = PendingIntent.getActivity(
            this, card.hashCode(), ContactExchanger.insertIntent(card).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = listOf(card.title, card.details).filter { it.isNotBlank() }.joinToString(" – ")
        val n = NotificationCompat.Builder(this, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Kontakt empfangen")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\nTippen, um ihn in deinen Kontakten zu speichern."))
            .setAutoCancel(true)
            .setContentIntent(save)
            .addAction(0, "Speichern", save)
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_CONTACT_BASE + (card.hashCode() and 0xfff), n)
        }
    }

    companion object {
        private const val TAG = "ReceiverService"
        const val ACTION_STOP = "com.bytestreamdevelopment.dataapp.STOP_RECEIVER"
        const val ACTION_REFRESH = "com.bytestreamdevelopment.dataapp.REFRESH_RECEIVER"
        private const val NOTIF_CONTACT_BASE = 5000
        private const val CHANNEL_ONGOING = "receiver"
        private const val CHANNEL_RESULT = "results"
        private const val NOTIF_ONGOING = 1
        private const val NOTIF_RESULT_BASE = 100

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ReceiverService::class.java))
        }

        fun stop(context: Context) {
            if (ReceiverState.status.value is ReceiverStatus.Error) ReceiverState.set(ReceiverStatus.Off)
            context.startService(Intent(context, ReceiverService::class.java).setAction(ACTION_STOP))
        }

        /** Nach Änderung von Visitenkarte/Einstellungen aufrufen (wirkt nur, wenn der Dienst läuft). */
        fun refresh(context: Context) {
            if (ReceiverState.status.value is ReceiverStatus.Waiting || ReceiverState.status.value is ReceiverStatus.Receiving) {
                context.startService(Intent(context, ReceiverService::class.java).setAction(ACTION_REFRESH))
            }
        }

        fun ensureHistory(context: Context) {
            val prefs = AppPrefs(context)
            if (ReceiverState.history.value == null) ReceiverState.setHistory(prefs.loadHistory())
            if (ReceiverState.contacts.value == null) ReceiverState.setContacts(prefs.loadContacts())
        }

        fun removeContact(context: Context, entry: ReceivedContact) {
            val list = (ReceiverState.contacts.value ?: emptyList()).filterNot { it == entry }
            ReceiverState.setContacts(list)
            AppPrefs(context).saveContacts(list)
        }

        fun removeFromHistory(context: Context, file: ReceivedFile) {
            val list = (ReceiverState.history.value ?: emptyList()).filterNot { it.uri == file.uri && it.time == file.time }
            ReceiverState.setHistory(list)
            AppPrefs(context).saveHistory(list)
        }

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ONGOING, "Empfangsbereitschaft", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_RESULT, "Empfangene Dateien", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }
}
