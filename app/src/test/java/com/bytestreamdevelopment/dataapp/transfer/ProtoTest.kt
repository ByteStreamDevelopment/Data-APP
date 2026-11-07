package com.bytestreamdevelopment.dataapp.transfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

class ProtoTest {

    @Test
    fun headerUndDateienRoundtrip() {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            Proto.writeHeader(out, Proto.Header("Pixel 8", 1, payload.size.toLong()))
            Proto.writeFileHeader(out, Proto.FileHeader("Foto.jpg", "image/jpeg", payload.size.toLong()))
            out.write(payload)
        }
        val inp = DataInputStream(ByteArrayInputStream(bos.toByteArray()))
        assertEquals(Proto.Header("Pixel 8", 1, 200_000), Proto.readHeader(inp))
        val fh = Proto.readFileHeader(inp)
        assertEquals(Proto.FileHeader("Foto.jpg", "image/jpeg", 200_000), fh)
        val out = ByteArrayOutputStream()
        var counted = 0L
        Proto.copyExactly(inp, out, fh.size) { counted += it }
        assertArrayEquals(payload, out.toByteArray())
        assertEquals(200_000L, counted)
    }

    @Test(expected = IOException::class)
    fun falschesMagicWirdAbgelehnt() {
        Proto.readHeader(DataInputStream(ByteArrayInputStream(ByteArray(32))))
    }

    @Test(expected = EOFException::class)
    fun abgebrocheneUebertragungWirftFehler() {
        Proto.copyExactly(ByteArrayInputStream(ByteArray(10)), ByteArrayOutputStream(), 20)
    }

    @Test
    fun dateinamenWerdenBereinigt() {
        assertEquals("passwd", Proto.sanitizeFileName("../../etc/passwd"))
        assertEquals("a_b.txt", Proto.sanitizeFileName("C:\\x\\a:b.txt"))
        assertEquals("bashrc", Proto.sanitizeFileName(".bashrc"))
        assertEquals("Datei", Proto.sanitizeFileName("   "))
        assertEquals("Urlaub 2026.jpg", Proto.sanitizeFileName("Urlaub 2026.jpg"))
    }

    @Test
    fun utf8KuerzungZerschneidetKeineZeichen() {
        assertEquals("Hällö", Proto.truncateUtf8("Hällö Welt", 7))
        assertEquals("ab", Proto.truncateUtf8("ab😀", 5))
        assertEquals("", Proto.truncateUtf8("😀", 3))
    }

    @Test
    fun advertRoundtrip() {
        val data = Proto.encodeAdvert(0x0081, "Galaxy S24 Ultra")
        assertTrue(data.size <= 2 + Proto.MAX_ADVERT_NAME_BYTES)
        assertEquals(Proto.Advert(0x81, "Galaxy S24"), Proto.decodeAdvert(data))
        assertNull(Proto.decodeAdvert(byteArrayOf(1)))
        assertNull(Proto.decodeAdvert(null))
    }

    @Test
    fun naeheErstNachMehrerenStarkenWerten() {
        val t = ProximityTracker(alpha = 0.5)
        t.update("a", -40)
        assertFalse(t.isClose("a", -50)) // nur ein Messwert
        t.update("a", -40)
        assertTrue(t.isClose("a", -50))
        t.update("b", -90)
        t.update("b", -30) // geglättet: -60
        assertFalse(t.isClose("b", -50))
    }
}
