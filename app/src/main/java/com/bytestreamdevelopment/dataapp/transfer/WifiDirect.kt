package com.bytestreamdevelopment.dataapp.transfer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.bytestreamdevelopment.dataapp.storage.AppPrefs
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * WLAN-Direct für große Übertragungen. Der Empfänger eröffnet eine Gruppe mit zufälligem Namen und Passwort,
 * schickt beides über den verschlüsselten Bluetooth-Kanal, und der Sender tritt damit ohne Systemdialog bei
 * (WifiP2pConfig.Builder, ab Android 10). Die Daten laufen dann über einen TCP-Socket.
 */
@SuppressLint("MissingPermission") // isAvailable() prüft die Berechtigung vor jeder Nutzung
object WifiDirect {
    private const val TAG = "WifiDirect"

    /** Erst ab dieser Größe lohnt der Aufbau (dauert einige Sekunden). */
    const val MIN_BYTES = 5L * 1024 * 1024

    /** Nötige Laufzeit-Berechtigung: ab Android 13 „Geräte in der Nähe (WLAN)“, davor Standort. */
    val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION

    fun isSupported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun isAvailable(context: Context, prefs: AppPrefs): Boolean =
        prefs.wifiDirect && isSupported(context) && hasPermission(context) &&
            context.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true

    private val random = SecureRandom()
    private const val ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private fun randomText(length: Int) = (1..length).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    private fun config(info: Proto.WifiInfo): WifiP2pConfig =
        WifiP2pConfig.Builder()
            .setNetworkName(info.ssid)
            .setPassphrase(info.passphrase)
            .enablePersistentMode(false)
            .build()

    /** Führt eine WifiP2pManager-Aktion aus und wartet auf ihr Ergebnis. */
    private fun await(timeoutMs: Long, action: (WifiP2pManager.ActionListener) -> Unit): Boolean {
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        action(object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                ok.set(true)
                latch.countDown()
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "WLAN-Direct-Aktion fehlgeschlagen (Grund $reason)")
                latch.countDown()
            }
        })
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS) && ok.get()
    }

    private abstract class Session(context: Context) : Closeable {
        protected val manager: WifiP2pManager =
            context.applicationContext.getSystemService(WifiP2pManager::class.java) ?: throw IOException("Kein WLAN-Direct")
        protected val channel: WifiP2pManager.Channel =
            manager.initialize(context.applicationContext, Looper.getMainLooper(), null)
                ?: throw IOException("WLAN-Direct nicht verfügbar")
        protected val closed = AtomicBoolean(false)

        protected fun checkOpen() {
            if (closed.get()) throw IOException("Abgebrochen")
        }

        protected fun removeGroup() {
            runCatching { await(2000) { manager.removeGroup(channel, it) } }
        }

        protected open fun onClose() {}

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            onClose()
            removeGroup()
            runCatching { channel.close() }
        }
    }

    /** Empfängerseite: eröffnet die Gruppe und wartet auf die TCP-Verbindung des Senders. */
    class Host private constructor(context: Context) : Closeable {
        private val session = object : Session(context) {
            fun open(info: Proto.WifiInfo): Boolean {
                removeGroup() // Reste einer früheren Sitzung
                if (!await(8000) { manager.createGroup(channel, config(info), it) }) return false
                // Warten, bis die Gruppe wirklich steht.
                repeat(20) {
                    checkOpen()
                    val formed = AtomicBoolean(false)
                    val latch = CountDownLatch(1)
                    manager.requestGroupInfo(channel) { group ->
                        formed.set(group != null && group.isGroupOwner)
                        latch.countDown()
                    }
                    latch.await(2, TimeUnit.SECONDS)
                    if (formed.get()) return true
                    Thread.sleep(250)
                }
                return false
            }

            override fun onClose() {
                runCatching { server.close() }
            }
        }
        private val server = ServerSocket(0)
        val info = Proto.WifiInfo("DIRECT-DA-" + randomText(6), randomText(16), server.localPort)

        fun accept(timeoutMs: Int): Socket {
            server.soTimeout = timeoutMs
            return server.accept().apply { tcpNoDelay = true }
        }

        override fun close() = session.close()

        companion object {
            /** Liefert null, wenn die Gruppe nicht eröffnet werden konnte (dann weiter über Bluetooth). */
            fun open(context: Context): Host? {
                val host = try {
                    Host(context)
                } catch (e: Exception) {
                    Log.w(TAG, "WLAN-Direct-Start fehlgeschlagen", e)
                    return null
                }
                val ok = runCatching { host.session.open(host.info) }.getOrDefault(false)
                if (!ok) {
                    host.close()
                    return null
                }
                return host
            }
        }
    }

    /** Senderseite: tritt der Gruppe bei und verbindet sich per TCP mit dem Empfänger. */
    class Client(context: Context) : Closeable {
        private val socketRef = AtomicReference<Socket?>()
        private val session = object : Session(context) {
            fun connect(info: Proto.WifiInfo): Socket {
                removeGroup()
                checkOpen()
                if (!await(10_000) { manager.connect(channel, config(info), it) }) {
                    throw IOException("WLAN-Direct-Verbindung abgelehnt")
                }
                var last: IOException? = null
                repeat(60) { // bis zu ~20 s
                    checkOpen()
                    val owner = AtomicReference<java.net.InetAddress?>()
                    val latch = CountDownLatch(1)
                    manager.requestConnectionInfo(channel) { ci ->
                        if (ci != null && ci.groupFormed && !ci.isGroupOwner) owner.set(ci.groupOwnerAddress)
                        latch.countDown()
                    }
                    latch.await(2, TimeUnit.SECONDS)
                    val address = owner.get()
                    if (address != null) {
                        try {
                            val socket = Socket()
                            socketRef.set(socket)
                            socket.tcpNoDelay = true
                            socket.connect(InetSocketAddress(address, info.port), 3000)
                            return socket
                        } catch (e: IOException) {
                            last = e // Adresse steht manchmal kurz vor der Route bereit → erneut versuchen
                        }
                    }
                    Thread.sleep(300)
                }
                throw IOException("WLAN-Direct-Verbindung kam nicht zustande", last)
            }

            override fun onClose() {
                runCatching { socketRef.get()?.close() }
            }
        }

        fun connect(info: Proto.WifiInfo): Socket = session.connect(info)

        override fun close() = session.close()
    }
}
