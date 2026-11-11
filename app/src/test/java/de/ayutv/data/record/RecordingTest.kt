package de.ayutv.data.record

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingTest {
    @Test
    fun liveRecordingHasPadding() {
        val r = Recording("rec-1", Recording.Kind.RECORDING, "Tatort", url = "http://x/1.ts", userAgent = "ua", start = 1_000_000, stop = 2_000_000)
        assertEquals(1_000_000 - Recording.PAD_BEFORE, r.from)
        assertEquals(2_000_000 + Recording.PAD_AFTER, r.until)
        val d = r.copy(kind = Recording.Kind.DOWNLOAD)
        assertEquals(1_000_000, d.from)
        assertEquals(Long.MAX_VALUE, d.until)
    }

    @Test
    fun fileExtensionFromUrl() {
        assertEquals(".mkv", Recordings.extensionOf("http://h/movie/u/p/12.mkv"))
        assertEquals(".mp4", Recordings.extensionOf("http://h/series/u/p/12.mp4?token=1"))
        assertEquals(".mp4", Recordings.extensionOf("http://h/play/12"))
    }
}
