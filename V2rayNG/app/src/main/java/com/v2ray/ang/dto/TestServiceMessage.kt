package com.v2ray.ang.dto

import java.io.Serializable

data class TestServiceMessage(
    val key: Int,
    val subscriptionId: String = "",
    val serverGuids: List<String> = emptyList(),
    val onlyTcp: Boolean = false,
    /** Measure download and upload throughput sequentially instead of delay. */
    val speedTest: Boolean = false
) : Serializable

