package com.bytestreamdevelopment.dataapp.transfer

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Übertragungsprotokoll (Version 2). Jede Verbindung beginnt mit dem [Handshake]; alles Folgende ist verschlüsselt.
 *
 * Dateien (S = Sender, E = Empfänger), über den BLE-L2CAP-Kanal:
 *  S→E  MAGIC (int), Absendername (UTF), Dateianzahl (int), Gesamtgröße (long), Flags (byte: WLAN angeboten)
 *  S→E  Geräteschlüssel + Signatur über die Handshake-Schlüssel (siehe [writeAuth])
 *  E→S  REJECT, oder ACCEPT + Transport (byte): TRANSPORT_BLE, oder TRANSPORT_WIFI + SSID, Passwort, Port
 *  S→E  nur bei WLAN: 1 = WLAN-Verbindung steht, 0 = hat nicht geklappt → weiter über Bluetooth
 * Danach auf dem gewählten Kanal (WLAN-TCP mit eigenen, aus derselben Sitzung abgeleiteten Schlüsseln):
 *  S→E  je Datei: Name (UTF), MIME-Typ (UTF), Größe (long), danach genau <Größe> Rohbytes
 *  E→S  DONE (byte) – erst danach schließt der Sender die Verbindung
 *
 * Kontaktaustausch (I = Initiator, A = Annehmender), ausgelöst durch Aneinanderhalten ohne Datei:
 *  I→A  CONTACT_MAGIC (int), Instanz-ID (int), Visitenkarte (+ Foto)
 *  A→I  ACCEPT + eigene Visitenkarte (+ Foto), oder REJECT
 *  I→A  DONE (byte)
 *
 * Reine JVM-Logik ohne Android-Abhängigkeit → per Unit-Test prüfbar.
 */
object Proto {
    /** BLE-Service-UUID, an der sich empfangsbereite Geräte erkennen. */
    val SERVICE_UUID: UUID = UUID.fromString("6d1f0b4e-3c2a-4d8e-9b7a-2f5c8e1d4a90")

    const val MAGIC = 0x44415050 // "DAPP"
    const val CONTACT_MAGIC = 0x44434F4E // "DCON"
    const val VERSION = 2
    const val ACCEPT = 1
    const val REJECT = 0
    const val DONE = 2
    const val TRANSPORT_BLE = 0
    const val TRANSPORT_WIFI = 1

    /** Geteilter Text/Link: wird nicht als Datei gespeichert, sondern in die Zwischenablage gelegt. */
    const val MIME_TEXT = "application/x-dataapp-text"
    const val MAX_TEXT_BYTES = 1024 * 1024

    /** Hersteller-ID für die Metadaten im Advertising (0xFFFF = für Tests/ohne eigene Firmen-ID reserviert). */
    const val MANUFACTURER_ID = 0xFFFF
    private const val FLAG_CONTACTS = 0x01
    private const val FLAG_WIFI = 0x01
    private const val MAX_CARD_FIELDS = 32
    private const val MAX_CARD_VALUE = 500
    const val MAX_PHOTO_BYTES = 300 * 1024

    /** Platz für den Gerätenamen in der Scan-Response (31 Byte gesamt − 18 UUID-Overhead − 2 PSM − Reserve). */
    const val MAX_ADVERT_NAME_BYTES = 10

    const val MAX_FILES = 10_000
    private const val BUFFER = 64 * 1024

    data class Header(val senderName: String, val fileCount: Int, val totalBytes: Long, val wifiOffered: Boolean = false)
    data class FileHeader(val name: String, val mimeType: String, val size: Long)
    data class WifiInfo(val ssid: String, val passphrase: String, val port: Int)

    fun writeHeader(out: DataOutputStream, h: Header) {
        out.writeInt(MAGIC)
        out.writeUTF(h.senderName.take(100))
        out.writeInt(h.fileCount)
        out.writeLong(h.totalBytes)
        out.writeByte(if (h.wifiOffered) FLAG_WIFI else 0)
    }

    /** [magic] kann übergeben werden, wenn es schon gelesen wurde (Unterscheidung Datei/Kontakt). */
    fun readHeader(inp: DataInputStream, magic: Int = inp.readInt()): Header {
        if (magic != MAGIC) throw IOException("Unbekanntes Protokoll")
        val name = inp.readUTF()
        val count = inp.readInt()
        val total = inp.readLong()
        val flags = inp.readUnsignedByte()
        if (count !in 1..MAX_FILES || total < 0) throw IOException("Ungültiger Header")
        return Header(name, count, total, flags and FLAG_WIFI != 0)
    }

    /** Weist das Gerät aus: öffentlicher Geräteschlüssel + Signatur über die Schlüssel dieses Handshakes. */
    fun writeAuth(out: DataOutputStream, identity: Identity, transcript: ByteArray) {
        val signature = identity.sign(transcript)
        out.writeShort(identity.publicKey.size)
        out.write(identity.publicKey)
        out.writeShort(signature.size)
        out.write(signature)
    }

    /** Prüft die Signatur und liefert die Geräte-ID; wirft bei gefälschtem Ausweis. */
    fun readAuth(inp: DataInputStream, transcript: ByteArray): String {
        val key = readBlock(inp)
        val signature = readBlock(inp)
        if (!Crypto.verify(Crypto.publicKey(key), transcript, signature)) {
            throw IOException("Das andere Handy konnte sich nicht ausweisen")
        }
        return Crypto.fingerprint(key)
    }

    private fun readBlock(inp: DataInputStream): ByteArray {
        val len = inp.readUnsignedShort()
        if (len !in 1..512) throw IOException("Ungültiger Geräteausweis")
        return ByteArray(len).also(inp::readFully)
    }

    fun writeWifiInfo(out: DataOutputStream, w: WifiInfo) {
        out.writeUTF(w.ssid)
        out.writeUTF(w.passphrase)
        out.writeInt(w.port)
    }

    fun readWifiInfo(inp: DataInputStream): WifiInfo {
        val info = WifiInfo(inp.readUTF(), inp.readUTF(), inp.readInt())
        if (info.port !in 1..65535 || info.ssid.length > 32 || info.passphrase.length !in 8..63) {
            throw IOException("Ungültige WLAN-Daten")
        }
        return info
    }

    fun writeFileHeader(out: DataOutputStream, f: FileHeader) {
        out.writeUTF(f.name.take(255))
        out.writeUTF(f.mimeType.take(127))
        out.writeLong(f.size)
    }

    fun readFileHeader(inp: DataInputStream): FileHeader {
        val name = sanitizeFileName(inp.readUTF())
        val mime = inp.readUTF().ifBlank { "application/octet-stream" }
        val size = inp.readLong()
        if (size < 0) throw IOException("Ungültige Dateigröße")
        return FileHeader(name, mime, size)
    }

    /** Kopiert genau [size] Bytes; [onBytes] erhält die jeweils neu kopierte Menge. */
    fun copyExactly(inp: InputStream, out: OutputStream, size: Long, onBytes: (Int) -> Unit = {}) {
        val buf = ByteArray(BUFFER)
        var remaining = size
        while (remaining > 0) {
            val n = inp.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) throw EOFException("Verbindung vorzeitig beendet")
            out.write(buf, 0, n)
            remaining -= n
            onBytes(n)
        }
    }

    /** Entfernt Pfadanteile und problematische Zeichen aus einem empfangenen Dateinamen. */
    fun sanitizeFileName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_").trim().trimStart('.')
        return cleaned.ifEmpty { "Datei" }.take(200)
    }

    /** Liefert die Adresse, wenn [text] nur aus einem einzelnen http(s)-Link besteht. */
    fun asLink(text: String): String? {
        val t = text.trim()
        val isLink = (t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true)) &&
            t.none { it.isWhitespace() } && t.length in 11..2000
        return t.takeIf { isLink }
    }

    /** Kürzt [s] auf höchstens [maxBytes] UTF-8-Bytes, ohne ein Zeichen zu zerschneiden. */
    fun truncateUtf8(s: String, maxBytes: Int): String {
        val sb = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val len = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + len > maxBytes) break
            sb.appendCodePoint(cp)
            bytes += len
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /** Service-Daten der Scan-Response: PSM (2 Byte, big endian) + gekürzter Gerätename. */
    fun encodeAdvert(psm: Int, name: String): ByteArray {
        val nameBytes = truncateUtf8(name, MAX_ADVERT_NAME_BYTES).toByteArray(Charsets.UTF_8)
        return byteArrayOf((psm shr 8).toByte(), psm.toByte()) + nameBytes
    }

    data class Advert(val psm: Int, val name: String)

    /** Zusatzdaten im Advertising: zufällige Instanz-ID (für die Rollenverteilung) und Kontaktaustausch an/aus. */
    data class Meta(val instanceId: Int, val contactExchange: Boolean)

    fun encodeMeta(m: Meta): ByteArray {
        val id = m.instanceId
        return byteArrayOf(
            (if (m.contactExchange) FLAG_CONTACTS else 0).toByte(),
            (id shr 24).toByte(), (id shr 16).toByte(), (id shr 8).toByte(), id.toByte(),
        )
    }

    fun decodeMeta(data: ByteArray?): Meta? {
        if (data == null || data.size < 5) return null
        val id = ((data[1].toInt() and 0xff) shl 24) or ((data[2].toInt() and 0xff) shl 16) or
            ((data[3].toInt() and 0xff) shl 8) or (data[4].toInt() and 0xff)
        return Meta(id, data[0].toInt() and FLAG_CONTACTS != 0)
    }

    /** Visitenkarte samt optionalem Foto (JPEG). */
    class CardPayload(val card: ContactCard, val photo: ByteArray?)

    fun writeCard(out: DataOutputStream, card: ContactCard, photo: ByteArray? = null) {
        val fields = card.toMap().filterValues { it.isNotBlank() }.entries.take(MAX_CARD_FIELDS)
        out.writeByte(fields.size)
        fields.forEach { (k, v) ->
            out.writeUTF(k)
            out.writeUTF(v.take(MAX_CARD_VALUE))
        }
        val p = photo?.takeIf { it.isNotEmpty() && it.size <= MAX_PHOTO_BYTES }
        out.writeInt(p?.size ?: 0)
        if (p != null) out.write(p)
    }

    fun readCard(inp: DataInputStream): CardPayload {
        val count = inp.readUnsignedByte()
        if (count > MAX_CARD_FIELDS) throw IOException("Ungültige Visitenkarte")
        val map = HashMap<String, String>()
        repeat(count) {
            val k = inp.readUTF()
            val v = inp.readUTF().take(MAX_CARD_VALUE).trim()
            map[k] = v
        }
        val photoLen = inp.readInt()
        if (photoLen !in 0..MAX_PHOTO_BYTES) throw IOException("Ungültiges Kontaktfoto")
        val photo = if (photoLen > 0) ByteArray(photoLen).also(inp::readFully) else null
        return CardPayload(ContactCard.fromMap(map), photo)
    }

    fun writeContactRequest(out: DataOutputStream, instanceId: Int, card: ContactCard, photo: ByteArray? = null) {
        out.writeInt(CONTACT_MAGIC)
        out.writeInt(instanceId)
        writeCard(out, card, photo)
        out.flush()
    }

    /** Liest den Rest einer Kontaktanfrage, nachdem [CONTACT_MAGIC] bereits gelesen wurde. Liefert (Instanz-ID, Karte). */
    fun readContactRequest(inp: DataInputStream): Pair<Int, CardPayload> {
        val id = inp.readInt()
        return id to readCard(inp)
    }

    fun decodeAdvert(data: ByteArray?): Advert? {
        if (data == null || data.size < 2) return null
        val psm = ((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)
        if (psm == 0) return null
        val name = String(data, 2, data.size - 2, Charsets.UTF_8)
        return Advert(psm, name)
    }
}

/** Glättet RSSI-Werte je Gerät, um Ausreißer beim Annähern abzufangen. */
class ProximityTracker(private val alpha: Double = 0.5) {
    private val smoothed = HashMap<String, Double>()
    private val samples = HashMap<String, Int>()

    fun update(key: String, rssi: Int): Int {
        val prev = smoothed[key]
        val next = if (prev == null) rssi.toDouble() else prev + alpha * (rssi - prev)
        smoothed[key] = next
        samples[key] = (samples[key] ?: 0) + 1
        return Math.round(next).toInt()
    }

    fun isClose(key: String, thresholdDbm: Int, minSamples: Int = 2): Boolean {
        val v = smoothed[key] ?: return false
        return (samples[key] ?: 0) >= minSamples && v >= thresholdDbm
    }

    fun remove(key: String) {
        smoothed.remove(key)
        samples.remove(key)
    }
}

/**
 * Entscheidet, ob dieses Handy beim Aneinanderhalten einen Kontaktaustausch startet.
 * Damit nicht beide Handys gleichzeitig verbinden, startet immer das mit der kleineren Instanz-ID.
 */
class ContactTrigger(
    private val cooldownMs: Long = 120_000,
    private val afterTransferMs: Long = 60_000,
) {
    private val lastExchange = HashMap<Int, Long>()

    fun shouldInitiate(ownId: Int, peer: Proto.Meta, close: Boolean, now: Long, lastTransferAt: Long?): Boolean =
        close && peer.contactExchange && ownId < peer.instanceId && mayExchange(peer.instanceId, now, lastTransferAt)

    /** Auch auf der annehmenden Seite: kein Austausch kurz nach einer Dateiübertragung oder doppelt mit demselben Gerät. */
    fun mayExchange(peerId: Int, now: Long, lastTransferAt: Long?): Boolean {
        val recent = lastExchange[peerId]?.let { now - it < cooldownMs } ?: false
        val afterTransfer = lastTransferAt != null && now - lastTransferAt < afterTransferMs
        return !recent && !afterTransfer
    }

    fun markExchanged(peerId: Int, now: Long) {
        lastExchange[peerId] = now
    }
}
