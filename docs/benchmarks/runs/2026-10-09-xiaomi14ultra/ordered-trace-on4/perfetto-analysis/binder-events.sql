SELECT r.ts,t.tid,p.pid,r.name,a.key,a.int_value,a.string_value
FROM raw r JOIN thread t ON r.utid=t.utid LEFT JOIN process p ON t.upid=p.upid
LEFT JOIN args a ON r.arg_set_id=a.arg_set_id
WHERE r.name IN ('binder_transaction','binder_transaction_received') ORDER BY r.ts,a.key;
