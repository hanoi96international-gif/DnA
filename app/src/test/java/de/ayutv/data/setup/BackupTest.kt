package de.ayutv.data.setup

import de.ayutv.data.parse.Hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupTest {
    @Test
    fun favoriteKeysFollowNewPlaylistNumbers() {
        val ids = mapOf(3L to 1L, 5L to 7L)
        assertEquals("1:c:1234", Backup.remapKey("3:c:1234", ids))
        assertEquals(Hid.s("c1QiFxATOQiXRX4GNVkkbDh1uyYlVCM="), Backup.remapKey(Hid.s("cVQiFxATOQiXRX4GNVkkbDh1uyYlVCM="), ids))
        assertNull(Backup.remapKey("9:c:1", ids))
        assertNull(Backup.remapKey(Hid.s("Lw8xWCAi"), ids))
    }
}
