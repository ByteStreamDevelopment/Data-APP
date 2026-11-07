package com.bytestreamdevelopment.dataapp.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.IntentCompat
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Wandelt einen „Teilen“-Intent (ACTION_SEND / ACTION_SEND_MULTIPLE) in zu sendende Dateien um. */
object ShareParser {

    fun parse(context: Context, intent: Intent): List<OutgoingItem> {
        val cache = File(context.cacheDir, "outgoing").apply {
            deleteRecursively()
            mkdirs()
        }
        val uris = buildList {
            when (intent.action) {
                Intent.ACTION_SEND ->
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
                Intent.ACTION_SEND_MULTIPLE ->
                    IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::addAll)
            }
            if (isEmpty()) {
                intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.forEach(::add) }
            }
        }.distinct()

        if (uris.isNotEmpty()) return uris.map { fromUri(context, it, intent.type, cache) }

        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (!text.isNullOrEmpty()) {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY).format(Date())
            val bytes = text.toByteArray(Charsets.UTF_8)
            return listOf(OutgoingItem("Text_$stamp.txt", "text/plain", bytes.size.toLong()) { bytes.inputStream() })
        }
        return emptyList()
    }

    private fun fromUri(context: Context, uri: Uri, intentType: String?, cache: File): OutgoingItem {
        val resolver = context.contentResolver
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        if (uri.scheme == "file") uri.path?.let { size = size ?: File(it).length() }

        var mime = resolver.getType(uri)
            ?: intentType?.takeIf { !it.contains('*') }
            ?: name?.substringAfterLast('.', "")?.lowercase()?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: "application/octet-stream"
        if (mime.contains('*')) mime = "application/octet-stream"

        var fileName = Proto.sanitizeFileName(name ?: uri.lastPathSegment ?: "Datei")
        if (!fileName.contains('.')) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { fileName = "$fileName.$it" }
        }

        val known = size
        if (known != null && known >= 0) {
            return OutgoingItem(fileName, mime, known) {
                resolver.openInputStream(uri) ?: throw IOException("$fileName kann nicht gelesen werden")
            }
        }
        // Größe unbekannt → zwischenspeichern, weil das Protokoll die Größe vorab braucht.
        val tmp = File.createTempFile("share", null, cache)
        (resolver.openInputStream(uri) ?: throw IOException("$fileName kann nicht gelesen werden")).use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        return OutgoingItem(fileName, mime, tmp.length()) { tmp.inputStream() }
    }
}
