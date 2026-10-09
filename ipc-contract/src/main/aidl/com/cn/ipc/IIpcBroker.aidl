package com.cn.ipc;

import com.cn.ipc.ClientHello;
import com.cn.ipc.ProtocolInfo;
import com.cn.ipc.ClientServiceSchema;
import com.cn.ipc.ServiceSchema;

/**
 * IPC 核心 Broker 接口（AIDL）。
 *
 * 作为跨进程通信的中枢，所有客户端在使用具体业务服务前，
 * 必须先通过 handshake 完成协议协商，再通过 getService 获取目标服务的 Binder。
 */
interface IIpcBroker {

    /**
     * 执行握手协议协商。
     *
     * 客户端在绑定服务后应首先调用此方法，发送 ClientHello 完成版本与能力的双向协商。
     * 协商失败时返回可通过 Parcel 编码的 IllegalArgumentException。
     *
     * @param client 客户端握手请求，包含版本信息、包名及请求能力集合。
     * @return 服务端握手响应，包含协商后的协议版本、能力集合及本次会话 ID。
     */
    ProtocolInfo handshake(in ClientHello client) = 1;

    /**
     * 获取指定业务服务的 Binder 对象。
     *
     * 握手成功后，客户端通过此方法按 serviceId 向 Broker 请求具体业务服务的 Binder。
     * 若服务不存在或版本不满足要求，服务端将抛出 IllegalArgumentException。
     *
     * @param serviceId     目标服务的全局唯一 ID（对应 @IpcFacade.serviceId）。
     * @param minApiVersion 客户端要求的最低服务 API 版本，低于此版本时请求被拒绝。
     * @return 目标服务的 IBinder，客户端可将其转换为对应的 AIDL Stub。
     */
    IBinder getService(int serviceId, int minApiVersion) = 2;

    /** Metadata only; no business Binder is granted by this transaction. */
    ServiceSchema getServiceSchema(int serviceId) = 3;

    /** Acquire only after checking the client's concrete wire contract. */
    IBinder getServiceChecked(int serviceId, int minApiVersion, in ClientServiceSchema clientSchema) = 4;
}
