# ModernIpc SDK 接入与发布指南

核对日期：2026-10-09。本指南介绍共享契约、双端接入、生命周期、请求与 Flow 终态、诊断和 Maven 发布。模块关系与协议图见[架构](ModernIPC_Architecture.md)，四个多 App 案例见[业务指南](ModernIPC_Business_Interaction_Guide.md)。

**当前验收状态**：当前源码的五应用构建成功，最新 `more-scenarios-build` 的安装与小米设备验证为 **NOT_RUN**；此前 `4494...` APK 的 64 项回归和请求 trace 属于历史版本。独立 HandshakeExecutor 在该实测版本之后加入，新增 9 项握手与完整当前回归仍待验收。构建身份、历史回归与性能分别集中在[多 App 报告](benchmarks/Multi_App_Report.md)、[回归报告](benchmarks/Regression_Report.md)和[性能报告](benchmarks/Performance_Report.md)，不能用旧 APK 的结果代替当前源码验收。

## 0. 依赖与生成配置

仓库内示例直接使用 Gradle project 依赖。外部工程需先按[第 10 节](#10-maven-构建发布与外部消费)核实产物与版本；文档中的坐标不是远端可下载证明。

定义新业务接口的 Android library 必须同时配置 KSP 和接口使用到的运行时依赖；客户端与服务端共享该契约及生成代码。依赖已有 `ipc-api` 只得到仓库内契约，不会为其他模块的新接口自动生成代理。以下是自定义契约模块的依赖骨架：

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}
dependencies {
    implementation("com.modernipc:ipc-annotations:2.0.1")
    implementation("com.modernipc:ipc-contract:2.0.1")
    implementation("com.modernipc:ipc-runtime-client:2.0.1")
    implementation("com.modernipc:ipc-runtime-server:2.0.1")
    ksp("com.modernipc:ipc-compiler:2.0.1")
}
```

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

生成Stub实现IpcServiceSchemaProvider/IpcServiceLifecycle。Service.onDestroy通过Registry.dispose释放请求、订阅及scope，已释放Stub拒绝新任务。activeRequestCount/activeSubscriptionCount在Job实际完成后更新，计数不是取消ACK。

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

连接握手使用独立 HandshakeExecutor，最多 4 个 worker、无排队任务，与上述 4 个发现 worker 分池。绑定截止或解绑取消本地 waiter；握手晚到结果按连接、Binding 和 handshake Job 身份拒绝发布。已经开始的同步 Binder 仍可能占用 worker，取消 waiter 不表示远端调用结束；新增握手门禁尚待当前 APK 设备验收。

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

常见失败为兼容异常、断连/释放、String业务错误、SERVER_BUSY、DUPLICATE_REQUEST、SERVER_DISPOSED、SERVER_SCOPE_CANCELLED。RpcError尚未接入，idempotent不自动重试。基本类型帧能识别callback时可立即返回校验错误；坏Token/损坏帧依赖本地截止；Parcelable仍先鉴权再反序列化。

## 6. Flow终态与缓冲

| observer code | 内容 | 新客户端行为 |
| --- | --- | --- |
| 1 NEXT | 原item payload | 解码/trySend |
| 2 COMPLETE | 无payload | 排空缓冲后正常完成 |
| 3 ERROR | String | 异常结束 |

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

发布前需完成双端构建、版本/Schema 矩阵、关闭/重连、有限/错误/慢消费者 Flow、冷发现和握手门禁。历史 52 项、64 项回归与待执行的 73 项计划各有独立版本和范围，见[回归报告](benchmarks/Regression_Report.md)。性能比较见[性能报告](benchmarks/Performance_Report.md)，局部减少分配不等于整次 RPC 零分配或稳定 RTT 收益。

## 8. 请求关联trace诊断

[IpcRequestTrace](../ipc-contract/src/main/kotlin/com/cn/ipc/IpcRequestTrace.kt)默认关闭，start(run)启用当前进程，stopAndFlush(output)停止后返回events/dropped。省略output时事件集中输出Logcat；传入File时事件和END写入该文件，Logcat只记录END摘要。应先结束测量窗口再导出文件。客户端、服务端需各自通过其宿主/测试入口启停；在客户端调用start不会远程启用服务端。历史成功 Echo 的完整性与内核诊断按各自 APK 记录在[性能报告](benchmarks/Performance_Report.md)；错误、取消、冷发现、Direct 和完整 Flow 尚无同等阶段完整性证明。

每进程最多缓冲16,384个事件，超限计入dropped；窗口内不逐事件写Logcat，但仍有Event分配、短同步append和Android Trace marker成本。使用隔离的单客户端/单Controller并保持同一run，按requestId关联双端。callback identity仅在本进程内有意义，服务端generation=0；没有新增wire身份，不能直接用callback数值跨进程连接，也不能把多客户端相同requestId混合。

缺事件、错误/取消链及dropped应单独计数；只对完整链解释分段耗时；区间包含埋点及阶段间工作，不能直接当作纯编码或纯排队时间。早期时间点可在读出requestId/callback后补写，分析应使用记录的原始ns，不能把marker实际出现位置当成早期时间。先结束测量窗口再stopAndFlush，启用trace所得数据也需注明埋点开销。

## 9. 可选短任务调度配置

生成 Stub 的 coroutineScope 由宿主提供，SDK 没有改动调度机制或强制改为专用线程。对有严格短耗时保证、无阻塞 I/O 的挂起任务，可以通过现有 scope 注入专用 dispatcher；Direct 仍是独立的同步协议，不因该配置而改变。可运行实现见[MyBrokerService](../demo-app/src/main/kotlin/com/cn/ipc/demo/MyBrokerService.kt)：默认 scope 使用 Dispatchers.Default，专用单线程执行器按需创建，两种 scope 共享同一 SupervisorJob，切换只用于受控空闲测试，成功路径显式恢复 Default，异常 finally 尝试恢复；专用线程保留至服务销毁时关闭。

固定配置的宿主可保有 shortJob、shortDispatcher 与 shortScope，并在生成 Stub 内使用 `override val coroutineScope = shortScope`。以下只展示服务宿主的资源配置与销毁代码；需要导入 kotlinx.coroutines 的相应类型、asCoroutineDispatcher 和 java.util.concurrent.Executors：

```kotlin
private val shortJob = SupervisorJob()
private val shortDispatcher = Executors.newSingleThreadExecutor {
    Thread(it, "IpcShortTasks")
}.asCoroutineDispatcher()
private val shortScope = CoroutineScope(shortDispatcher + shortJob)

override fun onDestroy() {
    try {
        super.onDestroy() // Registry 先释放已注册 Stub
    } finally {
        shortJob.cancel()
        shortDispatcher.close()
    }
}
```

scope 必须属于该服务生命周期，不共用无关组件的 Job；dispose 会取消它。专用单线程会串行执行该 scope 的任务，不等于新增全局队列上限；长计算、阻塞调用或大量订阅可能拖延其他任务。请求仍经过 Tracker 的 LAZY 登记、死亡监听、128 许可与取消处理，调用方仍在自己的 dispatcher 恢复。

调度实测4494...同APK两轮各28,096次、零失败；1024字符配对P50摘要两轮较低，但16字符第二轮有2/4配对反向，不能据此修改SDK默认调度或承诺真实业务固定收益。完整条件、原始数据与 Direct 语义对照见[性能报告](benchmarks/Performance_Report.md)。

## 10. Maven 构建、发布与外部消费

### 坐标与本地发布

[发布脚本](../build-logic/src/main/kotlin/convention.publish.gradle.kts)使用 `groupId=com.modernipc`、当前版本 `3.0.0-rc.1`，版本来源为根 `gradle.properties` 的 `VERSION_NAME`，Gradle参数可覆盖；artifactId为模块名。可发布的 Android library 为 `ipc-api`、`ipc-contract`、`ipc-runtime-client`、`ipc-runtime-server`，产物为 release AAR；JVM library `ipc-annotations`、`ipc-compiler` 发布 JAR。`demo-client-common`另发布为示例共用AAR；应用模块通过五个Debug APK交付，不是SDK坐标。

```powershell
.\gradlew.bat :ipc-api:assembleRelease :ipc-runtime-client:assembleRelease :ipc-runtime-server:assembleRelease
.\gradlew.bat :ipc-api:publishReleasePublicationToProjectLocalRepository :ipc-contract:publishReleasePublicationToProjectLocalRepository :ipc-runtime-client:publishReleasePublicationToProjectLocalRepository :ipc-runtime-server:publishReleasePublicationToProjectLocalRepository :ipc-annotations:publishJavaPublicationToProjectLocalRepository :ipc-compiler:publishJavaPublicationToProjectLocalRepository
```

项目仓库位于根目录 `local-maven`。只发布某个模块时，执行其对应 ProjectLocal 任务；根级 `publish` 会尝试所有配置仓库，包括需要凭据的 GitHub Packages。先以 Gradle `tasks` 核对具体任务，并等待最终退出码。

另一工程可在 `settings.gradle.kts` 的 `dependencyResolutionManagement.repositories` 中添加本地地址，再按实际发布版本消费：

```kotlin
maven { url = uri("D:/Developer/WorkSpace/ModernIpc/local-maven") }
// 业务模块的 dependencies 中：
implementation("com.modernipc:ipc-api:3.0.0-rc.1")
```

新接口仍需[第 0 节](#0-依赖与生成配置)的 KSP 配置。核对 POM 的传递依赖、外部工程 Kotlin/AGP/KSP 版本和宿主 minSdk；仓库项目依赖构建成功不等于 Maven 外部集成已通过。

### GitHub Packages 与 CI

远端 owner/repo 依次取 `GITHUB_REPOSITORY` 环境变量、`gpr.repo` 属性、`gpr.modern.ipc.repo` 属性，最后回退到源码中的 `NingCui29/ModernIpc`。应显式指定实际 owner/repo。用户名/令牌依次取 `GITHUB_ACTOR`/`GITHUB_TOKEN` 或 `gpr.user`/`gpr.key`，保存于本机 Gradle 用户属性或 CI 密钥。

```properties
gpr.repo=实际用户名/ModernIpc
gpr.user=实际用户名
gpr.key=具有对应权限的令牌
```

单个 Android 模块向远端发布的任务形式：

```powershell
.\gradlew.bat :ipc-api:publishReleasePublicationToGitHubPackagesRepository
```

外部使用者需在自己的仓库配置中添加对应 Maven URL `https://maven.pkg.github.com/<owner>/<repo>` 和具备读权限的凭据。远端产物存在性、可见性、版本与权限应通过实际 Gradle resolve 核实；发布是否成功以对应版本的Actions、远端POM和Release附件核验为准。公开仓库的GitHub Maven Packages下载仍需账号与具有read:packages权限的令牌；不要把凭据提交到源码。

[release.yml](../.github/workflows/release.yml)从v标签提取版本并核对VERSION_NAME，以JDK17构建六SDK、示例共用AAR和五个同签名Debug APK。先验证本地POM/module内部版本和APK版本/签名，再发布七个Maven产物、核对远端POM，最后创建Release。附件含SDK及sources/javadoc、APK、Maven仓库、源码、仓库内证据和SHA-256清单；带rc的标签标为预发行。完整历史APK证据由本地另行打包上传，CI仓库证据归档不自动包含Git忽略的历史APK。工作流存在不表示执行成功；设备NOT_RUN与构建成功分别报告。
