package dev.jacobhayes.bootleg.core.catalog

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress

/** The test [Http] impls honor the size contract: the cap is in bytes and enforced while reading. */
class TestHttpTest {
    @Test
    fun `JdkHttp stops reading a multibyte body one byte past the cap`() {
        val limitChars = (CatalogLimits.RESPONSE_BYTES_MAX / 2).toInt()
        val bodies = mapOf("/ok" to "é".repeat(limitChars).toByteArray(), "/big" to "é".repeat(limitChars + 1).toByteArray())
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            exchange.use {
                val body = bodies.getValue(it.requestURI.path)
                it.sendResponseHeaders(200, body.size.toLong())
                // The client hangs up once it has read one byte past the cap.
                runCatching { it.responseBody.write(body) }
            }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val ok = runBlocking { JdkHttp().get("$base/ok") }
            assertEquals(limitChars, ok.body.length)
            val error = assertThrows<IOException> { runBlocking { JdkHttp().get("$base/big") } }
            assertEquals("response larger than ${CatalogLimits.RESPONSE_BYTES_MAX} bytes: $base/big", error.message)
        } finally {
            server.stop(0)
        }
    }
}
