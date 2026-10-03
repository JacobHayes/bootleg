package dev.jacobhayes.bootleg.catalog

import dev.jacobhayes.bootleg.BuildConfig
import dev.jacobhayes.bootleg.core.catalog.CatalogLimits
import dev.jacobhayes.bootleg.core.catalog.Http
import dev.jacobhayes.bootleg.core.catalog.HttpResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Catalog network seam over OkHttp: connect/call timeouts and a response size cap (CatalogLimits).
 * Calls run asynchronously so cancelling the coroutine cancels the call at once (a blocking
 * execute() would keep its thread and socket busy until the call timeout).
 */
class OkHttpHttp(
    baseClient: OkHttpClient,
) : Http {
    private val client =
        baseClient
            .newBuilder()
            .connectTimeout(CatalogLimits.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(CatalogLimits.REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(CatalogLimits.REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

    override suspend fun get(url: String): HttpResponse =
        execute(
            Request
                .Builder()
                .url(url)
                .get()
                .build(),
        )

    override suspend fun head(url: String): HttpResponse =
        execute(
            Request
                .Builder()
                .url(url)
                .head()
                .build(),
        )

    private suspend fun execute(request: Request): HttpResponse =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        // Runs on OkHttp's dispatcher thread; reading the body is bounded by the call timeout.
                        val result = runCatching { response.use { read(request, it) } }
                        result.fold(continuation::resume, continuation::resumeWithException)
                    }
                },
            )
        }

    private fun read(
        request: Request,
        response: Response,
    ): HttpResponse {
        val body = response.body
        val declaredBytes = body.contentLength()
        if (declaredBytes > CatalogLimits.RESPONSE_BYTES_MAX) throw IOException("response too large: ${request.url}")
        val source = body.source()
        // request() buffers up to N bytes; true means there's more than the cap.
        if (source.request(CatalogLimits.RESPONSE_BYTES_MAX + 1)) throw IOException("response too large: ${request.url}")
        val bytes = source.buffer.readByteArray()
        return HttpResponse(
            status = response.code,
            body = bytes.toString(Charsets.UTF_8),
            headers = response.headers.toMultimap(),
            url = response.request.url.toString(),
        )
    }

    companion object {
        val USER_AGENT = "Bootleg/${BuildConfig.VERSION_NAME} (Android)"

        fun baseClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .followRedirects(true)
                .addNetworkInterceptor { chain ->
                    chain.proceed(
                        chain
                            .request()
                            .newBuilder()
                            .header("User-Agent", USER_AGENT)
                            .build(),
                    )
                }.build()
    }
}
