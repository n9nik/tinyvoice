package com.n9nik.voicerecorder.recorder

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns the MediaRecorder instance. AAC encoding via MPEG_4 container
 * (MediaRecorder cannot encode MP3 natively — never attempt it).
 * minSdk 24, so pause()/resume() are always available.
 */
object VoiceRecorder {
    enum class State { IDLE, RECORDING, PAUSED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs

    private val _amplitudes = MutableStateFlow<List<Int>>(emptyList())
    val amplitudes: StateFlow<List<Int>> = _amplitudes

    // Incremented by RecordingService after the MediaStore save completes.
    private val _savedCount = MutableStateFlow(0)
    val savedCount: StateFlow<Int> = _savedCount

    fun markSaved() {
        _savedCount.value += 1
    }

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var samplerJob: Job? = null
    private var timerJob: Job? = null
    private var recordStartEpoch = 0L
    private var pausedTotalMs = 0L
    private var pauseStartEpoch = 0L

    @Synchronized
    fun start(context: Context): Boolean {
        if (_state.value != State.IDLE) return false
        return try {
            val dir = File(context.cacheDir, "recordings").apply { mkdirs() }
            val file = File(dir, "rec_${System.currentTimeMillis()}.m4a")
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(128_000)
            rec.setAudioSamplingRate(44_100)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            outputFile = file
            recordStartEpoch = System.currentTimeMillis()
            pausedTotalMs = 0L
            _amplitudes.value = emptyList()
            _elapsedMs.value = 0L
            _state.value = State.RECORDING
            startSampler()
            startTimer()
            true
        } catch (_: Exception) {
            cleanup()
            false
        }
    }

    @Synchronized
    fun pause(): Boolean {
        if (_state.value != State.RECORDING) return false
        return try {
            recorder?.pause()
            pauseStartEpoch = System.currentTimeMillis()
            _state.value = State.PAUSED
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun resume(): Boolean {
        if (_state.value != State.PAUSED) return false
        return try {
            recorder?.resume()
            pausedTotalMs += System.currentTimeMillis() - pauseStartEpoch
            _state.value = State.RECORDING
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Stops recording and returns the raw file, or null on failure. */
    @Synchronized
    fun stop(): File? {
        if (_state.value == State.IDLE) return null
        return try {
            recorder?.stop()
            val f = outputFile
            cleanup()
            f?.takeIf { it.exists() && it.length() > 0 }
        } catch (_: Exception) {
            cleanup()
            null
        }
    }

    @Synchronized
    fun cancel() {
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        val f = outputFile
        cleanup()
        f?.delete()
    }

    private fun startSampler() {
        samplerJob?.cancel()
        samplerJob = scope.launch {
            val amps = ArrayDeque<Int>()
            while (_state.value != State.IDLE) {
                if (_state.value == State.RECORDING) {
                    val amp = try {
                        recorder?.maxAmplitude ?: 0
                    } catch (_: Exception) {
                        0
                    }
                    amps.addLast(amp)
                    if (amps.size > 150) amps.removeFirst()
                    _amplitudes.value = amps.toList()
                }
                delay(120)
            }
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (_state.value != State.IDLE) {
                val now = System.currentTimeMillis()
                val elapsed = if (_state.value == State.PAUSED) {
                    pauseStartEpoch - recordStartEpoch - pausedTotalMs
                } else {
                    now - recordStartEpoch - pausedTotalMs
                }
                _elapsedMs.value = elapsed.coerceAtLeast(0L)
                delay(200)
            }
        }
    }

    private fun cleanup() {
        samplerJob?.cancel()
        timerJob?.cancel()
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
        outputFile = null
        _state.value = State.IDLE
        _elapsedMs.value = 0L
    }
}
