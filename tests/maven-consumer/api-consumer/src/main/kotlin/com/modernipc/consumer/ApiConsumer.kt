package com.modernipc.consumer

import android.content.Context
import com.cn.ipc.ClientServiceSchema
import com.cn.ipc.ServiceSchema
import com.cn.ipc.annotations.IpcFacade
import com.cn.ipc.api.test.IUserServiceClientAdapter
import com.cn.ipc.api.test.IUserServiceIpcSchema
import com.cn.ipc.api.test.IUserServiceServerStub
import com.cn.ipc.client.IpcConnectionController
import com.cn.ipc.server.IpcServiceLifecycle
import com.cn.ipc.server.IpcServiceSchemaProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// Proves annotation export for source authors; the annotation is SOURCE-retained.
@IpcFacade(serviceId = 7301)
interface SourceAnnotationConsumer

class ApiConsumerStub(context: Context, override val coroutineScope: CoroutineScope) :
    IUserServiceServerStub(context) {
    override fun ping() = Unit
    override fun logMessage(msg: String) = Unit
    override suspend fun getUserInfo(userId: String): String = userId
    override suspend fun getLargeData(sizeInBytes: Int): String = "x".repeat(sizeInBytes)
    override fun observeUserStatus(userId: String): Flow<Int> = flowOf(1)
    override fun observeGlobalBroadcast(): Flow<String> = flowOf("ready")
}

fun createPublishedAdapter(controller: IpcConnectionController): IUserServiceClientAdapter =
    IUserServiceClientAdapter(controller)

fun clientSchema(): ClientServiceSchema = IUserServiceIpcSchema.CLIENT_SCHEMA

fun serverSchema(): ServiceSchema = ServiceSchema(1001, 2, 1,
    IUserServiceIpcSchema.DESCRIPTOR, IUserServiceIpcSchema.METHOD_SIGNATURES)

fun lifecycle(stub: ApiConsumerStub): IpcServiceLifecycle = stub
fun schemaProvider(stub: ApiConsumerStub): IpcServiceSchemaProvider = stub
