package com.cn.ipc.client.common

import android.util.Log
import com.cn.ipc.api.hub.IMessageHubService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.take
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * ModernIPC 真实设备性能压力与基准测试套件。
 */
object IpcBenchmarkSuite {

    private const val TAG = "IPC_BENCHMARK"

    data class RttStats(
        val totalCalls: Int,
        val minUs: Long,
        val avgUs: Double,
        val p50Us: Long,
        val p90Us: Long,
        val p99Us: Long,
        val maxUs: Long,
        val stdDevUs: Double
    ) {
        val minMs get() = minUs / 1000.0
        val avgMs get() = avgUs / 1000.0
        val p50Ms get() = p50Us / 1000.0
        val p90Ms get() = p90Us / 1000.0
        val p99Ms get() = p99Us / 1000.0
        val maxMs get() = maxUs / 1000.0
    }

    data class ConcurrencyStats(
        val concurrency: Int,
        val totalTimeMs: Long,
        val successCount: Int,
        val failCount: Int,
        val qps: Double,
        val avgTimeMs: Double
    )

    data class PayloadStats(
        val payloadSizeBytes: Int,
        val iterations: Int,
        val totalTimeMs: Long,
        val avgRttMs: Double,
        val throughputMBps: Double
    )

    data class OnewayStats(
        val count: Int,
        val totalTimeMs: Long,
        val throughputQps: Double,
        val avgDispatchUs: Double
    )

    data class FlowStats(
        val count: Int,
        val totalTimeMs: Long,
        val receivedCount: Int,
        val throughputEventsPerSec: Double
    )

    data class BenchmarkReport(
        val rtt: RttStats,
        val concurrency: List<ConcurrencyStats>,
        val payload: List<PayloadStats>,
        val oneway: OnewayStats,
        val flow: FlowStats
    )

    /**
     * 执行 1,000 次连续同步挂起 RPC 往返时延 (RTT) 基准测试。
     */
    suspend fun testRtt(
        service: IMessageHubService,
        clientId: String,
        iterations: Int = 1000,
        onProgress: (String) -> Unit = {}
    ): RttStats = withContext(Dispatchers.IO) {
        onProgress("正在预热 JIT 与 Binder 缓冲池 (50 次)...")
        repeat(50) {
            service.sendMessage(clientId, "SERVER_ONLY", "[BENCHMARK] warm-up $it")
        }

        onProgress("开始执行 1,000 次连续挂起 RPC 往返压测...")
        val latenciesUs = LongArray(iterations)
        val payload = "[BENCHMARK] rtt_probe"

        for (i in 0 until iterations) {
            val startNs = System.nanoTime()
            val ack = service.sendMessage(clientId, "SERVER_ONLY", payload)
            val costNs = System.nanoTime() - startNs
            latenciesUs[i] = costNs / 1000

            if ((i + 1) % 200 == 0 || i == iterations - 1) {
                onProgress("RTT 测试进度: ${i + 1}/$iterations 完成 (最新单次: ${latenciesUs[i] / 1000.0}ms)")
            }
        }

        latenciesUs.sort()
        val min = latenciesUs.first()
        val max = latenciesUs.last()
        val avg = latenciesUs.average()
        val p50 = latenciesUs[(iterations * 0.50).toInt()]
        val p90 = latenciesUs[(iterations * 0.90).toInt()]
        val p99 = latenciesUs[(iterations * 0.99).toInt()]

        var varianceSum = 0.0
        for (v in latenciesUs) {
            varianceSum += (v - avg) * (v - avg)
        }
        val stdDev = sqrt(varianceSum / iterations)

        val stats = RttStats(
            totalCalls = iterations,
            minUs = min,
            avgUs = avg,
            p50Us = p50,
            p90Us = p90,
            p99Us = p99,
            maxUs = max,
            stdDevUs = stdDev
        )

        Log.i(TAG, "=== RTT 延迟基准完成 ===")
        Log.i(TAG, "Min: ${stats.minMs}ms, Avg: ${stats.avgMs}ms, P50: ${stats.p50Ms}ms, P90: ${stats.p90Ms}ms, P99: ${stats.p99Ms}ms, Max: ${stats.maxMs}ms, StdDev: ${stats.stdDevUs / 1000.0}ms")
        stats
    }

    /**
     * 执行高并发挂起协程压测 (100, 500, 1000, 2000 并发)。
     */
    suspend fun testConcurrency(
        service: IMessageHubService,
        clientId: String,
        levels: List<Int> = listOf(100, 500, 1000, 2000),
        onProgress: (String) -> Unit = {}
    ): List<ConcurrencyStats> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ConcurrencyStats>()

        for (concurrency in levels) {
            onProgress("正在压测 [$concurrency 并发] 挂起请求...")
            val success = AtomicInteger(0)
            val fail = AtomicInteger(0)

            val startMs = System.currentTimeMillis()
            val jobs = (1..concurrency).map { idx ->
                async(Dispatchers.Default) {
                    try {
                        val ack = service.sendMessage(clientId, "SERVER_ONLY", "[BENCHMARK] conc_$concurrency#$idx")
                        success.incrementAndGet()
                    } catch (e: Throwable) {
                        fail.incrementAndGet()
                    }
                }
            }
            jobs.awaitAll()
            val durationMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1)
            val qps = (concurrency.toDouble() / durationMs) * 1000.0
            val avgTime = durationMs.toDouble() / concurrency

            val stat = ConcurrencyStats(
                concurrency = concurrency,
                totalTimeMs = durationMs,
                successCount = success.get(),
                failCount = fail.get(),
                qps = qps,
                avgTimeMs = avgTime
            )
            results.add(stat)

            onProgress("[$concurrency 并发完成]: 耗时 ${durationMs}ms, 成功 ${stat.successCount}/${concurrency}, QPS = ${"%.1f".format(qps)} req/s")
            Log.i(TAG, "Concurrency $concurrency: total=${durationMs}ms, success=${stat.successCount}, fail=${stat.failCount}, QPS=${qps}")
            delay(200) // 简短冷却
        }
        results
    }

    /**
     * 大数据包传输阶梯压测 (1KB ~ 300KB 以及极限 500KB 边界探测)。
     */
    suspend fun testPayloadScaling(
        service: IMessageHubService,
        clientId: String,
        sizes: List<Int> = listOf(1024, 10 * 1024, 50 * 1024, 100 * 1024, 200 * 1024, 300 * 1024),
        iterationsPerSize: Int = 20,
        onProgress: (String) -> Unit = {}
    ): List<PayloadStats> = withContext(Dispatchers.IO) {
        val results = mutableListOf<PayloadStats>()
        val prefix = "[BENCHMARK] DATA:"

        for (size in sizes) {
            val padLen = (size - prefix.length).coerceAtLeast(0)
            val body = "K".repeat(padLen)
            val payload = prefix + body
            val sizeKb = size / 1024

            onProgress("正在压测大数据传输 [${sizeKb}KB] (每轮测试 $iterationsPerSize 次)...")
            val startMs = System.currentTimeMillis()

            var success = 0
            for (i in 1..iterationsPerSize) {
                try {
                    service.sendMessage(clientId, "SERVER_ONLY", payload)
                    success++
                } catch (e: Throwable) {
                    Log.w(TAG, "Payload at $sizeKb KB failed (Binder limit): ${e.message}")
                }
            }

            val totalMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1)
            val avgRttMs = totalMs.toDouble() / iterationsPerSize
            val totalBytesTransferred = size.toLong() * success
            val throughputMBps = if (totalMs > 0 && success > 0) {
                (totalBytesTransferred / (1024.0 * 1024.0)) / (totalMs / 1000.0)
            } else 0.0

            val stat = PayloadStats(
                payloadSizeBytes = size,
                iterations = iterationsPerSize,
                totalTimeMs = totalMs,
                avgRttMs = avgRttMs,
                throughputMBps = throughputMBps
            )
            results.add(stat)

            onProgress("[${sizeKb}KB 数据包]: 成功 $success/$iterationsPerSize, 平均 RTT = ${"%.2f".format(avgRttMs)}ms, 传输吞吐带宽 = ${"%.2f".format(throughputMBps)} MB/s")
            Log.i(TAG, "Payload ${sizeKb}KB: total=${totalMs}ms, success=$success, avgRTT=${avgRttMs}ms, throughput=${throughputMBps} MB/s")
            delay(100)
        }
        results
    }

    /**
     * Oneway 极速单向投递压测 (2,000 次，分批防 Binder 缓冲区耗尽)。
     */
    suspend fun testOneway(
        service: IMessageHubService,
        clientId: String,
        count: Int = 2000,
        onProgress: (String) -> Unit = {}
    ): OnewayStats = withContext(Dispatchers.IO) {
        onProgress("正在执行 Oneway 极速单向投递压测 ($count 次连续调用)...")
        val startNs = System.nanoTime()

        for (i in 0 until count) {
            service.ping(clientId)
            if (i % 100 == 99) {
                delay(2) // 微量休眠释放 Binder 驱动缓冲区
            }
        }

        val totalNs = System.nanoTime() - startNs
        val totalMs = (totalNs / 1_000_000).coerceAtLeast(1)
        val qps = (count.toDouble() / totalMs) * 1000.0
        val avgDispatchUs = (totalNs / 1000.0) / count

        val stat = OnewayStats(
            count = count,
            totalTimeMs = totalMs,
            throughputQps = qps,
            avgDispatchUs = avgDispatchUs
        )

        onProgress("[Oneway 完成]: $count 次发送总耗时 ${totalMs}ms, 客户端吞吐率 = ${"%.1f".format(qps)} calls/sec, 单次调度 = ${"%.2f".format(avgDispatchUs)} μs")
        Log.i(TAG, "Oneway $count: total=${totalMs}ms, QPS=${qps}, avgDispatchUs=${avgDispatchUs}us")
        stat
    }

    /**
     * 跨进程 Flow 热流路由吞吐压测 (200 条广播流)。
     */
    suspend fun testFlowBroadcast(
        service: IMessageHubService,
        clientId: String,
        count: Int = 200,
        flowListenerSetter: (( ((String) -> Unit)? ) -> Unit)? = null,
        onProgress: (String) -> Unit = {}
    ): FlowStats = withContext(Dispatchers.IO) {
        onProgress("正在准备 $count 条跨进程 Flow 管道吞吐压测...")

        val receivedCount = AtomicInteger(0)
        flowListenerSetter?.invoke { msg ->
            receivedCount.incrementAndGet()
        }

        delay(300) // 确保 Flow 监听就绪
        onProgress("正在向 Flow 管道推入 $count 条事件...")

        val startMs = System.currentTimeMillis()
        // 定向发给自己以形成完整的 Client->Server->Flow->Client 跨进程环回压测
        for (i in 1..count) {
            service.sendMessage(clientId, clientId, "[BENCHMARK] flow_event_$i")
            if (i % 20 == 0) delay(1) // 微量喘息防缓冲区堆积
        }

        // 等待 Flow 接收完毕，最多等待 5 秒
        val deadline = System.currentTimeMillis() + 5000
        while (receivedCount.get() < count && System.currentTimeMillis() < deadline) {
            delay(30)
        }
        flowListenerSetter?.invoke(null)

        val totalMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1)
        val throughput = (receivedCount.get().toDouble() / totalMs) * 1000.0

        val stat = FlowStats(
            count = count,
            totalTimeMs = totalMs,
            receivedCount = receivedCount.get(),
            throughputEventsPerSec = throughput
        )

        onProgress("[Flow 吞吐完成]: 推送 $count 条, 成功接收 ${stat.receivedCount} 条, 耗时 ${totalMs}ms, 吞吐 = ${"%.1f".format(throughput)} events/s")
        Log.i(TAG, "Flow Broadcast: sent=$count, received=${stat.receivedCount}, total=${totalMs}ms, throughput=$throughput")
        stat
    }

    /**
     * 运行全量性能基准测试套件。
     */
    suspend fun runFullBenchmark(
        service: IMessageHubService,
        clientId: String,
        flowListenerSetter: (( ((String) -> Unit)? ) -> Unit)? = null,
        onProgress: (String) -> Unit = {}
    ): BenchmarkReport {
        Log.i(TAG, "================== MODERN IPC BENCHMARK START ==================")
        onProgress("⚡ ModernIPC 真实设备性能压测开始...")

        // 1. RTT 延迟基准
        val rtt = runCatching {
            testRtt(service, clientId, 1000, onProgress)
        }.getOrElse { e ->
            Log.e(TAG, "RTT 测试异常: ${e.message}")
            RttStats(0, 0, 0.0, 0, 0, 0, 0, 0.0)
        }
        delay(1000)

        // 2. 高并发压测 (至 2,000 并发)
        val concurrency = runCatching {
            testConcurrency(service, clientId, listOf(100, 500, 1000, 2000), onProgress)
        }.getOrElse { e ->
            Log.e(TAG, "并发测试异常: ${e.message}")
            emptyList()
        }
        delay(1000)

        // 3. 大数据包传输阶梯压测
        val payload = runCatching {
            testPayloadScaling(service, clientId, listOf(1024, 10 * 1024, 50 * 1024, 100 * 1024, 200 * 1024), 10, onProgress)
        }.getOrElse { e ->
            Log.e(TAG, "大包测试异常: ${e.message}")
            emptyList()
        }
        delay(1000)

        // 4. Oneway 极速投递 (1,000 次)
        val oneway = runCatching {
            testOneway(service, clientId, 1000, onProgress)
        }.getOrElse { e ->
            Log.e(TAG, "Oneway 测试异常: ${e.message}")
            OnewayStats(0, 1, 0.0, 0.0)
        }
        delay(1000)

        // 5. 跨进程 Flow 路由与广播 (200 条)
        val flow = runCatching {
            testFlowBroadcast(service, clientId, 200, flowListenerSetter, onProgress)
        }.getOrElse { e ->
            Log.e(TAG, "Flow 测试异常: ${e.message}")
            FlowStats(0, 1, 0, 0.0)
        }

        val report = BenchmarkReport(rtt, concurrency, payload, oneway, flow)
        Log.i(TAG, "================== MODERN IPC BENCHMARK COMPLETE ==================")
        onProgress("🎉 全套性能压测已全部执行完成！")
        return report
    }
}
