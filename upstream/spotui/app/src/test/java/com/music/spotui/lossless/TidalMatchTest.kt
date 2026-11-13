package com.music.spotui.lossless

import com.music.spotui.lossless.LosslessSource.TidalHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

class TidalMatchTest {
    // Real search result shape for "Make Some Noize Noize MC" (album first, then live).
    private val studio = TidalHit("287117740", "Make Some Noize", "Noize MC", 213, "RUUM71600088")
    private val live = TidalHit("59944194", "Make Some Noize (Live in Minsk)", "Noize MC", 250, "QMFME1553903")
    private val reissue = TidalHit("288392326", "Make Some Noize", "Noize MC", 213, "RUUM71900211")

    @Test
    fun isrcWinsOverOrder() {
        assertEquals("288392326", LosslessSource.pickTidalHit(listOf(studio, reissue), "RUUM71900211", "Make Some Noize", "Noize MC", 213)?.id)
    }

    @Test
    fun withoutIsrcSameTitleArtistAndLength() {
        assertEquals("287117740", LosslessSource.pickTidalHit(listOf(live, studio), "", "Make Some Noize", "Noize MC", 214)?.id)
    }

    @Test
    fun liveVersionOrOtherArtistIsNotAccepted() {
        assertNull(LosslessSource.pickTidalHit(listOf(live), "", "Make Some Noize", "Noize MC", 213))
        assertNull(LosslessSource.pickTidalHit(listOf(studio.copy(artist = "Someone")), "", "Make Some Noize", "Noize MC", 213))
    }

    @Test
    fun flacLengthFromStreamInfo() {
        // First 42 bytes of the real full Tidal FLAC (213.29 s, 44.1 kHz, 16-bit).
        val head = hex("664c614300000022100010000003c100390b0ac442f0008f86087f648bffadba15a95fc7b92725639f9d")
        assertEquals(213, LosslessSource.flacDurationSec(head))
        assertNull(LosslessSource.flacDurationSec(hex("000000186674797069736f6d")))
    }
}
