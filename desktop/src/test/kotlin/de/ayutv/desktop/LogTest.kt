package de.ayutv.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LogTest {
    /** Harter Absturz vom letzten Mal (hs_err_pid…) landet als „absturz-…“ im Protokollordner. */
    @Test
    fun crashFileMovedIntoLogs() {
        val temp = Files.createTempDirectory("dna-tmp").toFile()
        val logs = Files.createTempDirectory("dna-logs").toFile()
        val old = System.getProperty("java.io.tmpdir")
        try {
            System.setProperty("java.io.tmpdir", temp.path)
            File(temp, "hs_err_pid4711.log").writeText("#\n# A fatal error has been detected\n# Problematic frame:\n# C  [libvlc.dll+0x1234]\n")
            File(temp, "anderes.log").writeText("x")
            Log.collectCrashFiles(logs)
            val moved = logs.listFiles { f -> f.name.startsWith("absturz-") }.orEmpty()
            assertEquals(1, moved.size)
            assertTrue(moved[0].readText().contains("libvlc.dll"))
            assertTrue(!File(temp, "hs_err_pid4711.log").exists())
            assertTrue(File(temp, "anderes.log").exists())
        } finally {
            System.setProperty("java.io.tmpdir", old)
            temp.deleteRecursively(); logs.deleteRecursively()
        }
    }
}
