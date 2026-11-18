package com.v2ray.ang.dto

import java.io.Serializable

/**
 * One persisted throughput-test result delivered from the task process to the UI.
 *
 * Each speed is in bytes per second: `0` means untested and a negative value means the
 * transfer failed.
 */
data class SpeedTestResult(
    val guid: String,
    val downloadBytesPerSecond: Long,
    val uploadBytesPerSecond: Long,
) : Serializable
