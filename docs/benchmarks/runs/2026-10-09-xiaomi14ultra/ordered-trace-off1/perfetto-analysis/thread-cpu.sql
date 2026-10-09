SELECT p.pid, t.tid, p.name AS process, t.name AS thread,
COUNT(*) AS sched_slices, SUM(s.dur)/1000000.0 AS cpu_ms FROM sched s
JOIN thread t ON s.utid=t.utid JOIN process p ON t.upid=p.upid
WHERE s.dur > 0 AND (p.name = 'com.cn.ipc.demo' OR p.name GLOB 'com.cn.ipc.demo:*') GROUP BY p.pid,t.tid,p.name,t.name ORDER BY cpu_ms DESC;
