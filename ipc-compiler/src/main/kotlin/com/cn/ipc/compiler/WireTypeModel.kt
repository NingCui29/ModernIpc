package com.cn.ipc.compiler

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Nullability

enum class WireTypeKind {
    STRING, INT, LONG, BOOLEAN, BYTES, FLOAT, DOUBLE, PARCELABLE
}

/** Codec identity shared by KSP validation and both sides' generated protocol metadata. */
data class WireTypeModel(
    val kind: WireTypeKind,
    val signature: String,
    val qualifiedName: String
) {
    companion object {
        /** Parcelable is opaque: the class name does not describe or verify its field layout. */
        fun resolve(type: KSType, allowFloatingPoint: Boolean = false): WireTypeModel? {
            if (type.isError || type.nullability != Nullability.NOT_NULL || type.arguments.isNotEmpty()) return null
            val declaration = type.declaration
            val name = declaration.qualifiedName?.asString() ?: return null
            val primitive = when (name) {
                "kotlin.String" -> WireTypeKind.STRING to "string!"
                "kotlin.Int" -> WireTypeKind.INT to "int32!"
                "kotlin.Long" -> WireTypeKind.LONG to "int64!"
                "kotlin.Boolean" -> WireTypeKind.BOOLEAN to "bool-int32!"
                "kotlin.ByteArray" -> WireTypeKind.BYTES to "byte-array!"
                "kotlin.Float" -> if (allowFloatingPoint) WireTypeKind.FLOAT to "float32!" else null
                "kotlin.Double" -> if (allowFloatingPoint) WireTypeKind.DOUBLE to "float64!" else null
                else -> null
            }
            if (primitive != null) return WireTypeModel(primitive.first, primitive.second, name)
            val classDeclaration = declaration as? KSClassDeclaration ?: return null
            if (classDeclaration.typeParameters.isNotEmpty()) return null
            val parcelable = name == "android.os.Parcelable" || classDeclaration.getAllSuperTypes().any {
                it.declaration.qualifiedName?.asString() == "android.os.Parcelable"
            }
            return if (parcelable) WireTypeModel(WireTypeKind.PARCELABLE, "parcelable-opaque<$name>!", name) else null
        }
    }
}

/** KSP represents enum arguments as symbols; keep validation and generation on the same parser. */
internal fun ipcStreamOverflowName(function: KSFunctionDeclaration): String? {
    val annotation = function.annotations.firstOrNull { it.shortName.asString() == "IpcStream" } ?: return null
    val value = annotation.arguments.firstOrNull { it.name?.asString() == "overflowPolicy" }?.value ?: return "ERROR"
    return when (value) {
        is KSType -> value.declaration.simpleName.asString()
        is KSDeclaration -> value.simpleName.asString()
        is KSName -> value.asString().substringAfterLast('.')
        else -> value.toString().substringAfterLast('.')
    }
}
