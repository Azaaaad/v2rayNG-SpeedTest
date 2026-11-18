package com.v2ray.ang.service

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.SpeedTestEvent
import com.v2ray.ang.dto.SpeedTestResult
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import libv2ray.CoreCallbackHandler
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Worker that measures real download and upload throughput of a batch of servers.
 *
 * Unlike [RealPingWorkerService], servers are tested one at a time: concurrent transfers
 * would share the device's bandwidth and understate every result. For each server a
 * temporary core instance exposes the profile through a loopback HTTP proxy, data is
 * transferred through it, and the instance is stopped before the next server starts.
 */
class SpeedTestWorkerService(
    private val context: Context,
    private val guids: List<String>,
    private val onEvent: (SpeedTestEvent) -> Unit = {}
) : TestBatchWorker {
    private val job = SupervisorJob()
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(job + dispatcher + CoroutineName("SpeedTestBatchWorker"))

    override fun start() {
        scope.launch {
            try {
                val total = guids.size
                guids.forEachIndexed { index, guid ->
                    ensureActive()
                    onEvent(SpeedTestEvent.Progress("${total - index} / $total"))
                    val result = measure(guid)
                    ensureActive()
                    onEvent(SpeedTestEvent.Result(result))
                }
                onEvent(SpeedTestEvent.Finish)
            } catch (_: CancellationException) {
                // A cancelled batch sends no finish event; the service replies with cancel instead.
            } finally {
                close()
            }
        }
    }

    override fun cancel() {
        job.cancel()
    }

    private fun close() {
        try {
            dispatcher.close()
        } catch (_: Throwable) {
            // ignore
        }
    }

    private suspend fun measure(guid: String): SpeedTestResult {
        val failed = SpeedTestResult(guid, -1L, -1L)

        val config = MmkvManager.decodeServerConfig(guid) ?: return failed
        if (!isTcpReachable(config)) {
            return failed
        }
        val httpPort = try {
            Utils.findRandomFreePort()
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "Speed test: no free loopback port, server=$guid", e)
            return failed
        }
        val configResult = CoreConfigManager.getV2rayConfig4ThroughputTest(context, guid, httpPort)
        if (!configResult.status) {
            LogUtil.w(AppConfig.TAG, "Speed test: config build failed, server=$guid, ${configResult.errorMessage}")
            return failed
        }
        return RealPingExecutionLimiter.run(config.configType) {
            measureThroughCore(guid, configResult.content, httpPort)
        }
    }

    private fun measureThroughCore(guid: String, content: String, httpPort: Int): SpeedTestResult {
        val failed = SpeedTestResult(guid, -1L, -1L)
        val controller = try {
            CoreNativeManager.newCoreController(SilentCoreCallback)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Speed test: core controller creation failed, server=$guid", e)
            return failed
        }
        try {
            // 0 = no TUN descriptor; the test instance only listens on loopback.
            controller.startLoop(content, 0)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Speed test: core start failed, server=$guid", e)
            stopCore(controller, guid)
            return failed
        }

        val client = SpeedtestManager.newLoopbackProxyClient(httpPort)
        try {
            val isActive = { job.isActive }
            val download = SpeedtestManager.measureDownloadSpeed(
                client = client,
                url = AppConfig.SPEED_TEST_DOWNLOAD_URL,
                isActive = isActive,
            )
            // A server that cannot download will not upload either; skip the second wait.
            val upload = if (download > 0L && job.isActive) {
                SpeedtestManager.measureUploadSpeed(
                    client = client,
                    url = AppConfig.SPEED_TEST_UPLOAD_URL,
                    isActive = isActive,
                )
            } else {
                -1L
            }
            return SpeedTestResult(guid, download, upload)
        } finally {
            SpeedtestManager.closeClient(client)
            stopCore(controller, guid)
        }
    }

    private fun stopCore(controller: libv2ray.CoreController, guid: String) {
        try {
            controller.stopLoop()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Speed test: core stop failed, server=$guid", e)
        }
    }

    /** Fail fast on servers whose TCP port is closed, matching the real-ping precheck. */
    private fun isTcpReachable(config: ProfileItem): Boolean {
        if (config.configType.isComplexType()
            || config.configType == EConfigType.HYSTERIA2
            || config.configType == EConfigType.WIREGUARD
            || config.alpn?.startsWith("h3") == true
            || !config.server.isNotNullEmpty()
        ) {
            return true
        }
        val port = config.serverPort?.toIntOrNull() ?: return true
        return SpeedtestManager.socketConnectTime(config.server.orEmpty(), port, 1000) > -1L
    }

    private object SilentCoreCallback : CoreCallbackHandler {
        override fun startup(): Long = 0

        override fun shutdown(): Long = 0

        override fun onEmitStatus(code: Long, message: String?): Long = 0
    }
}
