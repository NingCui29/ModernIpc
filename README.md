# Modern IPC 🚀

![Version](https://img.shields.io/badge/version-2.0.0-blue.svg)
![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)
![Kotlin](https://img.shields.io/badge/kotlin-1.9.22-orange.svg)
![Coroutines](https://img.shields.io/badge/coroutines-1.7.3-success.svg)

**Modern IPC** 是一个专为 Android 现代架构设计的**纯协程、全类型安全、零 AIDL** 的跨进程通信（IPC）框架。
抛弃传统的 `.aidl` 文件与恶心的回调地狱，直接使用 Kotlin 接口 + 注解，通过 KSP (Kotlin Symbol Processing) 在编译期自动生成所有底层 Binder 桥接代码。

完全拥抱 **Kotlin Coroutines** 与 **Kotlin Flow**，让跨进程调用像调用本地挂起函数一样简单、安全且高效。

---

## ✨ 核心特性 (v2.0.0)

- **🛑 告别 AIDL**：只需定义普通的 Kotlin `interface`，打上 `@IpcFacade` 注解即可。
- **⚡ 纯协程驱动 (Suspend)**：原生支持 `suspend fun`。底层采用非阻塞的 `PendingCallRegistry` 机制，千万级并发下也绝不发生 ANR 或死锁。
- **🚀 亚毫秒级 RTT 响应 & 多级代理缓存**：v2.0 引入本地代理多级缓存，消除每次请求重复查询 Broker 的跨进程往返，实测 RTT 突破至 **0.72ms**，平均时延压降约 40%。
- **🔥 6,100+ QPS 极限突发并发**：2,000 协程并发齐发仅用时 326ms 全部安全返回，无一超时、无一掉单。
- **🌊 跨进程 Flow 状态流订阅**：原生支持 `Flow<T>`，热流 (SharedFlow) 跨进程多点广播，冷流按需拉取，数据变化实时推送到 UI。
- **🛡️ 极致容灾与全生命周期防泄漏**：
  - **进程崩溃在途即时熔断**：Server 端意外被杀时，底层立即触发 `failAllForGeneration`，在途挂起请求抛出 `DeadObjectException` 快速恢复，杜绝页面无限期假死。
  - **指数退避重连**：结合 Jitter 随机抖动，避免服务端重启后的重连风暴。
  - **协程双向防泄漏**：客户端协程取消时 `invokeOnCancellation` 自动清理 Continuation；服务端 Flow 订阅挂载 `DeathRecipient`，客户端异常退出时服务端后台 Job 立即取消，彻底杜绝僵尸协程。
- **📦 复杂类型与原生 ByteArray 支持**：KSP 代码生成器原生支持 `ByteArray`（极速二进制传输）与 `Parcelable` 复杂对象跨进程直接返回。
- **🔐 强校验鉴权网关**：内置 `CallerAuthenticator`，从 UID、PID 到包名签名进行全链路安全拦截。
- **⚡ Oneway 极速投递**：支持 `@IpcOneway`，纯 Fire-and-Forget 单向穿透，实测吞吐达 **13,698 calls/s**，单次系统调度开销仅 **73.71 μs**。

---

## 🏗️ 模块架构设计

- `:ipc-annotations`: 定义 `@IpcFacade`, `@IpcAsync`, `@IpcStream` 等核心元数据注解。
- `:ipc-compiler`: KSP 符号处理器。负责在编译期解析接口，自动生成 `XxxClientAdapter` 和 `XxxServerStub`。
- `:ipc-runtime-client`: 客户端引擎。管理 IPC 状态机 (`IpcConnectionController`)、挂起请求调度池及重连机制。
- `:ipc-runtime-server`: 服务端引擎。包含多线程限流器 (`BoundedDispatcher`)、安全鉴权网关及 Service 容器。
- `:ipc-api`: 存放业务通信接口定义 (`IMessageHubService`, `IUserService` 等)，KSP 在此模块自动生成代理代码。
- `:demo-client-common`: 客户端公共库。封装 BaseClientActivity、IPC 状态机绑定、消息分发与统一 UI。
- `:app-server`: **服务端独立 App** (`com.cn.ipc.server.app`)。多端消息路由中枢、连接监控看板、主动消息广播控制台。
- `:app-client1`: **客户端 1 独立 App** (`com.cn.ipc.client1`，宝蓝主题)。内置对照验证控制台（一键触发广播/定向/私有测试）。
- `:app-client2`: **客户端 2 独立 App** (`com.cn.ipc.client2`，翡翠绿主题)。验证接收端。
- `:app-client3`: **客户端 3 独立 App** (`com.cn.ipc.client3`，珊瑚橙主题)。验证接收端。
- `:demo-app`: 包含全场景高并发极限压测用例的单体验证 App。

## 📦 引入 SDK (依赖配置)

本项目已配置自动化发布。你可以非常方便地通过 **GitHub Packages Maven 仓库** 在外部项目中引入本 SDK。

### 1. 接入 GitHub Packages 远程仓库
在你的外部宿主项目的 `settings.gradle.kts` 中，添加 Modern IPC 的 GitHub Packages 专属远程源：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // 声明 ModernIpc 的 GitHub 远程仓库
        maven {
            url = uri("https://maven.pkg.github.com/Cuinings/ModernIpc")
            credentials {
                // 出于 GitHub Packages 的安全机制，拉取依赖也必须提供身份验证
                username = "你的GitHub用户名" 
                // 此处必须填入具备 `read:packages` 权限的 Personal Access Token (PAT)
                password = "ghp_xxxxxx..." 
            }
        }
    }
}
```
> **🔑 获取 Token 提示**：你可以前往 GitHub 的 `Settings -> Developer settings -> Personal access tokens (classic)` 免费生成一个只勾选了 `read:packages` 权限的 Token 来作为此处的密码。

### 2. 添加具体模块依赖
在对应模块的 `build.gradle.kts` 中添加组件依赖：
```kotlin
plugins {
    id("com.google.devtools.ksp") version "1.9.22-1.0.17" // 版本号需与项目的 Kotlin 版本匹配
}

dependencies {
    // 1. 契约定义与 KSP 编译器（全端必须）
    implementation("com.modernipc:ipc-annotations:2.0.0")
    ksp("com.modernipc:ipc-compiler:2.0.0")

    // 2. Client 端进程依赖（UI 层项目引入）
    implementation("com.modernipc:ipc-runtime-client:2.0.0")

    // 3. Server 端进程依赖（独立的服务进程项目引入）
    implementation("com.modernipc:ipc-runtime-server:2.0.0")
}
```

---

## 💻 快速开始

### 1. 定义通信接口 (Contract)
在 `:ipc-api` 模块中使用普通 Kotlin 语法定义接口：

```kotlin
@IpcFacade(serviceId = 1001, minApiVersion = 1)
interface IUserService {

    // 1. 简单的单向通信（不需要返回值，极速非阻塞）
    @IpcOneway(transaction = 1)
    fun ping()

    // 2. 异步挂起请求（像调用本地函数一样调用远程方法）
    @IpcAsync(requestTransaction = 10, cancelTransaction = 11, idempotent = true)
    suspend fun getUserInfo(userId: String): String

    // 3. 流式订阅（跨进程状态同步与广播）
    @IpcStream(subscribeTransaction = 20, unsubscribeTransaction = 21)
    fun observeGlobalBroadcast(): Flow<String>
}
```

### 2. 服务端实现 & 部署
实现接口并在 `IpcBrokerService` 中注册：

```kotlin
class UserServiceImpl : IUserService {
    override suspend fun getUserInfo(userId: String): String {
        delay(1000) // 模拟高耗时数据库查询
        return "User-$userId"
    }
    // ... 其他实现
}

class MyBrokerService : IpcBrokerService() {
    override fun onCreateRegistry(): DefaultIpcServiceRegistry {
        val registry = DefaultIpcServiceRegistry()
        
        // 注册实现并挂载生成的 ServerStub
        val stub = object : IUserServiceServerStub() {
            override val coroutineScope = CoroutineScope(Dispatchers.IO)
            /* 委托给 UserServiceImpl */
        }
        
        registry.register(RegisteredService(serviceId = 1001, binder = stub, ...))
        return registry
    }
}
```

### 3. 客户端调用
在 Activity 或 ViewModel 中极简调用：

```kotlin
// 1. 建立连接
val controller = IpcConnectionController(context, targetIntent, scope)
controller.connect()

// 2. 获取生成的代理类
val userService = IUserServiceClientAdapter(controller)

// 3. 发起挂起调用
scope.launch {
    try {
        val user = userService.getUserInfo("10086")
        println("收到数据: $user")
    } catch(e: Exception) {
        println("IPC 失败或超时: $e")
    }
}

// 4. 监听跨进程 Flow
userService.observeGlobalBroadcast()
    .onEach { msg -> println("收到广播: $msg") }
    .launchIn(scope)
```

---

## 📱 1 服务端 + 3 客户端跨 App 互相通讯 Demo

本项目提供了完整的 **1 个服务端 App + 3 个客户端 App** 跨应用多端互相通讯演示。4 个应用均拥有独立 `applicationId`，可同时安装在同一台 Android 设备或模拟器上并行运行。

```mermaid
graph TD
    subgraph ServerApp [app-server 服务端: com.cn.ipc.server.app]
        Broker[ServerBrokerService]
        Router[智能消息路由器]
        ServerUI[服务端看板与广播控制]
    end

    subgraph Client1 [app-client1 客户端 1: com.cn.ipc.client1]
        C1_Ctrl[对照验证控制台]
        C1_Flow[订阅通道 Flow]
    end

    subgraph Client2 [app-client2 客户端 2: com.cn.ipc.client2]
        C2_Flow[订阅通道 Flow]
        C2_Log[消息接收列表]
    end

    subgraph Client3 [app-client3 客户端 3: com.cn.ipc.client3]
        C3_Flow[订阅通道 Flow]
        C3_Log[消息接收列表]
    end

    C1_Flow & C2_Flow & C3_Flow <==>|Binder 连接与双向 Flow 推送| Broker
    Broker --> Router
    Router -->|1. 全员广播 ALL| C2_Flow & C3_Flow
    Router -->|2. 定向推送 client_2| C2_Flow
    Router -.->|安全阻断 (收不到)| C3_Flow
    Router -->|3. 仅服务端 SERVER_ONLY| ServerUI
    Router -.->|安全阻断 (均收不到)| C2_Flow & C3_Flow
```

### 🎯 重点验证对照测试用例
在 3 个 App 订阅同一个消息通道的前提下，由 `:app-client1` 发起消息，验证隔离与分发规则：

1. **对照验证 1【全员广播】👉 验证 Client 2 和 Client 3 均能收到**
   - Client 1 发送全员广播消息（`targetScope = "ALL"`）。
   - 服务端路由广播，Client 2 和 Client 3 界面均即时收到并高亮展示。
2. **对照验证 2【定向 Client 2】👉 验证 2 能收到，3 不能收到**
   - Client 1 发送私信（`targetScope = "client_2"`）。
   - 服务端精准投递：Client 2 即刻收到，而 Client 3 **绝对收不到**（无任何新消息，保持静默）。
3. **对照验证 3【定向 Client 3】👉 验证 3 能收到，2 不能收到**
   - Client 1 发送私信（`targetScope = "client_3"`）。
   - Client 3 即刻收到，而 Client 2 **绝对收不到**。
4. **对照验证 4【仅发服务端】👉 验证 2 和 3 均不能收到**
   - Client 1 上报机密数据（`targetScope = "SERVER_ONLY"`）。
   - 消息仅在服务端控制台审计记录，Client 2 与 Client 3 **均不能收到**。
5. **服务端主动广播与定向**：服务端可一键向全网推送通知，或向 Client 1/2/3 进行精准私聊。
6. **暂停/恢复订阅动态隔离**：任一客户端点击“暂停订阅”后，所有广播均不再接收，恢复订阅后立即重新接收。

### 🚀 一键编译与安装 4 个应用
```bash
# 1. 编译全部 4 个 App 的 Debug APK
./gradlew assembleDebug

# 2. 一键安装全部 4 个应用到连接的设备/模拟器
./gradlew :app-server:installDebug
./gradlew :app-client1:installDebug
./gradlew :app-client2:installDebug
./gradlew :app-client3:installDebug
```

---

## 🎮 Demo App 全场景测试用例

强烈建议您直接运行本项目自带的 `:demo-app`。它包含 8 大极度硬核的边缘场景测试验证，点击按钮即可体验：

1. **建立 IPC 连接**：观察连接状态机流转。
2. **极限压测**：瞬间发起 **1000 个挂起请求**，测试 BoundedDispatcher 吞吐量。
3. **长连接多流订阅**：同时订阅 3 个独立状态源。
4. **容灾测试 (强制杀掉 Server 进程)**：发送死亡指令，观察客户端 UI 不崩并执行 Exponential Backoff 重连。
5. **协程取消测试 (防内存泄漏)**：发起耗时请求并瞬间 `cancel()`，证明挂起句柄被安全回收。
6. **Oneway 单向消息投递**：Fire-and-Forget 高频埋点测试。
7. **大数据量跨进程传输**：单次传递 100KB 数据测试 Binder 序列化承载力。
8. **终极考验 (SharedFlow 广播)**：动态克隆 3 个独立的 Client Controller 同时连接 Server，验证同一条热流 (SharedFlow) 对多端的毫秒级精准广播。

---

## ⚡ 性能压测实测基准 (Xiaomi 14 Ultra · 骁龙 8 Gen 3)

本项目在真实物理旗舰机型 **Xiaomi 14 Ultra (SM8650-AB · Android 16)** 上执行了全套深度压测，各场景实测数据如下：

| 压测场景与维度 | 实测指标 (v2.0.0) | 较初始版本提升 | 综合评级 |
| :--- | :--- | :---: | :---: |
| **挂起往返时延 (RTT · 1000次)** | **极小值 0.72ms** · **中位数 1.63ms** · 平均 1.69ms | **+40.1%** | ⭐️⭐️⭐️⭐️⭐️ (亚毫秒级) |
| **高并发协程突发 (2000并发)** | **耗时 326ms** · **QPS = 6,135.0 req/s** (成功率 100%) | **+10.7%** | ⭐️⭐️⭐️⭐️⭐️ (极速清仓) |
| **Oneway 极速调度 (1000次)** | **总耗时 73ms** · **吞吐 = 13,698.6 calls/s** · 单次 73.71 μs | **+315% (超4倍)** | ⭐️⭐️⭐️⭐️⭐️ (微秒级) |
| **大数据阶梯带宽 (200KB)** | **有效传输带宽 = 42.46 MB/s** (单包 RTT 4.60ms) | **+6.5%** | ⭐️⭐️⭐️⭐️⭐️ (极速吞吐) |
| **跨进程 Flow 广播 (200条)** | **100% 精准投递送达** · 吞吐 = 515.5 events/s | **100% 成功率** | ⭐️⭐️⭐️⭐️⭐️ (零漏单) |

> 详细压测过程、底层 Linux Binder 驱动机制分析与内核参数分析，请查阅：[Xiaomi 14 Ultra 真实机型性能压测报告](docs/benchmarks/benchmark_Xiaomi_14_Ultra.md)。

---

## 🛠️ 构建与编译

本项目完全使用 Gradle Kotlin DSL 构建。
```bash
# 编译整个工程并生成 KSP 代码
./gradlew build

# 运行 Demo 验证测试
./gradlew :demo-app:installDebug
```

### 🚀 发布配置 (GitHub Maven Packages)

本项目已完全接入 GitHub Packages 作为远程 Maven 仓库。

**1. 自动化发布 (CI/CD)**
只需向 GitHub 推送以 `v` 开头的 Tag（例如 `v2.0.0`），GitHub Actions 将会自动完成构建，并发布 Release 附件与 Maven 包。

**2. 本地手动发布测试**
如果需要在本地机器执行发布，请在电脑系统全局的 `~/.gradle/gradle.properties` 文件中添加以下鉴权信息（请勿将其写在项目内的配置文件中，以防泄露）：
```properties
# 你的真实 GitHub 用户名
gpr.user=YourGithubUsername
# 你的 GitHub Personal Access Token (必须勾选 write:packages 权限)
gpr.key=ghp_xxxxxxxxxxxxxxxxx
# 你的远程仓库坐标 (例如 Cuinings/ModernIpc)
gpr.repo=YourGithubUsername/ModernIpc
```
配置完成后，在根目录执行一键发布命令，即可发布到本地目录与 GitHub Packages 远程仓库：
```bash
./gradlew publish
```

## 📝 设计蓝图与文档
- **Maven 依赖与打包发布指南** 请参阅：[`docs/ModernIPC_Maven_Guide.md`](docs/ModernIPC_Maven_Guide.md)
- **开发接入指南 (必读)** 请参阅：[`docs/ModernIPC_Business_Interaction_Guide.md`](docs/ModernIPC_Business_Interaction_Guide.md)
- **架构设计全景文档** 请参阅：[`docs/ModernIPC_Architecture.md`](docs/ModernIPC_Architecture.md)
- **各机型真实设备性能压测报告** 请参阅：[`docs/benchmarks/README.md`](docs/benchmarks/README.md)（含 [Xiaomi 14 Ultra 独立报告](docs/benchmarks/benchmark_Xiaomi_14_Ultra.md)）
- 组件状态机演进模型位于 `IpcClientState` 与 `IpcConnectionController` 源码注解中。
