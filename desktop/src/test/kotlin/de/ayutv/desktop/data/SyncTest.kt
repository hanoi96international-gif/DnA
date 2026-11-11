package de.ayutv.desktop.data

import de.ayutv.data.parse.Hid

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class SyncTest {
    @Test
    fun refreshLoadsOnlyWhatIsDue() = runBlocking {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(Hid.s("awIoXiB4emm2")) { exchange ->
            requests.incrementAndGet()
            val body = Hid.s("ZysZeRllQlDgTBw6CGMSbDpr4242ATRdeSJ+Lq9seUwFaHQqNwysZShMbWkRbDcIl0VkJgUnPCJjKvkma19zGnpmOWrtOGtfb1knXDQfm10NIAcXeWc3PbFmMR5sWT0iez/+KwArYVF0AHg2rytoPBVhdGJcUKt9MB57AntnJW3tOWpebxx7ZDkusAM=").toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val store = Store(Files.createTempDirectory(Hid.s("IAAgACcveTk=")).toFile())
            // Ohne freies EPG (kein Internet im Test).
            store.updateUser { it.copy(publicEpg = false) }
            store.savePlaylist(Playlist(1, "M3U", PlaylistType.M3U, "${Hid.s("LBo1XW55OGvxPmpebx16Zy0=")}${server.address.port}${Hid.s("awIoXiB4emm2")}"))
            val sync = Sync(store, okhttp3.OkHttpClient())

            assertEquals(emptyList<String>(), sync.refreshDue())
            assertEquals(1, requests.get())
            assertEquals(2, store.channels().size)
            assertEquals(listOf(Hid.s("ACt7DQYCW3qLTQ==")), store.allVisible().map { it.name })
            assertTrue(store.user.value.guideSyncedAt > 0)

            // Gleich danach ist nichts fällig.
            sync.refreshDue()
            assertEquals(1, requests.get())

            // 13 Stunden später lädt die Playlist neu.
            sync.refreshDue(now = System.currentTimeMillis() + 13 * 3_600_000L)
            assertEquals(2, requests.get())
        } finally {
            server.stop(0)
        }
    }
}
