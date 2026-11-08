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

    @Test
    fun metaRoundtrip() {
        val m = Proto.Meta(0x7a1b2c3d, contactExchange = true)
        assertEquals(m, Proto.decodeMeta(Proto.encodeMeta(m)))
        val off = Proto.Meta(42, contactExchange = false)
        assertEquals(off, Proto.decodeMeta(Proto.encodeMeta(off)))
        assertNull(Proto.decodeMeta(byteArrayOf(1, 2)))
    }

    @Test
    fun kontaktanfrageRoundtrip() {
        val card = ContactCard("Max Müller", "+49 170 1234567", "max@example.org", "", "https://example.org")
        val bos = ByteArrayOutputStream()
        Proto.writeContactRequest(DataOutputStream(bos), 1234, card)
        val inp = DataInputStream(ByteArrayInputStream(bos.toByteArray()))
        assertEquals(Proto.CONTACT_MAGIC, inp.readInt())
        assertEquals(1234 to card, Proto.readContactRequest(inp))
    }

    @Test
    fun dateiHeaderMitVorabGelesenemMagic() {
        val bos = ByteArrayOutputStream()
        Proto.writeHeader(DataOutputStream(bos), Proto.Header("A", 2, 10))
        val inp = DataInputStream(ByteArrayInputStream(bos.toByteArray()))
        val magic = inp.readInt()
        assertEquals(Proto.Header("A", 2, 10), Proto.readHeader(inp, magic))
    }

    @Test
    fun leereKarteUndTitel() {
        assertTrue(ContactCard(company = "Firma").isEmpty)
        assertEquals("0170", ContactCard(phone = "0170").title)
        assertEquals("0170 · a@b.de", ContactCard("Anna", "0170", "a@b.de").details)
        assertEquals(ContactCard("x", website = "w"), ContactCard.fromMap(mapOf("name" to "x", "website" to "w", "neu" to "?")))
    }

    @Test
    fun nurKleinereIdStartetKontaktaustausch() {
        val t = ContactTrigger(cooldownMs = 1000, afterTransferMs = 500)
        val peer = Proto.Meta(20, contactExchange = true)
        assertTrue(t.shouldInitiate(10, peer, close = true, now = 10_000, lastTransferAt = null))
        assertFalse(t.shouldInitiate(30, peer, close = true, now = 10_000, lastTransferAt = null)) // größere ID wartet
        assertFalse(t.shouldInitiate(10, peer, close = false, now = 10_000, lastTransferAt = null))
        assertFalse(t.shouldInitiate(10, peer.copy(contactExchange = false), close = true, now = 10_000, lastTransferAt = null))
        assertFalse(t.shouldInitiate(10, peer, close = true, now = 10_000, lastTransferAt = 9_800)) // gerade Datei übertragen
    }

    @Test
    fun keinDoppelterAustauschInnerhalbDerSperrzeit() {
        val t = ContactTrigger(cooldownMs = 1000, afterTransferMs = 500)
        t.markExchanged(20, now = 10_000)
        assertFalse(t.mayExchange(20, now = 10_500, lastTransferAt = null))
        assertTrue(t.mayExchange(20, now = 11_000, lastTransferAt = null))
        assertTrue(t.mayExchange(21, now = 10_500, lastTransferAt = null))
    }
}
