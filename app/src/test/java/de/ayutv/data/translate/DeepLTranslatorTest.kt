package de.ayutv.data.translate

import de.ayutv.data.repo.Translator
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

class DeepLTranslatorTest {
    /** Antwortet wie DeepL mit [code]; bei 200 „übersetzt“ jede Zeile zu „ID:<Zeile>“. */
    private class FakeDeepL(private val code: Int) : AutoCloseable {
        private val socket = ServerSocket(0)
        val url get() = "http://127.0.0.1:${socket.localPort}"
        val requests: MutableList<String> = Collections.synchronizedList(ArrayList())

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    client.use { c ->
                        val input = c.getInputStream().bufferedReader()
                        val head = generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                        val length = head.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
                        val body = CharArray(length).also { var read = 0; while (read < length) read += input.read(it, read, length - read) }.concatToString()
                        requests += head.joinToString("\n") + "\n\n" + body
                        val texts = Regex("\"text\":\\[(.*?)]").find(body)?.groupValues?.get(1)?.split("\",\"")?.map { it.trim('"') }.orEmpty()
                        val json = if (head.first().contains("/usage")) """{"character_count":1234,"character_limit":500000}"""
                        else """{"translations":[${texts.joinToString(",") { "{\"text\":\"ID:$it\"}" }}]}"""
                        val payload = if (code == 200) json else "{}"
                        c.getOutputStream().write("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${payload.toByteArray().size}\r\nConnection: close\r\n\r\n$payload".toByteArray())
                    }
                }
            }
        }

        override fun close() = socket.close()
    }

    private class Local : Translator {
        override val name = "Gerät"
        override fun supports(language: String) = true
        override suspend fun translate(texts: List<String>, target: String) = texts.map { "lokal:$it" }
    }

    @Test
    fun translatesWithLanguageCodesAndKey() = FakeDeepL(200).use { server ->
        val deepl = DeepLTranslator(OkHttpClient(), "abc:fx", Local(), server.url)
        val out = runBlocking { deepl.translate(listOf("Hello", "Where is he?"), "id", "en") }
        assertEquals(listOf("ID:Hello", "ID:Where is he?"), out)
        val request = server.requests.single()
        assertTrue(request, request.contains("Authorization: DeepL-Auth-Key abc:fx"))
        assertTrue(request, request.contains("\"target_lang\":\"ID\"") && request.contains("\"source_lang\":\"EN\""))
        assertNull(DeepLTranslator.problem)
        assertEquals(DeepLTranslator.Usage(1234, 500_000), runBlocking { deepl.usage() })
    }

    @Test
    fun quotaUsedUpFallsBackToDevice() = FakeDeepL(456).use { server ->
        val deepl = DeepLTranslator(OkHttpClient(), "abc:fx", Local(), server.url)
        val out = runBlocking { deepl.translate(listOf("Hello"), "id", "en") }
        assertEquals(listOf("lokal:Hello"), out)
        assertEquals("DeepL: Monatskontingent aufgebraucht", DeepLTranslator.problem)
    }

    @Test
    fun codesAndAddresses() {
        assertEquals("EN-GB", DeepLTranslator.targetCode("en"))
        assertEquals("ID", DeepLTranslator.targetCode("ms-ind"))
        assertEquals("NB", DeepLTranslator.targetCode("no"))
        assertNull(DeepLTranslator.targetCode("hi"))
        assertEquals("EN", DeepLTranslator.sourceCode("eng"))
        assertEquals("https://api-free.deepl.com", DeepLTranslator.base("x:fx"))
        assertEquals("https://api.deepl.com", DeepLTranslator.base("x"))
    }
}
