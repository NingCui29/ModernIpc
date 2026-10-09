"""Validate and summarize the four-run, same-APK authentication benchmark."""

import math
import re
import statistics
import sys
from pathlib import Path


RUNS = tuple(f"authab{index}" for index in range(1, 5))
MODES = ("legacy", "optimized")
APIS = ("suspend", "direct")
SAMPLE_COUNT = 1000


def fields(message: str) -> dict[str, str]:
    return dict(re.findall(r"\b([A-Za-z][A-Za-z0-9]*)=([^\s]+)", message))


def load_samples(path: Path) -> list[int]:
    values = []
    for index, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        try:
            value = int(line)
        except ValueError as error:
            raise ValueError(f"Invalid nanosecond sample at {path.name}:{index}") from error
        if value <= 0:
            raise ValueError(f"Non-positive nanosecond sample at {path.name}:{index}")
        values.append(value)
    if len(values) != SAMPLE_COUNT:
        raise ValueError(f"Expected {SAMPLE_COUNT} samples in {path.name}, found {len(values)}")
    return sorted(values)


def percentile_ms(samples: list[int], rank: float) -> float:
    return samples[int((len(samples) - 1) * rank)] / 1_000_000


def relative_change(old: float, new: float) -> float:
    return (new / old - 1.0) * 100.0


def validate_log(log: str, samples: dict[tuple[str, str, str], list[int]]) -> None:
    if "FAILED" in log:
        raise ValueError("FAILED found in logcat.txt")
    results = {}
    completed = []
    for line in log.splitlines():
        if "IpcAuthBench" not in line:
            continue
        result = re.search(r"\bRESULT\s+(.*)", line)
        done = re.search(r"\bDONE\s+(.*)", line)
        if result:
            values = fields(result.group(1))
            key = (values.get("run"), values.get("mode"), values.get("api"))
            if key not in samples or key in results:
                raise ValueError(f"Unexpected or duplicate authentication RESULT: {key}")
            if values.get("count") != str(SAMPLE_COUNT) or values.get("failures") != "0":
                raise ValueError(f"Invalid count/failures for RESULT: {key}")
            for name, rank in (("p50Ms", 0.5), ("p90Ms", 0.9), ("p99Ms", 0.99)):
                try:
                    reported = float(values[name])
                except (KeyError, ValueError) as error:
                    raise ValueError(f"Missing/invalid {name} for RESULT: {key}") from error
                expected = percentile_ms(samples[key], rank)
                if not math.isclose(reported, expected, rel_tol=1e-9, abs_tol=1e-9):
                    raise ValueError(f"CSV/RESULT {name} mismatch for {key}: {expected} vs {reported}")
            results[key] = values
        elif done:
            values = fields(done.group(1))
            if values.get("run") not in RUNS or values.get("restored") != "optimized":
                raise ValueError(f"Unexpected authentication DONE: {values}")
            completed.append(values["run"])
    if len(results) != 16 or set(results) != set(samples):
        raise ValueError(f"Expected all 16 authentication RESULT records, found {len(results)}")
    if len(completed) != 4 or set(completed) != set(RUNS):
        raise ValueError(f"Expected one DONE per run ({RUNS}), found {completed}")


def optional_integer(values: dict[str, str], name: str) -> int | None:
    value = values.get(name)
    return None if value in (None, "null", "unavailable") else int(value)


def print_micro(log: str) -> None:
    times = []
    allocations = []
    for line in log.splitlines():
        if "IpcAuthMicro" not in line:
            continue
        match = re.search(r"\b(TIME|ALLOC)\s+(.*)", line)
        if not match:
            continue
        kind, message = match.groups()
        values = fields(message)
        count = int(values["count"])
        if count <= 0 or values.get("mode") not in MODES:
            raise ValueError(f"Invalid microbenchmark block: {message}")
        if kind == "TIME":
            cpu = int(values["cpuNs"])
            wall = int(values["wallNs"])
            if cpu < 0 or wall < 0:
                raise ValueError(f"Negative microbenchmark duration: {message}")
            times.append((values.get("batch", "?"), values["mode"], count, cpu / count, wall / count))
        else:
            before = optional_integer(values, "beforeBytes")
            after = optional_integer(values, "afterBytes")
            allocated = optional_integer(values, "bytes")
            gc = optional_integer(values, "gc")
            if before is None or after is None:
                if allocated is not None:
                    raise ValueError(f"Allocation delta without both snapshots: {message}")
            elif allocated != after - before:
                raise ValueError(f"Allocation snapshot/delta mismatch: {message}")
            if (allocated is not None and allocated < 0) or (gc is not None and gc < 0):
                raise ValueError(f"Negative runtime-stat delta: {message}")
            allocations.append((values.get("batch", "?"), values["mode"], count,
                                None if allocated is None else allocated / count, gc))
    if times:
        print("\n| Micro TIME batch | Mode | Count | Thread CPU ns/call | Wall ns/call |")
        print("| --- | --- | ---: | ---: | ---: |")
        for batch, mode, count, cpu, wall in times:
            print(f"| {batch} | {mode} | {count} | {cpu:.3f} | {wall:.3f} |")
    if allocations:
        print("\n| Micro ALLOC batch | Mode | Count | Process-average bytes/call | GC delta |")
        print("| --- | --- | ---: | ---: | ---: |")
        for batch, mode, count, allocated, gc in allocations:
            byte_text = "unavailable" if allocated is None else f"{allocated:.3f}"
            gc_text = "unavailable" if gc is None else str(gc)
            print(f"| {batch} | {mode} | {count} | {byte_text} | {gc_text} |")
        print("Runtime allocation statistics are approximate process totals, not exact per-call allocations.")
    if not times and not allocations:
        print("\nNo IpcAuthMicro TIME/ALLOC records present.")


def main(directory: Path) -> None:
    samples = {(run, mode, api): load_samples(directory / f"auth-{run}-{mode}-{api}.csv")
               for run in RUNS for mode in MODES for api in APIS}
    log = (directory / "logcat.txt").read_text(encoding="utf-8-sig")
    validate_log(log, samples)
    print("Validated 16 CSV files, 16,000 positive ns samples, 16 RESULT records and 4 DONE records.")
    print("\n| Run | Mode | API | P50 ms | P99 ms | Max ms |")
    print("| --- | --- | --- | ---: | ---: | ---: |")
    for (run, mode, api), values in samples.items():
        print(f"| {run} | {mode} | {api} | {percentile_ms(values, 0.5):.6f} | "
              f"{percentile_ms(values, 0.99):.6f} | {values[-1] / 1_000_000:.6f} |")
    print("\nRelative change = (optimized / legacy - 1) * 100%; negative means lower latency.")
    print("\n| Run | API | P50 change % | P99 change % |")
    print("| --- | --- | ---: | ---: |")
    for run in RUNS:
        for api in APIS:
            old, new = samples[run, "legacy", api], samples[run, "optimized", api]
            p50 = relative_change(percentile_ms(old, 0.5), percentile_ms(new, 0.5))
            p99 = relative_change(percentile_ms(old, 0.99), percentile_ms(new, 0.99))
            print(f"| {run} | {api} | {p50:+.2f} | {p99:+.2f} |")
    print("\n| API | Legacy median-run-P50 ms | Optimized median-run-P50 ms | Change % |")
    print("| --- | ---: | ---: | ---: |")
    for api in APIS:
        medians = {mode: statistics.median(percentile_ms(samples[run, mode, api], 0.5)
                                          for run in RUNS) for mode in MODES}
        old, new = medians["legacy"], medians["optimized"]
        print(f"| {api} | {old:.6f} | {new:.6f} | {relative_change(old, new):+.2f} |")
    print_micro(log)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python analyze_auth.py <run-directory>")
    try:
        main(Path(sys.argv[1]))
    except (OSError, ValueError, KeyError) as error:
        raise SystemExit(f"ERROR: {error}") from error
