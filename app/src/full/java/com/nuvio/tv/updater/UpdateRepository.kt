package com.nuvio.tv.updater

import android.os.Build
import com.nuvio.tv.updater.model.AppUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

internal class NoEligibleUpdateException(channel: UpdateChannel) :
    IllegalStateException("No compatible release for ${channel.storedValue}")

@Singleton
class UpdateRepository @Inject constructor(client: OkHttpClient) {
    private val http=client.newBuilder().apply { interceptors().clear();networkInterceptors().clear() }
        .callTimeout(12,TimeUnit.SECONDS).connectTimeout(8,TimeUnit.SECONDS).readTimeout(8,TimeUnit.SECONDS).build()
    private val lock=Mutex()
    private var sessionResult: Result<AppUpdate>?=null

    suspend fun getLatestUpdate(channel: UpdateChannel,force: Boolean=false): Result<AppUpdate> = withContext(Dispatchers.IO) {
        lock.withLock {
            if(!force) sessionResult?.let { return@withLock it }
            val result=try {
                val request=Request.Builder().url(UPDATE_MANIFEST_URL).header("Accept","application/json")
                    .header("Cache-Control","no-cache").build()
                val update=http.newCall(request).execute().use { response ->
                    require(response.isSuccessful) { "Update unavailable" }
                    val body=response.body ?: error("Empty manifest")
                    // Bound even chunked responses; never read an arbitrary response into RAM.
                    val buffer=java.io.ByteArrayOutputStream()
                    body.byteStream().use { input ->
                        val chunk=ByteArray(4096)
                        while(true) {
                            val n=input.read(chunk);if(n<0) break
                            require(buffer.size()+n<=65536) { "Manifest too large" }
                            buffer.write(chunk,0,n)
                        }
                    }
                    val json=JSONObject(buffer.toString("UTF-8"))
                    val code=json.getLong("versionCode")
                    require(code in 1..2100000000L)
                    val version=json.getString("versionName").take(40)
                    require(version.isNotBlank())
                    val arm=Build.SUPPORTED_ABIS.any { it=="armeabi-v7a" }
                    val key=if(arm) "armeabiV7a" else "universal"
                    val name=if(arm) "NuvioTV-armeabi-v7a.apk" else "NuvioTV-universal.apk"
                    val url=json.getString(key)
                    require(url=="$RELEASE_BASE/latest/download/$name")
                    val hash=json.getString(key+"Sha256").lowercase()
                    require(hash.matches(Regex("[0-9a-f]{64}")))
                    val release=json.getString("releaseUrl")
                    require(release.startsWith("$RELEASE_BASE/tag/mod-v"))
                    AppUpdate(tag="mod-v$version-b$code",title="NuvioTV Mod $version",
                        notes=json.optString("changelog").take(16000),releaseUrl=release,
                        assetName=name,assetUrl=url,versionCode=code.toInt(),sha256=hash,assetSizeBytes=null)
                }
                Result.success(update)
            } catch(e: CancellationException) { throw e }
              catch(e: Exception) { Result.failure<AppUpdate>(IllegalStateException("Update unavailable")) }
            sessionResult=result
            result
        }
    }
    companion object {
        const val RELEASE_BASE="https://github.com/NuvioTvModTv/ModTv/releases"
        const val UPDATE_MANIFEST_URL="$RELEASE_BASE/latest/download/update.json"
    }
}
