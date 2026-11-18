package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.SpeedTestResult
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.handler.AngConfigManager

internal data class ServerRowUiModel(
    val guid: String,
    val profile: ProfileItem,
    val remarks: String,
    val statistics: String,
    val typeDescription: String,
    val testDelayMillis: Long,
    val subscriptionBadge: String,
    /** Bytes per second: 0 untested, negative failed. */
    val testDownloadBytesPerSecond: Long = 0L,
    /** Bytes per second: 0 untested, negative failed. */
    val testUploadBytesPerSecond: Long = 0L,
)

internal data class ServerGroupUiState(
    val servers: List<ServersCache> = emptyList(),
    val rows: List<ServerRowUiModel> = emptyList(),
)

internal fun buildServerRowUiModel(
    server: ServersCache,
    subscriptionRemarks: String,
): ServerRowUiModel {
    val profile = server.profile
    return ServerRowUiModel(
        guid = server.guid,
        profile = profile,
        remarks = profile.remarks,
        statistics = profile.description.nullIfBlank()
            ?: AngConfigManager.generateDescription(profile),
        typeDescription = serverProtocolDescription(profile),
        testDelayMillis = server.testDelayMillis,
        subscriptionBadge = subscriptionRemarks.firstOrNull()?.toString().orEmpty(),
        testDownloadBytesPerSecond = server.testDownloadBytesPerSecond,
        testUploadBytesPerSecond = server.testUploadBytesPerSecond,
    )
}

/** Megabits per second for a rate in bytes per second. */
internal fun bytesPerSecondToMbps(bytesPerSecond: Long): Double = bytesPerSecond * 8.0 / 1_000_000.0

internal fun applySpeedTestResults(
    servers: List<ServersCache>,
    updates: Map<String, SpeedTestResult>,
): List<ServersCache> = servers.map { server ->
    val result = updates[server.guid]
    if (result == null
        || (result.downloadBytesPerSecond == server.testDownloadBytesPerSecond
                && result.uploadBytesPerSecond == server.testUploadBytesPerSecond)
    ) {
        server
    } else {
        server.copy(
            testDownloadBytesPerSecond = result.downloadBytesPerSecond,
            testUploadBytesPerSecond = result.uploadBytesPerSecond,
        )
    }
}

internal fun applySpeedTestResultsToRows(
    rows: List<ServerRowUiModel>,
    updates: Map<String, SpeedTestResult>,
): List<ServerRowUiModel> = rows.map { row ->
    val result = updates[row.guid]
    if (result == null
        || (result.downloadBytesPerSecond == row.testDownloadBytesPerSecond
                && result.uploadBytesPerSecond == row.testUploadBytesPerSecond)
    ) {
        row
    } else {
        row.copy(
            testDownloadBytesPerSecond = result.downloadBytesPerSecond,
            testUploadBytesPerSecond = result.uploadBytesPerSecond,
        )
    }
}

/** Reset the speed results of [guids] so a new speed test starts from an untested state. */
internal fun clearSpeedTestResults(servers: List<ServersCache>, guids: Set<String>): List<ServersCache> =
    applySpeedTestResults(servers, guids.associateWith { SpeedTestResult(it, 0L, 0L) })

/** Reset the speed results of [guids] in the visible rows. */
internal fun clearSpeedTestResultsInRows(rows: List<ServerRowUiModel>, guids: Set<String>): List<ServerRowUiModel> =
    applySpeedTestResultsToRows(rows, guids.associateWith { SpeedTestResult(it, 0L, 0L) })

private fun serverProtocolDescription(profile: ProfileItem): String {
    if (profile.configType.isComplexType()) return profile.configType.name
    val parts = mutableListOf(profile.configType.name)
    profile.network?.let { network ->
        if (network.isNotBlank() && !network.equals("tcp", ignoreCase = true)) {
            parts.add(network)
        }
    }
    profile.security?.let { security ->
        if (security.isNotBlank()) {
            parts.add(
                if (profile.insecure == true && security.equals("tls", ignoreCase = true)) {
                    "$security insecure"
                } else {
                    security
                }
            )
        }
    }
    return parts.joinToString(" / ")
}
