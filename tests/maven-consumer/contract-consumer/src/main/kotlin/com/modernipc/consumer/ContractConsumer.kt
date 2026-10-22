package com.modernipc.consumer

import android.content.Context
import com.cn.ipc.ClientServiceSchema
import com.cn.ipc.client.IpcConnectionController
import com.cn.ipc.server.IpcServiceLifecycle
import com.cn.ipc.server.IpcServiceSchemaProvider
import com.modernipc.consumer.contract.ICustomServiceClientAdapter
import com.modernipc.consumer.contract.ICustomServiceIpcSchema
import com.modernipc.consumer.contract.ICustomServiceServerStub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class ContractConsumerStub(context: Context, override val coroutineScope: CoroutineScope) :
    ICustomServiceServerStub(context) {
    override suspend fun echo(payload: String): String = payload
    override fun events(): Flow<String> = flowOf("ready")
}

fun createCustomAdapter(controller: IpcConnectionController): ICustomServiceClientAdapter =
    ICustomServiceClientAdapter(controller)

fun customSchema(): ClientServiceSchema = ICustomServiceIpcSchema.CLIENT_SCHEMA
fun customLifecycle(stub: ContractConsumerStub): IpcServiceLifecycle = stub
fun customSchemaProvider(stub: ContractConsumerStub): IpcServiceSchemaProvider = stub
