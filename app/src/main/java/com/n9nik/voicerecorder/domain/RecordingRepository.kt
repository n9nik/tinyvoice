package com.n9nik.voicerecorder.domain

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Recording(
    val id: Long,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val dateAddedSec: Long,
    val sizeBytes: Long
)

/** MediaStore-backed storage: recordings land in Music/TinyVoice, no storage permission needed. */
object RecordingRepository {

    private const val MIME_TYPE = "audio/mp4"
    private const val EXTENSION = ".m4a"

    fun list(context: Context): List<Recording> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.SIZE
        )
        val (selection, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?" to arrayOf("Music/TinyVoice/%")
        } else {
            @Suppress("DEPRECATION")
            "${MediaStore.Audio.Media.DATA} LIKE ?" to arrayOf("%/TinyVoice/%")
        }
        val out = mutableListOf<Recording>()
        context.contentResolver.query(
            collection, projection, selection, args,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                out.add(
                    Recording(
                        id = id,
                        uri = Uri.withAppendedPath(collection, id.toString()),
                        name = cursor.getString(nameCol) ?: "Recording",
                        durationMs = cursor.getLong(durCol),
                        dateAddedSec = cursor.getLong(dateCol),
                        sizeBytes = cursor.getLong(sizeCol)
                    )
                )
            }
        }
        return out
    }

    /** Copies [file] into MediaStore and returns its content Uri, or null. */
    fun saveRecording(context: Context, file: File, displayName: String = defaultName()): Uri? {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val name = displayName.ensureExtension()
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, MIME_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/TinyVoice")
            }
        }
        val uri = context.contentResolver.insert(collection, values) ?: return null
        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: run {
                context.contentResolver.delete(uri, null, null)
                return null
            }
        } catch (_: Exception) {
            context.contentResolver.delete(uri, null, null)
            return null
        }
        return uri
    }

    fun rename(context: Context, recording: Recording, newName: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, newName.ensureExtension())
        }
        return try {
            context.contentResolver.update(recording.uri, values, null, null) > 0
        } catch (_: Exception) {
            false
        }
    }

    fun delete(context: Context, recording: Recording): Boolean {
        return try {
            context.contentResolver.delete(recording.uri, null, null) > 0
        } catch (_: Exception) {
            false
        }
    }

    fun durationOf(context: Context, uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    fun defaultName(): String =
        "Recording " + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun formatDate(dateAddedSec: Long): String =
        SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US).format(Date(dateAddedSec * 1000))

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        return "%.1f MB".format(kb / 1024.0)
    }

    private fun String.ensureExtension(): String =
        if (endsWith(EXTENSION, ignoreCase = true)) this else this + EXTENSION
}
