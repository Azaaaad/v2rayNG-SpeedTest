package com.v2ray.ang.dto.entities

data class ServersCache(
    val guid: String,
    val profile: ProfileItem,
    val testDelayMillis: Long = 0L,
    val testDownloadBytesPerSecond: Long = 0L,
    val testUploadBytesPerSecond: Long = 0L,
)
