package com.modernipc.consumer.contract

import com.cn.ipc.annotations.IpcAsync
import com.cn.ipc.annotations.IpcFacade
import com.cn.ipc.annotations.IpcStream
import kotlinx.coroutines.flow.Flow

@IpcFacade(serviceId = 7302, minApiVersion = 1)
interface ICustomService {
    @IpcAsync(requestTransaction = 10, cancelTransaction = 11)
    suspend fun echo(payload: String): String

    @IpcAsync(requestTransaction = 12, cancelTransaction = 13)
    suspend fun correlate(requestId: Long, controller: String, data: String, _data: Int): Long

    @IpcStream(subscribeTransaction = 20, unsubscribeTransaction = 21)
    fun events(): Flow<String>
}
