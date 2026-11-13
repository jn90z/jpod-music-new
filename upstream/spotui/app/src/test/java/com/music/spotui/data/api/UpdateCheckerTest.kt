package com.music.spotui.data.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun comparesVersionsNumerically() {
        assertTrue(UpdateChecker.isNewer("v1.7", "1.6"))
        assertTrue(UpdateChecker.isNewer("v1.10", "1.6"))
        assertTrue(UpdateChecker.isNewer("1.6.1", "1.6"))
        assertFalse(UpdateChecker.isNewer("v1.5", "1.6"))
        assertFalse(UpdateChecker.isNewer("v1.6", "1.6"))
        assertFalse(UpdateChecker.isNewer("Release", "1.6"))
    }
}
