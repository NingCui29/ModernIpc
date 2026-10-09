SELECT s.id,s.ts,s.dur,s.cpu,t.tid,p.pid
FROM sched s JOIN thread t ON t.utid=s.utid JOIN process p ON p.upid=t.upid
WHERE p.pid IN (1466, 3421) AND s.dur>0 AND s.ts<813350805673026 AND s.ts+s.dur>813349827015057
ORDER BY p.pid,t.tid,s.ts;
