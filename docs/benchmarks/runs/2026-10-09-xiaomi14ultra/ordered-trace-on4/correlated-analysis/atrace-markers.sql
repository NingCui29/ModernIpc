SELECT s.id,s.ts,s.dur,s.name,t.tid,p.pid FROM slice s
JOIN thread_track tt ON tt.id=s.track_id JOIN thread t ON t.utid=tt.utid
JOIN process p ON p.upid=t.upid WHERE p.pid IN (1466, 3421) AND s.name GLOB 'MIPC:*' ORDER BY s.ts;
