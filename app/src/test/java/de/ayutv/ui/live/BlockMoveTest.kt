package de.ayutv.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BlockMoveTest {
    private val list = listOf("a", "b", "c", "d", "e", "f")

    @Test
    fun gatherKeepsOrderAtFirstSelected() {
        // b, d, f ausgewählt: als Block an die Stelle von b, Reihenfolge untereinander bleibt.
        assertEquals(listOf("a", "b", "d", "f", "c", "e"), gatherBlock(list, setOf("f", "b", "d")) { it })
        assertEquals(list, gatherBlock(list, setOf("x")) { it })
    }

    @Test
    fun blockMovesAsOne() {
        val gathered = gatherBlock(list, setOf("c", "e")) { it } // a b c e d f
        assertEquals(listOf("a", "c", "e", "b", "d", "f"), moveBlock(gathered, setOf("c", "e"), up = true) { it })
        assertEquals(listOf("a", "b", "d", "c", "e", "f"), moveBlock(gathered, setOf("c", "e"), up = false) { it })
        // Am Rand: unverändert.
        val top = listOf("c", "e", "a")
        assertSame(top, moveBlock(top, setOf("c", "e"), up = true) { it })
        assertSame(list, moveBlock(list, setOf("f"), up = false) { it })
    }
}
