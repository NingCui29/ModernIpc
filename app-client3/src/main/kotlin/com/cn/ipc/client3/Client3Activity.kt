package com.cn.ipc.client3

import android.graphics.Color
import com.cn.ipc.client.common.BaseClientActivity

/**
 * 客户端 3 应用程序主 Activity。
 * 作为验证接收端，实时观察是否能够接收到来自客户端 1 的广播与定向消息。
 */
class Client3Activity : BaseClientActivity() {
    override val clientId: String = "client_3"
    override val clientName: String = "客户端 3"
    override val themeColor: Int = Color.parseColor("#BF360C") // 珊瑚深橙
    override val isSenderRole: Boolean = false
}
