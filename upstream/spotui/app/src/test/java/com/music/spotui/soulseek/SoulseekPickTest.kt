package com.music.spotui.soulseek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SoulseekPickTest {
    private fun f(path: String, slots: Boolean = true, dur: Int = 0) =
        SlskFile("peer", path, size = 30_000_000, durationSec = dur, slotsFree = slots)

    @Test
    fun picksTheSameSongOnly() {
        val right = f("Music\\FACE\\Album\\03 - ЧЕТВЁРТЫЙ ВСАДНИК.flac")
        val files = listOf(
            f("Music\\Other\\03 - ЧЕТВЁРТЫЙ ВСАДНИК.flac"), // wrong artist
            f("Music\\FACE\\Album\\03 - ЧЕТВЁРТЫЙ ВСАДНИК (Live).flac"), // live version
            f("Music\\FACE\\Album\\03 - ЧЕТВЁРТЫЙ ВСАДНИК.mp3"), // not FLAC
            right,
        )
        assertEquals(right, SoulseekSource.pick(files, "ЧЕТВЁРТЫЙ ВСАДНИК", "FACE", 0))
    }

    @Test
    fun rejectsWrongLengthWhenKnown() {
        assertNull(SoulseekSource.pick(listOf(f("FACE\\Song.flac", dur = 300)), "Song", "FACE", 180))
        assertEquals("FACE\\Song.flac", SoulseekSource.pick(listOf(f("FACE\\Song.flac", dur = 182)), "Song", "FACE", 180)?.filename)
    }

    @Test
    fun liveIsFineWhenTheTitleSaysLive() {
        val live = f("FACE\\Song (Live).flac")
        assertEquals(live, SoulseekSource.pick(listOf(live), "Song (Live)", "FACE", 0))
    }

    @Test
    fun spotifyExtrasDoNotBlockAMatch() {
        val file = f("Daft Punk\\Random Access Memories\\08 - Get Lucky (feat. Pharrell Williams & Nile Rodgers).flac")
        assertEquals(file, SoulseekSource.pick(listOf(file), "Get Lucky (feat. Pharrell Williams and Nile Rodgers)", "Daft Punk", 0))
        assertEquals("Get Lucky", SoulseekSource.baseTitle("Get Lucky - Radio Edit"))
    }

    @Test
    fun flacLengthReadsPastAnId3Tag() {
        // Real STREAMINFO header of a 213 s, 44.1 kHz FLAC.
        val flac = "664c614300000022100010000003c100390b0ac442f0008f86087f648bffadba15a95fc7b92725639f9d"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(213, SoulseekSource.flacLengthSec(flac))
        // Same audio behind a 300-byte ID3v2 tag (size is syncsafe: 2*128 + 44).
        val id3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 2, 44)
        assertEquals(213, SoulseekSource.flacLengthSec(id3 + ByteArray(300) + flac))
    }
}
