package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.IPAPIInfo
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

object SpeedtestManager {

    data class RemoteEndpointInfo(
        val country: String?,
        val ipAddress: String?,
    )

    /**
     * Measures the time taken to establish a TCP connection to a given URL and port.
     *
     * @param url The URL to connect to.
     * @param port The port to connect to.
     * @return The connection time in milliseconds, or -1 if the connection failed.
     */
    fun socketConnectTime(url: String, port: Int, timeoutMs: Int = 1500): Long {
        var socket: Socket? = null
        val start = System.currentTimeMillis()

        try {
            socket = Socket()
            socket.connect(InetSocketAddress(url, port), timeoutMs)

            return System.currentTimeMillis() - start
        } catch (e: UnknownHostException) {
            LogUtil.e(AppConfig.TAG, "Unknown host: $url", e)
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "socketConnectTime IOException: ${e.message}")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to establish socket connection to $url:$port", e)
        } finally {
            socket?.let { s ->
                try {
                    if (!s.isClosed) {
                        s.close()
                    }
                } catch (closeEx: IOException) {
                }
            }
        }
        return -1
    }

    fun getRemoteIPInfo(): RemoteEndpointInfo? {
        val url = MmkvManager.decodeSettingsString(AppConfig.PREF_IP_API_URL)
            .takeIf { !it.isNullOrBlank() } ?: AppConfig.IP_API_URL

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()
        val httpPort = SettingsManager.getHttpPort()
        if (httpPort == 0) return null
        val content = HttpUtil.getUrlContent(
            UrlContentRequest(
                url = url,
                timeout = 5000,
                httpPort = httpPort,
                proxyUsername = proxyUsername,
                proxyPassword = proxyPassword
            )
        ) ?: return null
        val ipInfo = JsonUtil.fromJsonSafe(content, IPAPIInfo::class.java) ?: return null

        val ip = listOf(
            ipInfo.ip,
            ipInfo.clientIp,
            ipInfo.ip_addr,
            ipInfo.query
        ).firstOrNull { !it.isNullOrBlank() }

        val country = listOf(
            ipInfo.country_code,
            ipInfo.country,
            ipInfo.countryCode,
            ipInfo.location?.country_code
        ).firstOrNull { !it.isNullOrBlank() }

        return RemoteEndpointInfo(
            country = country,
            ipAddress = ip,
        )
    }

    /**
     * Build an HTTP client whose every request goes through the loopback HTTP proxy that a
     * throughput-test core instance listens on.
     */
    fun newLoopbackProxyClient(httpPort: Int, timeoutMs: Long = AppConfig.SPEED_TEST_TIMEOUT_MS): OkHttpClient =
        OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(AppConfig.LOOPBACK, httpPort)))
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()

    /** Release the client's pooled connections and threads once a test instance is done. */
    fun closeClient(client: OkHttpClient) {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    /**
     * Download from [url] and measure the sustained transfer rate.
     *
     * The clock starts when the first body bytes arrive, so connection setup and the proxy
     * handshake do not count against throughput. Reading stops at end of body, after
     * [maxBytes], after [maxDurationMs], or when [isActive] returns false.
     *
     * @return Bytes per second, or -1 when the request fails or nothing was measured.
     */
    fun measureDownloadSpeed(
        client: OkHttpClient,
        url: String,
        maxBytes: Long = AppConfig.SPEED_TEST_DOWNLOAD_BYTES,
        maxDurationMs: Long = AppConfig.SPEED_TEST_MAX_DURATION_MS,
        isActive: () -> Boolean = { true },
    ): Long {
        // identity keeps OkHttp from decompressing, which would overstate the bytes transferred.
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-cache")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    LogUtil.w(AppConfig.TAG, "Speed test download failed, code=${response.code}")
                    return -1L
                }
                val source = response.body.source()
                val sink = Buffer()
                val maxDurationNanos = TimeUnit.MILLISECONDS.toNanos(maxDurationMs)
                var startNanos = 0L
                var bytes = 0L
                var elapsedNanos = 0L
                while (isActive()) {
                    val read = source.read(sink, TRANSFER_CHUNK_BYTES)
                    if (read == -1L) break
                    sink.clear()
                    if (startNanos == 0L) {
                        // The first chunk only marks when data started flowing.
                        startNanos = System.nanoTime()
                        continue
                    }
                    bytes += read
                    elapsedNanos = System.nanoTime() - startNanos
                    if (bytes >= maxBytes || elapsedNanos >= maxDurationNanos) break
                }
                if (!isActive()) return -1L
                return bytesPerSecond(bytes, elapsedNanos)
            }
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "Speed test download failed", e)
        } catch (e: IllegalArgumentException) {
            LogUtil.e(AppConfig.TAG, "Speed test download failed, invalid request", e)
        }
        return -1L
    }

    /**
     * Upload a random payload to [url] and measure the transfer rate.
     *
     * The body is streamed until [maxBytes], [maxDurationMs], or [isActive] returns false; the
     * clock runs from the first body write until the server's response arrives, so data still
     * buffered locally is not counted as sent.
     *
     * @return Bytes per second, or -1 when the request fails or nothing was measured.
     */
    fun measureUploadSpeed(
        client: OkHttpClient,
        url: String,
        maxBytes: Long = AppConfig.SPEED_TEST_UPLOAD_BYTES,
        maxDurationMs: Long = AppConfig.SPEED_TEST_MAX_DURATION_MS,
        isActive: () -> Boolean = { true },
    ): Long {
        val written = AtomicLong(0L)
        val startNanos = AtomicLong(0L)
        val maxDurationNanos = TimeUnit.MILLISECONDS.toNanos(maxDurationMs)
        // Random bytes so a compressing middlebox cannot shrink the payload.
        val payload = Random.nextBytes(TRANSFER_CHUNK_BYTES.toInt())
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()

            // Unknown length: the body ends when a limit is reached.
            override fun contentLength() = -1L

            override fun isOneShot() = true

            override fun writeTo(sink: BufferedSink) {
                val start = System.nanoTime()
                startNanos.set(start)
                while (isActive()) {
                    val sent = written.get()
                    if (sent >= maxBytes || System.nanoTime() - start >= maxDurationNanos) break
                    val count = minOf(payload.size.toLong(), maxBytes - sent).toInt()
                    sink.write(payload, 0, count)
                    sink.flush()
                    written.addAndGet(count.toLong())
                }
            }
        }
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("Cache-Control", "no-cache")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val endNanos = System.nanoTime()
                if (!response.isSuccessful) {
                    LogUtil.w(AppConfig.TAG, "Speed test upload failed, code=${response.code}")
                    return -1L
                }
                if (!isActive() || startNanos.get() == 0L) return -1L
                return bytesPerSecond(written.get(), endNanos - startNanos.get())
            }
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "Speed test upload failed", e)
        } catch (e: IllegalArgumentException) {
            LogUtil.e(AppConfig.TAG, "Speed test upload failed, invalid request", e)
        }
        return -1L
    }

    /**
     * Convert a transfer to bytes per second.
     *
     * @return The rate, at least 1, or -1 when no bytes or no time were measured.
     */
    internal fun bytesPerSecond(bytes: Long, elapsedNanos: Long): Long {
        if (bytes <= 0L || elapsedNanos <= 0L) return -1L
        val rate = bytes.toDouble() * TimeUnit.SECONDS.toNanos(1) / elapsedNanos
        return rate.toLong().coerceAtLeast(1L)
    }

    private const val TRANSFER_CHUNK_BYTES = 64L * 1024
}
