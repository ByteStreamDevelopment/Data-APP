package com.bytestreamdevelopment.dataapp.storage

import android.content.Context
import android.net.Uri
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
)

class AppPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("data_app", Context.MODE_PRIVATE)

    var proximity: Proximity
        get() = runCatching { Proximity.valueOf(prefs.getString("proximity", null)!!) }.getOrDefault(Proximity.TOUCH)
        set(v) = prefs.edit().putString("proximity", v.name).apply()

    /** Empfang beim Öffnen der App automatisch einschalten. */
    var autoReceive: Boolean
        get() = prefs.getBoolean("auto_receive", true)
        set(v) = prefs.edit().putBoolean("auto_receive", v).apply()

    fun loadHistory(): List<ReceivedFile> = runCatching {
        val arr = JSONArray(prefs.getString("history", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            ReceivedFile(
                o.getString("name"), o.getString("mime"), o.getLong("size"),
                Uri.parse(o.getString("uri")), o.getString("from"), o.getLong("time"),
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
            )
        }
        prefs.edit().putString("history", arr.toString()).apply()
    }

    companion object {
        const val MAX_HISTORY = 100
    }
}
