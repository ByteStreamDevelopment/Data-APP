package com.bytestreamdevelopment.dataapp.storage

import android.content.Context
import android.net.Uri
import com.bytestreamdevelopment.dataapp.transfer.ContactCard
import com.bytestreamdevelopment.dataapp.transfer.Identity
import org.json.JSONArray
import org.json.JSONObject

/** Wie nah sich die Handys kommen müssen, bevor automatisch gesendet wird. */
enum class Proximity(val label: String, val thresholdDbm: Int?) {
    TOUCH("Aneinanderhalten", -45),
    NEAR("Sehr nah (ca. 30 cm)", -60),
    ROOM("Im selben Raum", -90),
    MANUAL("Nur manuell antippen", null),
}

data class ReceivedFile(
    val name: String,
    val mimeType: String,
    val size: Long,
    val uri: Uri,
    val from: String,
    val time: Long,
    /** Nur bei geteiltem Text/Link gesetzt; dann gibt es keine Datei ([uri] ist leer). */
    val text: String? = null,
)

/** [photo] = Dateiname des Kontaktfotos (siehe CardPhotos) oder null. */
data class ReceivedContact(val card: ContactCard, val from: String, val time: Long, val photo: String? = null)

class AppPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("data_app", Context.MODE_PRIVATE)

    var proximity: Proximity
        get() = runCatching { Proximity.valueOf(prefs.getString("proximity", null)!!) }.getOrDefault(Proximity.TOUCH)
        set(v) = prefs.edit().putString("proximity", v.name).apply()

    /** Empfang beim Öffnen der App automatisch einschalten. */
    var autoReceive: Boolean
        get() = prefs.getBoolean("auto_receive", true)
        set(v) = prefs.edit().putBoolean("auto_receive", v).apply()

    /** Eigene Visitenkarte, die beim Aneinanderhalten ohne Datei ausgetauscht wird. */
    var myCard: ContactCard
        get() = cardFromJson(prefs.getString("my_card", null)) ?: ContactCard()
        set(v) = prefs.edit().putString("my_card", cardToJson(v).toString()).apply()

    var contactExchange: Boolean
        get() = prefs.getBoolean("contact_exchange", true)
        set(v) = prefs.edit().putBoolean("contact_exchange", v).apply()

    /** Kontaktaustausch ist nur aktiv, wenn eingeschaltet und eine Karte ausgefüllt ist. */
    val contactExchangeActive: Boolean get() = contactExchange && !myCard.isEmpty

    /** Vor dem Empfang von unbekannten Geräten nachfragen. */
    var askBeforeReceive: Boolean
        get() = prefs.getBoolean("ask_before_receive", true)
        set(v) = prefs.edit().putBoolean("ask_before_receive", v).apply()

    /** Vibration und Ton bei Erkennung/Abschluss. */
    var feedback: Boolean
        get() = prefs.getBoolean("feedback", true)
        set(v) = prefs.edit().putBoolean("feedback", v).apply()

    /** Schnellere Bluetooth-Verbindung (2M-PHY, kurzes Verbindungsintervall) anfordern. */
    var fastBluetooth: Boolean
        get() = prefs.getBoolean("fast_bluetooth", true)
        set(v) = prefs.edit().putBoolean("fast_bluetooth", v).apply()

    /** Große Übertragungen über WLAN-Direct statt Bluetooth. */
    var wifiDirect: Boolean
        get() = prefs.getBoolean("wifi_direct", true)
        set(v) = prefs.edit().putBoolean("wifi_direct", v).apply()

    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean("auto_start_boot", false)
        set(v) = prefs.edit().putBoolean("auto_start_boot", v).apply()

    /** Dauerhafter Geräteschlüssel; wird beim ersten Zugriff erzeugt. */
    val identity: Identity
        get() = synchronized(LOCK) {
            Identity.import(prefs.getString("identity", null))
                ?: Identity.generate().also { prefs.edit().putString("identity", it.export()).commit() }
        }

    /** Vertraute Geräte: Geräte-ID → Name. Von ihnen wird ohne Nachfrage empfangen. */
    var trusted: Map<String, String>
        get() = runCatching {
            val o = JSONObject(prefs.getString("trusted", "{}")!!)
            o.keys().asSequence().associateWith { o.optString(it) }
        }.getOrDefault(emptyMap())
        set(v) = prefs.edit().putString("trusted", JSONObject(v).toString()).apply()

    fun isTrusted(deviceId: String): Boolean = trusted.containsKey(deviceId)

    fun trust(deviceId: String, name: String) {
        trusted = trusted + (deviceId to name)
    }

    fun loadContacts(): List<ReceivedContact> = runCatching {
        val arr = JSONArray(prefs.getString("contacts", "[]"))
        (0 until arr.length()).mapNotNull {
            val o = arr.getJSONObject(it)
            val card = cardFromJson(o.optJSONObject("card")?.toString()) ?: return@mapNotNull null
            ReceivedContact(card, o.optString("from"), o.optLong("time"), o.optString("photo").ifEmpty { null })
        }
    }.getOrDefault(emptyList())

    fun saveContacts(list: List<ReceivedContact>) {
        val arr = JSONArray()
        list.take(MAX_HISTORY).forEach {
            arr.put(
                JSONObject().put("card", cardToJson(it.card)).put("from", it.from).put("time", it.time)
                    .put("photo", it.photo ?: "")
            )
        }
        prefs.edit().putString("contacts", arr.toString()).apply()
    }

    private fun cardToJson(c: ContactCard) = JSONObject().apply { c.toMap().forEach { (k, v) -> put(k, v) } }

    private fun cardFromJson(json: String?): ContactCard? = json?.let {
        runCatching {
            val o = JSONObject(it)
            ContactCard.fromMap(o.keys().asSequence().associateWith { k -> o.optString(k) })
        }.getOrNull()
    }

    fun loadHistory(): List<ReceivedFile> = runCatching {
        val arr = JSONArray(prefs.getString("history", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            ReceivedFile(
                o.getString("name"), o.getString("mime"), o.getLong("size"),
                Uri.parse(o.getString("uri")), o.getString("from"), o.getLong("time"),
                if (o.has("text")) o.getString("text") else null,
            )
        }
    }.getOrDefault(emptyList())

    fun saveHistory(list: List<ReceivedFile>) {
        val arr = JSONArray()
        list.take(MAX_HISTORY).forEach {
            arr.put(
                JSONObject()
                    .put("name", it.name).put("mime", it.mimeType).put("size", it.size)
                    .put("uri", it.uri.toString()).put("from", it.from).put("time", it.time)
                    .apply { if (it.text != null) put("text", it.text) }
            )
        }
        prefs.edit().putString("history", arr.toString()).apply()
    }

    companion object {
        const val MAX_HISTORY = 100
        private val LOCK = Any()
    }
}
