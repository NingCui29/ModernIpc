SELECT p.pid,t.tid,t.name,st.state,COUNT(*) AS slices,
SUM(st.dur)/1000000.0 AS duration_ms FROM thread_state st JOIN thread t ON st.utid=t.utid
JOIN process p ON t.upid=p.upid WHERE st.dur > 0 AND (p.name = 'com.cn.ipc.demo' OR p.name GLOB 'com.cn.ipc.demo:*')
GROUP BY p.pid,t.tid,t.name,st.state ORDER BY duration_ms DESC;
