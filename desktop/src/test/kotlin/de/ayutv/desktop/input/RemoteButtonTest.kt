package de.ayutv.desktop.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteButtonTest {
    @Test
    fun fireTvConsumerKeys() {
        assertEquals(RemoteButton.BACK, RemoteButton.fromConsumerUsage(0x224))
        assertEquals(RemoteButton.HOME, RemoteButton.fromConsumerUsage(0x223))
        assertEquals(RemoteButton.MENU, RemoteButton.fromConsumerUsage(0x40))
        assertEquals(RemoteButton.PLAY_PAUSE, RemoteButton.fromConsumerUsage(0xCD))
        assertEquals(RemoteButton.FAST_FORWARD, RemoteButton.fromConsumerUsage(0xB3))
        assertEquals(RemoteButton.REWIND, RemoteButton.fromConsumerUsage(0xB4))
        assertEquals(RemoteButton.OK, RemoteButton.fromConsumerUsage(0x41))
        assertEquals(RemoteButton.UP, RemoteButton.fromConsumerUsage(0x42))
        assertEquals(RemoteButton.CHANNEL_UP, RemoteButton.fromConsumerUsage(0x9C))
        // Lautstärke steuert Windows selbst.
        assertNull(RemoteButton.fromConsumerUsage(0xE9))
    }

    @Test
    fun windowsVirtualKeysFromConsumerKeys() {
        assertEquals(RemoteButton.BACK, RemoteButton.fromWindowsVirtualKey(0xA6))
        assertEquals(RemoteButton.HOME, RemoteButton.fromWindowsVirtualKey(0xAC))
        assertEquals(RemoteButton.PLAY_PAUSE, RemoteButton.fromWindowsVirtualKey(0xB3))
        // Lautstärke (0xAD–0xAF) nicht abfangen.
        assertNull(RemoteButton.fromWindowsVirtualKey(0xAF))
        // Was Windows zu Tastencodes macht, wird über den Hook gemeldet, nicht doppelt über Raw Input.
        for (usage in RemoteButton.consumerUsagesWithVirtualKey) {
            assertTrue("0x%X".format(usage), RemoteButton.fromConsumerUsage(usage) != null)
        }
    }

    @Test
    fun reportsWithSixteenBitUsages() {
        // Berichtsnummer 1, dann „AC Back“ (0x0224) als 16-Bit-Zahl, niedriges Byte zuerst.
        assertEquals(setOf(0x224), RemoteButton.usagesFromReport(byteArrayOf(1, 0x24, 0x02)))
        // Losgelassen: alles 0.
        assertEquals(emptySet<Int>(), RemoteButton.usagesFromReport(byteArrayOf(1, 0, 0)))
        // Zwei Tasten zugleich.
        assertEquals(setOf(0xB3, 0x40), RemoteButton.usagesFromReport(byteArrayOf(2, 0xB3.toByte(), 0, 0x40, 0)))
    }

    @Test
    fun recognisesAmazonRemote() {
        assertTrue(RemoteButton.isAmazon("\\\\?\\HID#{00001812-0000-1000-8000-00805f9b34fb}_Dev_VID&021949_PID&0410&Col02#a&1"))
        assertTrue(RemoteButton.isAmazon("\\\\?\\HID#VID_1949&PID_0410&Col02"))
        assertFalse(RemoteButton.isAmazon("\\\\?\\HID#VID_046D&PID_C52B&MI_01&Col02"))
    }

    @Test
    fun repeatingKeys() {
        assertTrue(RemoteButton.UP.repeats && RemoteButton.FAST_FORWARD.repeats)
        assertFalse(RemoteButton.OK.repeats || RemoteButton.MENU.repeats || RemoteButton.BACK.repeats)
    }
}
