package com.cn.ipc

import android.os.Parcelable
import android.os.Parcel
import kotlinx.parcelize.Parcelize
import java.security.MessageDigest
import java.util.Collections

/** Optional features negotiated by intersection during the unchanged handshake envelope. */
object IpcCapabilities {
    const val SCHEMA_CHECKED_SERVICES: Long = 1L
    const val STREAM_TERMINALS: Long = 2L
    const val SUPPORTED: Long = SCHEMA_CHECKED_SERVICES or STREAM_TERMINALS
}

/** The local expectation is snapshotted at construction; later changes to the input map are ignored. */
data class ClientServiceSchema(
    val descriptor: String,
    val contractVersion: Int,
    val methodSignatures: Map<Int, String>
) : Parcelable {
    val stableMethodSignatures: Map<Int, String> = Collections.unmodifiableMap(HashMap(methodSignatures))

    /** Computed once, never on the per-call cache lookup. Length framing prevents ambiguous joins. */
    val fingerprint: String = MessageDigest.getInstance("SHA-256").digest(buildString {
        append(descriptor.length).append(':').append(descriptor).append(':').append(contractVersion)
        stableMethodSignatures.toSortedMap().forEach { (transaction, signature) ->
            append(':').append(transaction).append(':').append(signature.length).append(':').append(signature)
        }
    }.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(descriptor)
        parcel.writeInt(contractVersion)
        parcel.writeInt(stableMethodSignatures.size)
        stableMethodSignatures.forEach { (transaction, signature) ->
            parcel.writeInt(transaction)
            parcel.writeString(signature)
        }
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<ClientServiceSchema> = object : Parcelable.Creator<ClientServiceSchema> {
            override fun createFromParcel(parcel: Parcel): ClientServiceSchema {
                val descriptor = requireNotNull(parcel.readString())
                val version = parcel.readInt()
                val count = parcel.readInt()
                require(count in 0..65535) { "Invalid schema transaction count" }
                val signatures = HashMap<Int, String>(count)
                repeat(count) { signatures[parcel.readInt()] = requireNotNull(parcel.readString()) }
                return ClientServiceSchema(descriptor, version, signatures)
            }

            override fun newArray(size: Int): Array<ClientServiceSchema?> = arrayOfNulls(size)
        }
    }
}

@Parcelize
data class ServiceSchema(
    val serviceId: Int,
    val apiVersion: Int,
    val minSupportedClientVersion: Int,
    val descriptor: String,
    val methodSignatures: Map<Int, String>
) : Parcelable
