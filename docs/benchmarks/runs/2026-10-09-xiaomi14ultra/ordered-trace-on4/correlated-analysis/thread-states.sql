SELECT st.id,st.ts,st.dur,st.state,st.cpu,st.utid,t.tid,p.pid,
st.io_wait,st.blocked_function,st.waker_utid,w.tid AS waker_tid,wp.pid AS waker_pid
FROM thread_state st JOIN thread t ON t.utid=st.utid JOIN process p ON p.upid=t.upid
LEFT JOIN thread w ON w.utid=st.waker_utid LEFT JOIN process wp ON wp.upid=w.upid
WHERE p.pid IN (1466, 3421) AND st.dur>0 AND st.ts<813350805673026 AND st.ts+st.dur>813349827015057
ORDER BY p.pid,t.tid,st.ts;
