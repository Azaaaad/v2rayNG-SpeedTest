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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
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
    fun uploadCountsOnlyConfirmedRequestsUpToByteLimit() {
        val maxBytes = 1L * 1024 * 1024
        val requestSizes = CopyOnWriteArrayList<Long>()
        serveOnce { input, output ->
            // OkHttp reuses the connection, so serve requests until the client closes it.
            while (true) {
                val headers = readHeaders(input)
                if (headers.isEmpty()) break
                val length = contentLength(headers)
                skipBytes(input, length)
                requestSizes.add(length)
                output.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
                output.flush()
            }
            ""
        }

        val speed = SpeedtestManager.measureUploadSpeed(client, "$baseUrl/up", maxBytes = maxBytes, maxDurationMs = 10_000)

        assertTrue(speed > 0L, "speed=$speed")
        // 256 KiB first, then the rest of the 1 MiB budget once the first request was fast.
        assertEquals(listOf(256L * 1024, 768L * 1024), requestSizes.toList())
    }

    @Test
    fun uploadStopsWhenInactive() {
        val speed = SpeedtestManager.measureUploadSpeed(client, "$baseUrl/up", isActive = { false })

        assertEquals(-1L, speed)
    }

    @Test
    fun uploadRequestGrowsOnlyWhileRequestsAreFast() {
        val kib = 1024L
        val second = TimeUnit.SECONDS.toNanos(1)
        assertEquals(1024 * kib, SpeedtestManager.nextUploadRequestBytes(256 * kib, second / 10))
        assertEquals(512 * kib, SpeedtestManager.nextUploadRequestBytes(256 * kib, second))
        assertEquals(256 * kib, SpeedtestManager.nextUploadRequestBytes(256 * kib, 3 * second))
        assertEquals(8 * 1024 * kib, SpeedtestManager.nextUploadRequestBytes(4 * 1024 * kib, second / 10))
    }

    private fun contentLength(headers: String): Long =
        headers.lineSequence()
            .firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toLong() ?: 0L

    private fun skipBytes(input: InputStream, count: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = count
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read == -1) return
            remaining -= read
        }
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
}
