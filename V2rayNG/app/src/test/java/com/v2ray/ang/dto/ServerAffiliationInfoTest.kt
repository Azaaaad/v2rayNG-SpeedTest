package com.v2ray.ang.dto

import com.v2ray.ang.dto.entities.ServerAffiliationInfo
import com.v2ray.ang.util.JsonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ServerAffiliationInfoTest {

    @Test
    fun readsRecordWrittenBeforeSpeedResultsExisted() {
        val stored = """{"testDelayMillis":321}"""

        val info = requireNotNull(JsonUtil.fromJsonSafe(stored, ServerAffiliationInfo::class.java))

        assertEquals(321L, info.testDelayMillis)
        assertEquals(0L, info.testDownloadBytesPerSecond)
        assertEquals(0L, info.testUploadBytesPerSecond)
    }

    @Test
    fun roundTripsSpeedResults() {
        val info = ServerAffiliationInfo(
            testDelayMillis = -1L,
            testDownloadBytesPerSecond = 1_250_000L,
            testUploadBytesPerSecond = -1L,
        )

        assertEquals(info, JsonUtil.fromJsonSafe(JsonUtil.toJson(info), ServerAffiliationInfo::class.java))
    }
}
