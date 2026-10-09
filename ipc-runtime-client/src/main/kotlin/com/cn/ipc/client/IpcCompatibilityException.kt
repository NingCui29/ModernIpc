package com.cn.ipc.client

/** Permanent incompatibility of the requested wire contract, not a connection retry signal. */
class IpcCompatibilityException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
