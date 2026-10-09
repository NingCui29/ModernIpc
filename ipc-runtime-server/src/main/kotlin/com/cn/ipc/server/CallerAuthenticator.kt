package com.cn.ipc.server

import android.os.Binder
import android.os.Process
import android.content.pm.PackageManager

/**
 * 调用方身份信息数据类，用于封装调用方的基础身份与鉴权数据。
 *
 * @property uid 调用方进程的 UID（User ID）
 * @property pid 调用方进程的 PID（Process ID）
 * @property packages 调用方 UID 关联的所有的包名集合
 * @property sessionId 当前的会话 ID
 */
data class CallerIdentity(
    val uid: Int,
    val pid: Int,
    val packages: Set<String>,
    val sessionId: Long
)

/**
 * 安全网关：验证调用方的身份并进行粗粒度的权限校验。
 * 作为跨进程通信的第一道防线，确保调用的合法性。
 */
class CallerAuthenticator(private val context: android.content.Context) {

    /**
     * 对调用方进行身份认证，获取其身份信息。
     * 
     * @param sessionId 本次连接的会话 ID，默认值为 0L
     * @return 封装了调用方信息的 [CallerIdentity] 实例
     */
    fun authenticate(sessionId: Long = 0L): CallerIdentity {
        val uid = Binder.getCallingUid()
        val pid = Binder.getCallingPid()
        
        // 相同 UID 的调用无需进一步查询包名；调用方可以处于另一个进程
        if (uid == Process.myUid()) {
            return CallerIdentity(uid, pid, setOf(context.packageName), sessionId)
        }
        
        // 查询该 UID 对应的所有包名
        val pm = context.packageManager
        val packages = pm.getPackagesForUid(uid)?.toSet() ?: emptySet()
        
        return CallerIdentity(uid, pid, packages, sessionId)
    }

    /**
     * 校验调用方是否拥有调用某个服务某个操作的权限
     */
    fun authorize(caller: CallerIdentity, serviceId: Int) {
        if (caller.uid == Process.myUid()) return
        if (caller.packages.isEmpty() ||
            context.packageManager.checkSignatures(Process.myUid(), caller.uid) != PackageManager.SIGNATURE_MATCH
        ) {
            throw SecurityException("Caller UID ${caller.uid} cannot access service $serviceId")
        }
    }

    /**
     * 直接校验当前 Binder 调用方，避免构造未使用的身份信息。
     * 不同 UID 的调用仍在每次事务中实时检查包名和签名。
     */
    fun authorizeCurrentCaller(serviceId: Int) {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return

        val pm = context.packageManager
        if (pm.getPackagesForUid(uid).isNullOrEmpty() ||
            pm.checkSignatures(Process.myUid(), uid) != PackageManager.SIGNATURE_MATCH
        ) {
            throw SecurityException("Caller UID $uid cannot access service $serviceId")
        }
    }
}
