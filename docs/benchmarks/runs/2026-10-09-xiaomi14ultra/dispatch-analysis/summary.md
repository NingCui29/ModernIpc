# Dispatch experiment

Validation: **PASS**; Xiaomi `925c23bb`; captures=2.

## dispatchperf1

28,096 rows; 24 serial / 4 concurrent blocks; 4 PASS / 28 RESULT / DONE; foreground max gap 505 ms.
APK SHA256: `4494F5299DF407EA3DB2DFE915B14057A39A6DCE5F4171217237FF8F88E1F0A4`

Four-package smoke apps in end snapshot: `com.cn.ipc.server.app` (PID 2791), `com.cn.ipc.client1` (PID 3493), `com.cn.ipc.client2` (PID 4458), `com.cn.ipc.client3` (PID 5721). This is process presence, not measured load.

### Serial: median block metrics

| Characters | Mode | Blocks | P50 ms | P99 ms | Mean ms | Completion/s |
| ---: | --- | ---: | ---: | ---: | ---: | ---: |
| 16 | dedicated | 4 | 0.504948 | 0.871719 | 0.546281 | 1831.70 |
| 16 | default | 4 | 0.665885 | 1.104089 | 0.682755 | 1493.28 |
| 16 | direct | 4 | 0.282239 | 0.426510 | 0.266284 | 3769.59 |
| 1024 | dedicated | 4 | 0.536901 | 0.868750 | 0.571285 | 1757.26 |
| 1024 | default | 4 | 0.724818 | 1.374740 | 0.763941 | 1308.79 |
| 1024 | direct | 4 | 0.220052 | 0.518802 | 0.259686 | 3849.92 |

Direct is a synchronous semantic reference; its per-block and paired results remain in JSON.

### Serial: every dedicated/default paired round

Latency: negative is lower. Completion/throughput rate: positive is higher.

| Round | Characters | Default P50 ms | Dedicated P50 ms | dP50 % | dP99 % | dMean % | dCompletion/s % |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 16 | 0.843906 | 0.642656 | -23.847443 | -34.232813 | -25.367406 | +33.983959 |
| 2 | 16 | 0.552187 | 0.480000 | -13.072926 | +28.425542 | -4.036118 | +4.205310 |
| 3 | 16 | 0.779583 | 0.529896 | -32.028277 | -41.101287 | -32.025546 | +47.127387 |
| 4 | 16 | 0.476823 | 0.425729 | -10.715507 | -13.229883 | -10.446811 | +11.667213 |
| 1 | 1024 | 0.706511 | 0.500052 | -29.222333 | -49.202498 | -29.068376 | +40.984604 |
| 2 | 1024 | 0.895313 | 0.610468 | -31.815131 | -40.766575 | -34.494949 | +52.661762 |
| 3 | 1024 | 0.595625 | 0.573750 | -3.672613 | -20.761441 | -7.364107 | +7.936548 |
| 4 | 1024 | 0.743125 | 0.457084 | -38.491640 | -43.680339 | -39.436750 | +65.121510 |

### Concurrent (16 workers): median block metrics

| Characters | Mode | Blocks | P50 ms | P99 ms | Mean ms | Throughput req/s |
| ---: | --- | ---: | ---: | ---: | ---: | ---: |
| 16 | dedicated | 2 | 2.112136 | 3.234323 | 2.122251 | 7440.36 |
| 16 | default | 2 | 2.320911 | 3.507760 | 2.357218 | 6548.39 |

### Concurrent (16 workers): every dedicated/default paired round

Latency: negative is lower. Completion/throughput rate: positive is higher.

| Round | Characters | Default P50 ms | Dedicated P50 ms | dP50 % | dP99 % | dMean % | dThroughput req/s % |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 16 | 2.337604 | 2.165886 | -7.345898 | -10.724511 | -8.194324 | +13.957184 |
| 2 | 16 | 2.304219 | 2.058386 | -10.668821 | -4.408430 | -11.784664 | +13.307320 |

## dispatchperf2

28,096 rows; 24 serial / 4 concurrent blocks; 4 PASS / 28 RESULT / DONE; foreground max gap 505 ms.
APK SHA256: `4494F5299DF407EA3DB2DFE915B14057A39A6DCE5F4171217237FF8F88E1F0A4`

Four-package smoke apps in end snapshot: none observed. This is process presence, not measured load.

### Serial: median block metrics

| Characters | Mode | Blocks | P50 ms | P99 ms | Mean ms | Completion/s |
| ---: | --- | ---: | ---: | ---: | ---: | ---: |
| 16 | dedicated | 4 | 0.755495 | 1.358985 | 0.796926 | 1255.10 |
| 16 | default | 4 | 0.780885 | 1.442604 | 0.842111 | 1187.23 |
| 16 | direct | 4 | 0.299218 | 0.442968 | 0.301499 | 3312.34 |
| 1024 | dedicated | 4 | 0.736770 | 1.451093 | 0.824192 | 1222.81 |
| 1024 | default | 4 | 0.903229 | 1.525157 | 0.958181 | 1046.86 |
| 1024 | direct | 4 | 0.410547 | 0.575287 | 0.397673 | 2513.43 |

Direct is a synchronous semantic reference; its per-block and paired results remain in JSON.

### Serial: every dedicated/default paired round

Latency: negative is lower. Completion/throughput rate: positive is higher.

| Round | Characters | Default P50 ms | Dedicated P50 ms | dP50 % | dP99 % | dMean % | dCompletion/s % |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 16 | 0.797552 | 0.650313 | -18.461367 | -17.453186 | -18.091655 | +22.087804 |
| 2 | 16 | 0.730156 | 0.860677 | +17.875769 | -5.000124 | +2.642912 | -2.577235 |
| 3 | 16 | 0.817604 | 0.504739 | -38.266080 | -12.932543 | -33.927900 | +51.361582 |
| 4 | 16 | 0.764218 | 0.923386 | +20.827565 | -5.620452 | -1.269590 | +1.285709 |
| 1 | 1024 | 0.789219 | 0.626302 | -20.642813 | -13.270441 | -19.226777 | +23.808955 |
| 2 | 1024 | 1.017240 | 0.847239 | -16.711985 | -3.586574 | -13.725792 | +15.911156 |
| 3 | 1024 | 1.054896 | 1.002187 | -4.996606 | -0.758111 | -2.088233 | +2.136089 |
| 4 | 1024 | 0.649792 | 0.533594 | -17.882338 | +2.243815 | -0.943528 | +0.957878 |

### Concurrent (16 workers): median block metrics

| Characters | Mode | Blocks | P50 ms | P99 ms | Mean ms | Throughput req/s |
| ---: | --- | ---: | ---: | ---: | ---: | ---: |
| 16 | dedicated | 2 | 2.060001 | 2.854532 | 2.056287 | 7650.33 |
| 16 | default | 2 | 2.380676 | 3.616172 | 2.431121 | 6249.76 |

### Concurrent (16 workers): every dedicated/default paired round

Latency: negative is lower. Completion/throughput rate: positive is higher.

| Round | Characters | Default P50 ms | Dedicated P50 ms | dP50 % | dP99 % | dMean % | dThroughput req/s % |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 16 | 2.353020 | 2.062136 | -12.362156 | -25.469754 | -13.875694 | +24.543322 |
| 2 | 16 | 2.408333 | 2.057865 | -14.552307 | -16.152108 | -16.920957 | +20.369748 |

## Dedicated/default within-capture paired medians

The two sessions have different background process context and are not pooled as equal-load independent experiments.

### serial

| Run | Characters | Pairs | dP50 % | dP99 % | dMean % | dCompletion/s % |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| dispatchperf1 | 16 | 4 | -18.460185 | -23.731348 | -17.907108 | +22.825586 |
| dispatchperf2 | 16 | 4 | -0.292799 | -9.276497 | -9.680623 | +11.686757 |
| dispatchperf1 | 1024 | 4 | -30.518732 | -42.223457 | -31.781662 | +46.823183 |
| dispatchperf2 | 1024 | 4 | -17.297162 | -2.172342 | -7.907012 | +9.023623 |

### concurrent

| Run | Characters | Pairs | dP50 % | dP99 % | dMean % | dThroughput req/s % |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| dispatchperf1 | 16 | 2 | -9.007359 | -7.566470 | -9.989494 | +13.632252 |
| dispatchperf2 | 16 | 2 | -13.457231 | -20.810931 | -15.398325 | +22.456535 |

## Interpretation limits

- Each request is a correlated observation inside a block, not an independent experiment; no pooled-request confidence interval or significance test is computed.
- Lane summaries are medians across block metrics. Paired changes compare modes in the same run/round/payload/phase; separate captures remain separate.
- Default versus dedicated is the same Async wire/business/caller path with application server-scope configuration changed. Direct is a synchronous semantic reference, not an equivalent Async optimization.
- QPS is completed requests divided by measured block duration, including the harness envelope; concurrent QPS is not the reciprocal of mean RTT.
- Serial count/blockNs is a serial completion rate, not concurrent throughput; concurrent phase results are reported separately.
- Concurrent sample indices group each of 16 workers' 64 serial requests; they are not global request-completion order or aligned pairs across modes.
- The serial dedicated lane is always the middle lane while default/direct reverse their positions across rounds; balanced order does not eliminate period drift or carry-over.
- Foreground qualification covers app events and bounded periodic samples, not uninterrupted CPU availability, equal frequency/load, GC/JIT state, or scheduler causality.
- Trace-off and resource-cleanup claims require the benchmark's explicit PASS assertions; filtered Logcat silence is not used as proof.
- Same APK/source does not establish equal session load. End-of-capture process inventories show presence, not CPU utilization; a cross-capture difference is not attributed to stopping those processes.
