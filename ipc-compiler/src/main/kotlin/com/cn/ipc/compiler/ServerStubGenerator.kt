package com.cn.ipc.compiler

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * 服务端存根生成器。
 * 负责根据标有 `@IpcFacade` 的接口生成对应的服务端 Stub 抽象类，
 * 该类继承自 Binder 并实现了接口，用于接收并处理来自客户端的 IPC 请求。
 *
 * @param codeGenerator KSP 代码生成器，用于将生成的代码写入文件。
 */
class ServerStubGenerator(
    private val codeGenerator: CodeGenerator
) {
    /**
     * 为指定的接口生成服务端存根类。
     *
     * @param classDeclaration 接口的类声明。
     */
    fun generate(classDeclaration: KSClassDeclaration) {
        val packageName = classDeclaration.packageName.asString()
        val interfaceName = classDeclaration.simpleName.asString()
        val stubClassName = "${interfaceName}ServerStub"
        val facadeAnnotation = classDeclaration.annotations.find { it.shortName.asString() == "IpcFacade" }
        val serviceId = facadeAnnotation?.arguments?.firstOrNull { it.name?.asString() == "serviceId" }?.value as? Int ?: -1

        val mapType = ClassName("java.util.concurrent", "ConcurrentHashMap").parameterizedBy(
            LONG,
            ClassName("kotlin", "Triple").parameterizedBy(
                com.squareup.kotlinpoet.INT,
                ClassName("kotlinx.coroutines", "Job"),
                ClassName("java.util.concurrent.atomic", "AtomicBoolean")
            )
        )

        // 1. 创建抽象类: abstract class IUserServiceServerStub : android.os.Binder(), IUserService
        val typeBuilder = TypeSpec.classBuilder(stubClassName)
            .addModifiers(KModifier.ABSTRACT)
            .superclass(ClassName("android.os", "Binder"))
            .addSuperinterface(classDeclaration.toClassName())
            .addSuperinterface(ClassName("com.cn.ipc.server", "IpcServiceLifecycle"))
            .addSuperinterface(ClassName("com.cn.ipc.server", "IpcServiceSchemaProvider"))
            .primaryConstructor(FunSpec.constructorBuilder()
                .addParameter("context", ClassName("android.content", "Context"))
                .build())
            .addProperty(PropertySpec.builder("context", ClassName("android.content", "Context"))
                .addModifiers(KModifier.PRIVATE)
                .initializer("context")
                .build())
            .addProperty(PropertySpec.builder("_authenticator", ClassName("com.cn.ipc.server", "CallerAuthenticator"))
                .addModifiers(KModifier.PRIVATE)
                .initializer("%T(context)", ClassName("com.cn.ipc.server", "CallerAuthenticator"))
                .build())
            .addFunction(FunSpec.builder("authorizeCaller")
                .addModifiers(KModifier.PROTECTED, KModifier.OPEN)
                .addKdoc("每次事务的鉴权入口；覆盖实现时必须保留调用方校验。\n")
                .addStatement("_authenticator.authorizeCurrentCaller(%L)", serviceId)
                .build())
            .addProperty(PropertySpec.builder("_requests", ClassName("com.cn.ipc.server", "IpcRequestTracker"))
                .addModifiers(KModifier.PRIVATE)
                .initializer("%T()", ClassName("com.cn.ipc.server", "IpcRequestTracker"))
                .build())
            .addProperty(PropertySpec.builder("_lifecycleLock", ClassName("kotlin", "Any"))
                .addModifiers(KModifier.PRIVATE)
                .initializer("Any()")
                .build())
            .addProperty(PropertySpec.builder("_disposed", ClassName("java.util.concurrent.atomic", "AtomicBoolean"))
                .addModifiers(KModifier.PRIVATE)
                .initializer("java.util.concurrent.atomic.AtomicBoolean(false)")
                .build())
            .addProperty(
                // 定义受保护的协程作用域，用于执行挂起函数
                PropertySpec.builder("coroutineScope", ClassName("kotlinx.coroutines", "CoroutineScope"))
                    .addModifiers(KModifier.PROTECTED, KModifier.ABSTRACT)
                    .build()
            )
            .addProperty(
                PropertySpec.builder("_subscriptionJobs", mapType)
                    .initializer("java.util.concurrent.ConcurrentHashMap<Long, Triple<Int, kotlinx.coroutines.Job, java.util.concurrent.atomic.AtomicBoolean>>()")
                    .addModifiers(KModifier.PRIVATE)
                    .build()
            )
            .addProperty(
                PropertySpec.builder("_subIdCounter", ClassName("java.util.concurrent.atomic", "AtomicLong"))
                    .initializer("java.util.concurrent.atomic.AtomicLong(1)")
                    .addModifiers(KModifier.PRIVATE)
                .build()
            )
            .addProperty(PropertySpec.builder("activeRequestCount", com.squareup.kotlinpoet.INT)
                .addModifiers(KModifier.OVERRIDE)
                .getter(FunSpec.getterBuilder().addStatement("return _requests.activeRequestCount").build())
                .build())
            .addProperty(PropertySpec.builder("activeSubscriptionCount", com.squareup.kotlinpoet.INT)
                .addModifiers(KModifier.OVERRIDE)
                .getter(FunSpec.getterBuilder().addStatement("return _subscriptionJobs.size").build())
                .build())
            .addProperty(PropertySpec.builder("ipcDescriptor", com.squareup.kotlinpoet.STRING)
                .addModifiers(KModifier.OVERRIDE)
                .getter(FunSpec.getterBuilder()
                    .addStatement("return %T.DESCRIPTOR", ClassName(packageName, "${interfaceName}IpcSchema"))
                    .build())
                .build())
            .addProperty(PropertySpec.builder("ipcMethodSignatures", ClassName("kotlin.collections", "Map").parameterizedBy(com.squareup.kotlinpoet.INT, com.squareup.kotlinpoet.STRING))
                .addModifiers(KModifier.OVERRIDE)
                .getter(FunSpec.getterBuilder()
                    .addStatement("return %T.METHOD_SIGNATURES", ClassName(packageName, "${interfaceName}IpcSchema"))
                    .build())
                .build())
            .addFunction(FunSpec.builder("dispose")
                .addModifiers(KModifier.OVERRIDE)
                .beginControlFlow("val subscriptions = synchronized(_lifecycleLock)")
                .beginControlFlow("if (!_disposed.compareAndSet(false, true))")
                .addStatement("return")
                .endControlFlow()
                .addStatement("_subscriptionJobs.values.map { it.second }")
                .endControlFlow()
                .addStatement("_requests.dispose()")
                .addStatement("subscriptions.forEach { it.cancel(kotlinx.coroutines.CancellationException(%S)) }", "SERVER_DISPOSED")
                .addStatement("coroutineScope.cancel()")
                .build())
            .addFunction(FunSpec.builder("ensureOpen")
                .addModifiers(KModifier.PRIVATE)
                .addStatement("check(!_disposed.get()) { %S }", "IPC service disposed")
                .build())

        // 2. 覆盖 onTransact 方法，分发不同事务码的调用
        val onTransactFun = FunSpec.builder("onTransact")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("code", kotlin.Int::class)
            .addParameter("data", com.squareup.kotlinpoet.ClassName("android.os", "Parcel"))
            .addParameter("reply", com.squareup.kotlinpoet.ClassName("android.os", "Parcel").copy(nullable = true))
            .addParameter("flags", kotlin.Int::class)
            .returns(kotlin.Boolean::class)
            .addStatement("data.enforceInterface(%S)", "$packageName.$interfaceName")
            .beginControlFlow("when (code)")

        val functions = classDeclaration.getAllFunctions()
        functions.forEach { function ->
            // 忽略 Object 的基础方法
            if (function.simpleName.asString() in listOf("equals", "hashCode", "toString")) return@forEach

            val funName = function.simpleName.asString()
            val isSuspend = function.modifiers.contains(Modifier.SUSPEND)
            val isDirect = function.annotations.any { it.shortName.asString() == "IpcDirect" }
            val returnType = function.returnType?.resolve()

            // 提取事务码
            var transactionCode = -1
            var unsubscribeCode = -1
            if (isSuspend) {
                val asyncAnnotation = function.annotations.find { it.shortName.asString() == "IpcAsync" }
                transactionCode = asyncAnnotation?.arguments?.firstOrNull { it.name?.asString() == "requestTransaction" }?.value as? Int ?: -1
            } else if (isDirect) {
                val directAnnotation = function.annotations.find { it.shortName.asString() == "IpcDirect" }
                transactionCode = directAnnotation?.arguments?.firstOrNull { it.name?.asString() == "transaction" }?.value as? Int ?: -1
            } else if (returnType?.declaration?.qualifiedName?.asString() == "kotlinx.coroutines.flow.Flow") {
                val streamAnnotation = function.annotations.find { it.shortName.asString() == "IpcStream" }
                transactionCode = streamAnnotation?.arguments?.firstOrNull { it.name?.asString() == "subscribeTransaction" }?.value as? Int ?: -1
                unsubscribeCode = streamAnnotation?.arguments?.firstOrNull { it.name?.asString() == "unsubscribeTransaction" }?.value as? Int ?: -1
            } else {
                val onewayAnnotation = function.annotations.find { it.shortName.asString() == "IpcOneway" }
                transactionCode = onewayAnnotation?.arguments?.firstOrNull { it.name?.asString() == "transaction" }?.value as? Int ?: -1
            }

            if (transactionCode != -1) {
                onTransactFun.beginControlFlow("%L -> ", transactionCode)
                if (isSuspend) {
                    onTransactFun.addStatement("val _receiveNs = com.cn.ipc.IpcRequestTrace.nowIfEnabled()")
                    val nativeAsync = function.parameters.all {
                        requireNotNull(WireTypeModel.resolve(it.type.resolve())).kind != WireTypeKind.PARCELABLE
                    }
                    // Parcelable construction remains behind authentication. Native fields can
                    // locate the trailing callback before null validation or authorization fails.
                    if (!nativeAsync) onTransactFun.addStatement("authorizeCaller()")
                    // 处理异步挂起调用
                    onTransactFun.addStatement("val requestId = data.readLong()")
                    // 读取并解析请求参数
                    function.parameters.forEach { param ->
                        val paramName = param.name!!.asString()
                        val readName = if (nativeAsync) "_raw_$paramName" else paramName
                        when (requireNotNull(WireTypeModel.resolve(param.type.resolve())).kind) {
                            WireTypeKind.STRING -> onTransactFun.addStatement(if (nativeAsync) "val %L = data.readString()" else "val %L = data.readString()!!", readName)
                            WireTypeKind.INT -> onTransactFun.addStatement("val %L = data.readInt()", readName)
                            WireTypeKind.LONG -> onTransactFun.addStatement("val %L = data.readLong()", readName)
                            WireTypeKind.BOOLEAN -> onTransactFun.addStatement("val %L = data.readInt() == 1", readName)
                            WireTypeKind.BYTES -> onTransactFun.addStatement(if (nativeAsync) "val %L = data.createByteArray()" else "val %L = data.createByteArray()!!", readName)
                            WireTypeKind.PARCELABLE -> onTransactFun.addStatement("val %L = data.readParcelable<android.os.Parcelable>(javaClass.classLoader) as %T", paramName, param.type.toTypeName())
                            else -> error("Floating point Async parameters are unsupported")
                        }
                    }
                    onTransactFun.addStatement("val responseBinder = data.readStrongBinder()")
                    onTransactFun.addStatement("com.cn.ipc.IpcRequestTrace.recordAt(%S, %S, requestId, callback = responseBinder, ns = _receiveNs)", "server", "server_receive")
                    if (nativeAsync) {
                        onTransactFun.beginControlFlow("try")
                        onTransactFun.addStatement("authorizeCaller()")
                        function.parameters.forEach { param ->
                            val name = param.name!!.asString()
                            val type = param.type.resolve()
                            if (requireNotNull(WireTypeModel.resolve(type)).kind in setOf(WireTypeKind.STRING, WireTypeKind.BYTES)) {
                                onTransactFun.addStatement("val %L = requireNotNull(_raw_%L) { %S }", name, name, "Missing $name")
                            } else {
                                onTransactFun.addStatement("val %L = _raw_%L", name, name)
                            }
                        }
                    }
                    // 开启协程执行挂起函数
                    onTransactFun.beginControlFlow("_requests.submit(responseBinder, requestId, coroutineScope)")
                    onTransactFun.beginControlFlow("try")
                    
                    // invoke function 调用实际的服务接口方法
                    val args = function.parameters.joinToString(", ") { it.name!!.asString() }
                    onTransactFun.addStatement("val result = %L(%L)", funName, args)
                    onTransactFun.addStatement("com.cn.ipc.IpcRequestTrace.recordAt(%S, %S, requestId, callback = responseBinder)", "server", "server_business_done")
                    
                    // send response 发送成功响应
                    onTransactFun.beginControlFlow("try")
                    onTransactFun.addStatement("val replyData = android.os.Parcel.obtain()")
                    onTransactFun.beginControlFlow("try")
                    onTransactFun.addStatement("replyData.writeLong(requestId)")
                    onTransactFun.addStatement("replyData.writeInt(1) // success")
                    when (requireNotNull(WireTypeModel.resolve(requireNotNull(returnType))).kind) {
                        WireTypeKind.INT -> onTransactFun.addStatement("replyData.writeInt(result)")
                        WireTypeKind.LONG -> onTransactFun.addStatement("replyData.writeLong(result)")
                        WireTypeKind.BOOLEAN -> onTransactFun.addStatement("replyData.writeInt(if (result) 1 else 0)")
                        WireTypeKind.BYTES -> onTransactFun.addStatement("replyData.writeByteArray(result)")
                        WireTypeKind.STRING -> onTransactFun.addStatement("replyData.writeString(result)")
                        WireTypeKind.PARCELABLE -> onTransactFun.addStatement("replyData.writeParcelable(result as android.os.Parcelable, 0)")
                        else -> error("Floating point Async results are unsupported")
                    }
                    onTransactFun.addStatement("com.cn.ipc.IpcRequestTrace.recordAt(%S, %S, requestId, callback = responseBinder)", "server", "server_reply")
                    onTransactFun.addStatement("responseBinder?.transact(1, replyData, null, android.os.IBinder.FLAG_ONEWAY)")
                    onTransactFun.nextControlFlow("finally")
                    onTransactFun.addStatement("replyData.recycle()")
                    onTransactFun.endControlFlow()
                    onTransactFun.nextControlFlow("catch (dead: android.os.RemoteException)")
                    onTransactFun.addStatement("// Client died, ignore")
                    onTransactFun.endControlFlow()
                    
                    // 处理异常响应
                    onTransactFun.nextControlFlow("catch (e: Exception)")
                    onTransactFun.beginControlFlow("try")
                    onTransactFun.addStatement("val errorData = android.os.Parcel.obtain()")
                    onTransactFun.beginControlFlow("try")
                    onTransactFun.addStatement("errorData.writeLong(requestId)")
                    onTransactFun.addStatement("errorData.writeInt(0) // error")
                    onTransactFun.addStatement("errorData.writeString(e.message ?: \"Remote error\")")
                    onTransactFun.addStatement("com.cn.ipc.IpcRequestTrace.recordAt(%S, %S, requestId, callback = responseBinder)", "server", "server_reply")
                    onTransactFun.addStatement("responseBinder?.transact(1, errorData, null, android.os.IBinder.FLAG_ONEWAY)")
                    onTransactFun.nextControlFlow("finally")
                    onTransactFun.addStatement("errorData.recycle()")
                    onTransactFun.endControlFlow()
                    onTransactFun.nextControlFlow("catch (dead: android.os.RemoteException)")
                    onTransactFun.addStatement("// Client died, ignore")
                    onTransactFun.endControlFlow()
                    onTransactFun.endControlFlow()
                    onTransactFun.endControlFlow()
                    if (nativeAsync) {
                        onTransactFun.nextControlFlow("catch (error: Exception)")
                        onTransactFun.addStatement("_requests.reject(responseBinder, requestId, error.message ?: %S)", "Invalid IPC request")
                        onTransactFun.endControlFlow()
                    }
                    val cancelAnnotation = function.annotations.find { it.shortName.asString() == "IpcAsync" }
                    val cancelCode = cancelAnnotation?.arguments?.firstOrNull { it.name?.asString() == "cancelTransaction" }?.value as? Int ?: -1
                    if (cancelCode != -1) {
                        onTransactFun.addStatement("return true")
                        onTransactFun.endControlFlow()
                        onTransactFun.beginControlFlow("%L -> ", cancelCode)
                        onTransactFun.addStatement("authorizeCaller()")
                        onTransactFun.addStatement("val requestId = data.readLong()")
                        onTransactFun.addStatement("val responseBinder = data.readStrongBinder()")
                        onTransactFun.addStatement("_requests.cancel(responseBinder, requestId)")
                    }
                } else if (isDirect) {
                    onTransactFun.addStatement("authorizeCaller()")
                    onTransactFun.addStatement("ensureOpen()")
                    onTransactFun.addStatement("val response = reply ?: return false")
                    function.parameters.forEach { param ->
                        val name = param.name!!.asString()
                        when (requireNotNull(WireTypeModel.resolve(param.type.resolve())).kind) {
                            WireTypeKind.STRING -> onTransactFun.addStatement("val %L = data.readString() ?: throw IllegalArgumentException(%S)", name, "Missing $name")
                            WireTypeKind.INT -> onTransactFun.addStatement("val %L = data.readInt()", name)
                            WireTypeKind.LONG -> onTransactFun.addStatement("val %L = data.readLong()", name)
                            WireTypeKind.BOOLEAN -> onTransactFun.addStatement("val %L = data.readInt() == 1", name)
                            WireTypeKind.BYTES -> onTransactFun.addStatement("val %L = data.createByteArray() ?: throw IllegalArgumentException(%S)", name, "Missing $name")
                            else -> error("Unsupported Direct parameter")
                        }
                    }
                    val args = function.parameters.joinToString(", ") { it.name!!.asString() }
                    onTransactFun.beginControlFlow("val result = try")
                    onTransactFun.addStatement("%L(%L)", funName, args)
                    onTransactFun.nextControlFlow("catch (error: Exception)")
                    onTransactFun.addStatement("response.writeNoException()")
                    onTransactFun.addStatement("response.writeInt(0)")
                    onTransactFun.addStatement("response.writeString(error.message ?: %S)", "Remote error")
                    onTransactFun.addStatement("return true")
                    onTransactFun.endControlFlow()
                    onTransactFun.addStatement("response.writeNoException()")
                    onTransactFun.addStatement("response.writeInt(1)")
                    when (requireNotNull(WireTypeModel.resolve(requireNotNull(returnType))).kind) {
                        WireTypeKind.STRING -> onTransactFun.addStatement("response.writeString(result)")
                        WireTypeKind.INT -> onTransactFun.addStatement("response.writeInt(result)")
                        WireTypeKind.LONG -> onTransactFun.addStatement("response.writeLong(result)")
                        WireTypeKind.BOOLEAN -> onTransactFun.addStatement("response.writeInt(if (result) 1 else 0)")
                        WireTypeKind.BYTES -> onTransactFun.addStatement("response.writeByteArray(result)")
                        else -> error("Unsupported Direct result")
                    }
                } else if (returnType?.declaration?.qualifiedName?.asString() == "kotlinx.coroutines.flow.Flow") {
                    onTransactFun.addStatement("authorizeCaller()")
                    onTransactFun.addStatement("ensureOpen()")
                    function.parameters.forEach { param ->
                        val paramName = param.name!!.asString()
                        when (requireNotNull(WireTypeModel.resolve(param.type.resolve())).kind) {
                            WireTypeKind.STRING -> onTransactFun.addStatement("val %L = data.readString()!!", paramName)
                            WireTypeKind.INT -> onTransactFun.addStatement("val %L = data.readInt()", paramName)
                            WireTypeKind.LONG -> onTransactFun.addStatement("val %L = data.readLong()", paramName)
                            WireTypeKind.BOOLEAN -> onTransactFun.addStatement("val %L = data.readInt() == 1", paramName)
                            WireTypeKind.BYTES -> onTransactFun.addStatement("val %L = data.createByteArray()!!", paramName)
                            WireTypeKind.PARCELABLE -> onTransactFun.addStatement("val %L = data.readParcelable<android.os.Parcelable>(javaClass.classLoader) as %T", paramName, param.type.toTypeName())
                            else -> error("Floating point Stream parameters are unsupported")
                        }
                    }
                    onTransactFun.addStatement("val response = reply ?: return false")
                    onTransactFun.addStatement("val observerBinder = data.readStrongBinder() ?: throw IllegalArgumentException(%S)", "Missing stream observer")
                    onTransactFun.addStatement("val ownerUid = android.os.Binder.getCallingUid()")
                    onTransactFun.addStatement("val subId = _subIdCounter.incrementAndGet()")
                    onTransactFun.addCode(
                        """
                        |val quietCancellation = java.util.concurrent.atomic.AtomicBoolean(false)
                        |val terminalSent = java.util.concurrent.atomic.AtomicBoolean(false)
                        |val callbackLock = Any()
                        |fun sendTerminal(eventCode: Int, message: String? = null) {
                        |    synchronized(callbackLock) {
                        |        if (quietCancellation.get() || !terminalSent.compareAndSet(false, true)) return
                        |        val terminalData = android.os.Parcel.obtain()
                        |        try {
                        |            if (eventCode == 3) terminalData.writeString(message ?: "Remote stream error")
                        |            observerBinder.transact(eventCode, terminalData, null, android.os.IBinder.FLAG_ONEWAY)
                        |        } catch (_: Exception) {
                        |            // The observer may already be dead; a terminal is attempted only once.
                        |        } finally { terminalData.recycle() }
                        |    }
                        |}
                        |""".trimMargin()
                    )
                    val args = function.parameters.joinToString(", ") { it.name!!.asString() }
                    onTransactFun.addStatement("val flow = %L(%L)", funName, args)
                    onTransactFun.beginControlFlow("val job = coroutineScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY)")
                    onTransactFun.beginControlFlow("try")
                    onTransactFun.beginControlFlow("flow.collect { item ->")
                    onTransactFun.beginControlFlow("synchronized(callbackLock)")
                    onTransactFun.beginControlFlow("if (!quietCancellation.get() && !terminalSent.get())")
                    onTransactFun.addStatement("val eventData = android.os.Parcel.obtain()")
                    onTransactFun.beginControlFlow("try")
                    val streamType = requireNotNull(returnType.arguments.firstOrNull()?.type?.resolve())
                    when (requireNotNull(WireTypeModel.resolve(streamType, allowFloatingPoint = true)).kind) {
                        WireTypeKind.INT -> onTransactFun.addStatement("eventData.writeInt(item)")
                        WireTypeKind.LONG -> onTransactFun.addStatement("eventData.writeLong(item)")
                        WireTypeKind.BOOLEAN -> onTransactFun.addStatement("eventData.writeInt(if (item) 1 else 0)")
                        WireTypeKind.FLOAT -> onTransactFun.addStatement("eventData.writeFloat(item)")
                        WireTypeKind.DOUBLE -> onTransactFun.addStatement("eventData.writeDouble(item)")
                        WireTypeKind.BYTES -> onTransactFun.addStatement("eventData.writeByteArray(item)")
                        WireTypeKind.STRING -> onTransactFun.addStatement("eventData.writeString(item)")
                        WireTypeKind.PARCELABLE -> onTransactFun.addStatement("eventData.writeParcelable(item as android.os.Parcelable, 0)")
                    }
                    onTransactFun.addStatement("observerBinder?.transact(1, eventData, null, android.os.IBinder.FLAG_ONEWAY)")
                    onTransactFun.nextControlFlow("finally")
                    onTransactFun.addStatement("eventData.recycle()")
                    onTransactFun.endControlFlow()
                    onTransactFun.endControlFlow()
                    onTransactFun.endControlFlow()
                    onTransactFun.endControlFlow()
                    onTransactFun.addStatement("kotlinx.coroutines.currentCoroutineContext().ensureActive()")
                    onTransactFun.addStatement("sendTerminal(2)")
                    onTransactFun.nextControlFlow("catch (cancelled: kotlinx.coroutines.CancellationException)")
                    onTransactFun.addStatement("sendTerminal(3, if (_disposed.get()) %S else %S)", "SERVER_DISPOSED", "SERVER_SCOPE_CANCELLED")
                    onTransactFun.addStatement("throw cancelled")
                    onTransactFun.nextControlFlow("catch (e: Exception)")
                    onTransactFun.addStatement("sendTerminal(3, e.message ?: %S)", "Remote stream error")
                    onTransactFun.endControlFlow()
                    onTransactFun.endControlFlow()
                    onTransactFun.addStatement("val record = Triple(ownerUid, job, quietCancellation)")
                    onTransactFun.addCode(
                        """
                        |val recipient = android.os.IBinder.DeathRecipient {
                        |    quietCancellation.set(true)
                        |    job.cancel()
                        |}
                        |try {
                        |    observerBinder.linkToDeath(recipient, 0)
                        |} catch (dead: android.os.RemoteException) {
                        |    quietCancellation.set(true)
                        |    job.cancel()
                        |}
                        |job.invokeOnCompletion { cause ->
                        |    if (cause != null) {
                        |        val message = when {
                        |            _disposed.get() -> "SERVER_DISPOSED"
                        |            !coroutineScope.isActive -> "SERVER_SCOPE_CANCELLED"
                        |            else -> cause.message ?: "Remote stream cancelled"
                        |        }
                        |        sendTerminal(3, message)
                        |    }
                        |    synchronized(_lifecycleLock) { _subscriptionJobs.remove(subId, record) }
                        |    try { observerBinder.unlinkToDeath(recipient, 0) } catch (_: Exception) {}
                        |}
                        |val accepted = synchronized(_lifecycleLock) {
                        |    if (_disposed.get() || !coroutineScope.isActive || job.isCancelled) {
                        |        false
                        |    } else {
                        |        _subscriptionJobs[subId] = record
                        |        true
                        |    }
                        |}
                        |if (!accepted) {
                        |    job.cancel()
                        |    throw IllegalStateException("IPC stream service is unavailable")
                        |}
                        |job.start()
                        |""".trimMargin()
                    )
                    onTransactFun.addStatement("response.writeNoException()")
                    onTransactFun.addStatement("response.writeLong(subId)")
                } else {
                    onTransactFun.addStatement("authorizeCaller()")
                    onTransactFun.addStatement("ensureOpen()")
                    // 处理单向调用
                    function.parameters.forEach { param ->
                        val paramName = param.name!!.asString()
                        when (requireNotNull(WireTypeModel.resolve(param.type.resolve())).kind) {
                            WireTypeKind.STRING -> onTransactFun.addStatement("val %L = data.readString()!!", paramName)
                            WireTypeKind.INT -> onTransactFun.addStatement("val %L = data.readInt()", paramName)
                            WireTypeKind.LONG -> onTransactFun.addStatement("val %L = data.readLong()", paramName)
                            WireTypeKind.BOOLEAN -> onTransactFun.addStatement("val %L = data.readInt() == 1", paramName)
                            WireTypeKind.BYTES -> onTransactFun.addStatement("val %L = data.createByteArray()!!", paramName)
                            WireTypeKind.PARCELABLE -> onTransactFun.addStatement("val %L = data.readParcelable<android.os.Parcelable>(javaClass.classLoader) as %T", paramName, param.type.toTypeName())
                            else -> error("Floating point Oneway parameters are unsupported")
                        }
                    }
                    val args = function.parameters.joinToString(", ") { it.name!!.asString() }
                    onTransactFun.addStatement("%L(%L)", funName, args)
                }
                onTransactFun.addStatement("return true")
                onTransactFun.endControlFlow()

                if (unsubscribeCode != -1) {
                    onTransactFun.beginControlFlow("%L -> ", unsubscribeCode)
                    onTransactFun.addStatement("authorizeCaller()")
                    onTransactFun.addStatement("val subId = data.readLong()")
                    onTransactFun.addStatement("val record = _subscriptionJobs[subId]")
                    onTransactFun.beginControlFlow("if (record != null && record.first == android.os.Binder.getCallingUid())")
                    onTransactFun.addStatement("record.third.set(true)")
                    onTransactFun.addStatement("record.second.cancel()")
                    onTransactFun.endControlFlow()
                    onTransactFun.addStatement("return true")
                    onTransactFun.endControlFlow()
                }
            }
        }

        // 处理未知的事务码
        onTransactFun.beginControlFlow("else ->")
        onTransactFun.addStatement("authorizeCaller()")
        onTransactFun.addStatement("return super.onTransact(code, data, reply, flags)")
        onTransactFun.endControlFlow()
        onTransactFun.endControlFlow() // end when

        typeBuilder.addFunction(onTransactFun.build())

        // 3. 写入文件
        val fileSpec = FileSpec.builder(packageName, stubClassName)
            .addType(typeBuilder.build())
            .addImport("kotlinx.coroutines", "launch", "cancel", "isActive", "ensureActive")
            .build()

        fileSpec.writeTo(codeGenerator, Dependencies(aggregating = false, classDeclaration.containingFile!!))
    }
}
