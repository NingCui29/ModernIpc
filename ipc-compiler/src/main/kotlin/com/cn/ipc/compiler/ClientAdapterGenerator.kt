package com.cn.ipc.compiler

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.FileSpec
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

        // 2. 为每个接口方法生成对应的 override 实现
        val functions = classDeclaration.getAllFunctions()
        functions.forEach { function ->
            // 忽略 Object 的基础方法
            if (function.simpleName.asString() in listOf("equals", "hashCode", "toString")) return@forEach

            val funName = function.simpleName.asString()
            val isSuspend = function.modifiers.contains(Modifier.SUSPEND)

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
                val returnTypeName = returnType?.declaration?.qualifiedName?.asString() ?: "kotlin.String"
                val deserializerCode = when (returnTypeName) {
                    "kotlin.Int" -> "{ it.readInt() }"
                    "kotlin.Long" -> "{ it.readLong() }"
                    "kotlin.Boolean" -> "{ it.readInt() == 1 }"
                    "kotlin.ByteArray" -> "{ it.createByteArray() ?: ByteArray(0) }"
                    "kotlin.String" -> "{ it.readString() ?: \"\" }"
                    else -> "{ it.readParcelable<android.os.Parcelable>(javaClass.classLoader) }"
                }
                // 生成异步挂起调用的 Binder 通信代码
                funBuilder.beginControlFlow(
                    "return controller.pendingCallRegistry.callSuspend(serviceId = %L, operationId = %L, generation = controller.currentGeneration, deserializer = %L) { requestId ->", 
                    serviceId, transactionCode, deserializerCode
                )
                funBuilder.addStatement("val _data = android.os.Parcel.obtain()")
                funBuilder.addStatement("_data.writeInterfaceToken(%S)", "$packageName.$interfaceName")
                funBuilder.addStatement("_data.writeLong(requestId)")
                function.parameters.forEach { param ->
                    val paramName = param.name!!.asString()
                    val paramType = param.type.resolve().declaration.qualifiedName?.asString()
                    when (paramType) {
                        "kotlin.String" -> funBuilder.addStatement("_data.writeString(%L)", paramName)
                        "kotlin.Int" -> funBuilder.addStatement("_data.writeInt(%L)", paramName)
                        "kotlin.Long" -> funBuilder.addStatement("_data.writeLong(%L)", paramName)
                        "kotlin.Boolean" -> funBuilder.addStatement("_data.writeInt(if (%L) 1 else 0)", paramName)
                        "kotlin.ByteArray" -> funBuilder.addStatement("_data.writeByteArray(%L)", paramName)
                        else -> funBuilder.addStatement("_data.writeParcelable(%L, 0)", paramName)
                    }
                }
                funBuilder.addStatement("_data.writeStrongBinder(controller.globalResponseBinder)")
                funBuilder.addStatement("val _binder = controller.getServiceBinder(%L)", serviceId)
                funBuilder.addStatement("_binder.transact(%L, _data, null, android.os.IBinder.FLAG_ONEWAY)", transactionCode)
                funBuilder.addStatement("_data.recycle()")
                funBuilder.endControlFlow()
            } else if (returnType?.declaration?.qualifiedName?.asString() == "kotlinx.coroutines.flow.Flow") {
                // 生成 Flow 流式订阅的 Binder 通信代码
                val flowTypeArg = returnType.arguments.firstOrNull()?.type?.resolve()
                val flowTypeArgName = flowTypeArg?.declaration?.qualifiedName?.asString() ?: "kotlin.String"
                val trySendCode = when (flowTypeArgName) {
                    "kotlin.Int" -> "trySend(item.toIntOrNull() ?: 0)"
                    "kotlin.Long" -> "trySend(item.toLongOrNull() ?: 0L)"
                    "kotlin.Boolean" -> "trySend(item.toBoolean())"
                    else -> "trySend(item)"
                }
                funBuilder.beginControlFlow("return kotlinx.coroutines.flow.callbackFlow")
                funBuilder.addCode(
                    """
                    |val _observer = object : android.os.Binder() {
                    |    override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
                    |        if (code == 1) {
                    |            val item = data.readString() ?: ""
                    |            %L
                    |            return true
                    |        }
                    |        return super.onTransact(code, data, reply, flags)
                    |    }
                    |}
                    |val _data = android.os.Parcel.obtain()
                    |_data.writeInterfaceToken(%S)
                    |""".trimMargin(), trySendCode, "$packageName.$interfaceName"
                )
                function.parameters.forEach { param ->
                    val paramName = param.name!!.asString()
                    val paramType = param.type.resolve().declaration.qualifiedName?.asString()
                    when (paramType) {
                        "kotlin.String" -> funBuilder.addStatement("_data.writeString(%L)", paramName)
                        "kotlin.Int" -> funBuilder.addStatement("_data.writeInt(%L)", paramName)
                        "kotlin.Long" -> funBuilder.addStatement("_data.writeLong(%L)", paramName)
                        "kotlin.Boolean" -> funBuilder.addStatement("_data.writeInt(if (%L) 1 else 0)", paramName)
                        "kotlin.ByteArray" -> funBuilder.addStatement("_data.writeByteArray(%L)", paramName)
                        else -> funBuilder.addStatement("_data.writeParcelable(%L, 0)", paramName)
                    }
                }
                funBuilder.addCode(
                    """
                    |_data.writeStrongBinder(_observer)
                    |val _reply = android.os.Parcel.obtain()
                    |val _binder = controller.getServiceBinder(%L)
                    |_binder.transact(%L, _data, _reply, 0)
                    |val subId = _reply.readLong()
                    |_reply.recycle()
                    |_data.recycle()
                    |
                    |awaitClose {
                    |    val _unsubData = android.os.Parcel.obtain()
                    |    _unsubData.writeInterfaceToken(%S)
                    |    _unsubData.writeLong(subId)
                    |    val _b = controller.getServiceBinder(%L)
                    |    _b.transact(%L, _unsubData, null, android.os.IBinder.FLAG_ONEWAY)
                    |    _unsubData.recycle()
                    |}
                    |""".trimMargin(), serviceId, transactionCode, "$packageName.$interfaceName", serviceId, unsubscribeTransactionCode
                )
                funBuilder.endControlFlow()
            } else {
                // 生成单向通信的 Binder 通信代码
                funBuilder.addStatement("val _data = android.os.Parcel.obtain()")
                funBuilder.addStatement("_data.writeInterfaceToken(%S)", "$packageName.$interfaceName")
                function.parameters.forEach { param ->
                    val paramName = param.name!!.asString()
                    val paramType = param.type.resolve().declaration.qualifiedName?.asString()
                    when (paramType) {
                        "kotlin.String" -> funBuilder.addStatement("_data.writeString(%L)", paramName)
                        "kotlin.Int" -> funBuilder.addStatement("_data.writeInt(%L)", paramName)
                        "kotlin.Long" -> funBuilder.addStatement("_data.writeLong(%L)", paramName)
                        "kotlin.Boolean" -> funBuilder.addStatement("_data.writeInt(if (%L) 1 else 0)", paramName)
                        "kotlin.ByteArray" -> funBuilder.addStatement("_data.writeByteArray(%L)", paramName)
                        else -> funBuilder.addStatement("_data.writeParcelable(%L, 0)", paramName)
                    }
                }
                funBuilder.addStatement("val _binder = controller.getServiceBinder(%L)", serviceId)
                funBuilder.addStatement("_binder.transact(%L, _data, null, android.os.IBinder.FLAG_ONEWAY)", transactionCode)
                funBuilder.addStatement("_data.recycle()")
            }

            typeBuilder.addFunction(funBuilder.build())
        }

        // 3. 将生成的代码写入文件
        val fileSpec = FileSpec.builder(packageName, adapterClassName)
            .addType(typeBuilder.build())
            .addImport("kotlinx.coroutines.channels", "awaitClose")
            .build()

        fileSpec.writeTo(codeGenerator, Dependencies(aggregating = false, classDeclaration.containingFile!!))
    }
}
