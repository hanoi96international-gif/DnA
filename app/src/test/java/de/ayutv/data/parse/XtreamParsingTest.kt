package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XtreamParsingTest {

    @Test
    fun toleratesMixedTypesAndNulls() {
        val items = mutableListOf<XtreamLive>()
        XtreamParsing.liveStreams(
            Hid.s("H2RhDXR2N3rjKWROYQ10dmx4rXwpTHsceHR5O65sZlRjbAYSNXbhejAcJEw5CX4+4TN1XnABdiVjKKZoKTEoTjs4NWDhK2hMJF0zCXQyomcqCy1yPTI1YK18KAJtDzc3Yz+kZjYXHkQwdC149itoTDVbCzdlOatgMgtjF2V6NS61ViUcIkU9IHIFp3w2DzVEOzg1YOE+ZhNtJ3R2N3rjKWROYQ10djd6uCsqGywPbnQleO8rKg8sSHZsNQCHT2ZCY14gJHI7rlYtCmMXdmcnaOElZgsxSgs1fzutZyECHkQwdC14uW0iQCVIdno1OaJ9IQkuXy0Jfj7hM3ETbSd0djd64ylkTmENdHY3ergrKg8sSHZsNTWrZyFOKEl2Kx164ylkTmENdHY3euNU").byteInputStream()
        ) { items += it }

        assertEquals(2, items.size)
        assertEquals("101", items[0].streamId)
        assertNull(items[0].icon)
        assertNull(items[0].epgId)
        assertEquals(7, items[0].archiveDays)
        assertEquals(2, items[1].number)
        assertEquals("5", items[1].categoryId)
    }

    @Test
    fun readsCategoriesAndAccount() {
        val categories = XtreamParsing.categories("""[{"category_id":"1","category_name":"Sport"}]""".byteInputStream())
        assertEquals(mapOf("1" to "Sport"), categories)

        val account = XtreamParsing.account("""{"user_info":{"auth":1,"status":"Active","exp_date":"1700000000","max_connections":"2"}}""".byteInputStream())
        assertEquals(1_700_000_000_000L, account?.expiresAt)
        assertEquals(2, account?.maxConnections)
        assertNull(XtreamParsing.account("""{"user_info":{"auth":0}}""".byteInputStream()))
    }

    @Test
    fun readsEpisodesInBothShapes() {
        val asObject = """{"episodes":{"2":[{"id":"9","episode_num":1,"season":2,"container_extension":"mkv","info":{"duration_secs":"1500"}}],
                           "1":[{"id":"8","episode_num":"3","season":"1","title":"Pilot"}]}}"""
        val episodes = XtreamParsing.episodes(asObject.byteInputStream())
        assertEquals(listOf("8", "9"), episodes.map { it.id })
        assertEquals(1500L, episodes[1].durationSeconds)
        assertEquals("mkv", episodes[1].extension)

        val asArray = """{"episodes":[[{"id":"1","episode_num":1,"season":1}]]}"""
        assertEquals(1, XtreamParsing.episodes(asArray.byteInputStream()).size)
    }

    @Test
    fun nonArrayResponseYieldsNothing() {
        var count = 0
        XtreamParsing.vodStreams("""{"user_info":{"auth":0}}""".byteInputStream()) { count++ }
        assertEquals(0, count)
    }
}
