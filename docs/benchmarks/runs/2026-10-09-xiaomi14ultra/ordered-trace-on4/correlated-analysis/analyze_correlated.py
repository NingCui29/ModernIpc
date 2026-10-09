"""Read-only request/PID correlation of saved Perfetto and authoritative event files.

Writes only this analysis directory. No ADB, capture, raw-file rewrite, or production edit.
Stage bounds always use buffered elapsedRealtimeNanos, never ATrace emission timestamps.
"""
import bisect
import csv
import hashlib
import io
import json
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
RUN_DIR = HERE.parent
ROOT = next(path for path in HERE.parents if (path / "docs/benchmarks/harness").is_dir())
sys.path.insert(0, str(ROOT / "docs/benchmarks/harness"))
from analyze_request_trace import PAIRS, read_event_files, request_groups, validate_benchmark_artifacts

RUN = "traceon4"
PROCESSOR = ROOT / ".gradle/trace-tools/trace_processor_shell.exe"
TRACE = RUN_DIR / "request-trace.perfetto-trace"


def query(name, sql):
    (HERE / f"{name}.sql").write_text(sql + "\n", encoding="utf-8")
    result = subprocess.run([str(PROCESSOR), "-Q", sql, str(TRACE)], capture_output=True,
                            text=True, encoding="utf-8", errors="strict", timeout=60)
    (HERE / f"{name}.stderr.txt").write_text(result.stderr, encoding="utf-8")
    (HERE / f"{name}.csv").write_text(result.stdout, encoding="utf-8")
    if result.returncode:
        raise RuntimeError(f"Perfetto query failed: {name}")
    return list(csv.DictReader(io.StringIO(result.stdout)))


def integer(value):
    return None if value in (None, "[NULL]", "") else int(value)


def metrics(values):
    values = sorted(values)
    if not values:
        return {"count": 0, "meanMs": None, "p50Ms": None, "p90Ms": None, "p99Ms": None, "maxMs": None}
    return dict(count=len(values), meanMs=sum(values) / len(values) / 1e6,
                p50Ms=values[int((len(values)-1)*.5)]/1e6,
                p90Ms=values[int((len(values)-1)*.9)]/1e6,
                p99Ms=values[int((len(values)-1)*.99)]/1e6, maxMs=max(values)/1e6)


def write_csv(name, rows):
    if not rows:
        raise ValueError(f"No rows: {name}")
    with (HERE / name).open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def main():
    # Loss statistics are the first processor query, before any attribution.
    stats = query("loss-statistics", "SELECT name,idx,severity,source,value FROM stats ORDER BY name,idx;")
    nonzero_bad = [row for row in stats if row["severity"] in ("error", "data_loss", "notice") and int(row["value"]) != 0]
    if nonzero_bad:
        raise ValueError(f"Nonzero loss/error/notice statistics: {nonzero_bad}")
    artifact = validate_benchmark_artifacts(RUN_DIR, RUN, True)
    events, ends, sources = read_event_files(RUN_DIR, RUN)
    requests, unjoined = request_groups(events, True)
    assert len(requests) == 1000 and unjoined == 0
    pids = (ends["client"]["pid"], ends["server"]["pid"])
    low, high = min(event["ns"] for event in events), max(event["ns"] for event in events)
    clocks = query("clock-snapshots", "SELECT * FROM clock_snapshot ORDER BY snapshot_id,clock_id;")
    boot = [row for row in clocks if row["clock_name"] == "BOOTTIME"]
    offsets = [int(row["ts"]) - int(row["clock_value"]) for row in boot]
    if not offsets or set(offsets) != {0}:
        raise ValueError(f"BOOTTIME and processor trace timestamp domains are not identical: {offsets}")
    bounds = query("trace-bounds", "SELECT * FROM trace_bounds;")[0]
    if low < int(bounds["start_ts"]) or high > int(bounds["end_ts"]):
        raise ValueError("Request event timestamps lie outside Perfetto trace bounds")
    processes = query("process-identity", f"SELECT * FROM process WHERE pid IN {pids};")
    service_version = query("service-version", "SELECT name,str_value FROM metadata WHERE name='tracing_service_version';")
    first_sched = query("first-sched-by-cpu", "SELECT cpu,MIN(ts) AS first_sched_ns FROM sched GROUP BY cpu ORDER BY cpu;")
    states = query("thread-states", f"""SELECT st.id,st.ts,st.dur,st.state,st.cpu,st.utid,t.tid,p.pid,
st.io_wait,st.blocked_function,st.waker_utid,w.tid AS waker_tid,wp.pid AS waker_pid
FROM thread_state st JOIN thread t ON t.utid=st.utid JOIN process p ON p.upid=t.upid
LEFT JOIN thread w ON w.utid=st.waker_utid LEFT JOIN process wp ON wp.upid=w.upid
WHERE p.pid IN {pids} AND st.dur>0 AND st.ts<{high} AND st.ts+st.dur>{low}
ORDER BY p.pid,t.tid,st.ts;""")
    sched = query("sched-slices", f"""SELECT s.id,s.ts,s.dur,s.cpu,t.tid,p.pid
FROM sched s JOIN thread t ON t.utid=s.utid JOIN process p ON p.upid=t.upid
WHERE p.pid IN {pids} AND s.dur>0 AND s.ts<{high} AND s.ts+s.dur>{low}
ORDER BY p.pid,t.tid,s.ts;""")
    markers = query("atrace-markers", f"""SELECT s.id,s.ts,s.dur,s.name,t.tid,p.pid FROM slice s
JOIN thread_track tt ON tt.id=s.track_id JOIN thread t ON t.utid=tt.utid
JOIN process p ON p.upid=t.upid WHERE p.pid IN {pids} AND s.name GLOB 'MIPC:*' ORDER BY s.ts;""")
    binder = query("binder-flows", f"""SELECT f.id,so.id AS out_id,si.id AS in_id,
so.ts AS send_ns,si.ts AS recv_ns,so.name AS out_name,si.name AS in_name,
po.pid AS src_pid,tt.tid AS src_tid,pi.pid AS dst_pid,ti.tid AS dst_tid,
EXTRACT_ARG(so.arg_set_id,'transaction id') AS transaction_id,
EXTRACT_ARG(so.arg_set_id,'destination node') AS destination_node,
EXTRACT_ARG(so.arg_set_id,'code') AS code,EXTRACT_ARG(so.arg_set_id,'flags') AS flags
FROM flow f JOIN slice so ON so.id=f.slice_out JOIN slice si ON si.id=f.slice_in
JOIN thread_track sto ON sto.id=so.track_id JOIN thread tt ON tt.utid=sto.utid
JOIN process po ON po.upid=tt.upid JOIN thread_track sti ON sti.id=si.track_id
JOIN thread ti ON ti.utid=sti.utid JOIN process pi ON pi.upid=ti.upid
WHERE po.pid IN {pids} AND pi.pid IN {pids} AND po.pid!=pi.pid
AND so.ts>={low} AND si.ts<={high} ORDER BY so.ts;""")
    gc = query("gc-slices", f"""SELECT s.id,s.ts,s.dur,s.name,t.tid,p.pid FROM slice s
JOIN thread_track tt ON tt.id=s.track_id JOIN thread t ON t.utid=tt.utid
JOIN process p ON p.upid=t.upid WHERE p.pid IN {pids} AND s.dur>0
AND s.ts<{high} AND s.ts+s.dur>{low} AND (s.name GLOB '*GC*' OR s.name GLOB '*gc*') ORDER BY s.ts;""")
    by_thread = defaultdict(list)
    sched_by_thread = defaultdict(list)
    for row in states:
        for key in ("ts", "dur", "pid", "tid", "waker_tid", "waker_pid"):
            row[key] = integer(row[key])
        by_thread[row["pid"], row["tid"]].append(row)
    for row in sched:
        for key in ("ts", "dur", "pid", "tid"):
            row[key] = integer(row[key])
        sched_by_thread[row["pid"], row["tid"]].append(row)
    starts = {key: [row["ts"] for row in rows] for key, rows in by_thread.items()}
    sched_starts = {key: [row["ts"] for row in rows] for key, rows in sched_by_thread.items()}

    def overlaps(rows, index, start, end):
        for row in rows[max(0, bisect.bisect_right(index, start)-1):]:
            if row["ts"] >= end:
                break
            duration = min(end, row["ts"]+row["dur"]) - max(start, row["ts"])
            if duration > 0:
                yield row, duration

    interval_rows, stage_rows, flow_rows = [], [], []
    for request in requests:
        st = request["stages"]
        rid = request["key"][-1]
        for name, (first, last) in PAIRS.items():
            stage_rows.append(dict(requestId=rid, interval=name, wallNs=st[last]["ns"]-st[first]["ns"]))
        definitions = (
            ("server_admission", "receive_thread", "server_receive", "server_enqueued", "server_receive", None),
            ("server_queue", "target_worker", "server_enqueued", "server_job_start", "server_job_start", "server_enqueued"),
            ("server_queue", "source_binder", "server_enqueued", "server_job_start", "server_enqueued", None),
            ("client_resume", "target_worker", "client_reply", "client_resume", "client_resume", "client_reply"),
            ("client_resume", "source_callback", "client_reply", "client_resume", "client_reply", None),
            ("client_resolve", "client_worker", "client_entry", "client_resolved", "client_resolved", None),
            ("client_register", "client_worker", "client_resolved", "client_registered", "client_registered", None),
            ("client_encode", "client_worker", "client_registered", "client_send", "client_send", None),
        )
        for name, role, first, last, target, waker in definitions:
            start, end = st[first]["ns"], st[last]["ns"]
            key = st[target]["pid"], st[target]["tid"]
            categories = Counter()
            matching_wakes = []
            for row, duration in overlaps(by_thread[key], starts[key], start, end):
                state = row["state"]
                category = "running" if state == "Running" else "runnable" if state in ("R", "R+") else "sleep" if state == "S" else "uninterruptible" if state == "D" else "other"
                categories[category] += duration
                if waker and state in ("R", "R+") and start <= row["ts"] < end and (row["waker_pid"], row["waker_tid"]) == (st[waker]["pid"], st[waker]["tid"]):
                    matching_wakes.append(row)
            running = sum(duration for _, duration in overlaps(sched_by_thread[key], sched_starts[key], start, end))
            covered = sum(categories.values())
            if covered > end-start or running != categories["running"]:
                raise ValueError(f"Inconsistent scheduler/state coverage: {rid} {name} {role}")
            row = dict(requestId=rid, interval=name, role=role, pid=key[0], tid=key[1],
                       startNs=start, endNs=end, wallNs=end-start,
                       runningNs=categories["running"], runnableNs=categories["runnable"],
                       sleepNs=categories["sleep"], uninterruptibleNs=categories["uninterruptible"],
                       otherNs=categories["other"], uncoveredNs=end-start-covered,
                       matchingWakeCount=len(matching_wakes),
                       matchingWakeNs=matching_wakes[0]["ts"] if len(matching_wakes)==1 else None,
                       matchingWakeToRunNs=matching_wakes[0]["dur"] if len(matching_wakes)==1 else None)
            interval_rows.append(row)
        for direction, first, last in (("request", "client_send", "server_receive"), ("reply", "server_reply", "client_reply")):
            a,b=st[first],st[last]
            matching = [row for row in binder if int(row["src_pid"])==a["pid"] and int(row["src_tid"])==a["tid"]
                        and int(row["dst_pid"])==b["pid"] and int(row["dst_tid"])==b["tid"]
                        and a["ns"]<=int(row["send_ns"])<=int(row["recv_ns"])<=b["ns"]
                        and row["out_name"]=="binder transaction async" and row["in_name"]=="binder async rcv"]
            if len(matching)!=1:
                raise ValueError(f"Binder association ambiguous/missing: {rid} {direction} matches={len(matching)}")
            flow=matching[0]
            receiver_state = Counter()
            target = b["pid"],b["tid"]
            for state,duration in overlaps(by_thread[target], starts[target], int(flow["send_ns"]), int(flow["recv_ns"])):
                category = "running" if state["state"]=="Running" else "runnable" if state["state"] in ("R","R+") else "sleep" if state["state"]=="S" else "other"
                receiver_state[category] += duration
            kernel_span = int(flow["recv_ns"])-int(flow["send_ns"])
            flow_rows.append(dict(requestId=rid,direction=direction,flowId=int(flow["id"]),
                                  transactionId=int(flow["transaction_id"]),srcPid=a["pid"],srcTid=a["tid"],
                                  dstPid=b["pid"],dstTid=b["tid"],code=flow["code"],flags=flow["flags"],
                                  stageBeforeKernelNs=int(flow["send_ns"])-a["ns"],
                                  kernelSendToReceiveNs=kernel_span,
                                  kernelToStageAfterNs=b["ns"]-int(flow["recv_ns"]),
                                  receiverRunningNs=receiver_state["running"],receiverRunnableNs=receiver_state["runnable"],
                                  receiverSleepNs=receiver_state["sleep"],receiverOtherNs=receiver_state["other"],
                                  receiverUncoveredNs=kernel_span-sum(receiver_state.values())))
    write_csv("request-thread-state-breakdown.csv", interval_rows)
    write_csv("request-binder-breakdown.csv", flow_rows)
    marker_offsets = []
    event_by_identity = {(event["pid"],event["requestId"],event["stage"]):event for event in events}
    for marker in markers:
        _,stage,rid = marker["name"].split(":")
        event=event_by_identity[int(marker["pid"]),int(rid),stage]
        marker_offsets.append(dict(requestId=int(rid),stage=stage,pid=int(marker["pid"]),
                                   eventNs=event["ns"],atraceEmissionNs=int(marker["ts"]),
                                   emissionAfterEventNs=int(marker["ts"])-event["ns"]))
    write_csv("marker-emission-offsets.csv", marker_offsets)
    stage_summary={name:metrics([row["wallNs"] for row in stage_rows if row["interval"]==name]) for name in PAIRS}
    interval_summary={}
    for name,role in sorted({(row["interval"],row["role"]) for row in interval_rows}):
        rows=[row for row in interval_rows if (row["interval"],row["role"])==(name,role)]
        interval_summary[name+"/"+role]={
            "count":len(rows),"fullyCovered":sum(row["uncoveredNs"]==0 for row in rows),
            "matchingWakeRequests":sum(row["matchingWakeCount"]==1 for row in rows),
            "totalsNs":{key:sum(row[key] for row in rows) for key in ("wallNs","runningNs","runnableNs","sleepNs","uninterruptibleNs","otherNs","uncoveredNs")},
            "metrics":{key:metrics([row[key] for row in rows]) for key in ("wallNs","runningNs","runnableNs","sleepNs")},
        }
    flow_summary={direction:{key:metrics([row[key] for row in flow_rows if row["direction"]==direction])
                            for key in ("stageBeforeKernelNs","kernelSendToReceiveNs","kernelToStageAfterNs",
                                        "receiverRunningNs","receiverRunnableNs","receiverSleepNs","receiverOtherNs","receiverUncoveredNs")}
                  for direction in ("request","reply")}
    capture_metadata=json.loads((RUN_DIR/"capture-metadata.json").read_text(encoding="utf-8-sig"))
    summary=dict(run=RUN,traceSha256=hashlib.sha256(TRACE.read_bytes()).hexdigest().upper(),
                 provenance={key:capture_metadata[key] for key in ("serial","apkSha256","installedSha256")},
                 artifactValidation=artifact,processMetadata=processes,
                 clock=dict(boottimeOffsetsNs=offsets,verifiedSameDomain=True,eventMinNs=low,eventMaxNs=high,traceBounds=bounds),
                 loss=dict(nonzeroBad=nonzero_bad,nonzeroCautions=[row for row in stats if int(row["value"])!=0 and any(word in row["name"] for word in ("discard","skipped","clock_sync"))],
                           tracingServiceVersion=service_version,firstSchedByCpu=first_sched,
                           latestFirstSchedBeforeRequestMs=(low-max(int(row["first_sched_ns"]) for row in first_sched))/1e6,
                           interpretation=["Eight switch and eight waking skips are per-CPU initialization, completed before the request window.",
                                           "Global traced_chunks_discarded=1 is a tracing-service lifetime count, distinct from current-session buffer discard.",
                                           "There is only one final TraceStats packet and no starting counter; the global discarded count cannot be assigned to historical or present requests.",
                                           "Zero session buffer/kernel loss counters and complete request-window state coverage support this analysis; absolute zero loss is not established."]),
                 stages=stage_summary,intervals=interval_summary,binder=flow_summary,
                 binderAssociatedPairs=len(flow_rows),uniqueBinderTransactionIds=len({row["transactionId"] for row in flow_rows}),
                 fullyCoveredBinderReceiverWindows=sum(row["receiverUncoveredNs"]==0 for row in flow_rows),
                 slowestBinderWindows={direction:sorted([row for row in flow_rows if row["direction"]==direction],
                                                           key=lambda row:row["kernelSendToReceiveNs"],reverse=True)[:5]
                                       for direction in ("request","reply")},
                 slowestTargetWindows={name:sorted([row for row in interval_rows if row["interval"]==name and row["role"]=="target_worker"],
                                                    key=lambda row:row["wallNs"],reverse=True)[:5]
                                       for name in ("server_queue","client_resume")},
                 markerCountsByPid=dict(Counter(row["pid"] for row in marker_offsets)),
                 markerOffsets={stage:metrics([row["emissionAfterEventNs"] for row in marker_offsets if row["stage"]==stage]) for stage in sorted({row["stage"] for row in marker_offsets})},
                 overlappingGcSlices=gc,
                 copyingGcRequestOverlap=[dict(sliceId=int(row["id"]),requestIds=[request["key"][-1] for request in requests
                                            if request["stages"]["client_entry"]["ns"]<int(row["ts"])+int(row["dur"])
                                            and request["stages"]["client_resume"]["ns"]>int(row["ts"])])
                                          for row in gc if "copying GC" in row["name"]],
                 references=["https://github.com/google/perfetto/blob/v58.2/src/trace_processor/importers/ftrace/ftrace_sched_event_tracker.cc#L132",
                             "https://github.com/google/perfetto/blob/v49.0/src/tracing/service/tracing_service_impl.cc#L2899",
                             "https://github.com/google/perfetto/blob/v49.0/src/tracing/service/tracing_service_impl.h#L856",
                             "https://github.com/google/perfetto/blob/v58.2/protos/perfetto/common/trace_stats.proto#L170",
                             "https://developer.android.com/reference/android/os/SystemClock#elapsedRealtimeNanos()"],
                 limits=["Thread Running time is CPU residency, including any unrelated work on that thread; it is not a method profile.",
                         "Source and destination thread breakdowns overlap in wall time and must not be summed.",
                         "Sleep before a target wake can include callback decode/resume or dispatch preparation; it is not all scheduler queue delay.",
                         "Only buffered event ns defines stage boundaries; ATrace offsets are reported separately, never substituted.",
                         "GC-name searches also include JIT names mentioning GC; only actual copying-GC slices are used for request-overlap attribution.",
                         "Diagnostic trace instrumentation changes scheduling/allocation cost; tracing-disabled performance needs its own repeated comparison.",
                         "Process names can be stale after USAP specialization; event PID/TID and Binder flows establish this request scope."])
    (HERE/"summary.json").write_text(json.dumps(summary,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({"count":len(requests),"clock":summary["clock"],"stages":stage_summary,"intervals":interval_summary,"binder":flow_summary,"gc":gc},indent=2))


if __name__ == "__main__":
    main()
