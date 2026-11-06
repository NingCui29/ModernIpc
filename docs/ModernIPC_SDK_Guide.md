# ModernIpc SDK 接入与发布指南

接入与发布配置核对日期：2026-11-06；下方历史设备记录保留各自日期。本指南介绍共享契约、双端接入、生命周期、请求与 Flow 终态、诊断和 Maven 发布。模块关系与协议图见[架构](ModernIPC_Architecture.md)，四个多 App 案例见[业务指南](ModernIPC_Business_Interaction_Guide.md)。

**历史小米验收状态（2026-10-22）**：依赖导出修复后的未发布 demo APK SHA-256 为 `D0E76D896BDC3F0C9F01E14073F34201FFE79BFC6B07106E7885EA4181F28A9A`，2026-10-22 在小米 `925c23bb` 通过原框架 73 项、独立 Async scope 4 项、新增业务取消/超时错误终态 9 项及同 UID 鉴权微测；见[本轮 summary](benchmarks/runs/2026-10-22-xiaomi14ultra/engineering-regression2/summary.json)。独立 Maven metadata/POM-only 消费和 27 项 host 门禁也已通过；见[修复审计](ModernIPC_Engineering_Audit.md#10-本轮修复验收)。性能对照仍绑定此前 `4F7ED...` APK，未转用于本轮新包。固定 `v3.0.0-rc.1` 标签与四个多 App 业务场景的设备验收仍保持各自边界。构建、回归与测量范围分别见[多 App 报告](benchmarks/Multi_App_Report.md)、[回归报告](benchmarks/Regression_Report.md)和[性能报告](benchmarks/Performance_Report.md)。

**源码修复范围**：2026-10-30 修正生成参数隔离、死亡重连预算、Flow 取消分类和辅助订阅工具；详见[修复审计第 12 节](ModernIPC_Engineering_Audit.md#12-2026-10-30生成代码重连与订阅修复未发布)。这些变化已纳入 `v3.0.0-rc.3`，不包含在旧 rc.2 制品中。最终 rc.3 发布 APK 的设备验收仍为 NOT_RUN，详见[发布记录](releases/v3.0.0-rc.3/README.md)。

## 0. 依赖与生成配置

仓库内示例直接使用 Gradle project 依赖。外部工程默认从公开 `maven` 分支下载，版本为 `3.0.0-rc.3`。在宿主 `settings.gradle.kts` 配置以下仓库，无需填写用户名或 Token：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository {
                maven {
                    name = "ModernIpcPublic"
                    url = uri("https://raw.githubusercontent.com/NingCui29/ModernIpc/maven")
                }
            }
            filter { includeGroup("com.modernipc") }
        }
    }
}
```

`exclusiveContent` 将整个 `com.modernipc` group 固定到此仓库，避免旧 GitHub Packages 仓库先返回 401。迁移时删除旧的 ModernIPC Packages 仓库项；如果它仍用于其他 group，保留其独立配置即可。源码托管在 GitHub 与使用 GitHub Packages 是两回事：这里读取的是公开分支中的 Maven 文件，不是 Packages 服务，也不是 Maven Central。

匿名渠道的发布和远端核验状态见[rc.3 发布记录](releases/v3.0.0-rc.3/README.md)，维护流程见[第 10 节](#10-maven-构建发布与外部消费)。

定义新业务接口的 Android library 必须同时配置 KSP 和接口使用到的运行时依赖；客户端与服务端共享该契约及生成代码。依赖已有 `ipc-api` 只得到仓库内契约，不会为其他模块的新接口自动生成代理。以下是自定义契约模块的依赖骨架：

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}
val ipcVersion = providers.gradleProperty("ipcVersion").get()
dependencies {
    // 生成代码的公开 ABI 引用契约、双端 runtime 与协程类型，因此必须用 api 导出。
    api("com.modernipc:ipc-annotations:$ipcVersion")
    api("com.modernipc:ipc-contract:$ipcVersion")
    api("com.modernipc:ipc-runtime-client:$ipcVersion")
    api("com.modernipc:ipc-runtime-server:$ipcVersion")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    ksp("com.modernipc:ipc-compiler:$ipcVersion")
}
```

生成 Adapter/Stub 的构造签名与父接口会引用 runtime 与 `kotlinx.coroutines` 类型，所以契约模块必须用 `api` 导出这些依赖；用 `implementation` 会让只依赖该契约模块的客户端编译失败。
`ipc-annotations` 为 `SOURCE` 保留，导出它主要是接入便利性（编写新契约时需要注解），本身不构成二进制 ABI 要求。

在宿主 `gradle.properties` 设置版本：

```properties
ipcVersion=3.0.0-rc.3
```

本次分发渠道调整复用现有 rc.3 制品，不改动既有版本字节、Kotlin 包名、Android namespace 或 Binder descriptor；旧 rc.1/rc.2 发布物保持原样。

插件版本与 Android 配置仍需在宿主工程中声明；上例省略这些配置。仓库当前版本来源为 [libs.versions.toml](../gradle/libs.versions.toml)：AGP 8.2.0、Kotlin 1.9.22、KSP 1.9.22-1.0.17、协程 1.7.3、minSdk 21、compile/targetSdk 34，构建使用 JDK 17。它们是当前仓库配置，不表示所有外部工程组合已验证。

## 1. 定义共享契约

下面是[IMessageHubService](../ipc-api/src/main/kotlin/com/cn/ipc/api/hub/IMessageHubService.kt)的摘录；完整接口另有注销、状态查询和心跳。

```kotlin
@IpcFacade(serviceId = 2001, minApiVersion = 2, contractVersion = 2)
interface IMessageHubService {
    @IpcOneway(transaction = 1)
    fun registerClient(clientId: String, clientName: String)

    @IpcAsync(requestTransaction = 10, cancelTransaction = 11)
    suspend fun sendMessage(fromClientId: String, targetScope: String, content: String): String

    @IpcStream(subscribeTransaction = 20, unsubscribeTransaction = 21)
    fun observeMessages(clientId: String): Flow<String>
}
```

minApiVersion要求服务端API下限；contractVersion是客户端自身契约版本，默认0沿用minApiVersion，不是应用versionCode。KSP生成ClientAdapter、ServerStub、IpcSchema；后者提供DESCRIPTOR、CLIENT_CONTRACT_VERSION、METHOD_SIGNATURES、CLIENT_SCHEMA，宿主仍手工注册。

`@IpcFacade` 只支持包级顶层接口；嵌套门面现在会收到明确 KSP 错误，请移到包级并使用唯一类名。业务参数可使用 `requestId`、`controller`、`data` 等名称，生成 Adapter 保留公开参数名并隔离传输局部变量，Stub 使用位置别名解码。受旧生成器同名覆盖影响的接口需双端重新生成升级；wire schema 相同并不代表旧代码的参数值错误已修复。

每方法只选一种协议，满足suspend/Flow/Unit约束，所有相关事务码在1..0x00ffffff且接口内唯一。不同业务Binder可复用事务码；serviceId需统一分配，Registry拒绝重复注册。

| 位置 | 支持的非空类型 |
| --- | --- |
| Async参数/返回、Oneway参数、Stream参数 | String、Int、Long、Boolean、ByteArray、非泛型Parcelable |
| Stream item | 上述类型及Float/Double |
| Direct参数/返回 | 五种基本类型，不含Parcelable |
| Oneway返回 | Unit |

nullable、泛型/集合、Async Unit、其他位置Float/Double、接口属性、泛型接口/方法、扩展接收者及vararg暂不支持。类型别名及平台空值类型没有明确支持保证。Parcelable必须实现android.os.Parcelable；Schema仅记录其完整类名，字段、CREATOR和内部布局为opaque，仍需双方验证DTO兼容性。普通data class不会自动成为Parcelable。

## 2. 服务端注册

继承IpcBrokerService，在onCreateRegistry()构造生成Stub、实现完整业务接口并注册。下面仅为注册骨架，省略方法实现：

```kotlin
override fun onCreateRegistry(): DefaultIpcServiceRegistry {
    val registry = super.onCreateRegistry()
    val stub = object : IMessageHubServiceServerStub(this) {
        override val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        // 实现完整接口的方法，转发到业务对象。
    }
    registry.register(RegisteredService(
        serviceId = 2001,
        apiVersion = 2,
        apiHash = "hub_v2", // 人工标识，实际兼容比较生成的Schema。
        binder = stub,
        minSupportedClientVersion = 2
    ))
    return registry
}
```

可运行源码：[ServerBrokerService](../app-server/src/main/kotlin/com/cn/ipc/server/app/ServerBrokerService.kt)、[业务实现](../app-server/src/main/kotlin/com/cn/ipc/server/app/MessageHubServiceImpl.kt)。Stub应拥有可释放scope，不能随意共用无关组件的scope，因为dispose会取消它。

生成Stub实现IpcServiceSchemaProvider/IpcServiceLifecycle。Service.onDestroy通过Registry.dispose释放请求、订阅及scope，已释放Stub拒绝新任务。本次候选新增的 asyncCoroutineScope 默认回落 coroutineScope；分别覆盖时 dispose 会取消两者，宿主还需关闭自建执行器，配置见[第 9 节](#9-可选短任务调度配置)。activeRequestCount/activeSubscriptionCount在Job实际完成后更新，计数不是取消ACK。

floor即minSupportedClientVersion，范围1..apiVersion。Hub/User设floor2，legacy getService拒绝其旧客户端；缺少SchemaProvider也不能严格发现。Manifest配置exported/signature权限及客户端可见性仍必要，每事务仍鉴权，示例另限制UID对应clientId；Registry的requiredCapability/permission字段尚无通用ACL实现。

## 3. 客户端连接与调用

传入明确owner scope，例如lifecycleScope。Controller建立独立IO控制scope，使owner已取消后的释放仍可启动。

```kotlin
val controller = IpcConnectionController(
    context = this,
    targetIntent = Intent("com.cn.ipc.ACTION_BROKER_SERVICE").apply {
        component = ComponentName("com.cn.ipc.server.app",
            "com.cn.ipc.server.app.ServerBrokerService")
    },
    scope = lifecycleScope,
    clientPackage = packageName,
    bindingTimeoutMs = 5_000,
    defaultCallTimeoutMs = 30_000
)
val hub = IMessageHubServiceClientAdapter(controller)
lifecycleScope.launch {
    val connection = controller.awaitConnected() // 默认等待5秒；Async不自动等待连接。
    // Oneway使用同步解析：Main首次调用前，以可取消接口预热同一Schema键。
    controller.awaitServiceBinderForConnection(
        connection, serviceId = 2001, minApiVersion = 2,
        expectedSchema = IMessageHubServiceIpcSchema.CLIENT_SCHEMA
    )
    hub.registerClient("client_1", "客户端1")
    val ack = withTimeout(5_000) {
        hub.sendMessage("client_1", "client_2", "你好")
    }
    // 按业务处理ack，不代表目标UI已消费。
}
val subscription = hub.observeMessages("client_1")
    .onEach { raw -> /* 解析并处理 */ }
    .catch { error -> /* 处理远端错误、溢出或永久释放 */ }
    .launchIn(lifecycleScope)
```

需相应Android/协程/Flow/lifecycle及生成Schema导入。界面宿主示例见[BaseClientActivity](../demo-client-common/src/main/kotlin/com/cn/ipc/client/common/BaseClientActivity.kt)：首次/重连先挂起预热严格Hub Schema，核对连接快照后注册和订阅，状态变化取消旧注册任务。注册是Oneway，无完成回执；要求确认时另设有结果方法。

适配器自动传CLIENT_SCHEMA。首次要求握手SCHEMA_CHECKED_SERVICES能力，再getServiceSchema/getServiceChecked，两侧检查descriptor、版本区间及期待事务。缓存按generation/serviceId/minApiVersion/schema fingerprint隔离，命中后不重复两次Broker RPC。服务端新增额外事务可兼容已有期待。

生成 Async/Flow 使用可取消的 awaitServiceBinderForConnection；同一 Controller、同一缓存键的并发发现共享结果，一个等待者退出不影响其余等待者。所有 Controller 在同一进程/classloader 内共享最多 4 个发现 worker，无排队任务；全部占用时新冷发现以 RejectedExecutionException 失败，温缓存仍可读取。最后等待者退出后放弃该 flight，远端 RPC 返回时检查连接与需求，停止后续步骤；取消到 finally 释放之间仍有调度窗口，底层同步 Binder 不会被强制中断。

连接握手使用独立 HandshakeExecutor，最多 4 个 worker、无排队任务，与上述 4 个发现 worker 分池。绑定截止或解绑取消本地 waiter；握手晚到结果按连接、Binding 和 handshake Job 身份拒绝发布。已经开始的同步 Binder 仍可能占用 worker，取消 waiter 不表示远端调用结束；9 项握手门禁已在上述 `4F7ED...` 候选 APK 的小米回归通过，不能追溯为固定发布标签的设备验收。

手写同步 getServiceBinder/getServiceBinderForConnection 在后台线程有限等待；Main 上冷缓存立即失败，应改用上述挂起接口。Direct 仍整体拒绝 Main；Oneway 可在 Main 使用已经预热的缓存，但发现完成到发送之间仍可能断连。示例的预热使用生成 CLIENT_SCHEMA 和适配器相同的 serviceId/minApiVersion，不能手写省略 Schema 的 legacy 预热来替代。

旧Broker缺capability或方法时报IpcCompatibilityException，不隐式降级。手工省略expectedSchema才走legacy getService，仅能访问floor1服务；生成器总走严格路径。

Broker业务兼容拒绝用标准IllegalArgumentException返回，鉴权失败为SecurityException；Controller将严格发现的业务校验失败封装为IpcCompatibilityException。RemoteException属于传输失败，不表示业务版本兼容。

## 4. 关闭与永久释放

```kotlin
controller.closeAndJoin() // 等待本地关闭，稍后可重连。
controller.connect()
controller.awaitConnected()

subscription.cancel()
controller.disposeAndJoin() // 永久退出，此后connect拒绝。
```

挂起方法从适当协程调用；close()/dispose()是异步非挂起入口，AndJoin在NonCancellable等待本地清理。Closed保留Flow等待重连能力，Disposed结束外部collector。每代次独立observer拒绝旧事件，finally经captured Binder/subId退订，不查当前cache。

owner Job**最终完成**自动dispose，可能晚于最初cancel；阻塞子任务会延迟它。需要提前释放时显式dispose；没有owner Job的scope由调用方明确释放。关闭不保证立即完成远端Job，不强制中断同步Binder或回滚副作用。

close/dispose 先发布状态，再替换并作废发现缓存容器，使其 waiter 失败，然后继续解绑和 pending 清理；不等待已经开始的发现 RPC 返回。旧 worker 的晚到结果不能写入新代次缓存。AndJoin 等待本地清理，不表示所有底层 Binder worker 已退出或收到远端取消 ACK。

BaseClientActivity 的注册与心跳均先在 Main 协程中挂起预热，并核对连接快照和适配器；取消原样传播。主动断开时的 unregister 保留尽力发送语义，失败可能未移除服务端在线记录；onDestroy 只取消 scope/dispose，不额外启动注销等待，不保证远端已注销。

## 5. Async错误与截止

生成 Async 从入口开始使用默认30秒预算，Controller.defaultCallTimeoutMs可配置，覆盖冷发现及注册后的发送/回调等待；外层withTimeout可设置更短等待。冷发现阶段到期时尚未注册业务requestId或发送业务请求；注册后到期pending摘除，经原Binder尝试一次取消，关闭/断连同样取消，成功不误发取消。

当前生成Async顺序是入口截止→await完整Schema resolve并捕获Binder→WithinDeadline注册pending→编码请求→`controller.checkServiceBinderForConnection(connection, capturedBinder)`→transact。首次解析保留expectedSchema与版本/事务检查；guard在原Binder的isBinderAlive检查前后核验同一Connected实例identity和未dispose，不再第二次完整解析，不自动换Binder或重放。检查到transact仍有断连/死亡竞态，本地失败不能证明远端未执行；取消继续用captured Binder。冷发现与发送前 guard 的门禁见[回归报告](benchmarks/Regression_Report.md)，局部性能测量按[性能报告](benchmarks/Performance_Report.md)中的独立 APK 解释。

awaitConnected、bindingTimeoutMs 和生成 Async 入口预算仍是不同计时点。可取消的是等待，不是远端同步 Binder 本身；已开始的同步transact及无挂起编码不会被截止抢占，Direct业务也没有同样的默认RPC截止。

callSuspendWithinDeadline 是底层 primitive，不再自己创建超时，调用者必须建立有效的外层截止并保留取消生命周期。生成适配器已负责入口预算，通常直接调用适配器；手写调用者若直接使用此 primitive，不会自动获得30秒截止。普通 PendingCallRegistry.callSuspend 保留注册请求默认截止，但不会覆盖它之前的手写发现逻辑。

`maxReconnectAttempts=0` 同时禁用绑定失败与已连接后 Binder 死亡的自动重连；仍允许显式 `connect()`。主机验证覆盖两种死亡/断开回调顺序，小米实际死亡路径尚未复测。

常见失败为兼容异常、断连/释放、String业务错误、SERVER_BUSY、DUPLICATE_REQUEST、SERVER_DISPOSED、SERVER_SCOPE_CANCELLED。RpcError尚未接入，idempotent不自动重试。基本类型帧能识别callback时可立即返回校验错误；坏Token/损坏帧依赖本地截止；Parcelable仍先鉴权再反序列化。

## 6. Flow终态与缓冲

| observer code | 内容 | 新客户端行为 |
| --- | --- | --- |
| 1 NEXT | 原item payload | 解码/trySend |
| 2 COMPLETE | 无payload | 排空缓冲后正常完成 |
| 3 ERROR | String | 异常结束 |

Flow 业务内部 timeout/主动 `CancellationException` 保留业务消息；只有父 scope 失效才报告 `SERVER_SCOPE_CANCELLED`，销毁报告 `SERVER_DISPOSED`。主动退订保持 quiet 语义。

首事件/终态可早于subId回复；同代次gate和CAS防重复/过期终态。业务异常和scope失效有错误通知，主动collector取消/退订无附加业务错误。旧客户端只解释NEXT，需要升级使用终态语义。

每个 Connected 代次的发现等待使用 defaultCallTimeoutMs，超时在当轮仍有效时以异常结束 Flow；collector取消/状态切换按取消处理。这个发现截止不限制整段业务流，也不能中断已经开始的同步 subscribe transact。需要限制消费总时长时，调用方自行设置预算。

默认ERROR策略在本地缓冲满时以“IPC stream buffer overflow”结束并退订。允许跳过中间值的状态接口可明确标注：

```kotlin
@IpcStream(subscribeTransaction = 30, unsubscribeTransaction = 31,
    overflowPolicy = IpcStreamOverflow.CONFLATE)
fun observeStatus(): Flow<Int>
```

CONFLATE生成本地conflate，不适合逐条必须消费的消息。框架无订阅上限、credit、消费ACK或重放游标，重连只恢复实时值。Hub源SharedFlow仍DROP_OLDEST且无replay；本地溢出报错不补回源头丢值，“Delivered”也不保证目标UI已消费。可靠消息需存储、消息ID、确认及去重。

## 7. Direct与升级

仓库[echo契约](../ipc-api/src/main/kotlin/com/cn/ipc/api/test/IBenchmarkEchoService.kt)保留Async并增加Direct；完整接口还含生命周期探针，下面为摘录：

```kotlin
@IpcFacade(serviceId = 9001, minApiVersion = 6)
interface IBenchmarkEchoService {
    @IpcAsync(requestTransaction = 10, cancelTransaction = 11)
    suspend fun echo(payload: String): String
    @IpcDirect(transaction = 12)
    fun echoDirect(payload: String): String
}
val result = withContext(Dispatchers.IO) { echo.echoDirect("hello") }
```

Direct拒绝主线程，服务端Binder线程同步执行并通过同一reply返回标准异常/业务status；仅五种非空基本类型。冷发现等待有限，但业务同步transact占用调用方线程，无远端取消、默认业务RPC截止或128 Async许可，适合短、无阻塞I/O、可并发的读取/计算。历史[Direct性能报告](benchmarks/Performance_Report.md)不代表当前Schema APK结果。

保持serviceId/事务号稳定；已有参数顺序、codec、返回模式、取消码或封装变化会被Schema拒绝。破坏性改动保留旧解码或分配新事务，并正确配置contractVersion/apiVersion/floor；仅提升minApiVersion不能保护旧客户端。AIDL旧方法1/2格式保留，新严格方法3/4；floor>1拒绝legacy。Parcelable字段兼容另测。

发布前需完成双端构建、版本/Schema 矩阵、关闭/重连、有限/错误/慢消费者 Flow、冷发现和握手门禁。历史 52 项、64 项回归与本次 `4F7ED...` 候选通过的 73 项各有独立版本和范围，见[回归报告](benchmarks/Regression_Report.md)。性能比较见[性能报告](benchmarks/Performance_Report.md)，局部减少分配不等于整次 RPC 零分配或稳定 RTT 收益。

## 8. 请求关联trace诊断

[IpcRequestTrace](../ipc-contract/src/main/kotlin/com/cn/ipc/IpcRequestTrace.kt)默认关闭，start(run)启用当前进程，stopAndFlush(output)停止后返回events/dropped。省略output时事件集中输出Logcat；传入File时事件和END写入该文件，Logcat只记录END摘要。应先结束测量窗口再导出文件。客户端、服务端需各自通过其宿主/测试入口启停；在客户端调用start不会远程启用服务端。历史成功 Echo 的完整性与内核诊断按各自 APK 记录在[性能报告](benchmarks/Performance_Report.md)；错误、取消、冷发现、Direct 和完整 Flow 尚无同等阶段完整性证明。

每进程最多缓冲16,384个事件，超限计入dropped；窗口内不逐事件写Logcat，但仍有Event分配、短同步append和Android Trace marker成本。使用隔离的单客户端/单Controller并保持同一run，按requestId关联双端。callback identity仅在本进程内有意义，服务端generation=0；没有新增wire身份，不能直接用callback数值跨进程连接，也不能把多客户端相同requestId混合。

2026-10-30 的参数隔离修复增加公开方法到私有传输方法的尾调用桥接；`client_entry` 在私有传输方法入口采集，不包含此前桥接指令，外层调用 RTT 则仍包含它。本轮未对该改动进行性能测量。

缺事件、错误/取消链及dropped应单独计数；只对完整链解释分段耗时；区间包含埋点及阶段间工作，不能直接当作纯编码或纯排队时间。早期时间点可在读出requestId/callback后补写，分析应使用记录的原始ns，不能把marker实际出现位置当成早期时间。先结束测量窗口再stopAndFlush，启用trace所得数据也需注明埋点开销。

## 9. 可选短任务调度配置

**`asyncCoroutineScope` 从 `3.0.0-rc.2` 源码提供；固定 `v3.0.0-rc.1` 标签的生成器与 Maven 产物不包含此扩展点。** 使用候选源码重新运行 KSP 后，生成 Stub 保留宿主提供的 `coroutineScope`，新增 `protected open val asyncCoroutineScope`，默认 getter 返回 `coroutineScope`。只有 Async 的 Tracker 提交改读新属性；Flow 的订阅 Job 创建、收集和 scope 存活检查仍使用 `coroutineScope`。不覆盖新属性时调度与原配置一致，SDK 不替宿主选择 dispatcher，也不改变调用方恢复上下文或 Direct 协议。

对有严格短耗时保证、无阻塞 I/O 的 Async 方法，宿主可让 Flow 使用 Default，并为 Async 单独注入专用 scope。以下只展示固定配置骨架，省略注册和接口方法实现；需要导入 kotlinx.coroutines 的相应类型、asCoroutineDispatcher 和 java.util.concurrent.Executors。两个 scope 应稳定持有并共享服务自己的父 Job，getter 不能每次构造新 scope：

```kotlin
private val serviceJob = SupervisorJob()
private val flowScope = CoroutineScope(Dispatchers.Default + serviceJob)
private val shortDispatcher = Executors.newSingleThreadExecutor {
    Thread(it, "IpcShortTasks")
}.asCoroutineDispatcher()
private val shortScope = CoroutineScope(shortDispatcher + serviceJob)

private fun createHubStub() = object : IMessageHubServiceServerStub(this) {
    override val coroutineScope: CoroutineScope get() = flowScope
    override val asyncCoroutineScope: CoroutineScope get() = shortScope
    // 实现完整接口的方法，随后由宿主注册该 Stub。
}

override fun onDestroy() {
    try {
        super.onDestroy() // Registry 先释放已注册 Stub
    } finally {
        serviceJob.cancel()
        shortDispatcher.close()
    }
}
```

scope 必须属于该服务生命周期，不共用无关组件的 Job；Stub.dispose 会取消 `asyncCoroutineScope` 和 `coroutineScope`，不会替宿主关闭 ExecutorCoroutineDispatcher。即使两个属性回落同一个 scope，重复 cancel 也不创建额外任务。共享父 Job 时取消任一 scope 会取消该服务的共同子任务，不能把共享 Job 当作两个独立生命周期。

专用单线程会串行执行该 Async scope 的任务，不等于新增全局队列上限；长计算、阻塞调用会拖延其他请求。请求仍经过 Tracker 的 LAZY 登记、死亡监听、128 许可、原 UID/callback/requestId 所有权检查和取消处理。独立 Flow scope 避免把 Flow 收集也迁到短任务线程，但业务 Flow 工厂本身仍需及时返回，客户端仍在原有上下文恢复。

可运行实验见[MyBrokerService](../demo-app/src/main/kotlin/com/cn/ipc/demo/MyBrokerService.kt)：Flow 固定使用 Default，Async 默认同样使用 Default，专用执行器按需创建并保留至服务销毁关闭。动态选择仅供受控、单客户端且 Async 空闲的测试；`activeRequestCount == 0` 是瞬时检查，不能作为生产环境多客户端切换的并发协议。测试成功与 finally 清理均恢复 Default，生产配置应固定持有 scope。

`4F7ED...` 与后续 `D0E76...` APK 分别通过 4 项独立 scope 检查，覆盖专用配置选择、取消与并行 Flow、截止和默认配置恢复。新包另有 9 项生成 Stub 回归，覆盖业务内部 timeout/主动抛出 CancellationException 在两种 scope 下的错误终态及默认配置恢复。它们证明这些探针通过，不证明性能收益。历史 `4494...` 两轮实验与 `4F7ED...` 小米对照分开解释，完整条件、原始数据及 AndLinker/Direct 语义差异见[性能报告](benchmarks/Performance_Report.md)。

## 10. Maven 构建、发布与外部消费

### 坐标与本地发布

[发布脚本](../build-logic/src/main/kotlin/convention.publish.gradle.kts)使用 `groupId=com.modernipc`、当前版本 `3.0.0-rc.3`，版本来源为根 `gradle.properties` 的 `VERSION_NAME`，Gradle参数可覆盖；artifactId为模块名。可发布的 Android library 为 `ipc-api`、`ipc-contract`、`ipc-runtime-client`、`ipc-runtime-server`，产物为 release AAR；JVM library `ipc-annotations`、`ipc-compiler` 发布 JAR。`demo-client-common`另发布为示例共用AAR；五个示例应用与一个 AndLinker 对照应用通过六个Debug APK交付，不是SDK坐标。

```powershell
.\gradlew.bat :ipc-api:assembleRelease :ipc-runtime-client:assembleRelease :ipc-runtime-server:assembleRelease
.\gradlew.bat :ipc-api:publishReleasePublicationToProjectLocalRepository :ipc-contract:publishReleasePublicationToProjectLocalRepository :ipc-runtime-client:publishReleasePublicationToProjectLocalRepository :ipc-runtime-server:publishReleasePublicationToProjectLocalRepository :ipc-annotations:publishJavaPublicationToProjectLocalRepository :ipc-compiler:publishJavaPublicationToProjectLocalRepository
```

项目仓库默认位于根目录 `local-maven`，可用 `-PprojectLocalRepository=绝对路径` 选择隔离目录。只发布某个模块时，执行其对应 ProjectLocal 任务；根级 `publish` 会尝试所有配置仓库，包括需要凭据的 GitHub Packages。先以 Gradle `tasks` 核对具体任务，并等待最终退出码。不要覆盖已发布版本来验证源码修复。

另一工程可在 `settings.gradle.kts` 的 `dependencyResolutionManagement.repositories` 中添加本地地址，再按实际发布版本消费：

```kotlin
maven { url = uri("D:/实际的已核验仓库") }
// 业务模块的 dependencies 中：
val ipcVersion = providers.gradleProperty("ipcVersion").get()
implementation("com.modernipc:ipc-api:$ipcVersion")
```

新接口仍需[第 0 节](#0-依赖与生成配置)的 KSP 配置。核对 POM 的传递依赖、外部工程 Kotlin/AGP/KSP 版本和宿主 minSdk；仓库项目依赖构建成功不等于 Maven 外部集成已通过。

### 独立消费与 host 门禁

日常验证使用 JDK 17 启动 Gradle；host 的 JVM toolchain 配置不能修复用不兼容 JDK 启动 Gradle 的问题。Wrapper 已配置官方 Gradle 8.5 分发 SHA-256 校验。执行：

```powershell
./gradlew.bat check
python scripts/verify_maven_consumers.py --java-home $env:JAVA_HOME --android-sdk $env:ANDROID_HOME
```

根 `:check` 执行真实 PendingCallRegistry/IpcRequestTracker 源码的 **12+15 项** host 检查，Android doubles 不证明真实 Binder。独立[消费工程](../tests/maven-consumer)由[脚本](../scripts/verify_maven_consumers.py)发布六 SDK 的唯一临时版本至隔离仓库，再分别通过 Gradle metadata 与 POM-only 编译：仅依赖 ipc-api 的消费者、自定义 Async+Flow KSP 契约，以及仅依赖该契约坐标的下游；同时检查编译依赖图，防止重复直接依赖掩盖导出缺陷。它不会改写 `local-maven` 的既有 RC1。

2026-10-22 的 Windows 本机验证见[历史归档](benchmarks/runs/2026-10-22-xiaomi14ultra/engineering-fixes-build/maven-consumers/archive.json)。[日常 CI](../.github/workflows/verify.yml)与发布 CI 已接入全量 `check` 和独立消费两个门禁；rc.3 的远端执行结果见[发布记录](releases/v3.0.0-rc.3/README.md)，不以本机结果代替远端结果。原 `0003` 取消降噪补丁已撤回，原因和设备终态回归见[审计](ModernIPC_Engineering_Audit.md)。

### 默认：公开 maven 分支与匿名下载

默认仓库为 `https://raw.githubusercontent.com/NingCui29/ModernIpc/maven`，依赖坐标仍是 `com.modernipc:<module>:<version>`。此方案参考 TaskQueue：`maven` 分支只存放 Maven 目录、元数据和发布回执；SDK 源码继续在主分支和版本分支维护。消费者不需要 GitHub Token，也不需要注册或验证 Maven Central 命名空间。维护者向 GitHub 推送分支仍需仓库写权限。

[匿名发布工作流](../.github/workflows/publish-maven.yml)用于将已发布版本补充到默认渠道。在 Actions 中从包含该工作流的主分支运行，指定已有标签，例如 `v3.0.0-rc.3`。它复用该 Release 的 `maven-repository.zip`、`manifest.json` 和校验清单，核对来源与内容后合并到 `maven` 分支。已有版本存在不同字节时应失败，不能重新编译同版本或覆盖历史 GAV；新增源码修复必须使用新版本。

[准备与核验脚本](../scripts/publish_anonymous_maven.py)负责七模块完整性、内部依赖与文件校验。对应命令形式为：

```powershell
python scripts/publish_anonymous_maven.py prepare --archive <Release的maven-repository.zip> --manifest <Release的manifest.json> --repository <maven分支检出目录> --version 3.0.0-rc.3 --source-repository NingCui29/ModernIpc
python scripts/publish_anonymous_maven.py verify --repository <maven分支检出目录> --version 3.0.0-rc.3 --base-url https://raw.githubusercontent.com/NingCui29/ModernIpc/maven --output <核验报告.json>
```

默认渠道包含六 SDK 与 `demo-client-common` 共七模块的 AAR/JAR、POM、Gradle `.module`、sources/javadoc 及校验文件；不是只上传 `ipc-annotations` 或仅修改一个仓库地址。已有固定版本文件保持不变，发布新版本时更新模块级 `maven-metadata.xml`。

远端文件下载通过后，还要通过真实独立消费工程验证 metadata、POM-only、KSP 与传递依赖：

```powershell
python scripts/verify_maven_consumers.py --remote-repository https://raw.githubusercontent.com/NingCui29/ModernIpc/maven --version 3.0.0-rc.3 --java-home $env:JAVA_HOME
```

该模式从匿名 URL 解析六 SDK，测试专用契约只写入本地隔离仓库，不向远端发布；不带该选项时继续使用前文的唯一临时版本和本地仓库。SDK 路径沿用脚本的环境变量 / 根 `local.properties` 探测规则。匿名 HTTP 下载、真实消费编译与 Android 真机验收分别记录，不能相互替代。

### 可选：GitHub Packages

GitHub Packages 保留为第二条分发渠道。它的公开 Maven 包下载仍需认证，不能把公开源码仓库的权限当作匿名下载能力。普通消费者使用自己的 PAT classic，并授予 `read:packages`；不要把 Token 写进工程或提交到 Git。

维护者发布时，远端 owner/repo 依次取 `GITHUB_REPOSITORY` 环境变量、`gpr.repo` 属性、`gpr.modern.ipc.repo` 属性，最后回退到源码中的 `NingCui29/ModernIpc`。用户名/令牌依次取 `GITHUB_ACTOR`/`GITHUB_TOKEN` 或 `gpr.user`/`gpr.key`，保存于本机 Gradle 用户属性或 CI 密钥：

```properties
gpr.repo=实际用户名/ModernIpc
gpr.user=实际用户名
gpr.key=具有对应权限的令牌
```

单个 Android 模块向 Packages 发布的任务形式：

```powershell
.\gradlew.bat :ipc-api:publishReleasePublicationToGitHubPackagesRepository
```

明确选择此渠道的消费者需将默认 `com.modernipc` 独占仓库替换为 `https://maven.pkg.github.com/ningcui29/modernipc`，并在该仓库的 `credentials` 中读取自己的账号和令牌。发布端的凭据配置不会自动应用于消费者。仅使用默认匿名渠道时无需这一步。

### Release CI 与验证边界

[release.yml](../.github/workflows/release.yml)从 v 标签提取版本并核对 VERSION_NAME，以 JDK 17 先执行全量子模块 `check` 和隔离 Maven 消费门禁，再构建六 SDK、示例共用 AAR 和六个同签名 Debug APK。验证本地 POM/module 内部版本和 APK 版本/签名后发布制品，并核对远端文件内容；匿名 Maven 渠道和可选 Packages 渠道分别验证。

默认发布到 Release 和匿名 Maven。需要同时发布 GitHub Packages 时，在手动运行 Release 工作流时勾选 `publish_packages`，或设置仓库变量 `PUBLISH_GITHUB_PACKAGES=true`；该开关不改变消费者使用匿名渠道的方式。已有 Release 使用 `publish-maven.yml` 手动输入原标签补发匿名渠道，不重新运行会拒绝已发布版本的 Release 构建流程。

Release 附件包含 SDK 及 sources/javadoc、APK、Maven 仓库、源码、仓库内证据和 SHA-256 清单；带 rc 的标签标为预发行。完整历史 APK 证据由本地另行打包上传，CI 仓库证据归档不自动包含 Git 忽略的历史 APK。已发布 `v3.0.0-rc.3` 的标签、原 Release 附件和历史 Packages 核验保持不变，本次只新增匿名分发渠道。工作流文件存在不表示执行成功，最终以版本发布回执为准；设备 NOT_RUN 与构建成功分别报告。

### 2026-11-03 未发布补充

Gradle 启动环境要求 Java 17，settings 阶段明确拒绝其他版本；Robolectric Test 任务独立选择 Java 17 launcher。请配置当前 shell 的 JAVA_HOME 或 Android Studio Gradle JDK，不在仓库提交个人安装绝对路径。

awaitConnected 在控制锁内启动或加入连接尝试，并等待该尝试的独立完成对象。重试耗尽返回连接错误，主动关闭结束旧尝试；从 Disconnected/Closed 调用可建立新尝试，不把旧终态误判为新失败。调用方超时/取消仅退出自己的等待，不取消共享连接尝试。

辅助订阅管理器在锁外解除旧注册及失败注册的死亡监听，登记仍在锁内与死亡/kill 串行化。broadcast 忽略 RemoteException，其他异常（包括协程取消）向调用方传播并停止本轮；需要逐观察者业务隔离时，由 action 显式处理及报告可恢复错误，不默认吞掉编程错误。

本轮本机 `:check` 通过：27 项状态机及 12 组 Robolectric 回归。小米 925c23bb（24031PN0DC）新 APK 已完成 86 项真机断言（核心 73、独立作用域 4、生成终态 9），安装前后拉包 SHA-256 与候选包一致。握手组新增连接尝试等待断言，覆盖关闭/销毁、重试耗尽、显式重连及旧握手隔离。首轮发现缺参诊断泄露内部变量别名，修复后完整复跑通过；首轮失败证据保留。解绑锁范围和广播异常策略另有主机定向回归，不将其等同于设备竞态穷举。本轮没有进行 AndLinker 性能对照。见[11/03执行记录](benchmarks/runs/2026-11-03-xiaomi14ultra/connection-fixes/summary.json)。

### 2026-11-03 深层审计修正（未发布）

- 注册表的注册、查询和版本快照在同一锁下执行，重复 serviceId 原子拒绝。dispose 先关闭并清空注册表，再在锁外按对象身份去重释放 Stub；关闭后不能重新注册。重复 dispose 不重复回调，但不承诺等待另一个并发 dispose 完成。注册与关闭竞争时，注册先成功的对象纳入释放；关闭先发生的注册被拒绝，调用方负责释放未被接收的对象。
- 冷发现仍为严格双 RPC：客户端验证元数据，服务端验证客户端契约后返回 Binder。未新增协议方法或放宽服务标识、版本、签名验证。全进程最多 4 个工作线程和 4 个排队任务；超过容量返回发现失败。最后一个等待者取消或缓存失效会移除排队任务，执行前再检查需求；已经进入同步 Binder 的工作不能可靠中断。排队时间计入调用期限，不使用无限扩容或调用线程代执行。
- Stream 事件 transact 返回 false 时，生成 Stub 终止生产、尝试一次错误终态并清理订阅；异常也走既有错误终态路径。回调通道失效时无法保证错误送达。客户端缓冲溢出仍为显式失败，不承诺无损远程背压；可用 Flow.buffer 配置本地消费缓冲，但不是远端流量控制。没有添加无限缓冲或隐式丢弃策略。
- Broker 复用无授权缓存的 CallerAuthenticator，并使用 authorizeCurrentCaller 避免构造未使用的 CallerIdentity/包名 Set；每事务仍验证当前调用 UID，跨 UID 仍实时检查包名与签名。没有永久 uid→允许缓存；不宣称每次必然穿透两次系统 Binder，也不据此声称 P99 改善。
- API 21 前台门禁使用 isKeyguardLocked 作为保守回退；API 23 以下运行时分配统计返回不可用，而非零。夹具避免 API 24 的 computeIfAbsent/updateAndGet，生成终态日志标签符合旧系统长度要求。

Parcelable 演进示例：OrderDto v1 按 id、name 写入，v2 插入 price 时，即便类名不变也不能认定旧 CREATOR 能读取。若不保留旧编码，应将契约版本和服务 apiVersion 升为 2，并设置 minSupportedClientVersion=2 拒绝旧端；或者保留旧事务和 DTO，新增 v2 事务。只提高 apiVersion/contractVersion、不提高允许旧端的版本下限，不能阻止旧端被接纳。Schema 的 parcelable-opaque 标识不验证字段布局。回归验证版本下限拒绝旧端，不能替代业务 DTO 的新旧编码互读测试。

本轮设备与主机证据见[回归报告](benchmarks/Regression_Report.md)；API 21 行为没有用高版本小米实测冒充。

### 全量检查与 Maven 消费验证入口（11/03 补正）

根任务 `:check` 只聚合显式声明的主机回归，不代表所有子模块 Lint 已运行。完整验证使用 `./gradlew check`（Windows 为 `.\gradlew.bat check`），CI verify/release 已切换到这个入口。运行前仍需选择 JDK 17；Android SDK 和所需组件必须已安装。

`python scripts/verify_maven_consumers.py` 的 SDK 查找顺序为：`--android-sdk`、`ANDROID_HOME`、`ANDROID_SDK_ROOT`、根目录 `local.properties` 的 `sdk.dir`。选定后对子进程统一设置两个 SDK 环境变量，不写入消费工程或全局环境；支持 Java properties 的 Windows 路径转义和续行。显式指定不存在的目录会提前失败，不悄悄改用其他 SDK。“无需 SDK 参数”不代表自动安装 JDK/Android SDK。

Binder 死亡处理在 API 23 以上保留带 generation 的 DeadObjectException 消息，API 21/22 使用无参构造器；两条路径均进入相同重连预算与失败完成逻辑。
