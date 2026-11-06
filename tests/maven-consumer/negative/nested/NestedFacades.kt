package com.modernipc.consumer.negative

import com.cn.ipc.annotations.IpcDirect
import com.cn.ipc.annotations.IpcFacade

object Accounts {
    @IpcFacade(serviceId = 7901)
    interface Service {
        @IpcDirect(transaction = 10)
        fun echo(data: String): String
    }
}

object Devices {
    @IpcFacade(serviceId = 7902)
    interface Service {
        @IpcDirect(transaction = 10)
        fun echo(data: String): String
    }
}
