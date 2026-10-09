package com.cn.ipc.compiler

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * 客户端适配器生成器。
 * 负责根据标有 `@IpcFacade` 的接口生成对应的客户端代理类，
 * 该代理类将本地的方法调用转换为跨进程的 Binder 通信。
 *
 * @param codeGenerator KSP 代码生成器，用于将生成的代码写入文件。
 */
class ClientAdapterGenerator(
    private val codeGenerator: CodeGenerator
) {
    /**
     * 为指定的接口生成客户端适配器类。
     *
     * @param classDeclaration 接口的类声明。
     */
    fun generate(classDeclaration: KSClassDeclaration) {
        val packageName = classDeclaration.packageName.asString()
        val interfaceName = classDeclaration.simpleName.asString()
        val adapterClassName = "${interfaceName}ClientAdapter"

        // 1. 创建类: class IUserServiceClientAdapter(val controller: IpcConnectionController) : IUserService
        val typeBuilder = TypeSpec.classBuilder(adapterClassName)
            .addSuperinterface(classDeclaration.toClassName())
            .addModifiers(KModifier.PUBLIC)
            
        // 添加主构造函数，接收 IPC 连接控制器
        val constructorBuilder = FunSpec.constructorBuilder()
            .addParameter("controller", com.squareup.kotlinpoet.ClassName("com.cn.ipc.client", "IpcConnectionController"))
        
        typeBuilder.primaryConstructor(constructorBuilder.build())
            .addProperty(
                com.squareup.kotlinpoet.PropertySpec.builder("controller", com.squareup.kotlinpoet.ClassName("com.cn.ipc.client", "IpcConnectionController"))
                    .initializer("controller")
                    .addModifiers(KModifier.PRIVATE)
                    .build()
            )

        val facadeAnnotation = classDeclaration.annotations.find { it.shortName.asString() == "IpcFacade" }
        val serviceId = facadeAnnotation?.arguments?.firstOrNull { it.name?.asString() == "serviceId" }?.value as? Int ?: 1001
        val minApiVersion = facadeAnnotation?.arguments?.firstOrNull { it.name?.asString() == "minApiVersion" }?.value as? Int ?: 1
        val schemaClassName = com.squareup.kotlinpoet.ClassName(packageName, "${interfaceName}IpcSchema")

        // 2. 为每个接口方法生成对应的 override 实现
        val functions = classDeclaration.getAllFunctions()
        functions.forEach { function ->
            // 忽略 Object 的基础方法
            if (function.simpleName.asString() in listOf("equals", "hashCode", "toString")) return@forEach

            val funName = function.simpleName.asString()
            val isSuspend = function.modifiers.contains(Modifier.SUSPEND)
            val isDirect = function.annotations.any { it.shortName.asString() == "IpcDirect" }

            val funBuilder = FunSpec.builder(funName)
                .addModifiers(KModifier.OVERRIDE)

            // 如果是挂起函数，需添加 suspend 修饰符
            if (isSuspend) {
                funBuilder.addModifiers(KModifier.SUSPEND)
            }

            // 添加方法参数
            function.parameters.forEach { param ->
                val paramName = param.name?.asString() ?: "arg"
                funBuilder.addParameter(paramName, param.type.toTypeName())
            }

            // 设置返回类型（Unit 忽略）
            val returnType = function.returnType?.resolve()
            if (returnType != null && returnType.declaration.qualifiedName?.asString() != "kotlin.Unit") {
                funBuilder.returns(function.returnType!!.toTypeName())
            }

            // 提取注解中的事务码
            var transactionCode = -1
            var unsubscribeTransactionCode = -1
            if (isSuspend) {
                val asyncAnnotation = function.annotations.find { it.shortName.asString() == "IpcAsync" }
                transactionCode = asyncAnnotation?.arguments?.firstOrNull { it.name?.asString() == "requestTransaction" }?.value as? Int ?: -1
            } else if (isDirect) {
                val directAnnotation = function.annotations.find { it.shortName.asString() == "IpcDirect" }
                transactionCode = directAnnotation?.arguments?.firstOrNull { it.name?.asString() == "transaction" }?.value as? Int ?: -1
            } else if (returnType?.declaration?.qualifiedName?.asString() == "kotlinx.coroutines.flow.Flow") {
                val streamAnnotation = function.annotations.find { it.shortName.asString() == "IpcStream" }
                transactionCode = streamAnnotation?.arguments?.firstOrNull { it.name?.asString() == "subscribeTransaction" }?.value as? Int ?: -1
                unsubscribeTransactionCode = streamAnnotation?.arguments?.firstOrNull { it.name?.asString() == "unsubscribeTransaction" }?.value as? Int ?: -1
            } else {
                val onewayAnnotation = function.annotations.find { it.shortName.asString() == "IpcOneway" }
                transactionCode = onewayAnnotation?.arguments?.firstOrNull { it.name?.asString() == "transaction" }?.value as? Int ?: -1
            }

            // 根据方法类型生成真实调用逻辑
            if (isSuspend) {
                val traceClassName = com.squareup.kotlinpoet.ClassName("com.cn.ipc", "IpcRequestTrace")
                funBuilder.addStatement("val _traceEntry = %T.nowIfEnabled()", traceClassName)
                funBuilder.addStatement("val _traceEntryTid = %T.threadIfEnabled()", traceClassName)
                funBuilder.beginControlFlow("return kotlinx.coroutines.withTimeout(controller.defaultCallTimeoutMs)")
                funBuilder.addStatement(
                    "val _connection = controller.state.value as? %T ?: throw IllegalStateException(%S)",
                    com.squareup.kotlinpoet.ClassName("com.cn.ipc.client", "IpcClientState", "Connected"),
                    "IPC not connected"
                )
                funBuilder.addStatement("val _binder = controller.awaitServiceBinderForConnection(_connection, %L, %L, expectedSchema = %T.CLIENT_SCHEMA)", serviceId, minApiVersion, schemaClassName)
                funBuilder.addStatement("val _traceResolved = %T.nowIfEnabled()", traceClassName)
                funBuilder.addStatement("val _traceResolvedTid = %T.threadIfEnabled()", traceClassName)
                val asyncAnnotation = function.annotations.find { it.shortName.asString() == "IpcAsync" }
                val cancelCode = asyncAnnotation?.arguments?.firstOrNull { it.name?.asString() == "cancelTransaction" }?.value as? Int ?: -1
                val cancelLambda = if (cancelCode < 0) "null" else """{ cancelledId: Long ->
                    try {
                        val cancelData = android.os.Parcel.obtain()
                        try {
                            cancelData.writeInterfaceToken("$packageName.$interfaceName")
                            cancelData.writeLong(cancelledId)
                            cancelData.writeStrongBinder(controller.globalResponseBinder)
                            _binder.transact($cancelCode, cancelData, null, android.os.IBinder.FLAG_ONEWAY)
                        } finally { cancelData.recycle() }
                    } catch (_: Exception) {}
                }"""
                val deserializerCode = CodeBlock.of("{ %L }", readValue("it", returnType!!))

                // Emit separate paths so disabled tracing does not allocate a captured mutable requestId.
                fun emitAsyncCall(traced: Boolean) {
                    if (traced) {
                        funBuilder.beginControlFlow(
                            "val _result: %T = controller.pendingCallRegistry.callSuspendWithinDeadline(serviceId = %L, operationId = %L, generation = _connection.generation, deserializer = %L, onRemoteCancel = %L) { requestId ->",
                            returnType!!.toTypeName(), serviceId, transactionCode, deserializerCode, cancelLambda
                        )
                        funBuilder.addStatement("%T.recordAt(%S, %S, requestId, generation = _connection.generation, callback = controller.globalResponseBinder)", traceClassName, "client", "client_registered")
                        funBuilder.addStatement("_traceRequestId = requestId")
                        funBuilder.addStatement("%T.recordAt(%S, %S, requestId, generation = _connection.generation, callback = controller.globalResponseBinder, ns = _traceEntry, tid = _traceEntryTid)", traceClassName, "client", "client_entry")
                        funBuilder.addStatement("%T.recordAt(%S, %S, requestId, generation = _connection.generation, callback = controller.globalResponseBinder, ns = _traceResolved, tid = _traceResolvedTid)", traceClassName, "client", "client_resolved")
                    } else {
                        funBuilder.beginControlFlow(
                            "return@withTimeout controller.pendingCallRegistry.callSuspendWithinDeadline(serviceId = %L, operationId = %L, generation = _connection.generation, deserializer = %L, onRemoteCancel = %L) { requestId ->",
                            serviceId, transactionCode, deserializerCode, cancelLambda
                        )
                    }
                    funBuilder.addStatement("val _data = android.os.Parcel.obtain()")
                    funBuilder.beginControlFlow("try")
                    funBuilder.addStatement("_data.writeInterfaceToken(%S)", "$packageName.$interfaceName")
                    funBuilder.addStatement("_data.writeLong(requestId)")
                    function.parameters.forEach { param ->
                        val paramName = param.name!!.asString()
                        writeParameter(funBuilder, paramName, param.type.resolve())
                    }
                    funBuilder.addStatement("_data.writeStrongBinder(controller.globalResponseBinder)")
                    funBuilder.addStatement("controller.checkServiceBinderForConnection(_connection, _binder)")
                    if (traced) {
                        funBuilder.addStatement("%T.recordAt(%S, %S, requestId, generation = _connection.generation, callback = controller.globalResponseBinder)", traceClassName, "client", "client_send")
                    }
                    funBuilder.beginControlFlow("if (!_binder.transact(%L, _data, null, android.os.IBinder.FLAG_ONEWAY))", transactionCode)
                    funBuilder.addStatement("throw IllegalStateException(%S)", "Remote service rejected transaction $transactionCode")
                    funBuilder.endControlFlow()
                    funBuilder.nextControlFlow("finally")
                    funBuilder.addStatement("_data.recycle()")
                    funBuilder.endControlFlow()
                    funBuilder.endControlFlow()
                }

                funBuilder.beginControlFlow("if (_traceEntry == 0L)")
                emitAsyncCall(traced = false)
                funBuilder.endControlFlow()
                funBuilder.addStatement("var _traceRequestId = 0L")
                emitAsyncCall(traced = true)
                funBuilder.addStatement("%T.recordAt(%S, %S, _traceRequestId, generation = _connection.generation, callback = controller.globalResponseBinder)", traceClassName, "client", "client_resume")
                funBuilder.addStatement("return@withTimeout _result")
                funBuilder.endControlFlow()
            } else if (isDirect) {
                funBuilder.addStatement("check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { %S }", "@IpcDirect must be called off the main thread")
                funBuilder.addStatement("val _data = android.os.Parcel.obtain()")
                funBuilder.addStatement("val _reply = android.os.Parcel.obtain()")
                funBuilder.beginControlFlow("try")
                funBuilder.addStatement("_data.writeInterfaceToken(%S)", "$packageName.$interfaceName")
                function.parameters.forEach { param ->
                    val name = param.name!!.asString()
                    writeParameter(funBuilder, name, param.type.resolve())
                }
                funBuilder.addStatement("val _binder = controller.getServiceBinder(%L, %L, expectedSchema = %T.CLIENT_SCHEMA)", serviceId, minApiVersion, schemaClassName)
                funBuilder.beginControlFlow("if (!_binder.transact(%L, _data, _reply, 0))", transactionCode)
                funBuilder.addStatement("throw IllegalStateException(%S)", "Remote service rejected @IpcDirect transaction $transactionCode")
                funBuilder.endControlFlow()
                funBuilder.addStatement("_reply.readException()")
                funBuilder.beginControlFlow("if (_reply.readInt() != 1)")
                funBuilder.addStatement("throw RuntimeException(_reply.readString() ?: %S)", "Remote error")
                funBuilder.endControlFlow()
                funBuilder.addStatement("return %L", readValue("_reply", returnType!!))
                funBuilder.nextControlFlow("finally")
                funBuilder.addStatement("_reply.recycle()")
                funBuilder.addStatement("_data.recycle()")
                funBuilder.endControlFlow()
            } else if (returnType?.declaration?.qualifiedName?.asString() == "kotlinx.coroutines.flow.Flow") {
                // 生成 Flow 流式订阅的 Binder 通信代码
                val flowTypeArg = returnType.arguments.firstOrNull()?.type?.resolve()
                val conflate = ipcStreamOverflowName(function) == "CONFLATE"
                val trySendCode = CodeBlock.of("trySend(%L)", readValue("data", flowTypeArg!!, allowFloatingPoint = true))
                funBuilder.beginControlFlow("val _events = kotlinx.coroutines.flow.callbackFlow<%T>", flowTypeArg?.toTypeName() ?: com.squareup.kotlinpoet.STRING)
                funBuilder.addCode(
                    """
                    |val _watcher = launch(kotlinx.coroutines.Dispatchers.IO) {
                    |    controller.state.collectLatest { state ->
                    |        if (state is com.cn.ipc.client.IpcClientState.Disposed) {
                    |            close(IllegalStateException(%S))
                    |            return@collectLatest
                    |        }
                    |        if (state !is com.cn.ipc.client.IpcClientState.Connected) return@collectLatest
                    |        val _subscriptionJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!
                    |        val _active = java.util.concurrent.atomic.AtomicBoolean(true)
                    |        var _subscriptionBinder: android.os.IBinder? = null
                    |        var _subId = 0L
                    |        val _observer = object : android.os.Binder() {
                    |            override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
                    |                if (code in 1..3) {
                    |                    if (_active.get() && _subscriptionJob.isActive && controller.state.value === state) {
                    |                        try {
                    |                            when (code) {
                    |                                1 -> {
                    |                                    val result = %L
                    |                                    if (result.isFailure && !result.isClosed && _active.compareAndSet(true, false)) {
                    |                                        close(IllegalStateException(%S))
                    |                                    }
                    |                                }
                    |                                2 -> if (_active.compareAndSet(true, false)) close()
                    |                                3 -> {
                    |                                    val message = data.readString() ?: %S
                    |                                    if (_active.compareAndSet(true, false)) close(RuntimeException(message))
                    |                                }
                    |                            }
                    |                        } catch (error: Exception) {
                    |                            if (_active.compareAndSet(true, false)) close(error)
                    |                        }
                    |                    }
                    |                    return true
                    |                }
                    |                return super.onTransact(code, data, reply, flags)
                    |            }
                    |        }
                    |        try {
                    |            val _binder = controller.awaitServiceBinderForConnection(state, %L, %L, expectedSchema = %T.CLIENT_SCHEMA)
                    |            _subscriptionBinder = _binder
                    |            val _data = android.os.Parcel.obtain()
                    |            try {
                    |                _data.writeInterfaceToken(%S)
                    |""".trimMargin(), "IPC controller disposed", trySendCode, "IPC stream buffer overflow",
                    "Remote stream error", serviceId, minApiVersion, schemaClassName, "$packageName.$interfaceName"
                )
                function.parameters.forEach { param ->
                    val paramName = param.name!!.asString()
                    writeParameter(funBuilder, paramName, param.type.resolve())
                }
                funBuilder.addCode(
                    """
                    |                _data.writeStrongBinder(_observer)
                    |                val _reply = android.os.Parcel.obtain()
                    |                try {
                    |                    check(_binder.transact(%L, _data, _reply, 0))
                    |                    _reply.readException()
                    |                    _subId = _reply.readLong().also { check(it > 0L) }
                    |                } finally { _reply.recycle() }
                    |            } finally { _data.recycle() }
                    |            kotlinx.coroutines.awaitCancellation()
                    |        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                    |            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    |            if (_subscriptionJob.isActive && controller.state.value === state) close(timeout)
                    |        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    |            throw cancelled
                    |        } catch (_: android.os.DeadObjectException) {
                    |            // Binder death will cause a later Connected generation to retry.
                    |        } catch (error: Exception) {
                    |            if (_subscriptionJob.isActive && controller.state.value === state) close(error)
                    |        } finally {
                    |            _active.set(false)
                    |            val _capturedBinder = _subscriptionBinder
                    |            if (_subId > 0L && _capturedBinder != null) {
                    |                try {
                    |                    val _unsubData = android.os.Parcel.obtain()
                    |                    try {
                    |                        _unsubData.writeInterfaceToken(%S)
                    |                        _unsubData.writeLong(_subId)
                    |                        _capturedBinder.transact(%L, _unsubData, null, android.os.IBinder.FLAG_ONEWAY)
                    |                    } finally { _unsubData.recycle() }
                    |                } catch (_: Exception) {}
                    |            }
                    |        }
                    |    }
                    |}
                    |awaitClose { _watcher.cancel() }
                    |""".trimMargin(), transactionCode, "$packageName.$interfaceName", unsubscribeTransactionCode
                )
                funBuilder.endControlFlow()
                funBuilder.addStatement(if (conflate) "return _events.conflate()" else "return _events")
            } else {
                // 生成单向通信的 Binder 通信代码
                funBuilder.addStatement("val _data = android.os.Parcel.obtain()")
                funBuilder.beginControlFlow("try")
                funBuilder.addStatement("_data.writeInterfaceToken(%S)", "$packageName.$interfaceName")
                function.parameters.forEach { param ->
                    val paramName = param.name!!.asString()
                    writeParameter(funBuilder, paramName, param.type.resolve())
                }
                funBuilder.addStatement("val _binder = controller.getServiceBinder(%L, %L, expectedSchema = %T.CLIENT_SCHEMA)", serviceId, minApiVersion, schemaClassName)
                funBuilder.beginControlFlow("if (!_binder.transact(%L, _data, null, android.os.IBinder.FLAG_ONEWAY))", transactionCode)
                funBuilder.addStatement("throw IllegalStateException(%S)", "Remote service rejected transaction $transactionCode")
                funBuilder.endControlFlow()
                funBuilder.nextControlFlow("finally")
                funBuilder.addStatement("_data.recycle()")
                funBuilder.endControlFlow()
            }

            typeBuilder.addFunction(funBuilder.build())
        }

        // 3. 将生成的代码写入文件
        val fileSpec = FileSpec.builder(packageName, adapterClassName)
            .addType(typeBuilder.build())
            .addImport("kotlinx.coroutines.channels", "awaitClose")
            .addImport("kotlinx.coroutines", "launch", "ensureActive")
            .addImport("kotlinx.coroutines.flow", "collectLatest", "conflate")
            .build()

        fileSpec.writeTo(codeGenerator, Dependencies(aggregating = false, classDeclaration.containingFile!!))
    }

    private fun writeParameter(builder: FunSpec.Builder, name: String, type: KSType) {
        when (requireNotNull(WireTypeModel.resolve(type)).kind) {
            WireTypeKind.STRING -> builder.addStatement("_data.writeString(%L)", name)
            WireTypeKind.INT -> builder.addStatement("_data.writeInt(%L)", name)
            WireTypeKind.LONG -> builder.addStatement("_data.writeLong(%L)", name)
            WireTypeKind.BOOLEAN -> builder.addStatement("_data.writeInt(if (%L) 1 else 0)", name)
            WireTypeKind.BYTES -> builder.addStatement("_data.writeByteArray(%L)", name)
            WireTypeKind.PARCELABLE -> builder.addStatement("_data.writeParcelable(%L, 0)", name)
            WireTypeKind.FLOAT, WireTypeKind.DOUBLE -> error("Floating-point parameters are not supported")
        }
    }

    private fun readValue(
        receiver: String,
        type: KSType,
        allowFloatingPoint: Boolean = false
    ): CodeBlock {
        return when (requireNotNull(WireTypeModel.resolve(type, allowFloatingPoint)).kind) {
            WireTypeKind.STRING -> CodeBlock.of("%L.readString() ?: throw IllegalStateException(%S)", receiver, "Missing IPC String value")
            WireTypeKind.INT -> CodeBlock.of("%L.readInt()", receiver)
            WireTypeKind.LONG -> CodeBlock.of("%L.readLong()", receiver)
            WireTypeKind.BOOLEAN -> CodeBlock.of("%L.readInt() == 1", receiver)
            WireTypeKind.BYTES -> CodeBlock.of("%L.createByteArray() ?: throw IllegalStateException(%S)", receiver, "Missing IPC ByteArray value")
            WireTypeKind.FLOAT -> CodeBlock.of("%L.readFloat()", receiver)
            WireTypeKind.DOUBLE -> CodeBlock.of("%L.readDouble()", receiver)
            WireTypeKind.PARCELABLE -> CodeBlock.of("%L.readParcelable<%T>(javaClass.classLoader) ?: throw IllegalStateException(%S)", receiver, type.toTypeName(), "Missing IPC Parcelable value")
        }
    }
}
