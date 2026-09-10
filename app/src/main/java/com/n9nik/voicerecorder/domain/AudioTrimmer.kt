package com.n9nik.voicerecorder.domain

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/** Selection range in milliseconds, coerced into a valid [0, duration] window. */
data class TrimRange(val startMs: Long, val endMs: Long)

/** Offline trim for AAC-in-M4A: copies audio samples in [startMs, endMs) via MediaExtractor/MediaMuxer. */
object AudioTrimmer {

    /** Coerces a requested range into a valid non-empty selection inside [0, durationMs]. */
    fun coerceRange(requestedStartMs: Long, requestedEndMs: Long, durationMs: Long): TrimRange? {
        if (durationMs <= 0) return null
        val start = requestedStartMs.coerceIn(0, durationMs)
        val end = requestedEndMs.coerceIn(0, durationMs)
        if (end - start < 500) return null // ignore sub-half-second slivers
        return TrimRange(start, end)
    }

    /** Trims the range into a new .m4a file in cache. Returns the file, or null on failure. */
    fun trimToFile(context: Context, uri: Uri, range: TrimRange): File? {
        return try {
            val outFile = File(context.cacheDir, "trim_${System.currentTimeMillis()}.m4a")
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(afd.fileDescriptor)
                    val trackIndex = findAudioTrack(extractor) ?: return null
                    val format = extractor.getTrackFormat(trackIndex)
                    val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    try {
                        val outTrack = muxer.addTrack(format)
                        muxer.start()
                        extractor.selectTrack(trackIndex)
                        extractor.seekTo(range.startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        val buffer = ByteBuffer.allocate(512 * 1024)
                        val info = MediaCodec.BufferInfo()
                        while (true) {
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) break
                            val sampleTimeUs = extractor.sampleTime
                            if (sampleTimeUs >= range.endMs * 1000) break
                            // Clamp: the sync frame before startUs must not produce negative timestamps.
                            val ptsUs = (sampleTimeUs - range.startMs * 1000).coerceAtLeast(0)
                            info.set(0, size, ptsUs, extractor.sampleFlags)
                            muxer.writeSampleData(outTrack, buffer, info)
                            if (!extractor.advance()) break
                        }
                        muxer.stop()
                    } finally {
                        muxer.release()
                    }
                } finally {
                    extractor.release()
                }
            } ?: return null
            outFile.takeIf { it.exists() && it.length() > 0 }
        } catch (_: Exception) {
            null
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }
}
