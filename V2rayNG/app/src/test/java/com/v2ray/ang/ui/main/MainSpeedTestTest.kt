package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.SpeedTestResult
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MainSpeedTestTest {

    private fun server(guid: String, download: Long = 0L, upload: Long = 0L) = ServersCache(
        guid = guid,
        profile = ProfileItem.create(EConfigType.VLESS).apply { remarks = guid },
        testDelayMillis = 120L,
        testDownloadBytesPerSecond = download,
        testUploadBytesPerSecond = upload,
    )

    private fun row(server: ServersCache) = ServerRowUiModel(
        guid = server.guid,
        profile = server.profile,
        remarks = server.guid,
        statistics = "",
        typeDescription = "VLESS",
        testDelayMillis = server.testDelayMillis,
        subscriptionBadge = "",
        testDownloadBytesPerSecond = server.testDownloadBytesPerSecond,
        testUploadBytesPerSecond = server.testUploadBytesPerSecond,
    )

    @Test
    fun selectedTargetIsOnlyTheSelectedGuid() {
        assertEquals(listOf("b"), speedTestTargets(selectedOnly = true, selectedGuid = "b", visibleGuids = listOf("a", "b", "c")))
    }

    @Test
    fun selectedTargetIsEmptyWithoutSelection() {
        assertTrue(speedTestTargets(selectedOnly = true, selectedGuid = null, visibleGuids = listOf("a")).isEmpty())
        assertTrue(speedTestTargets(selectedOnly = true, selectedGuid = " ", visibleGuids = listOf("a")).isEmpty())
    }

    @Test
    fun allTargetsAreTheVisibleGuidsInOrder() {
        assertEquals(listOf("c", "a"), speedTestTargets(selectedOnly = false, selectedGuid = "a", visibleGuids = listOf("c", "a")))
        assertTrue(speedTestTargets(selectedOnly = false, selectedGuid = "a", visibleGuids = emptyList()).isEmpty())
    }

    @Test
    fun resultsApplyByGuidAndKeepOtherServers() {
        val servers = listOf(server("a"), server("b"))
        val updates = mapOf("b" to SpeedTestResult("b", 2_500_000L, -1L))

        val updated = applySpeedTestResults(servers, updates)

        assertSame(servers[0], updated[0])
        assertEquals(2_500_000L, updated[1].testDownloadBytesPerSecond)
        assertEquals(-1L, updated[1].testUploadBytesPerSecond)
        assertEquals(120L, updated[1].testDelayMillis)

        val rows = applySpeedTestResultsToRows(servers.map(::row), updates)
        assertEquals(2_500_000L, rows[1].testDownloadBytesPerSecond)
        assertEquals(-1L, rows[1].testUploadBytesPerSecond)
        assertEquals(0L, rows[0].testDownloadBytesPerSecond)
    }

    @Test
    fun unchangedResultKeepsTheSameInstance() {
        val servers = listOf(server("a", 10L, 20L))

        val updated = applySpeedTestResults(servers, mapOf("a" to SpeedTestResult("a", 10L, 20L)))

        assertSame(servers[0], updated[0])
    }

    @Test
    fun clearingResetsOnlyTheTargetedServers() {
        val servers = listOf(server("a", 10L, 20L), server("b", -1L, -1L))

        val cleared = clearSpeedTestResults(servers, setOf("b"))
        val clearedRows = clearSpeedTestResultsInRows(servers.map(::row), setOf("a"))

        assertSame(servers[0], cleared[0])
        assertEquals(0L, cleared[1].testDownloadBytesPerSecond)
        assertEquals(0L, cleared[1].testUploadBytesPerSecond)
        assertEquals(0L, clearedRows[0].testDownloadBytesPerSecond)
        assertEquals(-1L, clearedRows[1].testUploadBytesPerSecond)
    }

    @Test
    fun bytesPerSecondConvertsToMegabits() {
        assertEquals(8.0, bytesPerSecondToMbps(1_000_000L))
        assertEquals(0.0, bytesPerSecondToMbps(0L))
        assertEquals(100.0, bytesPerSecondToMbps(12_500_000L))
    }
}
