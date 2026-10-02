package dev.jacobhayes.bootleg.catalog

import dev.jacobhayes.bootleg.BuildConfig
import dev.jacobhayes.bootleg.core.catalog.CatalogLimits
import dev.jacobhayes.bootleg.core.catalog.Http
import dev.jacobhayes.bootleg.core.catalog.HttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Catalog network seam over OkHttp: 20s timeouts and a response size cap (CatalogLimits). */
class OkHttpHttp(
    baseClient: OkHttpClient,
) : Http {
    private val client =
        baseClient
            .newBuilder()
            .connectTimeout(CatalogLimits.REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
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

    private suspend fun execute(request: Request): HttpResponse {
        val call = client.newCall(request)
        val cancelHandle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            return withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    val body = response.body
                    val declaredBytes = body.contentLength()
                    if (declaredBytes > CatalogLimits.RESPONSE_BYTES_MAX) throw IOException("response too large: ${request.url}")
                    val source = body.source()
                    // request() buffers up to N bytes; true means there's more than the cap.
                    if (source.request(CatalogLimits.RESPONSE_BYTES_MAX + 1)) throw IOException("response too large: ${request.url}")
                    val bytes = source.buffer.readByteArray()
                    HttpResponse(
                        status = response.code,
                        body = bytes.toString(Charsets.UTF_8),
                        headers = response.headers.toMultimap(),
                        url = response.request.url.toString(),
                    )
                }
            }
        } finally {
            cancelHandle.dispose()
        }
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
