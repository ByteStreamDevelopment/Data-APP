package com.bytestreamdevelopment.dataapp.transfer

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
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
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import com.bytestreamdevelopment.dataapp.storage.CardPhotos
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
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

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

    /** Offene Verbindungen der laufenden Übertragung – Schließen bricht sie ab. */
    private val active = CopyOnWriteArrayList<Closeable>()

    @Volatile
    private var cancelled = false

    /** true ab der Annahme einer Dateiübertragung (für Fehlermeldungen und „Abbrechen“). */
    @Volatile
    private var transferring = false
    private lateinit var wakeLock: PowerManager.WakeLock

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
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DataApp:Empfang")
            .apply { setReferenceCounted(false) }
        exchanger = ContactExchanger(this, scope, prefs, instanceId, trigger) { card, photo, from ->
            saveContact(card, photo, from)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CANCEL) {
            if (running) cancelTransfer() else stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ANSWER) {
            val decision = runCatching { Decision.valueOf(intent.getStringExtra(EXTRA_DECISION)!!) }.getOrDefault(Decision.REJECT)
            ReceiverState.answer(intent.getLongExtra(EXTRA_REQUEST, 0), decision)
            if (!running) stopSelf()
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
                this, NOTIF_ONGOING, ongoingNotification(IDLE_TEXT),
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
            cancelled = false
            active += client
            try {
                handle(client)
            } catch (e: Exception) {
                Log.w(TAG, "Übertragung fehlgeschlagen", e)
                if (transferring) {
                    Feedback.failure(this, prefs)
                    notifyResult(
                        "Übertragung abgebrochen",
                        if (cancelled) "Du hast den Empfang abgebrochen." else e.message ?: "Verbindung unterbrochen",
                        null,
                    )
                }
            } finally {
                transferring = false
                exchanger.busy = false
                active.forEach { runCatching { it.close() } }
                active.clear()
                if (wakeLock.isHeld) runCatching { wakeLock.release() }
                if (running) {
                    ReceiverState.set(ReceiverStatus.Waiting)
                    updateOngoing(IDLE_TEXT)
                }
            }
        }
    }

    private fun handle(client: BluetoothSocket) {
        val channel = Handshake.respond(BufferedInputStream(client.inputStream, 64 * 1024), client.outputStream)
        when (val magic = channel.input.readInt()) {
            Proto.CONTACT_MAGIC -> handleContact(channel.input, channel.output)
            else -> handleFiles(channel, magic)
        }
    }

    /** Annehmende Seite des Kontaktaustauschs (Gegenstück zu [ContactExchanger]). */
    private fun handleContact(inp: DataInputStream, out: DataOutputStream) {
        val (peerId, payload) = Proto.readContactRequest(inp)
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
        Proto.writeCard(out, prefs.myCard, CardPhotos.loadMine(this))
        out.flush()
        saveContact(payload.card, payload.photo, from = "")
        runCatching { inp.readUnsignedByte() } // DONE des Initiators abwarten
    }

    private fun handleFiles(channel: SecureChannel, magic: Int) {
        val inp = channel.input
        val out = channel.output
        ReceiverState.lastTransferAt = SystemClock.elapsedRealtime()
        val header = Proto.readHeader(inp, magic)
        val deviceId = Proto.readAuth(inp, channel.keys.transcript)
        Feedback.detected(this, prefs)

        if (prefs.askBeforeReceive && !prefs.isTrusted(deviceId)) {
            when (askUser(header, deviceId, channel.keys.code)) {
                Decision.ACCEPT -> Unit
                Decision.ACCEPT_AND_TRUST -> prefs.trust(deviceId, header.senderName)
                else -> {
                    out.writeByte(Proto.REJECT)
                    out.flush()
                    runCatching { inp.read() }
                    return
                }
            }
        }
        transferring = true
        runCatching { wakeLock.acquire(60 * 60 * 1000L) }

        // Große Übertragung: WLAN-Direct-Gruppe eröffnen und dem Sender die Zugangsdaten schicken.
        var host: WifiDirect.Host? = null
        if (header.wifiOffered && WifiDirect.isAvailable(this, prefs)) {
            ReceiverState.set(ReceiverStatus.Receiving(header.senderName, 0, header.fileCount, "WLAN-Verbindung wird aufgebaut …", 0, header.totalBytes))
            host = WifiDirect.Host.open(this)?.also { active += it }
        }
        out.writeByte(Proto.ACCEPT)
        if (host != null) {
            out.writeByte(Proto.TRANSPORT_WIFI)
            Proto.writeWifiInfo(out, host.info)
        } else {
            out.writeByte(Proto.TRANSPORT_BLE)
        }
        out.flush()

        var data = channel
        var viaWifi = false
        if (host != null) {
            if (inp.readUnsignedByte() == 1) {
                val tcp = host.accept(15_000)
                active += tcp
                data = Handshake.wrap(
                    tcp.getInputStream(), tcp.getOutputStream(), channel.keys, SessionKeys.WIFI_R2I, SessionKeys.WIFI_I2R,
                )
                viaWifi = true
            } else {
                host.close() // Sender kam nicht ins WLAN → weiter über Bluetooth
            }
        }

        val saved = mutableListOf<ReceivedFile>()
        var done = 0L
        var lastUi = 0L
        repeat(header.fileCount) { index ->
            val fh = Proto.readFileHeader(data.input)
            val isText = fh.mimeType == Proto.MIME_TEXT
            val label = if (isText) "Text" else fh.name
            fun publish(force: Boolean) {
                val now = SystemClock.elapsedRealtime()
                if (!force && now - lastUi < 250) return
                lastUi = now
                ReceiverState.set(
                    ReceiverStatus.Receiving(header.senderName, index, header.fileCount, label, done, header.totalBytes, viaWifi)
                )
                updateOngoing(
                    "Empfange $label (${index + 1}/${header.fileCount}) von ${header.senderName}",
                    progress = if (header.totalBytes > 0) (done * 100 / header.totalBytes).toInt() else 0,
                )
            }
            publish(force = true)
            val file = if (isText) {
                if (fh.size > Proto.MAX_TEXT_BYTES) throw IOException("Text ist zu lang")
                val bytes = ByteArrayOutputStream()
                Proto.copyExactly(data.input, bytes, fh.size) { n -> done += n }
                val text = bytes.toString("UTF-8")
                copyToClipboard(text)
                ReceivedFile(
                    text.trim().take(80), fh.mimeType, fh.size, Uri.EMPTY, header.senderName, System.currentTimeMillis(), text,
                )
            } else {
                val uri = FileSaver.save(this, fh.name, fh.mimeType) { os ->
                    Proto.copyExactly(data.input, os, fh.size) { n ->
                        done += n
                        publish(force = false)
                    }
                }
                ReceivedFile(fh.name, fh.mimeType, fh.size, uri, header.senderName, System.currentTimeMillis())
            }
            saved += file
            addToHistory(file)
        }
        data.output.writeByte(Proto.DONE)
        data.output.flush()
        // Warten, bis der Sender die Verbindung schließt (liest -1), damit DONE sicher ankommt.
        runCatching { data.input.read() }
        ReceiverState.lastTransferAt = SystemClock.elapsedRealtime()
        transferring = false
        Feedback.success(this, prefs)

        val single = saved.singleOrNull()
        val via = if (viaWifi) " (per WLAN)" else ""
        when {
            single?.text != null -> {
                val link = Proto.asLink(single.text)
                notifyResult(
                    if (link != null) "Link von ${header.senderName}" else "Text von ${header.senderName}",
                    "${single.text.trim().take(300)}\nIn die Zwischenablage kopiert" + if (link != null) " – tippen zum Öffnen." else ".",
                    single,
                )
            }
            single != null -> notifyResult(
                "Datei empfangen$via",
                "${single.name} von ${header.senderName} – gespeichert in ${FileSaver.folderDescription(single.mimeType)}",
                single,
            )
            else -> notifyResult(
                "${saved.size} Dateien empfangen$via",
                "Von ${header.senderName} – gespeichert im Ordner „${FileSaver.FOLDER}“",
                null,
            )
        }
    }

    /** Fragt den Nutzer (Dialog in der App + Benachrichtigung), ob empfangen werden soll. null = keine Antwort. */
    private fun askUser(header: Proto.Header, deviceId: String, code: String): Decision? {
        val request = IncomingRequest(
            SystemClock.elapsedRealtimeNanos(), header.senderName, deviceId, header.fileCount, header.totalBytes, code,
        )
        val answers = ReceiverState.ask(request)
        notifyRequest(request)
        return try {
            answers.poll(ASK_TIMEOUT_S, TimeUnit.SECONDS)
        } finally {
            ReceiverState.clearPending()
            getSystemService(NotificationManager::class.java).cancel(NOTIF_REQUEST)
        }
    }

    private fun copyToClipboard(text: String) {
        Handler(Looper.getMainLooper()).post {
            runCatching {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Data-App", text))
            }
        }
    }

    /** Bricht die laufende Übertragung bzw. offene Anfrage ab. */
    private fun cancelTransfer() {
        cancelled = true
        ReceiverState.rejectPending()
        active.forEach { runCatching { it.close() } }
    }

    private fun addToHistory(file: ReceivedFile) {
        val list = listOf(file) + (ReceiverState.history.value ?: emptyList())
        ReceiverState.setHistory(list.take(AppPrefs.MAX_HISTORY))
        prefs.saveHistory(list)
    }

    private fun saveContact(card: ContactCard, photo: ByteArray?, from: String) {
        if (card.isEmpty) return
        val time = System.currentTimeMillis()
        val photoName = photo?.let { runCatching { CardPhotos.saveReceived(this, it, time) }.getOrNull() }
        val entry = ReceivedContact(card, from, time, photoName)
        val list = listOf(entry) + (ReceiverState.contacts.value ?: prefs.loadContacts())
        ReceiverState.setContacts(list.take(AppPrefs.MAX_HISTORY))
        prefs.saveContacts(list)
        Feedback.success(this, prefs)
        notifyContact(card, photo)
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
        cancelTransfer()
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
            .apply {
                if (progress != null) {
                    setProgress(100, progress, false)
                    addAction(0, "Abbrechen", serviceIntent(3, Intent(this@ReceiverService, ReceiverService::class.java).setAction(ACTION_CANCEL)))
                } else {
                    addAction(0, "Beenden", stop)
                }
            }
            .build()
    }

    private fun updateOngoing(text: String, progress: Int? = null) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ONGOING, ongoingNotification(text, progress))
    }

    private fun serviceIntent(requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notifyRequest(request: IncomingRequest) {
        fun answer(code: Int, decision: Decision) = serviceIntent(
            code,
            Intent(this, ReceiverService::class.java).setAction(ACTION_ANSWER)
                .putExtra(EXTRA_REQUEST, request.id).putExtra(EXTRA_DECISION, decision.name),
        )
        val what = if (request.fileCount == 1) "1 Element" else "${request.fileCount} Elemente"
        val text = "${request.senderName} möchte $what senden (${formatBytes(request.totalBytes)}). Code: ${request.code}"
        val n = NotificationCompat.Builder(this, CHANNEL_REQUEST)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Empfang bestätigen")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(ASK_TIMEOUT_S * 1000)
            .setContentIntent(openAppIntent())
            .addAction(0, "Annehmen", answer(10, Decision.ACCEPT))
            .addAction(0, "Ablehnen", answer(11, Decision.REJECT))
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_REQUEST, n) }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(java.util.Locale.GERMANY, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private fun notifyResult(title: String, text: String, single: ReceivedFile?) {
        val link = single?.text?.let(Proto::asLink)
        val tap = if (link != null) {
            PendingIntent.getActivity(
                this, 4, Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        } else if (single != null && single.text == null) {
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

    private fun notifyContact(card: ContactCard, photo: ByteArray?) {
        val save = PendingIntent.getActivity(
            this, card.hashCode(), ContactExchanger.insertIntent(card, photo).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = listOf(card.title, card.details).filter { it.isNotBlank() }.joinToString(" – ")
        val n = NotificationCompat.Builder(this, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Kontakt empfangen")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\nTippen, um ihn in deinen Kontakten zu speichern."))
            .setLargeIcon(CardPhotos.decode(photo))
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
        const val ACTION_CANCEL = "com.bytestreamdevelopment.dataapp.CANCEL_TRANSFER"
        const val ACTION_ANSWER = "com.bytestreamdevelopment.dataapp.ANSWER_REQUEST"
        private const val EXTRA_REQUEST = "request"
        private const val EXTRA_DECISION = "decision"
        private const val CHANNEL_REQUEST = "requests"
        private const val NOTIF_REQUEST = 2
        private const val ASK_TIMEOUT_S = 45L
        private const val IDLE_TEXT = "Halte ein anderes Handy an dieses, um Dateien zu empfangen."

        /** Laufenden Empfang abbrechen. */
        fun cancelTransfer(context: Context) {
            context.startService(Intent(context, ReceiverService::class.java).setAction(ACTION_CANCEL))
        }
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
            CardPhotos.deleteReceived(context, entry.photo)
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
                NotificationChannel(CHANNEL_REQUEST, "Empfangsanfragen", NotificationManager.IMPORTANCE_HIGH)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_RESULT, "Empfangene Dateien", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }
}
