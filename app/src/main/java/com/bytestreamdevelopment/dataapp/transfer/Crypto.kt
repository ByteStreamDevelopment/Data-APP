package com.bytestreamdevelopment.dataapp.transfer

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Verschlüsselung der Übertragung (reine JVM, per Unit-Test geprüft):
 * ECDH (P-256) mit kurzlebigen Schlüsseln → HKDF-SHA256 → AES-256-GCM je Richtung.
 * Schützt vor Mitlesen; gegen einen aktiven Angreifer helfen der 4-stellige Code (unbekannte Geräte)
 * bzw. die Signatur des Geräteschlüssels (vertraute Geräte).
 */
object Crypto {
    fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun publicKey(encoded: ByteArray): PublicKey = try {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
    } catch (e: GeneralSecurityException) {
        throw IOException("Ungültiger Schlüssel der Gegenseite", e)
    }

    fun privateKey(encoded: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(encoded))

    fun ecdh(own: PrivateKey, peer: PublicKey): ByteArray = try {
        KeyAgreement.getInstance("ECDH").run {
            init(own)
            doPhase(peer, true)
            generateSecret()
        }
    } catch (e: GeneralSecurityException) {
        throw IOException("Schlüsselaustausch fehlgeschlagen", e)
    }

    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").apply { parts.forEach(::update) }.digest()

    /** HKDF nach RFC 5869 mit HMAC-SHA256. */
    fun hkdf(secret: ByteArray, salt: ByteArray, info: String, length: Int = 32): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(secret)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArrayOutputStream()
        var block = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            mac.update(block)
            mac.update(info.toByteArray(Charsets.UTF_8))
            mac.update(i.toByte())
            block = mac.doFinal()
            out.write(block)
            i++
        }
        return out.toByteArray().copyOf(length)
    }

    fun sign(key: PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(data)
            sign()
        }

    fun verify(key: PublicKey, data: ByteArray, signature: ByteArray): Boolean = try {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(data)
            verify(signature)
        }
    } catch (e: GeneralSecurityException) {
        false
    }

    /** Kurze, stabile Geräte-ID aus dem öffentlichen Geräteschlüssel. */
    fun fingerprint(publicKeyEncoded: ByteArray): String =
        sha256(publicKeyEncoded).take(16).joinToString("") { "%02x".format(it) }
}

/** Dauerhafter Geräteschlüssel: weist ein Gerät gegenüber Handys aus, die ihm vertrauen. */
class Identity(val publicKey: ByteArray, private val privateKey: ByteArray) {
    val id: String get() = Crypto.fingerprint(publicKey)

    fun sign(data: ByteArray): ByteArray = Crypto.sign(Crypto.privateKey(privateKey), data)

    fun export(): String =
        Base64.getEncoder().encodeToString(publicKey) + ":" + Base64.getEncoder().encodeToString(privateKey)

    companion object {
        fun generate(): Identity = Crypto.newKeyPair().let { Identity(it.public.encoded, it.private.encoded) }

        fun import(text: String?): Identity? = runCatching {
            val (pub, priv) = text!!.split(":")
            Identity(Base64.getDecoder().decode(pub), Base64.getDecoder().decode(priv)).also {
                Crypto.publicKey(it.publicKey)
                Crypto.privateKey(it.privateKey)
            }
        }.getOrNull()
    }
}

/** Aus dem ECDH-Geheimnis abgeleitete Sitzungsschlüssel. [transcript] = beide kurzlebigen öffentlichen Schlüssel. */
class SessionKeys(private val secret: ByteArray, val transcript: ByteArray) {
    private val salt = Crypto.sha256(transcript)

    fun key(info: String): SecretKey = SecretKeySpec(Crypto.hkdf(secret, salt, info), "AES")

    /** 4-stelliger Code, der auf beiden Handys gleich sein muss (sonst hängt jemand dazwischen). */
    val code: String
        get() {
            val b = Crypto.hkdf(secret, salt, "code", 4)
            val n = ((b[0].toInt() and 0x7f) shl 24) or ((b[1].toInt() and 0xff) shl 16) or
                ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
            return "%04d".format(n % 10000)
        }

    companion object {
        const val BLE_I2R = "ble-i2r"
        const val BLE_R2I = "ble-r2i"
        const val WIFI_I2R = "wifi-i2r"
        const val WIFI_R2I = "wifi-r2i"
    }
}

/** Verschlüsselter Kanal nach dem Handshake. */
class SecureChannel(val input: DataInputStream, val output: DataOutputStream, val keys: SessionKeys)

/**
 * Handshake zu Beginn jeder Verbindung (I = Initiator, R = Gegenseite), unverschlüsselt:
 *  I→R  HELLO_MAGIC (int), VERSION (byte), Länge (short) + kurzlebiger öffentlicher Schlüssel
 *  R→I  Länge (short) + kurzlebiger öffentlicher Schlüssel
 * Danach läuft alles über [SealedOutputStream]/[SealedInputStream].
 */
object Handshake {
    const val HELLO_MAGIC = 0x44415032 // "DAP2"
    private const val MAX_KEY = 256

    fun initiate(rawIn: InputStream, rawOut: OutputStream): SecureChannel {
        val pair = Crypto.newKeyPair()
        val mine = pair.public.encoded
        DataOutputStream(rawOut).run {
            writeInt(HELLO_MAGIC)
            writeByte(Proto.VERSION)
            writeShort(mine.size)
            write(mine)
            flush()
        }
        val theirs = readKey(DataInputStream(rawIn))
        val keys = SessionKeys(Crypto.ecdh(pair.private, Crypto.publicKey(theirs)), mine + theirs)
        return wrap(rawIn, rawOut, keys, SessionKeys.BLE_I2R, SessionKeys.BLE_R2I)
    }

    fun respond(rawIn: InputStream, rawOut: OutputStream): SecureChannel {
        val inp = DataInputStream(rawIn)
        if (inp.readInt() != HELLO_MAGIC) {
            throw IOException("Unbekanntes Protokoll – bitte Data-App auf beiden Handys aktualisieren")
        }
        val version = inp.readUnsignedByte()
        if (version != Proto.VERSION) {
            throw IOException("Nicht unterstützte Protokollversion $version – bitte App aktualisieren")
        }
        val theirs = readKey(inp)
        val pair = Crypto.newKeyPair()
        val mine = pair.public.encoded
        DataOutputStream(rawOut).run {
            writeShort(mine.size)
            write(mine)
            flush()
        }
        val keys = SessionKeys(Crypto.ecdh(pair.private, Crypto.publicKey(theirs)), theirs + mine)
        return wrap(rawIn, rawOut, keys, SessionKeys.BLE_R2I, SessionKeys.BLE_I2R)
    }

    /** Legt die Verschlüsselung über ein Stream-Paar; [sendInfo]/[receiveInfo] wählen die Richtungsschlüssel. */
    fun wrap(rawIn: InputStream, rawOut: OutputStream, keys: SessionKeys, sendInfo: String, receiveInfo: String) =
        SecureChannel(
            DataInputStream(SealedInputStream(rawIn, keys.key(receiveInfo))),
            DataOutputStream(SealedOutputStream(rawOut, keys.key(sendInfo))),
            keys,
        )

    private fun readKey(inp: DataInputStream): ByteArray {
        val len = inp.readUnsignedShort()
        if (len !in 1..MAX_KEY) throw IOException("Ungültiger Schlüssel der Gegenseite")
        return ByteArray(len).also(inp::readFully)
    }
}

private const val FRAME_SIZE = 32 * 1024
private const val TAG_BYTES = 16

private fun nonce(counter: Long) = ByteArray(12).also {
    for (i in 0 until 8) it[11 - i] = (counter ushr (8 * i)).toByte()
}

/** Schreibt Daten in AES-GCM-Blöcken: [Länge (int)] [Chiffretext + Tag]. Der Zähler dient als Nonce. */
class SealedOutputStream(private val out: OutputStream, private val key: SecretKey) : OutputStream() {
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val buf = ByteArray(FRAME_SIZE)
    private var pos = 0
    private var counter = 0L

    override fun write(b: Int) {
        if (pos == buf.size) writeFrame()
        buf[pos++] = b.toByte()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var o = off
        var remaining = len
        while (remaining > 0) {
            if (pos == buf.size) writeFrame()
            val n = minOf(remaining, buf.size - pos)
            System.arraycopy(b, o, buf, pos, n)
            pos += n
            o += n
            remaining -= n
        }
    }

    override fun flush() {
        writeFrame()
        out.flush()
    }

    override fun close() {
        try {
            flush()
        } finally {
            out.close()
        }
    }

    private fun writeFrame() {
        if (pos == 0) return
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, nonce(counter++)))
        val sealed = cipher.doFinal(buf, 0, pos)
        val frame = ByteArray(4 + sealed.size)
        frame[0] = (sealed.size ushr 24).toByte()
        frame[1] = (sealed.size ushr 16).toByte()
        frame[2] = (sealed.size ushr 8).toByte()
        frame[3] = sealed.size.toByte()
        System.arraycopy(sealed, 0, frame, 4, sealed.size)
        out.write(frame)
        pos = 0
    }
}

class SealedInputStream(inp: InputStream, private val key: SecretKey) : InputStream() {
    private val inp = DataInputStream(inp)
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private var buf = ByteArray(0)
    private var pos = 0
    private var counter = 0L

    override fun read(): Int {
        if (pos == buf.size && !fill()) return -1
        return buf[pos++].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pos == buf.size && !fill()) return -1
        val n = minOf(len, buf.size - pos)
        System.arraycopy(buf, pos, b, off, n)
        pos += n
        return n
    }

    override fun available(): Int = buf.size - pos

    override fun close() = inp.close()

    /** Liest den nächsten Block; false bei sauberem Ende des Datenstroms. */
    private fun fill(): Boolean {
        while (true) {
            val first = inp.read()
            if (first < 0) return false
            val rest = ByteArray(3).also(inp::readFully)
            val len = (first shl 24) or ((rest[0].toInt() and 0xff) shl 16) or
                ((rest[1].toInt() and 0xff) shl 8) or (rest[2].toInt() and 0xff)
            if (len < TAG_BYTES || len > FRAME_SIZE + TAG_BYTES) throw IOException("Beschädigte Übertragung")
            val sealed = ByteArray(len)
            try {
                inp.readFully(sealed)
            } catch (e: EOFException) {
                throw EOFException("Verbindung vorzeitig beendet")
            }
            try {
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, nonce(counter++)))
                buf = cipher.doFinal(sealed)
            } catch (e: GeneralSecurityException) {
                throw IOException("Übertragung konnte nicht entschlüsselt werden", e)
            }
            pos = 0
            if (buf.isNotEmpty()) return true
        }
    }
}
