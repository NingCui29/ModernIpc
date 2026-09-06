package com.cn.ipc.client2

import android.graphics.Color
import com.cn.ipc.client.common.BaseClientActivity

/**
 * 客户端 2 应用程序主 Activity。
 * 作为验证接收端，实时观察是否能够接收到来自客户端 1 的广播与定向消息。
 */
class Client2Activity : BaseClientActivity() {
    override val clientId: String = "client_2"
    override val clientName: String = "客户端 2"
    override val themeColor: Int = Color.parseColor("#004D40") // 翡翠深绿
    override val isSenderRole: Boolean = false
}
