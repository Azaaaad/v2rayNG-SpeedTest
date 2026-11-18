package com.v2ray.ang.handler

import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class SpeedtestManagerThroughputTest {

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()
    private val baseUrl = "http://127.0.0.1:${server.localPort}"

    @AfterEach
    fun tearDown() {
        server.close()
        SpeedtestManager.closeClient(client)
    }

    @Test
    fun bytesPerSecondScalesBytesByElapsedTime() {
        assertEquals(1_000L, SpeedtestManager.bytesPerSecond(1_000L, TimeUnit.SECONDS.toNanos(1)))
        assertEquals(4_000L, SpeedtestManager.bytesPerSecond(1_000L, TimeUnit.MILLISECONDS.toNanos(250)))
        assertEquals(1L, SpeedtestManager.bytesPerSecond(1L, TimeUnit.SECONDS.toNanos(10)))
    }

    @Test
    fun bytesPerSecondReportsFailureWithoutBytesOrTime() {
        assertEquals(-1L, SpeedtestManager.bytesPerSecond(0L, 1L))
        assertEquals(-1L, SpeedtestManager.bytesPerSecond(-5L, 1L))
        assertEquals(-1L, SpeedtestManager.bytesPerSecond(1_000L, 0L))
    }

    @Test
    fun downloadMeasuresBodyAfterFirstChunk() {
        val bodyBytes = 4L * 1024 * 1024
        val requestHeaders = serveOnce { input, output ->
            val headers = readHeaders(input)
            output.write("HTTP/1.1 200 OK\r\nContent-Length: $bodyBytes\r\nConnection: close\r\n\r\n".toByteArray())
            val chunk = ByteArray(64 * 1024)
            var sent = 0L
            while (sent < bodyBytes) {
                output.write(chunk)
                sent += chunk.size
            }
            output.flush()
            headers
        }

        val speed = SpeedtestManager.measureDownloadSpeed(client, "$baseUrl/down", maxBytes = bodyBytes, maxDurationMs = 10_000)

        assertTrue(speed > 0L, "speed=$speed")
        assertTrue(requestHeaders.get(5, TimeUnit.SECONDS).contains("accept-encoding: identity", ignoreCase = true))
    }

    @Test
    fun downloadStopsWhenInactive() {
        serveOnce { input, output ->
            readHeaders(input)
            output.write("HTTP/1.1 200 OK\r\nContent-Length: 1048576\r\nConnection: close\r\n\r\n".toByteArray())
            output.write(ByteArray(1024 * 1024))
            output.flush()
            ""
        }

        val speed = SpeedtestManager.measureDownloadSpeed(client, "$baseUrl/down", isActive = { false })

        assertEquals(-1L, speed)
    }

    @Test
    fun uploadStreamsUntilByteLimitAndMeasuresRate() {
        val maxBytes = 1L * 1024 * 1024
        val received = AtomicLong(-1L)
        val served = serveOnce { input, output ->
            readHeaders(input)
            received.set(readChunkedBody(input))
            output.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush()
            ""
        }

        val speed = SpeedtestManager.measureUploadSpeed(client, "$baseUrl/up", maxBytes = maxBytes, maxDurationMs = 10_000)

        served.get(5, TimeUnit.SECONDS)
        assertTrue(speed > 0L, "speed=$speed")
        assertEquals(maxBytes, received.get())
    }

    @Test
    fun uploadStopsWhenInactive() {
        serveOnce { input, output ->
            readHeaders(input)
            readChunkedBody(input)
            output.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush()
            ""
        }

        val speed = SpeedtestManager.measureUploadSpeed(client, "$baseUrl/up", isActive = { false })

        assertEquals(-1L, speed)
    }

    /** Serve one connection on a background thread; the future holds what [handler] returned. */
    private fun serveOnce(handler: (InputStream, OutputStream) -> String): CompletableFuture<String> {
        val result = CompletableFuture<String>()
        thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    val input = BufferedInputStream(socket.getInputStream())
                    result.complete(handler(input, socket.getOutputStream()))
                }
            } catch (e: Exception) {
                result.completeExceptionally(e)
            }
        }
        return result
    }

    private fun readLine(input: InputStream): String {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) break
            if (b == '\n'.code) break
            if (b != '\r'.code) line.write(b)
        }
        return line.toString(Charsets.US_ASCII.name())
    }

    private fun readHeaders(input: InputStream): String {
        val headers = StringBuilder()
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            headers.append(line).append('\n')
        }
        return headers.toString()
    }

    /** Read a chunked request body and return its decoded length. */
    private fun readChunkedBody(input: InputStream): Long {
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val size = readLine(input).substringBefore(';').trim().toLong(16)
            if (size == 0L) {
                readLine(input)
                return total
            }
            var remaining = size
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read == -1) return total
                remaining -= read
            }
            total += size
            readLine(input)
        }
    }
}
