package com.cn.ipc.demo

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.cn.ipc.ClientServiceSchema
import com.cn.ipc.api.test.IBenchmarkEchoServiceClientAdapter
import com.cn.ipc.api.test.IBenchmarkEchoServiceIpcSchema
import com.cn.ipc.client.IpcClientState
import com.cn.ipc.client.IpcCompatibilityException
import com.cn.ipc.client.IpcConnectionController
import kotlinx.coroutines.*

/** Explicit compatibility intent only; the old-Broker endpoint is a method-availability fixture. */
object IpcCompatibilityProbe {
    private const val TAG = "IpcCompatibilityProbe"
    private const val ECHO_ID = 9001
    private const val CHECKS = 16

    suspend fun run(context: Context, anchor: IpcConnectionController, runId: String) =
        withContext(Dispatchers.Default) {
            val connection = anchor.awaitConnected(10_000)
            val full = IBenchmarkEchoServiceIpcSchema.CLIENT_SCHEMA
            val remote = connection.broker.getServiceSchema(ECHO_ID)
            check(remote.serviceId == ECHO_ID && remote.apiVersion >= full.contractVersion)
            check(remote.minSupportedClientVersion == 1 && remote.descriptor == full.descriptor)
            check(full.methodSignatures.all { remote.methodSignatures[it.key] == it.value })
            val fullBinder = anchor.getServiceBinderForConnection(connection, ECHO_ID, full.contractVersion, full)
            check(connection.broker.getServiceChecked(ECHO_ID, full.contractVersion, full) == fullBinder)
            Log.i(TAG, "PASS run=$runId metadataMatch descriptor=true signatures=${full.methodSignatures.size} floor=1")

            val directSignature = full.methodSignatures.getValue(12)
            val subset = full.copy(contractVersion = 1, methodSignatures = mapOf(12 to directSignature))
            val subsetBinder = connection.broker.getServiceChecked(ECHO_ID, 1, subset)
            check(subsetBinder == fullBinder)
            check(anchor.getServiceBinderForConnection(connection, ECHO_ID, 1, subset) == fullBinder)
            check(rawDirectEcho(subsetBinder, full.descriptor, "subset-accepted") == "subset-accepted")
            Log.i(TAG, "PASS run=$runId additiveSubset clientVersion=1 requestedTransactions=1 extraServerTransactions=true")

            val mutations = linkedMapOf(
                "parameterChange" to changedDirect(full, directSignature, "params=[string!]", "params=[int32!]"),
                "returnChange" to changedDirect(full, directSignature, "return=string!", "return=int32!"),
                "modeChange" to changedDirect(full, directSignature, "mode=direct", "mode=oneway"),
                "envelopeChange" to full.copy(methodSignatures = full.methodSignatures + (12 to "$directSignature-incompatible-envelope")),
                "missingTransaction" to full.copy(methodSignatures = full.methodSignatures + (0x00ffffff to directSignature)),
                "descriptorChange" to full.copy(descriptor = "${full.descriptor}.incompatible"),
                "futureClientVersion" to full.copy(contractVersion = remote.apiVersion + 1)
            )
            mutations.forEach { (label, incompatible) ->
                rejectsBoth(anchor, connection, incompatible, full.contractVersion)
                Log.i(TAG, "PASS run=$runId $label brokerRejected=true controllerRejected=true")
            }
            check(anchor.getServiceBinderForConnection(connection, ECHO_ID, full.contractVersion, full) == fullBinder)
            check(anchor.getServiceBinderForConnection(connection, ECHO_ID, 1, subset) == fullBinder)
            Log.i(TAG, "PASS run=$runId schemaCacheIsolation fullCached=true changedSchemaRejected=true subsetAccepted=true")

            val mutableSignatures = full.stableMethodSignatures.toMutableMap()
            val frozenExpectation = ClientServiceSchema(full.descriptor, full.contractVersion, mutableSignatures)
            val initialFingerprint = frozenExpectation.fingerprint
            mutableSignatures[12] = directSignature.replace("params=[string!]", "params=[int32!]")
            check(mutableSignatures[12] != directSignature)
            check(frozenExpectation.fingerprint == initialFingerprint)
            check(frozenExpectation.stableMethodSignatures[12] == directSignature)
            // The direct Broker call exercises Parcelable serialization of the frozen snapshot.
            check(connection.broker.getServiceChecked(ECHO_ID, full.contractVersion, frozenExpectation) == fullBinder)
            check(anchor.getServiceBinderForConnection(connection, ECHO_ID, full.contractVersion, frozenExpectation) == fullBinder)
            val newlyConstructed = frozenExpectation.copy(methodSignatures = mutableSignatures)
            check(newlyConstructed.fingerprint != initialFingerprint)
            rejectsBoth(anchor, connection, newlyConstructed, full.contractVersion)
            Log.i(TAG, "PASS run=$runId constructionMapMutation fingerprintStable=true originalSnapshotAccepted=true brokerWireAccepted=true copiedMutationRejected=true")

            val userMetadata = connection.broker.getServiceSchema(1001)
            check(userMetadata.minSupportedClientVersion == 2)
            assertCompatibilityFailure(runCatching { connection.broker.getService(1001, 1) }.exceptionOrNull())
            check(connection.broker.getService(ECHO_ID, 1) == fullBinder)
            Log.i(TAG, "PASS run=$runId legacyFloor userFloor=2 userDenied=true echoFloor=1 echoAllowed=true")

            traceCache(context.applicationContext, full, runId)
            legacyMethods(context.applicationContext, full, runId)
            val service = IBenchmarkEchoServiceClientAdapter(anchor)
            check(service.inspectActiveRequestCount() == 0 && service.inspectActiveSubscriptionCount() == 0)
            check(anchor.pendingCallRegistry.activeCallCount == 0)
            Log.i(TAG, "DONE run=$runId passed=true checks=$CHECKS requests=0 subscriptions=0 pending=0")
        }

    private fun changedDirect(
        schema: ClientServiceSchema,
        signature: String,
        from: String,
        to: String
    ): ClientServiceSchema {
        check(signature.contains(from)) { "Generated canonical signature changed: $signature" }
        return schema.copy(methodSignatures = schema.methodSignatures + (12 to signature.replace(from, to)))
    }

    private fun rejectsBoth(
        controller: IpcConnectionController,
        connection: IpcClientState.Connected,
        expected: ClientServiceSchema,
        minimumVersion: Int
    ) {
        assertCompatibilityFailure(runCatching {
            connection.broker.getServiceChecked(ECHO_ID, minimumVersion, expected)
        }.exceptionOrNull())
        val clientError = runCatching {
            controller.getServiceBinderForConnection(connection, ECHO_ID, minimumVersion, expected)
        }.exceptionOrNull()
        check(clientError is IpcCompatibilityException) { "Wrong client compatibility failure: $clientError" }
    }

    private fun assertCompatibilityFailure(error: Throwable?) {
        check(error != null && listOf("schema", "mismatch", "client", "version", "incompatible").any {
            error.message.orEmpty().contains(it, ignoreCase = true)
        }) { "Wrong remote compatibility failure: $error" }
    }

    private suspend fun traceCache(context: Context, schema: ClientServiceSchema, runId: String) = coroutineScope {
        val owner = SupervisorJob(coroutineContext[Job])
        val keeper = newController(context, CompatibilityBrokerService::class.java, owner)
        val worker = newController(context, CompatibilityBrokerService::class.java, owner)
        try {
            val keeperConnection = keeper.awaitConnected(10_000)
            var workerConnection = worker.awaitConnected(10_000)
            val counterBinder = keeperConnection.broker.asBinder()
            readCounters(counterBinder, reset = true)
            worker.getServiceBinderForConnection(workerConnection, ECHO_ID, schema.contractVersion, schema)
            check(readCounters(counterBinder) == Counters(metadata = 1, checked = 1, legacy = 0))
            val service = IBenchmarkEchoServiceClientAdapter(worker)
            repeat(100) { check(service.echoDirect("hot-$it") == "hot-$it") }
            worker.getServiceBinderForConnection(workerConnection, ECHO_ID, schema.contractVersion, schema)
            check(readCounters(counterBinder) == Counters(metadata = 1, checked = 1, legacy = 0))
            Log.i(TAG, "PASS run=$runId strictCache coldMetadata=1 coldChecked=1 hotCalls=100 additionalDiscovery=0 legacy=0")

            val previousGeneration = workerConnection.generation
            worker.closeAndJoin()
            workerConnection = worker.awaitConnected(10_000)
            check(workerConnection.generation > previousGeneration)
            worker.getServiceBinderForConnection(workerConnection, ECHO_ID, schema.contractVersion, schema)
            check(readCounters(counterBinder) == Counters(metadata = 2, checked = 2, legacy = 0))
            Log.i(TAG, "PASS run=$runId generationRevalidate metadata=2 checked=2 legacy=0 generationAdvanced=true")
        } finally {
            withContext(NonCancellable) {
                worker.disposeAndJoin()
                keeper.disposeAndJoin()
                owner.cancelAndJoin()
            }
        }
    }

    private suspend fun legacyMethods(context: Context, schema: ClientServiceSchema, runId: String) = coroutineScope {
        val owner = SupervisorJob(coroutineContext[Job])
        val worker = newController(context, LegacyBrokerService::class.java, owner)
        try {
            val connection = worker.awaitConnected(10_000)
            val broker = connection.broker
            val counterBinder = broker.asBinder()
            check(rawDirectEcho(broker.getService(ECHO_ID, 1), schema.descriptor, "legacy-echo") == "legacy-echo")
            readCounters(counterBinder, reset = true)
            val missingMetadata = runCatching { broker.getServiceSchema(ECHO_ID) }
            val missingChecked = runCatching { broker.getServiceChecked(ECHO_ID, schema.contractVersion, schema) }
            // Older AIDL proxies may ignore transact(false) and decode an empty reply as null.
            check(missingMetadata.isFailure || missingMetadata.getOrNull() == null)
            check(missingChecked.isFailure || missingChecked.getOrNull() == null)
            check(readCounters(counterBinder) == Counters(metadata = 1, checked = 1, legacy = 0))
            Log.i(TAG, "PASS run=$runId legacyMethodsMissing metadataRejected=true checkedRejected=true legacyEchoAlive=true")

            readCounters(counterBinder, reset = true)
            val error = runCatching {
                worker.getServiceBinderForConnection(connection, ECHO_ID, schema.contractVersion, schema)
            }.exceptionOrNull()
            check(error is IpcCompatibilityException) { "Legacy endpoint did not fail explicitly: $error" }
            check(readCounters(counterBinder) == Counters(metadata = 1, checked = 0, legacy = 0))
            Log.i(TAG, "PASS run=$runId strictLegacyNoFallback explicitFailure=true metadataAttempts=1 checked=0 legacyFallbacks=0")
        } finally {
            withContext(NonCancellable) {
                worker.disposeAndJoin()
                owner.cancelAndJoin()
            }
        }
    }

    private fun newController(context: Context, target: Class<*>, owner: Job) = IpcConnectionController(
        context = context,
        targetIntent = Intent(context, target),
        scope = CoroutineScope(Dispatchers.Default + owner)
    )

    private data class Counters(val metadata: Int, val checked: Int, val legacy: Int)

    private fun readCounters(binder: IBinder, reset: Boolean = false): Counters {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CompatibilityBrokerService.DESCRIPTOR)
            val transaction = if (reset) CompatibilityBrokerService.RESET_COUNTERS else CompatibilityBrokerService.READ_COUNTERS
            check(binder.transact(transaction, data, reply, 0))
            reply.readException()
            Counters(reply.readInt(), reply.readInt(), reply.readInt())
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun rawDirectEcho(binder: IBinder, descriptor: String, payload: String): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descriptor)
            data.writeString(payload)
            check(binder.transact(12, data, reply, 0))
            reply.readException()
            check(reply.readInt() == 1)
            reply.readString() ?: error("Missing direct echo reply")
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
