package dev.jacobhayes.bootleg.core.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

/** Real network, for L3 live tests and fixture recording. JVM-only (java.net.http). */
class JdkHttp : Http {
    private val client =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofMillis(CatalogLimits.REQUEST_TIMEOUT_MS))
            .build()

    override suspend fun get(url: String): HttpResponse = send(url, "GET")

    override suspend fun head(url: String): HttpResponse = send(url, "HEAD")

    private suspend fun send(
        url: String,
        method: String,
    ): HttpResponse =
        withContext(Dispatchers.IO) {
            val request =
                HttpRequest
                    .newBuilder(URI(url))
                    .timeout(Duration.ofMillis(CatalogLimits.REQUEST_TIMEOUT_MS))
                    .header("User-Agent", "bootleg-catalog-test/0.1")
                    .method(method, HttpRequest.BodyPublishers.noBody())
                    .build()
            // Async so a coroutine timeout cancels the exchange instead of waiting out a hung server.
            val response = client.sendAsync(request, BodyHandlers.ofInputStream()).await()
            val body = response.body().use { readBodyCapped(it, url) }
            HttpResponse(response.statusCode(), body, response.headers().map(), response.uri().toString())
        }

    companion object {
        /** Reads at most [CatalogLimits.RESPONSE_BYTES_MAX] + 1 bytes, per the [Http] size contract. */
        fun readBodyCapped(
            stream: InputStream,
            url: String,
        ): String {
            val bytes = stream.readNBytes(Math.toIntExact(CatalogLimits.RESPONSE_BYTES_MAX + 1))
            if (bytes.size > CatalogLimits.RESPONSE_BYTES_MAX) {
                throw IOException("response larger than ${CatalogLimits.RESPONSE_BYTES_MAX} bytes: $url")
            }
            return bytes.toString(Charsets.UTF_8)
        }
    }
}

/**
 * Replays recorded responses from src/test/resources/fixtures/catalog/<key>.
 * Each URL maps to a file named by the first 16 hex chars of sha256(url), plus an index file
 * `urls.tsv` (hash \t status \t url) so recordings stay reviewable.
 * With system property `bootleg.record=true` it fetches through [JdkHttp] and writes the fixture.
 */
class FixtureHttp(
    private val dir: Path = Path.of("src/test/resources/fixtures/catalog"),
    private val record: Boolean = System.getProperty("bootleg.record") == "true",
) : Http {
    private val live = JdkHttp()

    override suspend fun get(url: String): HttpResponse {
        val file = dir.resolve(key(url))
        if (record) {
            val response = live.get(url)
            Files.createDirectories(dir)
            Files.writeString(file, response.body)
            appendIndex(key(url), response.status, url)
            return response
        }
        check(Files.exists(file)) { "no fixture for $url (run `mise run fixtures:refresh`)" }
        val body = Files.newInputStream(file).use { JdkHttp.readBodyCapped(it, url) }
        return HttpResponse(200, body, emptyMap(), url)
    }

    /** HEAD is never recorded: offline tests treat every image URL as reachable. */
    override suspend fun head(url: String): HttpResponse = HttpResponse(200, "", emptyMap(), url)

    private fun appendIndex(
        hash: String,
        status: Int,
        url: String,
    ) = synchronized(this) {
        val index = dir.resolve("urls.tsv")
        val lines = if (Files.exists(index)) Files.readAllLines(index).filterNot { it.startsWith("$hash\t") } else emptyList()
        Files.write(index, (lines + "$hash\t$status\t$url").sorted())
    }

    companion object {
        fun key(url: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(url.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(16)
    }
}
