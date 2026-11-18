package com.v2ray.ang.service

/** A cancellable batch of server tests owned by [CoreTestService]. */
interface TestBatchWorker {
    fun start()
    fun cancel()
}
