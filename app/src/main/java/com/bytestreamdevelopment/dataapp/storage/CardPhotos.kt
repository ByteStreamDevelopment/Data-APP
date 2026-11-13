package com.bytestreamdevelopment.dataapp.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.ContactsContract
import com.bytestreamdevelopment.dataapp.transfer.ContactCard
import java.io.ByteArrayOutputStream
import java.io.File

/** Kontaktfotos: eigenes Foto der Visitenkarte und Fotos empfangener Kontakte (kleine JPEGs im App-Speicher). */
object CardPhotos {
    private const val SIZE = 256

    private fun myFile(context: Context) = File(context.filesDir, "my_card.jpg")
    private fun receivedDir(context: Context) = File(context.filesDir, "contacts").apply { mkdirs() }

    fun loadMine(context: Context): ByteArray? = myFile(context).takeIf { it.exists() }?.let { runCatching { it.readBytes() }.getOrNull() }

    fun saveMine(context: Context, photo: ByteArray?) {
        if (photo == null) myFile(context).delete() else myFile(context).writeBytes(photo)
    }

    /** Speichert das Foto eines empfangenen Kontakts und liefert den Dateinamen. */
    fun saveReceived(context: Context, photo: ByteArray, time: Long): String {
        val name = "contact_$time.jpg"
        File(receivedDir(context), name).writeBytes(photo)
        return name
    }

    fun loadReceived(context: Context, name: String?): ByteArray? =
        name?.let { File(receivedDir(context), it) }?.takeIf { it.exists() }?.let { runCatching { it.readBytes() }.getOrNull() }

    fun deleteReceived(context: Context, name: String?) {
        if (name != null) File(receivedDir(context), name).delete()
    }

    fun decode(bytes: ByteArray?): Bitmap? =
        bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }

    /** Lädt ein Bild (Galerie-URI), schneidet es quadratisch zu und verkleinert es auf 256 px. */
    fun fromUri(context: Context, uri: Uri): ByteArray? = runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val shortest = minOf(info.size.width, info.size.height)
            decoder.setTargetSampleSize(maxOf(1, shortest / (SIZE * 2)))
        }
        compress(bitmap)
    }.getOrNull()

    fun compress(bitmap: Bitmap): ByteArray {
        val side = minOf(bitmap.width, bitmap.height)
        val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        val scaled = if (side > SIZE) Bitmap.createScaledBitmap(square, SIZE, SIZE, true) else square
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    /**
     * Liest Name, Telefon, E-Mail, Firma, Website und Foto eines Kontakts aus dem Adressbuch
     * (benötigt READ_CONTACTS; [contactUri] stammt aus der Kontaktauswahl).
     */
    fun readContact(context: Context, contactUri: Uri): Pair<ContactCard, ByteArray?>? = runCatching {
        val resolver = context.contentResolver
        var id: Long? = null
        var name = ""
        resolver.query(
            contactUri, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME), null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                id = c.getLong(0)
                name = c.getString(1).orEmpty()
            }
        }
        val contactId = id ?: return null
        var phone = ""
        var email = ""
        var company = ""
        var website = ""
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1),
            "${ContactsContract.Data.CONTACT_ID} = ?", arrayOf(contactId.toString()),
            "${ContactsContract.Data.IS_SUPER_PRIMARY} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val value = c.getString(1)?.trim().orEmpty()
                if (value.isEmpty()) continue
                when (c.getString(0)) {
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> if (phone.isEmpty()) phone = value
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> if (email.isEmpty()) email = value
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> if (company.isEmpty()) company = value
                    ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE -> if (website.isEmpty()) website = value
                }
            }
        }
        val photo = ContactsContract.Contacts.openContactPhotoInputStream(resolver, contactUri, true)?.use { stream ->
            BitmapFactory.decodeStream(stream)?.let(::compress)
        }
        ContactCard(name, phone, email, company, website) to photo
    }.getOrNull()
}
