package com.nuvio.tv.data.livetv

import android.content.Context
import com.nuvio.tv.data.local.ServerConfigurationStore
import com.nuvio.tv.core.di.NetworkModule
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.concurrent.thread

class LiveTvFixesTest {
    @Test fun officialConfigurationIsPopulated() {
        val store=ServerConfigurationStore(mockk())
        val method=store.javaClass.getDeclaredMethod("officialConfiguration").apply { isAccessible=true }
        val config=method.invoke(store) as ServerConfiguration
        assertEquals("https://api.nuvio.tv",config.backendUrl)
        assertEquals("https://api-two.nuvioapp.space",config.fallbackBackendUrl)
        assertTrue(config.publishableKey.startsWith("eyJ"))
    }
    @Test fun mixedAddonFiltersCatalogsAndResolvesOriginMetaHeaders() = runBlocking {
        val cache=Files.createTempDirectory("live-fix").toFile()
        val context=mockk<Context>();every { context.cacheDir } returns cache
        val server=ServerSocket(0);val base="http://127.0.0.1:${server.localPort}"
        val paths=java.util.Collections.synchronizedList(mutableListOf<String>())
        val worker=thread(isDaemon=true) {
            while(!server.isClosed) try { server.accept().use { socket ->
                val reader=socket.getInputStream().bufferedReader();val path=reader.readLine().split(' ')[1]
                while(!reader.readLine().isNullOrEmpty()) { }
                paths.add(path)
                val body=when {
                    path.startsWith("/catalog/") -> """{"metas":[{"id":"origin:one","type":"movie","name":"Channel"}]}"""
                    path.startsWith("/stream/") -> """{"streams":[]}"""
                    path.startsWith("/meta/") -> """{"meta":{"id":"origin:one","type":"movie","name":"Channel","streams":[{"externalUrl":"$base/opaque","behaviorHints":{"proxyHeaders":{"request":{"User-Agent":"LiveFixture","Referer":"$base/","Cookie":"fixture=1"},"response":{"Content-Type":"application/vnd.apple.mpegurl"}}}}]}}"""
                    else -> "{}"
                }.toByteArray()
                socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray());write(body);flush() }
            } } catch(e: Exception) { if(!server.isClosed) throw e }
        }
        try {
            val repo=LiveRepository(context,OkHttpClient(),NetworkModule.provideMoshi(),mockk())
            val catalogs=listOf(CatalogDescriptor(ContentType.MOVIE,id="movies",name="Movies"),CatalogDescriptor(ContentType.SERIES,id="series",name="Series"),CatalogDescriptor(ContentType.MOVIE,id="live",name="Canais ao vivo"))
            assertFalse(repo.isTvCatalog(catalogs[0]));assertFalse(repo.isTvCatalog(catalogs[1]));assertTrue(repo.isTvCatalog(catalogs[2]))
            val addon=Addon("mixed","Mixed",version="1",description=null,logo=null,baseUrl=base,catalogs=catalogs,types=listOf(ContentType.MOVIE),resources=listOf(AddonResource("stream",listOf("movie"),listOf("origin:")),AddonResource("meta",listOf("movie"),null)))
            val channels=repo.channels(1,listOf(addon),true).channels
            assertEquals(1,channels.size)
            val stream=repo.streams(channels.single()).single()
            assertEquals("$base/opaque",stream.url)
            assertEquals("LiveFixture",stream.behaviorHints?.proxyHeaders?.request?.get("User-Agent"))
            assertEquals("fixture=1",stream.behaviorHints?.proxyHeaders?.request?.get("Cookie"))
            assertEquals("$base/",stream.behaviorHints?.proxyHeaders?.request?.get("Referer"))
            assertEquals("application/x-mpegURL",PlayerMediaSourceFactory.probeMimeType(stream.url!!,emptyMap(),responseHeaders=stream.behaviorHints?.proxyHeaders?.response))
            assertEquals(listOf("/catalog/movie/live.json","/stream/movie/origin%3Aone.json","/meta/movie/origin%3Aone.json"),paths.toList())
        } finally { server.close();worker.join(1000);cache.deleteRecursively() }
    }
}
