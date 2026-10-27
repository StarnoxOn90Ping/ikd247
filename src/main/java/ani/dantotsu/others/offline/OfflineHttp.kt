package ani.dantotsu.others.offline

import ani.dantotsu.defaultHeaders
import ani.dantotsu.okHttpClient
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The server keeps saying "slow down" (or asked for a wait too long to sit through). Pause the run. */
class OfflineRateLimitedException(val host: String, val retryAfterSec: Long) :
    IOException("Rate limited by $host, retry in ${retryAfterSec}s")

/** Several requests in a row failed at the network level: treat the device as offline. */
class OfflineNetworkException(message: String) : IOException(message)

/**
 * The only HTTP entry point of the offline preload (AniList goes through Anilist.executeQuery
 * instead, which has its own token handling, but is throttled by [OfflineRateLimiter] too).
 *
 * Every attempt calls [OfflineRateLimiter.acquire] first. 429 and 503 are honoured: the host is
 * penalised for the Retry-After time and the request is retried (at most [MAX_ATTEMPTS] times,
 * waiting at most [MAX_INLINE_WAIT_SEC] inline). A longer demand throws
 * [OfflineRateLimitedException] so the run pauses and WorkManager resumes it later.
 * Uses the app's normal OkHttp client, so proxy/DNS/DoH settings apply.
 */
object OfflineHttp {
    private const val MAX_ATTEMPTS = 3
    private const val MAX_INLINE_WAIT_SEC = 90L

    data class Result(val code: Int, val body: String) {
        val ok get() = code in 200..299
    }

    private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    private fun retryAfterSec(response: Response, attempt: Int): Long {
        val header = response.header("Retry-After")?.trim()?.toLongOrNull()
        return (header ?: (20L * attempt)).coerceAtLeast(1L)
    }

    /** Sends [build]'s request with throttling/backoff, and maps the response with [read]. */
    private suspend fun <T> send(
        host: String,
        build: () -> Request,
        read: suspend (Response) -> T,
    ): T? {
        var lastIo: IOException? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            OfflineRateLimiter.acquire(host)
            val response = try {
                okHttpClient.newCall(build()).awaitResponse()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                lastIo = e
                delay(1500L * attempt)
                continue
            }
            response.use { r ->
                if (r.code == 429 || r.code == 503) {
                    val wait = retryAfterSec(r, attempt)
                    OfflineRateLimiter.penalize(host, wait * 1000)
                    if (wait > MAX_INLINE_WAIT_SEC || attempt == MAX_ATTEMPTS) {
                        throw OfflineRateLimitedException(host, wait)
                    }
                    Logger.log("OfflineHttp: $host asked to wait ${wait}s")
                    delay(wait * 1000)
                    return@use
                }
                if (r.code >= 500 && attempt < MAX_ATTEMPTS) {
                    delay(2000L * attempt)
                    return@use
                }
                return withContext(Dispatchers.IO) { read(r) }
            }
        }
        if (lastIo != null) throw OfflineNetworkException(lastIo.message ?: "Network error")
        return null
    }

    private fun Request.Builder.withDefaults(extra: Map<String, String>): Request.Builder {
        runCatching { defaultHeaders }.getOrNull()?.forEach { (k, v) -> header(k, v) }
        extra.forEach { (k, v) -> header(k, v) }
        return this
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): Result? {
        val req = { Request.Builder().url(url).withDefaults(headers).build() }
        return send(req().url.host, req) { Result(it.code, it.body.string()) }
    }

    suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): Result? {
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = { Request.Builder().url(url).withDefaults(headers).post(body).build() }
        return send(req().url.host, req) { Result(it.code, it.body.string()) }
    }

    /** Downloads [url] and stores it as the permanent copy. True if the file is now on disk. */
    suspend fun downloadImage(url: String): Boolean {
        val req = { Request.Builder().url(url).withDefaults(emptyMap()).build() }
        return send(req().url.host, req) { r ->
            if (!r.isSuccessful) false
            else OfflineCache.saveImage(url, r.body.byteStream()) != null
        } ?: false
    }
}
