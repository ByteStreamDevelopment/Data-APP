package com.bytestreamdevelopment.dataapp.transfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class CryptoTest {

    /** Baut über zwei Pipes einen Handshake zwischen Initiator und Gegenseite auf. */
    private fun channels(): Pair<SecureChannel, SecureChannel> {
        val i2rOut = PipedOutputStream()
        val i2rIn = PipedInputStream(i2rOut, 1 shl 20)
        val r2iOut = PipedOutputStream()
        val r2iIn = PipedInputStream(r2iOut, 1 shl 20)
        val responder = CompletableFuture.supplyAsync { Handshake.respond(i2rIn, r2iOut) }
        val initiator = Handshake.initiate(r2iIn, i2rOut)
        return initiator to responder.get(10, TimeUnit.SECONDS)
    }

    @Test
    fun handshakeErgibtGleichenCodeUndVerschluesseltBeideRichtungen() {
        val (a, b) = channels()
        assertEquals(a.keys.code, b.keys.code)
        assertEquals(4, a.keys.code.length)
        assertArrayEquals(a.keys.transcript, b.keys.transcript)

        val payload = ByteArray(150_000) { (it * 7).toByte() } // mehrere Blöcke
        a.output.writeUTF("Hallo")
        a.output.write(payload)
        a.output.flush()
        assertEquals("Hallo", b.input.readUTF())
        assertArrayEquals(payload, ByteArray(payload.size).also(b.input::readFully))

        b.output.writeByte(Proto.DONE)
        b.output.flush()
        assertEquals(Proto.DONE, a.input.readUnsignedByte())
    }

    @Test
    fun verschiedeneSitzungenHabenVerschiedeneSchluessel() {
        val (a, _) = channels()
        val (c, _) = channels()
        assertFalse(a.keys.key(SessionKeys.BLE_I2R).encoded.contentEquals(c.keys.key(SessionKeys.BLE_I2R).encoded))
        assertFalse(
            a.keys.key(SessionKeys.BLE_I2R).encoded.contentEquals(a.keys.key(SessionKeys.WIFI_I2R).encoded)
        )
    }

    @Test
    fun chiffretextEnthaeltKeinenKlartextUndManipulationFaelltAuf() {
        val key = SessionKeys(ByteArray(32) { 1 }, ByteArray(8)).key("test")
        val bos = ByteArrayOutputStream()
        SealedOutputStream(bos, key).use { it.write("streng geheim".toByteArray()) }
        val sealed = bos.toByteArray()
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("geheim"))

        val ok = SealedInputStream(ByteArrayInputStream(sealed), key).readBytes()
        assertEquals("streng geheim", String(ok))

        sealed[sealed.size - 1] = (sealed[sealed.size - 1] + 1).toByte()
        try {
            SealedInputStream(ByteArrayInputStream(sealed), key).readBytes()
            fail("Manipulation wurde nicht erkannt")
        } catch (expected: IOException) {
        }
    }

    @Test
    fun falscherSchluesselKannNichtLesen() {
        val bos = ByteArrayOutputStream()
        SealedOutputStream(bos, SessionKeys(ByteArray(32) { 1 }, ByteArray(8)).key("a")).use { it.write(ByteArray(100)) }
        try {
            SealedInputStream(ByteArrayInputStream(bos.toByteArray()), SessionKeys(ByteArray(32) { 2 }, ByteArray(8)).key("a"))
                .readBytes()
            fail("Falscher Schlüssel wurde akzeptiert")
        } catch (expected: IOException) {
        }
    }

    @Test
    fun leererStromEndetSauber() {
        val key = SessionKeys(ByteArray(32), ByteArray(8)).key("a")
        assertEquals(-1, SealedInputStream(ByteArrayInputStream(ByteArray(0)), key).read())
    }

    @Test
    fun geraeteausweisWirdGeprueft() {
        val identity = Identity.generate()
        val transcript = ByteArray(64) { it.toByte() }
        val bos = ByteArrayOutputStream()
        Proto.writeAuth(DataOutputStream(bos), identity, transcript)
        val bytes = bos.toByteArray()

        assertEquals(identity.id, Proto.readAuth(DataInputStream(ByteArrayInputStream(bytes)), transcript))
        assertEquals(32, identity.id.length)

        // Ausweis aus einer anderen Sitzung (anderes Transkript) wird abgelehnt → kein Wiedereinspielen möglich.
        try {
            Proto.readAuth(DataInputStream(ByteArrayInputStream(bytes)), ByteArray(64))
            fail("Fremde Signatur wurde akzeptiert")
        } catch (expected: IOException) {
        }
    }

    @Test
    fun geraeteschluesselExportUndImport() {
        val identity = Identity.generate()
        val restored = Identity.import(identity.export())
        assertNotNull(restored)
        assertEquals(identity.id, restored!!.id)
        val data = "x".toByteArray()
        assertTrue(Crypto.verify(Crypto.publicKey(identity.publicKey), data, restored.sign(data)))
        assertNotEquals(identity.id, Identity.generate().id)
        assertNull(Identity.import("kaputt"))
        assertNull(Identity.import(null))
    }

    @Test
    fun hkdfTestvektorRfc5869() {
        // RFC 5869, Testfall 3 (leeres Salt wird durch 32 Nullbytes ersetzt, leere Info).
        val okm = Crypto.hkdf(ByteArray(22) { 0x0b }, ByteArray(32), "", 42)
        val expected = "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d" +
            "9d201395faa4b61a96c8"
        assertEquals(expected, okm.joinToString("") { "%02x".format(it) })
    }
}
