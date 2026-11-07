package com.bytestreamdevelopment.dataapp.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.io.OutputStream

/**
 * Speichert empfangene Dateien über den MediaStore (ab Android 10 ohne Speicherberechtigung):
 * Bilder → Bilder/Data-App, Videos → Filme/Data-App, Audio → Musik/Data-App, Rest → Download/Data-App.
 */
object FileSaver {
    const val FOLDER = "Data-App"

    fun save(context: Context, name: String, mimeType: String, writer: (OutputStream) -> Unit): Uri {
        val (collection, dir) = when {
            mimeType.startsWith("image/") ->
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_PICTURES
            mimeType.startsWith("video/") ->
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MOVIES
            mimeType.startsWith("audio/") ->
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MUSIC
            else ->
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_DOWNLOADS
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: throw IOException("Datei konnte nicht angelegt werden")
        try {
            resolver.openOutputStream(uri)?.use(writer) ?: throw IOException("Datei konnte nicht geöffnet werden")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    fun folderDescription(mimeType: String): String = when {
        mimeType.startsWith("image/") -> "Bilder/$FOLDER"
        mimeType.startsWith("video/") -> "Filme/$FOLDER"
        mimeType.startsWith("audio/") -> "Musik/$FOLDER"
        else -> "Download/$FOLDER"
    }
}
