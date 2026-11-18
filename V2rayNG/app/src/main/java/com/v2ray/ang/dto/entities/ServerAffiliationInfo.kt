package com.v2ray.ang.dto.entities

/**
 * Per-server test results. Speeds are bytes per second: `0` means untested and a
 * negative value means the test failed. Every property keeps a default so records
 * written before a property existed still decode through the no-argument constructor.
 */
data class ServerAffiliationInfo(
    var testDelayMillis: Long = 0L,
    var testDownloadBytesPerSecond: Long = 0L,
    var testUploadBytesPerSecond: Long = 0L,
)
