package com.cn.ipc.client1

import android.graphics.Color
import com.cn.ipc.client.common.BaseClientActivity

/**
 * 客户端 1 应用程序主 Activity。
 * 担当主动发起对照测试的核心角色。
 */
class Client1Activity : BaseClientActivity() {
    override val clientId: String = "client_1"
    override val clientName: String = "客户端 1"
    override val themeColor: Int = Color.parseColor("#1A237E") // 宝蓝色
    override val isSenderRole: Boolean = true // 显示对照测试控制台
}
