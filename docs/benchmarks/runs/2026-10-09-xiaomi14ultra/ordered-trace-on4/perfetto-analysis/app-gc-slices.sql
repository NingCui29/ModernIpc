SELECT s.ts,s.dur,p.pid,t.tid,t.name,s.name FROM slice s
JOIN thread_track tt ON s.track_id=tt.id JOIN thread t ON tt.utid=t.utid
JOIN process p ON t.upid=p.upid WHERE (p.name = 'com.cn.ipc.demo' OR p.name GLOB 'com.cn.ipc.demo:*')
AND (s.name GLOB 'MIPC:*' OR s.name GLOB 'Ipc*' OR s.name GLOB '*GC*' OR s.name GLOB '*gc*') ORDER BY s.ts;
