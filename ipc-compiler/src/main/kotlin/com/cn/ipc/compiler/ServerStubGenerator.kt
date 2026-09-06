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

        val mapType = ClassName("java.util.concurrent", "ConcurrentHashMap").parameterizedBy(
            LONG,
            ClassName("kotlinx.coroutines", "Job")
        )

        // 1. 创建抽象类: abstract class IUserServiceServerStub : android.os.Binder(), IUserService
        val typeBuilder = TypeSpec.classBuilder(stubClassName)
            .addModifiers(KModifier.ABSTRACT)
            .superclass(ClassName("android.os", "Binder"))
            .addSuperinterface(classDeclaration.toClassName())
            .addProperty(
                // 定义受保护的协程作用域，用于执行挂起函数
                PropertySpec.builder("coroutineScope", ClassName("kotlinx.coroutines", "CoroutineScope"))
                    .addModifiers(KModifier.PROTECTED, KModifier.ABSTRACT)
                    .build()
            )
            .addProperty(
                PropertySpec.builder("_subscriptionJobs", mapType)
                    .initializer("java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.Job>()")
                    .addModifiers(KModifier.PRIVATE)
                    .build()
            )
            .addProperty(
                PropertySpec.builder("_subIdCounter", ClassName("java.util.concurrent.atomic", "AtomicLong"))
                    .initializer("java.util.concurrent.atomic.AtomicLong(1)")
                    .addModifiers(KModifier.PRIVATE)
                    .build()
            )

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
            val returnType = function.returnType?.resolve()

            // 提取事务码
            var transactionCode = -1
            var unsubscribeCode = -1
            if (isSuspend) {
                val asyncAnnotation = function.annotations.find { it.shortName.asString() == "IpcAsync" }
                transactionCode = asyncAnnotation?.arguments?.firstOrNull { it.name?.asString() == "requestTransaction" }?.value as? Int ?: -1
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
                    // 处理异步挂起调用
                    onTransactFun.addStatement("val requestId = data.readLong()")
                    // 读取并解析请求参数
                    function.parameters.forEach { param ->
                        val paramName = param.name!!.asString()
                        val paramType = param.type.resolve().declaration.qualifiedName?.asString()
                        when (paramType) {
                            "kotlin.String" -> onTransactFun.addStatement("val %L = data.readString()!!", paramName)
                            "kotlin.Int" -> onTransactFun.addStatement("val %L = data.readInt()", paramName)
                            "kotlin.Long" -> onTransactFun.addStatement("val %L = data.readLong()", paramName)
                            "kotlin.Boolean" -> onTransactFun.addStatement("val %L = data.readInt() == 1", paramName)
                            "kotlin.ByteArray" -> onTransactFun.addStatement("val %L = data.createByteArray() ?: ByteArray(0)", paramName)
                            else -> onTransactFun.addStatement("val %L = data.readParcelable<android.os.Parcelable>(javaClass.classLoader)!!", paramName)
                        }
                    }
                    onTransactFun.addStatement("val responseBinder = data.readStrongBinder()")
                    // 开启协程执行挂起函数
                    onTransactFun.beginControlFlow("coroutineScope.launch")
                    onTransactFun.beginControlFlow("try")
                    
                    // invoke function 调用实际的服务接口方法
                    val args = function.parameters.joinToString(", ") { it.name!!.asString() }
                    onTransactFun.addStatement("val result = %L(%L)", funName, args)
                    
                    // send response 发送成功响应
                    onTransactFun.beginControlFlow("try")
                    onTransactFun.addStatement("val replyData = android.os.Parcel.obtain()")
                    onTransactFun.addStatement("replyData.writeLong(requestId)")
                    onTransactFun.addStatement("replyData.writeInt(1) // success")
                    val returnTypeName = returnType?.declaration?.qualifiedName?.asString() ?: "kotlin.String"
                    when (returnTypeName) {
                        "kotlin.Int" -> onTransactFun.addStatement("replyData.writeInt(result)")
                        "kotlin.Long" -> onTransactFun.addStatement("replyData.writeLong(result)")
                        "kotlin.Boolean" -> onTransactFun.addStatement("replyData.writeInt(if (result) 1 else 0)")
                        "kotlin.ByteArray" -> onTransactFun.addStatement("replyData.writeByteArray(result)")
                        "kotlin.String" -> onTransactFun.addStatement("replyData.writeString(result ?: \"\")")
                        else -> onTransactFun.addStatement("replyData.writeParcelable(result as? android.os.Parcelable, 0)")
                    }
                    onTransactFun.addStatement("responseBinder?.transact(1, replyData, null, android.os.IBinder.FLAG_ONEWAY)")
                    onTransactFun.addStatement("replyData.recycle()")
                    onTransactFun.nextControlFlow("catch (dead: android.os.RemoteException)")
                    onTransactFun.addStatement("// Client died, ignore")
                    onTransactFun.endControlFlow()
                    
                    // 处理异常响应
                    onTransactFun.nextControlFlow("catch (e: Exception)")
                    onTransactFun.beginControlFlow("try")
                    onTransactFun.addStatement("val errorData = android.os.Parcel.obtain()")
                    onTransactFun.addStatement("errorData.writeLong(requestId)")
                    onTransactFun.addStatement("errorData.writeInt(0) // error")
                    onTransactFun.addStatement("errorData.writeString(e.message ?: \"Remote error\")")
                    onTransactFun.addStatement("responseBinder?.transact(1, errorData, null, android.os.IBinder.FLAG_ONEWAY)")
                    onTransactFun.addStatement("errorData.recycle()")
                    onTransactFun.nextControlFlow("catch (dead: android.os.RemoteException)")
                    onTransactFun.addStatement("// Client died, ignore")
                    onTransactFun.endControlFlow()
                    onTransactFun.endControlFlow()
                    onTransactFun.endControlFlow()
                } else if (returnType?.declaration?.qualifiedName?.asString() == "kotlinx.coroutines.flow.Flow") {
                    function.parameters.forEach { param ->
                        val paramName = param.name!!.asString()
                        val paramType = param.type.resolve().declaration.qualifiedName?.asString()
                        when (paramType) {
                            "kotlin.String" -> onTransactFun.addStatement("val %L = data.readString()!!", paramName)
                            "kotlin.Int" -> onTransactFun.addStatement("val %L = data.readInt()", paramName)
                            "kotlin.Long" -> onTransactFun.addStatement("val %L = data.readLong()", paramName)
                            "kotlin.Boolean" -> onTransactFun.addStatement("val %L = data.readInt() == 1", paramName)
                            "kotlin.ByteArray" -> onTransactFun.addStatement("val %L = data.createByteArray() ?: ByteArray(0)", paramName)
                            else -> onTransactFun.addStatement("val %L = data.readParcelable<android.os.Parcelable>(javaClass.classLoader)!!", paramName)
                        }
                    }
                    onTransactFun.addStatement("val observerBinder = data.readStrongBinder()")
                    onTransactFun.addStatement("val subId = _subIdCounter.incrementAndGet()")
                    onTransactFun.addStatement("reply?.writeLong(subId)")
                    val args = function.parameters.joinToString(", ") { it.name!!.asString() }
                    onTransactFun.addStatement("val flow = %L(%L)", funName, args)
                    onTransactFun.beginControlFlow("val job = coroutineScope.launch")
                    onTransactFun.beginControlFlow("try")
                    onTransactFun.beginControlFlow("flow.collect { item ->")
                    onTransactFun.addStatement("val eventData = android.os.Parcel.obtain()")
                    onTransactFun.addStatement("eventData.writeString(item.toString())")
                    onTransactFun.addStatement("observerBinder?.transact(1, eventData, null, android.os.IBinder.FLAG_ONEWAY)")
                    onTransactFun.addStatement("eventData.recycle()")
                    onTransactFun.endControlFlow()
                    onTransactFun.nextControlFlow("catch (dead: android.os.RemoteException)")
                    onTransactFun.addStatement("// Client died, cancel")
                    onTransactFun.nextControlFlow("catch (e: Exception)")
                    onTransactFun.addStatement("// Flow error or cancelled")
                    onTransactFun.endControlFlow()
                    onTransactFun.endControlFlow()
                    onTransactFun.addStatement("_subscriptionJobs[subId] = job")
                    onTransactFun.addCode(
                        """
                        |try {
                        |    observerBinder?.linkToDeath({
                        |        (_subscriptionJobs.remove(subId) as? kotlinx.coroutines.Job)?.cancel()
                        |    }, 0)
                        |} catch (dead: android.os.RemoteException) {
                        |    job.cancel()
                        |}
                        |""".trimMargin()
                    )
                } else {
                    // 处理单向调用
                    function.parameters.forEach { param ->
                        val paramName = param.name!!.asString()
                        val paramType = param.type.resolve().declaration.qualifiedName?.asString()
                        when (paramType) {
                            "kotlin.String" -> onTransactFun.addStatement("val %L = data.readString()!!", paramName)
                            "kotlin.Int" -> onTransactFun.addStatement("val %L = data.readInt()", paramName)
                            "kotlin.Long" -> onTransactFun.addStatement("val %L = data.readLong()", paramName)
                            "kotlin.Boolean" -> onTransactFun.addStatement("val %L = data.readInt() == 1", paramName)
                            "kotlin.ByteArray" -> onTransactFun.addStatement("val %L = data.createByteArray() ?: ByteArray(0)", paramName)
                            else -> onTransactFun.addStatement("val %L = data.readParcelable<android.os.Parcelable>(javaClass.classLoader)!!", paramName)
                        }
                    }
                    val args = function.parameters.joinToString(", ") { it.name!!.asString() }
                    onTransactFun.addStatement("%L(%L)", funName, args)
                }
                onTransactFun.addStatement("return true")
                onTransactFun.endControlFlow()

                if (unsubscribeCode != -1) {
                    onTransactFun.beginControlFlow("%L -> ", unsubscribeCode)
                    onTransactFun.addStatement("val subId = data.readLong()")
                    onTransactFun.addStatement("(_subscriptionJobs.remove(subId) as? kotlinx.coroutines.Job)?.cancel()")
                    onTransactFun.addStatement("return true")
                    onTransactFun.endControlFlow()
                }
            }
        }

        // 处理未知的事务码
        onTransactFun.addStatement("else -> return super.onTransact(code, data, reply, flags)")
        onTransactFun.endControlFlow() // end when

        typeBuilder.addFunction(onTransactFun.build())

        // 3. 写入文件
        val fileSpec = FileSpec.builder(packageName, stubClassName)
            .addType(typeBuilder.build())
            .addImport("kotlinx.coroutines", "launch")
            .build()

        fileSpec.writeTo(codeGenerator, Dependencies(aggregating = false, classDeclaration.containingFile!!))
    }
}
