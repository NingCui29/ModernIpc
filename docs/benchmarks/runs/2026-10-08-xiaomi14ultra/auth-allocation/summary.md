Validated 16 CSV files, 16,000 positive ns samples, 16 RESULT records and 4 DONE records.

| Run | Mode | API | P50 ms | P99 ms | Max ms |
| --- | --- | --- | ---: | ---: | ---: |
| authab1 | legacy | suspend | 1.428281 | 4.172188 | 12.607657 |
| authab1 | legacy | direct | 0.251823 | 0.410990 | 2.357761 |
| authab1 | optimized | suspend | 1.902083 | 4.969479 | 11341.402026 |
| authab1 | optimized | direct | 0.108230 | 0.741354 | 7.446562 |
| authab2 | legacy | suspend | 0.689323 | 1.486510 | 1.975573 |
| authab2 | legacy | direct | 0.316406 | 0.492031 | 0.940417 |
| authab2 | optimized | suspend | 0.593178 | 1.672292 | 4.531302 |
| authab2 | optimized | direct | 0.121562 | 0.921927 | 3.771614 |
| authab3 | legacy | suspend | 0.807344 | 1.492031 | 2.692605 |
| authab3 | legacy | direct | 0.288750 | 0.444271 | 0.735364 |
| authab3 | optimized | suspend | 0.571458 | 1.871875 | 8.554636 |
| authab3 | optimized | direct | 0.282604 | 0.594792 | 1.034739 |
| authab4 | legacy | suspend | 0.514323 | 1.485573 | 4.481302 |
| authab4 | legacy | direct | 0.125990 | 1.038906 | 6.002969 |
| authab4 | optimized | suspend | 0.936146 | 1.438281 | 2.971458 |
| authab4 | optimized | direct | 0.253594 | 0.491875 | 1.441042 |

Relative change = (optimized / legacy - 1) * 100%; negative means lower latency.

| Run | API | P50 change % | P99 change % |
| --- | --- | ---: | ---: |
| authab1 | suspend | +33.17 | +19.11 |
| authab1 | direct | -57.02 | +80.38 |
| authab2 | suspend | -13.95 | +12.50 |
| authab2 | direct | -61.58 | +87.37 |
| authab3 | suspend | -29.22 | +25.46 |
| authab3 | direct | -2.13 | +33.88 |
| authab4 | suspend | +82.02 | -3.18 |
| authab4 | direct | +101.28 | -52.65 |

| API | Legacy median-run-P50 ms | Optimized median-run-P50 ms | Change % |
| --- | ---: | ---: | ---: |
| suspend | 0.748333 | 0.764662 | +2.18 |
| direct | 0.270286 | 0.187578 | -30.60 |

| Micro TIME batch | Mode | Count | Thread CPU ns/call | Wall ns/call |
| --- | --- | ---: | ---: | ---: |
| 0 | legacy | 50000 | 2321.169 | 2430.671 |
| 1 | optimized | 50000 | 950.951 | 1002.867 |
| 2 | optimized | 50000 | 321.698 | 323.910 |
| 3 | legacy | 50000 | 818.562 | 828.728 |
| 0 | legacy | 50000 | 515.284 | 554.360 |
| 1 | optimized | 50000 | 187.022 | 188.478 |
| 2 | optimized | 50000 | 187.970 | 191.466 |
| 3 | legacy | 50000 | 444.298 | 448.257 |

| Micro ALLOC batch | Mode | Count | Process-average bytes/call | GC delta |
| --- | --- | ---: | ---: | ---: |
| 0 | legacy | 50000 | 64.225 | 0 |
| 1 | optimized | 50000 | 0.000 | 0 |
| 2 | optimized | 50000 | 0.000 | 0 |
| 3 | legacy | 50000 | 64.225 | 0 |
| 0 | legacy | 50000 | 65.208 | 1 |
| 1 | optimized | 50000 | 0.000 | 0 |
| 2 | optimized | 50000 | 0.000 | 0 |
| 3 | legacy | 50000 | 63.898 | 0 |
Runtime allocation statistics are approximate process totals, not exact per-call allocations.
