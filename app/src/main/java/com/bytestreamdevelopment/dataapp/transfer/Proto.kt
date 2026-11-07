package com.bytestreamdevelopment.dataapp.transfer

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Übertragungsprotokoll über einen Bluetooth-LE-L2CAP-Kanal.
 *
 * Ablauf (S = Sender, E = Empfänger):
 *  S→E  Header: MAGIC (int), VERSION (byte), Absendername (UTF), Dateianzahl (int), Gesamtgröße (long)
 *  E→S  ACCEPT oder REJECT (byte)
 *  S→E  je Datei: Name (UTF), MIME-Typ (UTF), Größe (long), danach genau <Größe> Rohbytes
 *  E→S  DONE (byte) – erst danach schließt der Sender die Verbindung
 *
 * Reine JVM-Logik ohne Android-Abhängigkeit → per Unit-Test prüfbar.
 */
object Proto {
    /** BLE-Service-UUID, an der sich empfangsbereite Geräte erkennen. */
    val SERVICE_UUID: UUID = UUID.fromString("6d1f0b4e-3c2a-4d8e-9b7a-2f5c8e1d4a90")

    const val MAGIC = 0x44415050 // "DAPP"
    const val VERSION = 1
    const val ACCEPT = 1
    const val REJECT = 0
    const val DONE = 2

    /** Platz für den Gerätenamen in der Scan-Response (31 Byte gesamt − 18 UUID-Overhead − 2 PSM − Reserve). */
    const val MAX_ADVERT_NAME_BYTES = 10

    const val MAX_FILES = 10_000
    private const val BUFFER = 64 * 1024

    data class Header(val senderName: String, val fileCount: Int, val totalBytes: Long)
    data class FileHeader(val name: String, val mimeType: String, val size: Long)

    fun writeHeader(out: DataOutputStream, h: Header) {
        out.writeInt(MAGIC)
        out.writeByte(VERSION)
        out.writeUTF(h.senderName.take(100))
        out.writeInt(h.fileCount)
        out.writeLong(h.totalBytes)
        out.flush()
    }

    fun readHeader(inp: DataInputStream): Header {
        if (inp.readInt() != MAGIC) throw IOException("Unbekanntes Protokoll")
        val version = inp.readUnsignedByte()
        if (version != VERSION) throw IOException("Nicht unterstützte Protokollversion $version – bitte App aktualisieren")
        val name = inp.readUTF()
        val count = inp.readInt()
        val total = inp.readLong()
        if (count !in 1..MAX_FILES || total < 0) throw IOException("Ungültiger Header")
        return Header(name, count, total)
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
