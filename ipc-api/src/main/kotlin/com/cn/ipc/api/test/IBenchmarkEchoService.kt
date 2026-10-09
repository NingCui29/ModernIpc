package com.cn.ipc.api.test

import com.cn.ipc.annotations.IpcAsync
import com.cn.ipc.annotations.IpcDirect
import com.cn.ipc.annotations.IpcFacade
import com.cn.ipc.annotations.IpcStream
import com.cn.ipc.annotations.IpcStreamOverflow
import kotlinx.coroutines.flow.Flow

/** No business work: return the exact payload to the caller for IPC measurements. */
@IpcFacade(serviceId = 9001, minApiVersion = 6)
interface IBenchmarkEchoService {
    @IpcAsync(requestTransaction = 10, cancelTransaction = 11)
    suspend fun echo(payload: String): String

    @IpcDirect(transaction = 12)
    fun echoDirect(payload: String): String

    @IpcDirect(transaction = 13)
    fun echoIntDirect(value: Int): Int

    @IpcDirect(transaction = 14)
    fun echoLongDirect(value: Long): Long

    @IpcDirect(transaction = 15)
    fun echoBooleanDirect(value: Boolean): Boolean

    @IpcDirect(transaction = 16)
    fun echoBytesDirect(value: ByteArray): ByteArray

    /** Test diagnostics only; counts belong to this service Stub. */
    @IpcDirect(transaction = 17)
    fun inspectActiveSubscriptionCount(): Int

    @IpcDirect(transaction = 18)
    fun inspectActiveRequestCount(): Int

    /** A cooperative request held until explicit remote cancellation or disposal. */
    @IpcAsync(requestTransaction = 19, cancelTransaction = 20)
    suspend fun holdLifecycleRequest(): String

    /** An infinite cooperative source used to detect leaked subscriptions. */
    @IpcStream(subscribeTransaction = 21, unsubscribeTransaction = 22)
    fun observeLifecycleTicks(): Flow<Int>

    /** Explicit terminal probe scenarios, isolated from the normal echo benchmark. */
    @IpcStream(subscribeTransaction = 23, unsubscribeTransaction = 24)
    fun observeTerminalScenario(mode: String): Flow<Int>

    /** Test-only state snapshot source with an explicit intermediate-value skip policy. */
    @IpcStream(subscribeTransaction = 25, unsubscribeTransaction = 26,
        overflowPolicy = IpcStreamOverflow.CONFLATE)
    fun observeConflatedScenario(): Flow<Int>
}
