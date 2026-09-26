package com.nuvio.tv.data.livetv

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.streams.supportsStreamResource
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.mapper.toDomainOrNull
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.livetv.*
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.*
import okio.buffer
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.*
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiveRepository @Inject constructor(@ApplicationContext context: Context,client: OkHttpClient,moshi: Moshi,private val db: EpgDatabase) {
    // Reuse Nuvio networking/DTOs without logging URLs or headers from private addons.
    private val http=client.newBuilder().apply { interceptors().clear();networkInterceptors().clear() }.cache(null)
        .callTimeout(90,TimeUnit.SECONDS).connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).build()
    private val addonHttp=http.newBuilder().addInterceptor { chain ->
        val response=chain.proceed(chain.request());val body=response.body ?: throw IOException("Empty response body")
        val bounded=object: ResponseBody() {
            private val stream=object: okio.ForwardingSource(body.source()) {
                var count=0L
                override fun read(sink: okio.Buffer,byteCount: Long): Long {
                    val n=super.read(sink,byteCount);if(n>0) { count+=n;if(count>16L*1024*1024) throw IOException("Catalog size limit") };return n
                }
            }.buffer()
            override fun contentType()=body.contentType()
            override fun contentLength()=body.contentLength()
            override fun source()=stream
        };response.newBuilder().body(bounded).build()
    }.build()
    private val api=Retrofit.Builder().baseUrl("https://localhost/").client(addonHttp).addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(AddonApi::class.java)
    private val cache=File(context.cacheDir,"live_catalogs").apply { mkdirs() }
    private val json=Json { ignoreUnknownKeys=true }
    private val epgValidators=context.getSharedPreferences("live_epg_http",Context.MODE_PRIVATE)
    private val matchCache=linkedMapOf<String,Pair<Long,Map<String,EpgMatch>>>()
    private val epgLock=Mutex();private val catalogLock=Mutex()
    private val sourceAddons=java.util.concurrent.ConcurrentHashMap<String,Addon>()
    private val streamsCache=linkedMapOf<String,Pair<Long,List<Stream>>>()
    data class ChannelsResult(val channels: List<LiveChannel>,val failed: Int)
    data class EpgResult(val matches: Map<String,EpgMatch>,val failed: Int)
    private val liveTypes=setOf("tv","channel","channels","iptv","live","livetv","live_tv","broadcast","broadcasts","radio","cctv")
    private val liveLabel=Regex("(^|[^a-z0-9])(iptv|live|livetv|live_tv|channels?|canal|canais|aovivo|broadcasts?|24h)([^a-z0-9]|$)|ao\\s+vivo|live[ _-]+tv",RegexOption.IGNORE_CASE)
    fun isTvCatalog(c: CatalogDescriptor): Boolean {
        // Generic TV/movie/series labels and mandatory searches do not declare live channels.
        if(c.extraRequired.any { it=="search" } || c.extra.any { it.name=="search" && it.isRequired }) return false
        return c.apiType.lowercase().trim() in liveTypes || liveLabel.containsMatchIn(c.id+" "+c.name)
    }
    fun isTvAddon(a: Addon): Boolean = a.enabled && a.catalogs.any { c ->
        fun supports(resource: String)=a.resources.any { it.name==resource && (it.types.isEmpty() || c.apiType in it.types) }
        isTvCatalog(c) && supports("catalog") &&
            // A generic VOD metadata endpoint is not evidence of playable live channels.
            (supports("stream") || (c.apiType.lowercase().trim() in liveTypes && supports("meta")))
    }
    suspend fun channels(profile: Int,addons: List<Addon>,force: Boolean)=withContext(Dispatchers.IO) {
        catalogLock.withLock {
            val all=linkedMapOf<String,LiveChannel>();var failed=0
            addons.forEach { sourceAddons[it.baseUrl]=it }
            for(a in addons) for(c in a.catalogs.filter(::isTvCatalog)) {
                ensureActive()
                val file=File(cache,liveHash("$profile:${a.baseUrl}:${c.apiType}:${c.id}")+".json")
                val cached=if(file.exists() && file.length()<16*1024*1024) runCatching { json.decodeFromString(ListSerializer(LiveChannel.serializer()),file.readText()) }.getOrNull() else null
                val list=if(!force && cached!=null && System.currentTimeMillis()-file.lastModified()<30*60000L) cached else try {
                    val loaded=linkedMapOf<String,LiveChannel>();var skip=0
                    for(page in 0 until 15) {
                        ensureActive()
                        val r=api.getCatalog(resourceUrl(a.baseUrl,"catalog",c.apiType,c.id,if(skip>0) "skip=$skip" else null))
                        if(!r.isSuccessful) throw IOException("Catalog HTTP error")
                        val rows=r.body()?.metas ?: throw IOException("Invalid catalog")
                        val before=loaded.size
                        rows.forEach { raw -> raw?.toDomainOrNull(c.apiType,a.baseUrl)?.let { m ->
                            val ch=LiveChannel(m.id,m.apiType,m.name.take(512),(m.logo?:m.poster)?.takeIf { it.length<=8192 },m.genres.take(32).map { it.take(128) }.ifEmpty { listOf(c.name) },m.description?.take(4096),a.displayName,a.baseUrl,a.logo,c.name)
                            if(loaded.size<20000) loaded[ch.key]=ch
                        } }
                        if(loaded.size==before || rows.isEmpty() || !(c.extra.any { it.name=="skip" } || "skip" in c.extraSupported)) break
                        skip+=rows.size
                    }
                    val output=loaded.values.toList();val tmp=File(file.path+".tmp")
                    tmp.writeText(json.encodeToString(ListSerializer(LiveChannel.serializer()),output))
                    if(!tmp.renameTo(file)) tmp.delete()
                    output
                } catch(e: CancellationException) { throw e } catch(e: Exception) { failed++;cached.orEmpty() }
                list.forEach { if(all.size<20000) all.putIfAbsent(it.key,it) }
            }
            var bytes=0L;cache.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { bytes+=it.length();if(bytes>32*1024*1024) it.delete() }
            ChannelsResult(all.values.toList(),failed)
        }
    }
    suspend fun streams(channel: LiveChannel,force: Boolean=false): List<Stream> = withContext(Dispatchers.IO) {
        if(!force) synchronized(streamsCache) { streamsCache[channel.key] }?.let { (time,list) -> if(System.currentTimeMillis()-time<120000) return@withContext list }
        val addon=sourceAddons[channel.addonUrl] ?: run {
            // Resolve only the originating addon. Never search unrelated addons for this ID.
            val base=channel.addonUrl.substringBefore('?').trimEnd('/').removeSuffix("/manifest.json")
            val query=channel.addonUrl.substringAfter('?',"").takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
            val response=api.getManifest("$base/manifest.json$query")
            if(!response.isSuccessful) throw IOException("Manifest HTTP ${response.code()}")
            (response.body() ?: throw IOException("Empty manifest")).toDomain(channel.addonUrl).also { sourceAddons[channel.addonUrl]=it }
        }
        var list=emptyList<Stream>()
        if(addon.supportsStreamResource(channel.type,channel.id)) {
            try {
                val response=api.getStreams(resourceUrl(addon.baseUrl,"stream",channel.type,channel.id))
                if(response.isSuccessful) list=response.body()?.streams.orEmpty().take(100).map { it.toDomain(channel.addonName,channel.addonLogo) }
                else Log.w("LiveTV", "stage=stream_resolution http=${response.code()}")
            } catch(e: CancellationException) { throw e } catch(e: Exception) {
                Log.w("LiveTV", "stage=stream_resolution exception=${e.javaClass.simpleName}")
            }
        } else Log.d("LiveTV", "stage=stream_resolution resource_not_supported")
        val returnedCount=list.size
        list=list.mapNotNull { stream -> playableUrl(stream)?.let { stream.copy(url=it) } }
        if(returnedCount>list.size) Log.d("LiveTV","stage=stream_url rejected=${returnedCount-list.size} returned=$returnedCount")
        if(list.isEmpty() && addon.resources.any { it.name=="meta" && (it.types.isEmpty() || channel.type in it.types) }) {
            try {
                val response=api.getMeta(resourceUrl(addon.baseUrl,"meta",channel.type,channel.id))
                if(response.isSuccessful) {
                    val meta=response.body()?.meta
                    val video=meta?.videos?.firstOrNull { it.id==channel.id }
                        ?: meta?.videos?.firstOrNull { it.id==meta.behaviorHints?.defaultVideoId }
                        ?: meta?.videos?.singleOrNull()
                    list=(meta?.streams.orEmpty()+video?.streams.orEmpty()).take(100)
                        .map { it.toDomain(channel.addonName,channel.addonLogo) }
                        .mapNotNull { stream -> playableUrl(stream)?.let { stream.copy(url=it) } }
                } else Log.w("LiveTV", "stage=meta_fallback http=${response.code()}")
            } catch(e: CancellationException) { throw e } catch(e: Exception) {
                Log.w("LiveTV", "stage=meta_fallback exception=${e.javaClass.simpleName}")
            }
        }
        Log.d("LiveTV", "stage=stream_resolution playable=${list.size}")
        synchronized(streamsCache) { streamsCache[channel.key]=System.currentTimeMillis() to list;while(streamsCache.size>2) streamsCache.remove(streamsCache.keys.first()) }
        list
    }
    suspend fun sync(sources: List<EpgSource>,channels: List<LiveChannel>,force: Boolean,
        onProgress: suspend (EpgResult)->Unit = {})=withContext(Dispatchers.IO) {
        epgLock.withLock {
            if(channels.isEmpty()) return@withLock EpgResult(emptyMap(),0)
            var failed=0;val now=System.currentTimeMillis()
            val signature=liveHash(channels.map { it.id+":"+it.name }.sorted().joinToString("\n"))
            val active=sources.filter { it.enabled }.distinctBy { it.url }.take(16)
                .map { it to liveHash(it.url+signature) }
            val sourceMatches=linkedMapOf<String,Map<String,EpgMatch>>()
            val channelKeys=liveHash(channels.joinToString("\n") { it.key })
            fun readMatches(key: String): Map<String,EpgMatch> {
                val updated=db.updated(key)
                matchCache[key+channelKeys]?.takeIf { it.first==updated }?.let { return it.second }
                val index=db.index(key).filter { it.hasPrograms }
                val matcher=EpgMatcher(index)
                val result=channels.mapNotNull { ch -> matcher.match(ch)?.let { ch.key to EpgMatch(key,it) } }.toMap()
                matchCache[key+channelKeys]=updated to result
                while(matchCache.size>4) matchCache.remove(matchCache.keys.first())
                return result
            }
            fun result(): EpgResult {
                val matches=linkedMapOf<String,EpgMatch>()
                active.forEach { (_,key) -> sourceMatches[key].orEmpty().forEach { (channel,match) -> matches.putIfAbsent(channel,match) } }
                return EpgResult(matches,failed)
            }
            // Read every saved source before starting any network operation.
            for((_,key) in active) {
                ensureActive();sourceMatches[key]=readMatches(key)
                if(sourceMatches[key].orEmpty().isNotEmpty()) onProgress(result())
            }
            for((source,key) in active) {
                ensureActive()
                if(!force && now-db.updated(key)<=6*3600000L) continue
                try {
                    val request=Request.Builder().url(source.url).header("User-Agent","Nuvio/1.0 LiveTV")
                    if(db.updated(key)>0) {
                        epgValidators.getString(key+":etag",null)?.let { request.header("If-None-Match",it) }
                        epgValidators.getString(key+":modified",null)?.let { request.header("If-Modified-Since",it) }
                    }
                    val call=http.newCall(request.build())
                    var contentChanged=false
                    call.execute().use { response ->
                        if(response.code==304 && db.updated(key)>0) db.touch(key,now)
                        else {
                            if(!response.isSuccessful) throw IOException("EPG HTTP error")
                            val body=response.body ?: throw IOException("Empty response body")
                            val raw=LimitedInputStream(body.byteStream(),64L*1024*1024).buffered()
                            raw.mark(2);val first=raw.read();val second=raw.read();raw.reset()
                            val input=if(first==0x1f && second==0x8b) GZIPInputStream(raw) else raw
                            val job=currentCoroutineContext()[Job]!!
                            LimitedInputStream(input,256L*1024*1024).use { db.replace(key,it,now,channels) { job.ensureActive() } }
                            contentChanged=true
                            epgValidators.edit().putString(key+":etag",response.header("ETag"))
                                .putString(key+":modified",response.header("Last-Modified")).apply()
                        }
                    }
                    if(contentChanged) { matchCache.remove(key+channelKeys);sourceMatches[key]=readMatches(key) }
                    else matchCache[key+channelKeys]=db.updated(key) to sourceMatches[key].orEmpty()
                    while(matchCache.size>4) matchCache.remove(matchCache.keys.first())
                    onProgress(result())
                } catch(e: CancellationException) { throw e } catch(e: Exception) {
                    failed++;Log.w("LiveTV","stage=epg_update exception=${e.javaClass.simpleName}")
                }
            }
            db.prune(now);result()
        }
    }
    suspend fun guide(match: EpgMatch?,from: Long,to: Long,limit: Int=200)=withContext(Dispatchers.IO) { if(match==null) ChannelGuide() else db.window(match,from,to,limit) }
    companion object {
        fun playableUrl(s: Stream): String? =
            (listOfNotNull(s.getStreamUrl(),s.url,s.externalUrl)+s.sources.orEmpty())
                .firstNotNullOfOrNull(::cleanPlayableUrl)

        // Unwrap a media address only. Never launch an intent/package or execute its extras.
        internal fun cleanPlayableUrl(raw: String): String? {
            var url=raw.trim()
            val wrappers=listOf("vlc://","mxplayer://","wuffy://","nplayer://","iplayer://")
            repeat(3) {
                if(url.startsWith("intent:",ignoreCase=true)) {
                    val body=url.substring(7)
                    val target=body.substringBefore("#Intent;")
                    if(target.startsWith("//")) {
                        val scheme=body.substringAfter("#Intent;","").split(';')
                            .firstOrNull { it.startsWith("scheme=",ignoreCase=true) }?.substringAfter('=')
                        if(!scheme.equals("http",true) && !scheme.equals("https",true)) return null
                        url="$scheme:$target"
                    } else url=target
                } else {
                    val prefix=wrappers.firstOrNull { url.startsWith(it,ignoreCase=true) }
                    if(prefix!=null) url=url.substring(prefix.length)
                }
            }
            val uri=runCatching { java.net.URI(url) }.getOrNull() ?: return null
            if(uri.scheme?.lowercase(java.util.Locale.ROOT) !in setOf("http","https","rtsp","rtsps","rtmp","rtmps","udp","rtp","mms","mmsh")) return null
            return url.takeIf { !uri.rawAuthority.isNullOrBlank() }
        }
        fun resourceUrl(base: String,resource: String,type: String,id: String,extra: String?=null): String {
            val path=base.substringBefore('?').trimEnd('/').removeSuffix("/manifest.json")
            val query=base.substringAfter('?',"").takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
            fun enc(s: String)=URLEncoder.encode(s,"UTF-8").replace("+","%20")
            return "$path/$resource/${enc(type)}/${enc(id)}"+(extra?.let { "/$it" }?:"")+".json"+query
        }
    }
}
