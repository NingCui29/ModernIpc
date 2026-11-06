package com.cn.ipc.demo.regression

import android.os.Bundle
import com.cn.ipc.annotations.*
import kotlinx.coroutines.flow.Flow

// Test-only contracts are processed by the same KSP compiler as consumer contracts.
@IpcFacade(serviceId = 7801)
interface IRegressionService {
    @IpcAsync(requestTransaction = 10, cancelTransaction = 11)
    suspend fun echo(requestId: Long): Long

    @IpcAsync(requestTransaction = 12, cancelTransaction = 13)
    suspend fun mix(requestId: Long, controller: String, data: String, responseBinder: String,
                    _data: Long, __ipcArgument0: Long, kotlin: String): String

    @IpcAsync(requestTransaction = 14, cancelTransaction = 15)
    suspend fun bundle(requestId: Long, data: Bundle): Long

    @IpcStream(subscribeTransaction = 20, unsubscribeTransaction = 21)
    fun events(data: String): Flow<Int>

    @IpcDirect(transaction = 30)
    fun read(data: String): String

    @IpcOneway(transaction = 40)
    fun send(data: String)
}
