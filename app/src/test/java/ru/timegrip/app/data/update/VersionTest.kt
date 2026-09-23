package ru.timegrip.app.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    @Test
    fun newerPartsWin() {
        assertTrue(isNewerVersion("1.4.22", "1.4.21"))
        assertTrue(isNewerVersion("1.10.0", "1.9.99"))
        assertTrue(isNewerVersion("2.0", "1.4.21"))
    }

    @Test
    fun sameOrOlderIsNotNewer() {
        assertFalse(isNewerVersion("1.4.21", "1.4.21"))
        assertFalse(isNewerVersion("1.4", "1.4.0"))
        assertFalse(isNewerVersion("1.4.20", "1.4.21"))
    }

    @Test
    fun nonNumericTagIsIgnored() {
        assertFalse(isNewerVersion("1.5.0-beta", "1.4.21"))
    }
}
