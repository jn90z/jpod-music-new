package com.metrolist.spotify

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyAuthTotpTest {
    // RFC 6238 appendix B, SHA-1 secret "12345678901234567890" in base32.
    private val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    @Test
    fun base32DecodesRfcSecret() {
        assertArrayEquals("12345678901234567890".toByteArray(), SpotifyAuth.base32Decode(secret))
    }

    @Test
    fun totpMatchesRfcVectors() {
        // RFC values are 8 digits; the 6-digit code is their last 6 digits.
        assertEquals("287082", SpotifyAuth.generateTotp(secret, 59))
        assertEquals("081804", SpotifyAuth.generateTotp(secret, 1111111109))
        assertEquals("353130", SpotifyAuth.generateTotp(secret, 20000000000))
    }
}
