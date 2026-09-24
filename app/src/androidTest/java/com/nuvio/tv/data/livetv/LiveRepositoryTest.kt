package com.nuvio.tv.data.livetv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.core.di.NetworkModule
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.domain.model.livetv.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class LiveRepositoryTest {
    /** A local HTTP fixture exercises the real Retrofit, parser, cache and matching stack. */
    @Test fun catalogEpgHeadersAndOfflineFallback() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().context
        val socket=ServerSocket(0)
        val base="http://127.0.0.1:${socket.localPort}"
        val now=System.currentTimeMillis()
        val date=DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)
        val xml="""<tv><channel id="one"><display-name>One HD</display-name></channel>
        <programme channel="one" start="${date.format(Instant.ofEpochMilli(now-3600000))}" stop="${date.format(Instant.ofEpochMilli(now+3600000))}"><title>Current show</title></programme></tv>"""
        val server=thread(isDaemon=true) {
            while(!socket.isClosed) try {
                socket.accept().use { client ->
                    val input=client.getInputStream().bufferedReader()
                    val path=input.readLine().split(' ')[1]
                    while(!input.readLine().isNullOrEmpty()) { }
                    val body=when {
                        path.startsWith("/catalog/") -> """{"metas":[{"id":"one","type":"tv","name":"One HD","genres":["News"]},{"id":"two","type":"tv","name":"No guide"},{"id":"invalid"}]}"""
                        path.startsWith("/stream/") -> """{"streams":[{"url":"$base/live.m3u8","behaviorHints":{"proxyHeaders":{"request":{"User-Agent":"FixtureTV","Referer":"$base/","Cookie":"test=one"}}}}]}"""
                        path=="/guide.xml" -> xml
                        else -> "<tv><programme>"
                    }.toByteArray()
                    client.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray());write(body);flush() }
                }
            } catch(e: Exception) { if(!socket.isClosed) throw e }
        }
        val addon=Addon("fixture","Fixture TV",version="1",description=null,logo=null,baseUrl=base,
            catalogs=listOf(CatalogDescriptor(ContentType.TV,id="live",name="TV")),types=listOf(ContentType.TV),resources=listOf(AddonResource("stream",listOf("tv"),null),AddonResource("meta",listOf("tv"),null)))
        val db=EpgDatabase(context)
        try {
            val repo=LiveRepository(context,OkHttpClient(),NetworkModule.provideMoshi(),db)
            val result=repo.channels(99,listOf(addon),true)
            assertEquals(0,result.failed);assertEquals(2,result.channels.size)
            val channel=result.channels.first()
            assertNull(channel.logo)
            val stream=repo.streams(channel).single()
            assertEquals("FixtureTV",stream.behaviorHints?.proxyHeaders?.request?.get("User-Agent"))
            assertEquals("test=one",stream.behaviorHints?.proxyHeaders?.request?.get("Cookie"))
            val epg=repo.sync(listOf(EpgSource("fixture","Fixture","$base/guide.xml")),result.channels,true)
            assertEquals(0,epg.failed);assertEquals(1,epg.matches.size)
            assertEquals("Current show",repo.guide(epg.matches[channel.key],now,now+1000).current(now)?.title)
            assertEquals(1,repo.sync(listOf(EpgSource("bad","Bad","$base/bad.xml")),result.channels,true).failed)
            socket.close();server.join(2000)
            val offline=repo.channels(99,listOf(addon),true)
            assertEquals(1,offline.failed);assertEquals(result.channels,offline.channels)
            val offlineEpg=repo.sync(listOf(EpgSource("fixture","Fixture","$base/guide.xml")),result.channels,true)
            assertEquals(1,offlineEpg.failed);assertEquals(epg.matches,offlineEpg.matches)
        } finally { socket.close();db.close() }
    }
}
