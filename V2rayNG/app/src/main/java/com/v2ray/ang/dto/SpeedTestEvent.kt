package com.v2ray.ang.dto

sealed class SpeedTestEvent {

    /** Progress update while the sequential batch is still running. */
    data class Progress(val text: String) : SpeedTestEvent()

    /** A single server throughput result is available. */
    data class Result(val result: SpeedTestResult) : SpeedTestEvent()

    /** The entire batch has finished. Not sent when the batch is cancelled. */
    data object Finish : SpeedTestEvent()
}
