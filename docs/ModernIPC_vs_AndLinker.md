# ModernIpc 与 AndLinker 详细对比

发布版3.0.0-rc.1的产物与构建身份见[发布记录](releases/v3.0.0-rc.1/build-result.json)。下文设备测量与门禁仍属于各自历史APK；发布版设备验收NOT_RUN。

对比基准：ModernIpc 为本仓库 2026-10-09 工作区源码；AndLinker 固定为已核对的[上游提交 7ae01bfd](https://github.com/codezjx/AndLinker/tree/7ae01bfd87ddcf021319148a8727a95345cf350e)。本文负责架构、调用语义及迁移差异。性能样本与APK统一见[性能报告](benchmarks/Performance_Report.md)，功能验收见[回归报告](benchmarks/Regression_Report.md)，四场景构建与设备状态见[多App报告](benchmarks/Multi_App_Report.md)。当前新APK没有新增AndLinker配对或设备性能结果。

模块与调用链见[架构分析](ModernIPC_Architecture.md)，优化推理与历史变体见[优化分析](ModernIPC_Optimization_Analysis.md)。

## 1. 接口模型与实现对比

ModernIpc 面向 Kotlin suspend/Flow 接口、跨应用消息中枢和统一连接管理。AndLinker 面向 Java 接口代理、同步或回调式调用，并提供 RxJava 适配。两者都基于 Android Binder；以下差异来自指定版本的实际调用链。

| 维度 | ModernIpc 当前源码 | AndLinker 7ae01bfd |
| --- | --- | --- |
| 接口与生成 | IpcFacade 配合 IpcAsync、IpcDirect、IpcStream、IpcOneway，KSP 生成 ClientAdapter、ServerStub 与逐事务 Schema。Broker 使用 AIDL，业务使用生成的 Parcel/Binder 协议。 | RemoteInterface 配合 Java Proxy，运行时解析并缓存 ServiceMethod；服务端缓存 MethodExecutor，底层使用 ITransfer。 |
| 同步调用 | IpcDirect 在一次同步 Binder 事务中返回；客户端拒绝主线程调用，服务端在 Binder 分发线程执行。Broker 握手、冷发现和流订阅仍使用同步事务；冷发现由专用 worker 执行，同步解析冷缓存在 Main 上拒绝。 | RemoteCall.execute 执行同步 ITransfer 调用；调用线程等待远端返回。 |
| 异步调用 | suspend 使用单向请求加独立回调 Binder，PendingCallRegistry 恢复 continuation；服务端由 IpcRequestTracker 管理业务 Job。 | enqueue 在线程池执行原有远程调用，再交付 Callback；提供 RxJava 1/2 CallAdapter。 |
| 请求截止 | 生成 Async 默认 30 秒入口预算覆盖可取消冷发现和 pending，可由 Controller 配置；已登记请求在截止时向捕获的原 Binder 发送取消。Flow 仅限制发现等待；Direct 业务与已开始的同步 transact 不会被强制中断。 | RemoteCall 没有等价的远端 Job 截止机制；应用需设计超时与业务取消。 |
| 取消与清理 | Async 按 UID、回调 Binder、请求 ID 定位远端 Job；服务端 dispose 取消请求、订阅及宿主 scope。取消不回滚副作用，也不保证停止非协作业务。 | RemoteCall.cancel 设置本地标志，没有对应的远端取消事务。RemoteCallbackList 管理 Callback Binder，不代表远端业务任务已取消。 |
| 持续事件 | IpcStream 按连接代次创建 Flow observer；有 NEXT、COMPLETE、ERROR。默认缓冲满时显式失败，CONFLATE 允许合并状态值。断线可重订阅，旧代次事件被过滤。 | 支持跨进程 Callback；持续事件的完成、错误、背压与重订阅由应用接口定义。Rx 单次调用适配不能直接等同于 Flow 订阅。 |
| 单向调用 | IpcOneway 使用 FLAG_ONEWAY；提交成功不等于业务完成，没有回执或远端取消。 | OneWay 通过单向 ITransfer 执行，同样没有业务完成回执。 |
| 类型支持 | WireTypeModel 统一校验、生成 codec 与 Schema：非空 String、Int、Long、Boolean、ByteArray、实际实现 Parcelable 的无泛型类型；Flow 元素另支持 Float、Double。拒绝任意 DTO、可空类型、泛型及未支持返回类型。Async Unit 暂不支持，Oneway 返回 Unit。 | README 列出基本类型、String、CharSequence、Parcelable、List、Map 及 In/Out/Inout；具体嵌套组合与参数方向应编译、实测。 |
| 协议发现 | 主版本校验与实际能力交集；严格发现检查 serviceId、版本区间、descriptor、客户端要求的逐事务签名。签名包含调用方式、事务关联、codec 与回复/终态封装。 | 绑定指定 Service 获得 ITransfer；没有等价的 serviceId、逐事务 Schema 与版本区间协商。 |
| 热路径缓存 | 服务 Binder 按 generation、serviceId、minApiVersion、Schema fingerprint 隔离；命中时不重复元数据 RPC 或摘要计算。同一 Controller 同键冷发现共享结果；Schema 构造时冻结，校验与写入 Parcel 使用同一快照。 | ServiceMethod 与 MethodExecutor 均缓存；动态代理和反射不能被描述为每次重新扫描接口。 |
| 生命周期 | close 关闭当前连接并允许重连；dispose 永久释放且拒绝新连接。关闭置换/作废发现容器并唤醒 waiter，不 join 冷发现 RPC；晚结果不进入新容器。控制 scope 独立于 owner，owner Job 最终完成后自动 dispose。覆盖绑定超时、onNullBinding、onBindingDied 与 Binder 死亡。 | ServiceConnection 通知连接与断开；未见内建退避重连循环，宿主负责绑定、解除和重连策略。 |
| 重连与设备策略 | 默认最多 10 次，基值从 500 ms 增长并封顶 30 s，另加抖动。仍受小米跨包唤醒及后台策略约束。 | 宿主自行实现重连；Binder 绑定仍受系统和厂商策略约束。 |
| 并发资源 | 同一进程/classloader 的握手和发现各有独立4个worker、无排队，饱和时拒绝。每 Stub 默认 128 个 Async 在途请求，超限返回 SERVER_BUSY。Direct/Oneway 不经过该限额，Flow 没有订阅数量上限；BoundedDispatcher 未接入默认链路。 | Dispatcher 的 ThreadPoolExecutor 最大线程数为 Integer.MAX_VALUE；突发 enqueue 的资源上限需由应用控制。 |
| 鉴权 | CallerAuthenticator 检查 UID 与服务端同签名；跨包示例另有 signature 绑定权限。Parcelable 请求先鉴权再反序列化。 | ITransfer 分发未见强制同签名校验；由宿主 exported、permission 与业务鉴权决定。 |
| 重试与可靠投递 | idempotent 未驱动业务自动重放；重连不等于 RPC 重试。流重订阅不补历史，Hub没有持久投递保证；案例仅提供Activity内有限确认/去重。 | 重试、事件恢复与持久可靠投递由宿主实现。 |
| 双栈与适配 | ModernIpcRouter 固定选择 Modern，AndLinker 回退未接入；RxIpcExtensions 未启用。 | 可独立使用；本仓库没有已验证的自动双栈切换。 |

ModernIpc 核对入口：[连接与缓存](../ipc-runtime-client/src/main/kotlin/com/cn/ipc/client/IpcConnectionController.kt)、[客户端生成器](../ipc-compiler/src/main/kotlin/com/cn/ipc/compiler/ClientAdapterGenerator.kt)、[服务端生成器](../ipc-compiler/src/main/kotlin/com/cn/ipc/compiler/ServerStubGenerator.kt)、[统一类型模型](../ipc-compiler/src/main/kotlin/com/cn/ipc/compiler/WireTypeModel.kt)、[Schema 契约](../ipc-contract/src/main/kotlin/com/cn/ipc/ServiceSchema.kt)、[Broker 校验](../ipc-runtime-server/src/main/kotlin/com/cn/ipc/server/IpcBrokerStub.kt)、[请求跟踪](../ipc-runtime-server/src/main/kotlin/com/cn/ipc/server/IpcRequestTracker.kt)。

AndLinker 核对入口：[README](https://github.com/codezjx/AndLinker/blob/7ae01bfd87ddcf021319148a8727a95345cf350e/README.md)、[AndLinker.java](https://github.com/codezjx/AndLinker/blob/7ae01bfd87ddcf021319148a8727a95345cf350e/andlinker/src/main/java/com/codezjx/andlinker/AndLinker.java)、[RemoteCall.java](https://github.com/codezjx/AndLinker/blob/7ae01bfd87ddcf021319148a8727a95345cf350e/andlinker/src/main/java/com/codezjx/andlinker/RemoteCall.java)、[ITransfer.java](https://github.com/codezjx/AndLinker/blob/7ae01bfd87ddcf021319148a8727a95345cf350e/andlinker/src/main/java/com/codezjx/andlinker/ITransfer.java)、[Dispatcher.java](https://github.com/codezjx/AndLinker/blob/7ae01bfd87ddcf021319148a8727a95345cf350e/andlinker/src/main/java/com/codezjx/andlinker/Dispatcher.java)、[LinkerBinderImpl.java](https://github.com/codezjx/AndLinker/blob/7ae01bfd87ddcf021319148a8727a95345cf350e/andlinker/src/main/java/com/codezjx/andlinker/LinkerBinderImpl.java)。

## 2. 架构与调用链

~~~mermaid
flowchart LR
    subgraph M["ModernIpc：编译期生成与服务发现"]
        M1["Kotlin 业务接口"] --> M2["KSP ClientAdapter + Schema"]
        M2 --> MC["Controller：绑定、代次、Binder 缓存"]
        MC -->|"可取消等待 / 同键共享"| MW["ServiceDiscoveryCache：进程内最多4 worker"]
        MW -->|"锁外同步冷发现"| MB["AIDL Broker：鉴权与 Schema 校验"]
        MB --> MS["生成 ServerStub"]
        MC -->|"缓存命中，业务 transact"| MS
        MS -->|"Async"| MJ["IpcRequestTracker / 业务 Job"]
        MJ --> MR["独立回调 Binder / continuation"]
        MS -->|"Direct"| MD["业务执行 / 同步 reply"]
        MS -->|"Stream"| MF["订阅 Job / observer 终态"]
    end
    subgraph A["AndLinker：运行时解析与缓存"]
        A1["Java 业务接口"] --> A2["Proxy / 缓存 ServiceMethod"]
        A2 --> A3["RemoteCall"]
        A3 -->|"execute"| A4["ITransfer"]
        A3 -->|"enqueue"| AQ["Dispatcher"]
        AQ --> A4
        A4 --> A5["LinkerBinderImpl / 缓存 MethodExecutor"]
        A5 --> A6["业务对象 / 同步 Response"]
        A6 --> A7["调用方结果或 Callback"]
    end
~~~

ModernIpc Async 的请求经过“单向业务事务 → 服务端 Job → 回调事务 → 协程恢复”。AndLinker enqueue 在线程池中执行同步远程调用，业务结果仍从原事务 reply 返回。两者的异步入口相似，取消、截止、调度和 Binder 往返结构有实际差异。

### 严格发现：冷路径与热路径

~~~mermaid
sequenceDiagram
    participant C as ClientAdapter
    participant K as Controller
    participant W as 发现 worker
    participant B as Broker
    participant S as ServerStub
    C->>K: 指定代次、serviceId、最低版本与 Schema
    alt 无可用缓存
        K->>W: 同键共享 / 等待可取消 / 有限预算
        W->>B: getServiceSchema(serviceId)
        B-->>W: 版本区间、descriptor、事务签名
        W->>W: 核对连接与需求 / 本地检查版本与事务
        W->>B: getServiceChecked(minApiVersion, 冻结 Schema)
        B->>B: 再次鉴权并检查版本、descriptor 与事务
        B-->>W: 已验证业务 Binder
        W->>W: 核对连接与需求 / 发布到原容器
        W-->>K: 发现结果
    else 相同键且 Binder 存活
        K->>K: 读取缓存，不请求元数据
    end
    K-->>C: 业务 Binder
    C->>S: 按生成协议调用
~~~

Async 在调用入口建立预算再执行上述 await，pending 使用 WithinDeadline 延续外层预算；该底层 primitive 不自行创建截止，手写调用者必须提供外层截止。Flow 只限制上述发现阶段，不给长期业务流设置整段超时。最后 waiter 退出后放弃发现，关闭作废旧容器；已开始的 Binder 不会被强制中断，取消检查到下一次 RPC 仍有竞态。同步冷解析在 Main 上 fail-fast，Oneway 的 Main 入口先挂起预热。详见[SDK 接入](ModernIPC_SDK_Guide.md)与[本轮报告](benchmarks/Regression_Report.md)。

握手能力为客户端请求位与服务端支持位的交集，定义了 SCHEMA_CHECKED_SERVICES、STREAM_TERMINALS。新生成客户端要求严格 Schema 发现，旧 Broker 不支持时拒绝，不能解释成自动降级。服务端要求客户端使用的事务子集匹配，允许在版本区间内新增其他方法。客户端版本须落在服务端最低兼容版本与 API 版本之间；旧 getService 无法证明客户端版本，服务最低兼容版本高于 1 时拒绝该入口。

Schema 检查 descriptor、事务号、参数/返回 codec、调用模式、回复封装和取消/退订关联。Parcelable 以类名和 opaque codec 标识，其字段布局未递归证明。apiHash、sessionId、nonce 未形成业务帧校验或会话绑定；maxInlinePayloadBytes 仍为声明值，未形成发送限额或大数据方案。

阶段 3 初次小米探针发现，Broker 业务拒绝抛出的 RemoteException 不能通过 Java Binder 的 Parcel.writeException 正常编码，导致客户端没有拿到预期拒绝原因。修复改用可传输的 IllegalArgumentException，鉴权仍保留 SecurityException；修复后的 compat3 已通过完整设备门禁。客户端协议不兼容使用 IpcCompatibilityException。

### 流终态与取消

| 情况 | ModernIpc 当前处理 | AndLinker 对应边界 |
| --- | --- | --- |
| 数据 | observer code 1，保持原裸 payload；连接实例与代次有效时接收。 | Callback 方法传数据，协议由应用定义。 |
| 自然完成 | code 2 COMPLETE；服务端与客户端终态各只接受一次，collector 正常结束。 | 应用定义完成回调，没有相同的通用终态帧。 |
| 业务异常 | code 3 ERROR 携带 String；客户端以异常结束流。 | 应用定义错误回调与异常传播。 |
| 慢消费者 | 默认 ERROR 显式报溢出；CONFLATE 合并中间状态。 | 应用或 Rx 层决定缓冲、丢弃和背压。 |
| collector 取消 / observer 死亡 | 安静退订并取消远端订阅 Job，不附加远端错误。 | RemoteCallbackList 管理死亡 Callback，业务任务取消需应用实现。 |
| 服务端 dispose / scope 已取消 | 拒绝新任务并终结已有协作任务；错误终态有兜底，取消异常不误报 COMPLETE。 | 宿主定义任务生命周期与终态。 |
| 断线 / 重连 | 旧 observer 失效，向捕获的旧 Binder/subId 退订，新代次重订阅；不补断线事件。 | 宿主定义绑定、注册和事件恢复。 |

Async 保持旧请求布局。原生安全参数在识别尾部回调后校验，用原有 String 错误回包；Parcelable 仍先鉴权再反序列化。坏 token、截断请求及无法安全识别回调的非法帧由客户端截止兜底，不通过猜测 Binder 帧尾绕过鉴权。当前生成 Async 的入口截止还覆盖冷发现等待，不能强行停止同步 Binder 调用。原始终态实测范围见[阶段 2 报告](benchmarks/Regression_Report.md)，冷发现修复另按[本轮报告](benchmarks/Regression_Report.md)验收。

## 3. 跨应用消息业务流程

下图描述 ModernIpc 消息中枢示例。AndLinker 可以承载同类接口，但注册、路由、订阅恢复和确认由宿主实现，库中没有等价消息中枢。

~~~mermaid
sequenceDiagram
    participant A as 客户端 A
    participant H as IMessageHubService
    participant R as 中枢注册与路由
    participant F as 客户端 B 的 Flow
    A->>H: registerClient / 单向登记业务身份
    H->>R: 校验并登记客户端
    F->>H: observeMessages / 生成层传入 observer
    H-->>F: 返回订阅标识 subId
    A->>H: sendMessage(fromClientId, targetScope, content)
    H->>R: 校验身份并查找接收方
    R-->>F: 发布消息 / NEXT
    R-->>H: 路由处理结果
    H-->>A: Async 请求结果
    Note over A,F: NEXT 与回执的观察次序不保证，回执不证明消费或持久化
    alt 连接变化
        F->>H: 向旧 Binder/subId 退订
        F->>H: 新代次重新订阅
        Note over R,F: 没有历史重放或消费确认
    else 永久释放
        F->>H: 取消订阅
        Note over H,F: dispose 结束本地流并回收协作任务
    end
~~~

终态处理与消息可靠投递属于不同层次。示例 SharedFlow 的 DROP_OLDEST 可能在进入 IPC 前丢值；客户端 CONFLATE 也允许合并状态。MessageEnvelope 已有服务端内存计数生成的消息 ID；可靠投递仍需持久化、接收确认、重放和去重。COMPLETE 或取消清理不能证明这些能力。

## 4. 正确性与资源成本

ModernIpc 的生命周期、请求唯一终结、流终态与严格兼容会引入状态管理；这些成本需要和业务需要一起评估。已实现的 cold discovery、pending、发送前guard、握手隔离及其验收边界由[架构](ModernIPC_Architecture.md)和[回归报告](benchmarks/Regression_Report.md)统一维护。

| 行为 | 成本与限制 |
| --- | --- |
| Async远端协作取消 | requestId、pending、Job、许可、死亡监听与独立callback；取消不能回滚副作用。 |
| 严格兼容 | 首次Schema+checked两个Broker RPC；热缓存不重复，Parcelable字段不递归校验。 |
| 流终态与拥塞显式错误 | observer、订阅Job、缓冲与唯一终态；源SharedFlow丢值仍不能补回。 |
| 有界阻塞容量 | 握手和发现各独立4 worker无队列；已开始Binder不能强制中断，饱和拒绝。 |
| 生命周期回收 | close允许重连、dispose永久结束；晚结果按原代次拒绝，非协作远端可能继续。 |

历史最终52/4与64/5只属于各自APK；当前握手9+既有64为73/6验收目标，尚未达到。多App场景另有业务ACK和64条Activity内去重，不构成持久可靠投递。

## 5. 性能对比与为什么 AndLinker 更快

### 同负载历史结果

2026-09-29 小米925c23bb、Debug、同包分进程、16/1024字符原样echo、每档50次预热、四轮各1000次串行调用，均返回正确、失败0。下表为各轮P50的中位数，单位ms：

| 入参 | ModernIpc suspend | AndLinker同步 | AndLinker enqueue |
| ---: | ---: | ---: | ---: |
| 16字符 | 0.536 | 0.258 | 0.327 |
| 1024字符 | 0.682 | 0.232 | 0.353 |

该次前台echo中AndLinker较低。两边targetSdk为34/26，协议与调度不同；此结果不是当前APK、后台跨包或所有业务的固定速度排名。Modern APK `47BB67B492393E116B7239EFF712E0010C265D29B1CE0194896374D39C156E30`、And APK `CFD5EE622B80470771C20391065F29611F17C5C1ED5A9AE038E174D5E8101B25`；逐轮P99、CSV、补丁和方法集中见[性能报告](benchmarks/Performance_Report.md)。

### 热路径结构解释

ModernIpc Async写入requestId/响应Binder，发送单向请求，服务端Tracker申请permit、登记Job与死亡监听，再调度业务并通过独立callback事务返回，客户端摘除pending、解码和恢复协程。AndLinker同步通过一次带reply的ITransfer事务执行缓存MethodExecutor；enqueue把这条同步调用交给线程池，再投递默认主线程Callback。双方仍有codec成本，AndLinker的ServiceMethod/MethodExecutor已缓存，不是每次重新扫描接口。

额外事务、状态登记与调度能解释极轻echo的一部分差距，但主对比没有分段净成本实验，不能指定每项贡献。Modern热缓存不每次走Broker，同包echo同UID不反复查询跨包签名；恢复上下文由调用方选择，库没有额外强制一次切换。

后续历史调度消融和5C8请求trace支持调度/准入/恢复均有成本，死亡监听消融没有稳定收益。它们分别属于不同构建与时点，不能用于解释旧主对比的精确差额。完整诊断见[性能报告](benchmarks/Performance_Report.md)，实施取舍见[优化分析](ModernIPC_Optimization_Analysis.md)。

### 可选 Direct 与后续优化

ModernIpc显式Direct在一次同步Binder事务返回，客户端拒绝Main。9/29另一个同APK内部对比，16/1024字符四轮P50中位数为suspend 0.543/0.602ms、Direct 0.129/0.328ms。该APK为 `208343DE0AA7968919EE1F054B35372738BACD6FBA927A7D0653B9E6B34BBA1B`，不能与较早AndLinker样本混算新排名。

Direct省略Async协作取消与128在途许可，适合明确的短方法；真实Hub仍为Async。后续auth、pending、guard的局部CPU/分配减少未证明通用RTT收益；4494专用dispatcher两次同APK实验的小包结果仍有反向配对。数据集中在[性能报告](benchmarks/Performance_Report.md)，这里不重复历史逐轮结果。

## 6. 迁移映射与未实现边界

| 现有 AndLinker 用法 | ModernIpc 对应方式 | 迁移时需要处理 |
| --- | --- | --- |
| 直接返回 / execute | 明确的极短方法用 IpcDirect；挂起业务用 IpcAsync。 | Direct 在后台线程调用；生成 Async 默认 30 秒入口预算覆盖发现与 pending，业务可选更短超时。同步 transact 不可强制中断。 |
| enqueue / Rx 单次结果 | suspend 配合调用方协程上下文，或自行 Rx 适配。 | RxIpcExtensions 未启用；保留调度与错误处理语义。 |
| Callback 注册 | IpcStream Flow。 | 完成/错误、取消与溢出策略明确；重订阅不补历史值。 |
| OneWay | IpcOneway。 | 两库 wire 不兼容，需要重定事务号与 codec；无业务回执。Main 冷缓存须先挂起严格预热或改在后台同步解析。 |
| 动态代理服务 | 共享注解契约并生成双端 Schema。 | 检查版本区间、descriptor、事务签名；Parcelable 字段变化另做兼容验证。 |
| In/Out/Inout 或复杂集合 | 用支持类型重新设计请求与返回 DTO。 | 没有等价的参数方向协议，不支持类型不能依赖任意 Parcelable fallback。 |
| 自定义服务权限 | 同签名校验与 Manifest 权限。 | 独立签名第三方需要先实现新的授权模型。 |

当前仍未实现：Parcelable 字段级 Schema、会话与业务帧绑定、按方法 permission/requiredCapability 执行、内联大小限额和分块/FD 传输、Flow 数量上限、Direct/Oneway 总量限制、持久可靠消息、RPC 幂等重放、Rx 兼容层与自动双栈回退。独立 SubscriptionRegistry、FlowAdapterHelper、BoundedDispatcher 等辅助代码不能算作默认生成链路已经使用的能力。

迁移应逐方法验证参数方向、空值、Parcelable、异常、超时、断线、版本拒绝与进程死亡。两个库的 Binder 协议不同，AndLinker 代理不能直接指向 ModernIpc Broker。

## 7. 如何选择与继续测量

- 既有 Java/Rx 接口可继续评估 AndLinker，核对宿主权限、线程与异步资源上限；固定版本的缓存和同步链路已有源码依据。
- Kotlin suspend/Flow、共享签名应用群、需要统一连接与逐事务兼容检查时，可评估 ModernIpc，并为 SERVER_BUSY、截止、断线、溢出和不兼容拒绝建立业务处理。
- 可靠投递需要应用层持久化与确认；性能结论需要针对真实方法进行配对测量。

历史 echo 已统一设备、同包分进程、负载与采样次数，尚未统一 targetSdk、构建工具和异步调度模型。历史同APK变量配对分别记录局部分配与RTT；后续真实业务、生成代理整体和suspend/Direct都需绑定新的源码及安装包哈希。新的两库速度结论需要新的配对数据。测试继续限定小米设备，统一签名/包拓扑、前后台状态、输入与返回，报告成功吞吐、失败数、P50/P90/P99、CPU、温度、电量及重连结果。Oneway 应测服务端完成时间，不能用客户端提交时间替代。

### 当前待验收状态

4494调度实测之后新增的HandshakeExecutor已构建，402684版本五包安装哈希通过，但新增9项与完整回归因当时锁屏未运行。最新四场景版本22DAB065已五包构建，指定小米未连接、安装和设备验收NOT_RUN；旧版本通过数与性能不能移用。详见[验证索引](benchmarks/README.md)。

归总前原文在[归档ZIP](archive/pre-consolidation-2026-10-09.zip)。
