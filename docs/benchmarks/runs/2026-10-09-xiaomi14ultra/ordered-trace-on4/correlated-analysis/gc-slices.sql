SELECT s.id,s.ts,s.dur,s.name,t.tid,p.pid FROM slice s
JOIN thread_track tt ON tt.id=s.track_id JOIN thread t ON t.utid=tt.utid
JOIN process p ON p.upid=t.upid WHERE p.pid IN (1466, 3421) AND s.dur>0
AND s.ts<813350805673026 AND s.ts+s.dur>813349827015057 AND (s.name GLOB '*GC*' OR s.name GLOB '*gc*') ORDER BY s.ts;
