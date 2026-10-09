SELECT f.id,so.id AS out_id,si.id AS in_id,
so.ts AS send_ns,si.ts AS recv_ns,so.name AS out_name,si.name AS in_name,
po.pid AS src_pid,tt.tid AS src_tid,pi.pid AS dst_pid,ti.tid AS dst_tid,
EXTRACT_ARG(so.arg_set_id,'transaction id') AS transaction_id,
EXTRACT_ARG(so.arg_set_id,'destination node') AS destination_node,
EXTRACT_ARG(so.arg_set_id,'code') AS code,EXTRACT_ARG(so.arg_set_id,'flags') AS flags
FROM flow f JOIN slice so ON so.id=f.slice_out JOIN slice si ON si.id=f.slice_in
JOIN thread_track sto ON sto.id=so.track_id JOIN thread tt ON tt.utid=sto.utid
JOIN process po ON po.upid=tt.upid JOIN thread_track sti ON sti.id=si.track_id
JOIN thread ti ON ti.utid=sti.utid JOIN process pi ON pi.upid=ti.upid
WHERE po.pid IN (1466, 3421) AND pi.pid IN (1466, 3421) AND po.pid!=pi.pid
AND so.ts>=813349827015057 AND si.ts<=813350805673026 ORDER BY so.ts;
