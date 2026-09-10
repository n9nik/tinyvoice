package com.n9nik.voicerecorder.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceLogicTest {

    @Test
    fun formatDuration_zero() {
        assertEquals("0:00", RecordingRepository.formatDuration(0))
    }

    @Test
    fun formatDuration_minutesAndSeconds() {
        assertEquals("1:05", RecordingRepository.formatDuration(65_000))
        assertEquals("12:34", RecordingRepository.formatDuration(754_000))
    }

    @Test
    fun formatDuration_hours() {
        assertEquals("1:00:00", RecordingRepository.formatDuration(3_600_000))
        assertEquals("2:03:04", RecordingRepository.formatDuration(7_384_000))
    }

    @Test
    fun coerceRange_validRange() {
        val range = AudioTrimmer.coerceRange(1_000, 5_000, 10_000)
        assertEquals(TrimRange(1_000, 5_000), range)
    }

    @Test
    fun coerceRange_clampsToDuration() {
        val range = AudioTrimmer.coerceRange(-500, 99_000, 10_000)
        assertEquals(TrimRange(0, 10_000), range)
    }

    @Test
    fun coerceRange_rejectsTooShort() {
        assertNull(AudioTrimmer.coerceRange(1_000, 1_200, 10_000))
    }

    @Test
    fun coerceRange_rejectsZeroDuration() {
        assertNull(AudioTrimmer.coerceRange(0, 5_000, 0))
    }
}
