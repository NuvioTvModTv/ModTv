package com.nuvio.tv.updater

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApkDownloader @Inject constructor(client: OkHttpClient) {
    private val http=client.newBuilder().apply { interceptors().clear();networkInterceptors().clear() }
        .connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(15,TimeUnit.MINUTES).build()
    suspend fun download(url: String,destinationFile: File,expectedSha256: String,
        onProgress: (Long,Long?)->Unit): Result<File> {
        val partial=File(destinationFile.path+".part")
        try {
            require(expectedSha256.matches(Regex("[0-9a-fA-F]{64}")))
            destinationFile.parentFile?.mkdirs();destinationFile.delete();partial.delete()
            val digest=MessageDigest.getInstance("SHA-256")
            http.newCall(Request.Builder().url(url).header("Cache-Control","no-cache").build()).execute().use { response ->
                require(response.isSuccessful) { "Download failed" }
                val body=response.body ?: error("Empty download")
                val total=body.contentLength().takeIf { it>0 }
                require(total==null || total<=512L*1024*1024)
                body.byteStream().use { input -> FileOutputStream(partial).use { output ->
                    val buffer=ByteArray(32768);var downloaded=0L;var lastProgress=0L
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val n=input.read(buffer);if(n<0) break
                        downloaded+=n;require(downloaded<=512L*1024*1024)
                        output.write(buffer,0,n);digest.update(buffer,0,n)
                        val now=android.os.SystemClock.elapsedRealtime()
                        if(now-lastProgress>=250) { onProgress(downloaded,total);lastProgress=now }
                    }
                    require(total==null || downloaded==total)
                    onProgress(downloaded,total)
                } }
            }
            val hash=digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            require(hash.equals(expectedSha256,true)) { "SHA-256 mismatch" }
            check(partial.renameTo(destinationFile))
            return Result.success(destinationFile)
        } catch(e: CancellationException) { partial.delete();destinationFile.delete();throw e }
          catch(e: Exception) { partial.delete();destinationFile.delete();return Result.failure(IllegalStateException("Download verification failed")) }
    }
}
