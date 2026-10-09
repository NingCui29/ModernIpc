package com.cn.ipc.compiler

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Nullability

/**
 * IPC 符号处理器，主要负责解析标注了 `@IpcFacade` 的接口，
 * 并生成对应的客户端适配器 (Client Adapter) 和服务端存根 (Server Stub)。
 *
 * @param codeGenerator KSP 代码生成器。
 * @param logger KSP 日志记录器，用于输出编译期信息和错误。
 */
class IpcSymbolProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    /**
     * 处理符号的核心逻辑。
     *
     * @param resolver 符号解析器。
     * @return 无法处理或延迟处理的符号列表。
     */
    override fun process(resolver: Resolver): List<KSAnnotated> {
        // 查找所有标注了 @IpcFacade 的符号
        val symbols = resolver.getSymbolsWithAnnotation("com.cn.ipc.annotations.IpcFacade")
        
        // 过滤出无法处理的非类声明符号
        val unableToProcess = symbols.filterNot { it is KSClassDeclaration }.toList()
        
        val clientAdapterGenerator = ClientAdapterGenerator(codeGenerator)
        val serverStubGenerator = ServerStubGenerator(codeGenerator)
        val schemaGenerator = IpcSchemaGenerator(codeGenerator)

        // 仅处理类的声明（实际上是接口，在 validateFacade 中验证）
        symbols.filterIsInstance<KSClassDeclaration>().forEach { classDeclaration ->
            val isValid = validateFacade(classDeclaration)
            if (isValid) {
                schemaGenerator.generate(classDeclaration)
                clientAdapterGenerator.generate(classDeclaration)
                serverStubGenerator.generate(classDeclaration)
                logger.info("ModernIPC: Successfully generated Client & Server for -> ${classDeclaration.simpleName.asString()}")
            }
        }

        return unableToProcess
    }

    /**
     * 校验 @IpcFacade 标注的类是否合法。
     * - 必须是 interface。
     * - 挂起函数必须有 @IpcAsync 注解。
     * - 返回 Flow 的函数必须有 @IpcStream 注解。
     *
     * @param classDeclaration 待校验的类声明。
     * @return 如果类结构符合要求则返回 true，否则返回 false。
     */
    private fun validateFacade(classDeclaration: KSClassDeclaration): Boolean {
        var isValid = true
        val transactionOwners = mutableMapOf<Int, String>()
        // 必须是接口
        if (classDeclaration.classKind != com.google.devtools.ksp.symbol.ClassKind.INTERFACE) {
            logger.error("@IpcFacade 只能用于 interface，但找到了 ${classDeclaration.classKind}", classDeclaration)
            return false
        }
        val facade = classDeclaration.annotations.first { it.shortName.asString() == "IpcFacade" }
        val serviceId = facade.arguments.firstOrNull { it.name?.asString() == "serviceId" }?.value as? Int ?: 0
        val minVersion = facade.arguments.firstOrNull { it.name?.asString() == "minApiVersion" }?.value as? Int ?: 1
        val contractVersion = facade.arguments.firstOrNull { it.name?.asString() == "contractVersion" }?.value as? Int ?: 0
        if (serviceId <= 0 || minVersion <= 0 || contractVersion < 0) {
            logger.error("serviceId/minApiVersion 必须为正数，contractVersion 必须为非负数（0 沿用 minApiVersion）。", classDeclaration)
            isValid = false
        }
        if (classDeclaration.typeParameters.isNotEmpty() || classDeclaration.getAllProperties().any()) {
            logger.error("IPC 门面暂不支持泛型接口或属性，请使用标注的方法。", classDeclaration)
            isValid = false
        }

        val functions = classDeclaration.getAllFunctions()
        functions.forEach { function ->
            // 忽略通用方法
            if (function.simpleName.asString() in listOf("equals", "hashCode", "toString")) return@forEach
            
            val isSuspend = function.modifiers.contains(Modifier.SUSPEND)
            val annotations = function.annotations.toList()
            fun has(name: String) = annotations.any { it.shortName.asString() == name }
            val hasDirect = has("IpcDirect")
            val result = function.returnType?.resolve()
            val isFlow = result?.declaration?.qualifiedName?.asString() == "kotlinx.coroutines.flow.Flow"
            if (function.typeParameters.isNotEmpty() || function.extensionReceiver != null || function.parameters.any { it.isVararg }) {
                logger.error("IPC 方法暂不支持泛型方法、扩展接收者或 vararg。", function)
                isValid = false
            }

            if (listOf("IpcAsync", "IpcStream", "IpcOneway", "IpcDirect").count(::has) != 1) {
                logger.error("${function.simpleName.asString()} 必须且只能选择一种 IPC 方法注解。", function)
                isValid = false
            }

            if (has("IpcAsync") && !isSuspend) {
                logger.error("@IpcAsync 只能用于挂起方法。", function)
                isValid = false
            }
            if (has("IpcStream") && (isSuspend || !isFlow)) {
                logger.error("@IpcStream 只能用于非挂起、返回 Flow 的方法。", function)
                isValid = false
            }
            if (has("IpcOneway") && (isSuspend || result?.declaration?.qualifiedName?.asString() != "kotlin.Unit" || result?.nullability != Nullability.NOT_NULL)) {
                logger.error("@IpcOneway 只能用于非挂起、返回 Unit 的方法。", function)
                isValid = false
            }

            for ((annotationName, argumentNames) in listOf(
                "IpcAsync" to listOf("requestTransaction", "cancelTransaction"),
                "IpcStream" to listOf("subscribeTransaction", "unsubscribeTransaction"),
                "IpcOneway" to listOf("transaction"),
                "IpcDirect" to listOf("transaction")
            )) {
                val annotation = annotations.firstOrNull { it.shortName.asString() == annotationName } ?: continue
                for (argumentName in argumentNames) {
                    val code = annotation.arguments.firstOrNull { it.name?.asString() == argumentName }?.value as? Int
                    if (code == null || code !in 1..0x00ffffff) {
                        logger.error("${function.simpleName.asString()} 的 $argumentName 必须是 1..0x00ffffff 范围内的 Binder 用户事务码。", function)
                        isValid = false
                    } else {
                        val owner = transactionOwners.putIfAbsent(code, "${function.simpleName.asString()}.$argumentName")
                        if (owner != null) {
                            logger.error("事务码 $code 已被 $owner 使用，不能重复分配。", function)
                            isValid = false
                        }
                    }
                }
            }

            function.parameters.forEach { parameter ->
                val model = WireTypeModel.resolve(parameter.type.resolve())
                if (model == null || (hasDirect && model.kind == WireTypeKind.PARCELABLE)) {
                    logger.error("IPC 参数仅支持非空 String/Int/Long/Boolean/ByteArray 或非泛型 Parcelable；Direct 不支持 Parcelable。", parameter)
                    isValid = false
                }
            }
            if (has("IpcAsync") && (result == null || WireTypeModel.resolve(result) == null)) {
                logger.error("@IpcAsync 返回值仅支持非空 String/Int/Long/Boolean/ByteArray 或非泛型 Parcelable；Unit、Float/Double 暂未实现。", function)
                isValid = false
            }
            if (has("IpcStream")) {
                val item = result?.arguments?.singleOrNull()?.type?.resolve()
                if (!isFlow || result?.nullability != Nullability.NOT_NULL || item == null || WireTypeModel.resolve(item, allowFloatingPoint = true) == null) {
                    logger.error("@IpcStream 必须返回非空 Flow<T>；T 支持非空基本 IPC 类型、Float/Double 或非泛型 Parcelable。", function)
                    isValid = false
                }
                if (ipcStreamOverflowName(function) !in setOf("ERROR", "CONFLATE")) {
                    logger.error("@IpcStream overflowPolicy 必须是 ERROR 或 CONFLATE。", function)
                    isValid = false
                }
            }
            if (hasDirect) {
                if (isSuspend || isFlow) {
                    logger.error("@IpcDirect 只能用于非挂起、非 Flow 方法。", function)
                    isValid = false
                }
                val model = result?.let { WireTypeModel.resolve(it) }
                if (model == null || model.kind == WireTypeKind.PARCELABLE) {
                    logger.error("@IpcDirect 只支持非空 String/Int/Long/Boolean/ByteArray 返回值。", function)
                    isValid = false
                }
            }
            
            // 检查挂起函数是否打上了 @IpcAsync 注解
            if (isSuspend) {
                val hasIpcAsync = has("IpcAsync")
                if (!hasIpcAsync) {
                    logger.error(
                        "挂起函数 ${function.simpleName.asString()} 必须打上 @IpcAsync 注解以声明事务码。", 
                        function
                    )
                    isValid = false
                }
            } else {
                // 非挂起函数，检查是否有 @IpcOneway 或 @IpcStream
                if (isFlow) {
                    val hasIpcStream = has("IpcStream")
                    if (!hasIpcStream) {
                        logger.error(
                            "返回 Flow 的流式函数 ${function.simpleName.asString()} 必须打上 @IpcStream 注解。", 
                            function
                        )
                        isValid = false
                    }
                }
            }
        }
        return isValid
    }
}
