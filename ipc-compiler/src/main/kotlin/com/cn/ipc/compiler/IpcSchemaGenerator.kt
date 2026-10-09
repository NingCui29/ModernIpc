package com.cn.ipc.compiler

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.writeTo

/** Generates one shared description of the actual transaction format, without hashing DTO fields. */
class IpcSchemaGenerator(private val codeGenerator: CodeGenerator) {
    fun generate(classDeclaration: KSClassDeclaration) {
        val packageName = classDeclaration.packageName.asString()
        val interfaceName = classDeclaration.simpleName.asString()
        val schemaName = "${interfaceName}IpcSchema"
        val facade = classDeclaration.annotations.first { it.shortName.asString() == "IpcFacade" }
        val minVersion = facade.arguments.first { it.name?.asString() == "minApiVersion" }.value as Int
        val declaredVersion = facade.arguments.firstOrNull { it.name?.asString() == "contractVersion" }?.value as? Int ?: 0
        val contractVersion = if (declaredVersion == 0) minVersion else declaredVersion
        val signatures = sortedMapOf<Int, String>()
        classDeclaration.getAllFunctions().forEach { function ->
            if (function.simpleName.asString() in setOf("equals", "hashCode", "toString")) return@forEach
            addMethodSignatures(function, signatures)
        }
        val mapInitializer = CodeBlock.builder().add("mapOf(\n").indent()
        signatures.forEach { (code, signature) -> mapInitializer.add("%L to %S,\n", code, signature) }
        mapInitializer.unindent().add(")")
        val schema = TypeSpec.objectBuilder(schemaName)
            .addKdoc("Generated transaction schema. Parcelable identities are opaque and do not verify DTO field layouts.\n")
            .addProperty(PropertySpec.builder("DESCRIPTOR", STRING)
                .addModifiers(KModifier.CONST)
                .initializer("%S", "$packageName.$interfaceName")
                .build())
            .addProperty(PropertySpec.builder("CLIENT_CONTRACT_VERSION", INT)
                .addModifiers(KModifier.CONST)
                .initializer("%L", contractVersion)
                .build())
            .addProperty(PropertySpec.builder("METHOD_SIGNATURES", MAP.parameterizedBy(INT, STRING))
                .initializer(mapInitializer.build())
                .build())
            .addProperty(PropertySpec.builder("CLIENT_SCHEMA", ClassName("com.cn.ipc", "ClientServiceSchema"))
                .initializer("%T(DESCRIPTOR, CLIENT_CONTRACT_VERSION, METHOD_SIGNATURES)", ClassName("com.cn.ipc", "ClientServiceSchema"))
                .build())
            .build()
        FileSpec.builder(packageName, schemaName).addType(schema).build()
            .writeTo(codeGenerator, Dependencies(aggregating = false, classDeclaration.containingFile!!))
    }

    private fun addMethodSignatures(function: KSFunctionDeclaration, target: MutableMap<Int, String>) {
        val parameters = function.parameters.joinToString(",") {
            requireNotNull(WireTypeModel.resolve(it.type.resolve())).signature
        }
        val annotations = function.annotations.toList()
        fun annotation(name: String) = annotations.firstOrNull { it.shortName.asString() == name }
        fun code(name: String, argument: String): Int = annotation(name)!!.arguments
            .first { it.name?.asString() == argument }.value as Int
        when {
            annotation("IpcAsync") != null -> {
                val request = code("IpcAsync", "requestTransaction")
                val cancel = code("IpcAsync", "cancelTransaction")
                val result = requireNotNull(WireTypeModel.resolve(function.returnType!!.resolve())).signature
                target[request] = "mode=async;role=request;flags=oneway;params=[$parameters];return=$result;control=requestId:int64,callback:binder;envelope=callback-code1(requestId:int64,status-v1[string-error]);cancel=$cancel"
                target[cancel] = "mode=async;role=cancel;flags=oneway;params=[requestId:int64,callback:binder];return=none;envelope=none;request=$request"
            }
            annotation("IpcDirect") != null -> {
                val result = requireNotNull(WireTypeModel.resolve(function.returnType!!.resolve())).signature
                target[code("IpcDirect", "transaction")] = "mode=direct;role=request;flags=sync;params=[$parameters];return=$result;envelope=parcel-exception+status-v1"
            }
            annotation("IpcStream") != null -> {
                val subscribe = code("IpcStream", "subscribeTransaction")
                val unsubscribe = code("IpcStream", "unsubscribeTransaction")
                val item = function.returnType!!.resolve().arguments.single().type!!.resolve()
                val result = requireNotNull(WireTypeModel.resolve(item, allowFloatingPoint = true)).signature
                target[subscribe] = "mode=stream;role=subscribe;flags=sync;params=[$parameters];return=flow<$result>;control=observer:binder;envelope=parcel-exception+subId:int64;events=observer-v2(1:$result,2:complete,3:string-error);unsubscribe=$unsubscribe"
                target[unsubscribe] = "mode=stream;role=unsubscribe;flags=oneway;params=[subId:int64];return=none;envelope=none;subscribe=$subscribe"
            }
            annotation("IpcOneway") != null -> {
                target[code("IpcOneway", "transaction")] = "mode=oneway;role=request;flags=oneway;params=[$parameters];return=none;envelope=none"
            }
        }
    }
}
