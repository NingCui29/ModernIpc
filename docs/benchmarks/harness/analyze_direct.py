"""Recalculate the final Xiaomi echo fast-path benchmark from raw CSV and Logcat."""

import re
import statistics
import sys
from pathlib import Path


def percentile(values: list[int], rank: float) -> float:
    return values[int((len(values) - 1) * rank)] / 1_000_000


def load_samples(path: Path, expected: int) -> list[int]:
    values = sorted(int(line) for line in path.read_text().splitlines())
    if len(values) != expected or values[0] <= 0:
        raise ValueError(f"Expected {expected} positive samples: {path}")
    return values


def main(directory: Path) -> None:
    summary: dict[tuple[str, int], list[float]] = {}
    print("| Mode | Characters | Run | P50 ms | P90 ms | P99 ms |")
    print("| --- | ---: | --- | ---: | ---: | ---: |")
    for run in range(1, 5):
        for size in (16, 1024):
            for mode, prefix in (("suspend", "modern"), ("direct", "modern-direct")):
                samples = load_samples(directory / f"{prefix}-release{run}-{size}.csv", 1000)
                p50, p90, p99 = (percentile(samples, rank) for rank in (0.5, 0.9, 0.99))
                summary.setdefault((mode, size), []).append(p50)
                print(f"| {mode} | {size} | release{run} | {p50:.3f} | {p90:.3f} | {p99:.3f} |")
    print("\nFour-run median of run P50:")
    for size in (16, 1024):
        suspend = statistics.median(summary["suspend", size])
        direct = statistics.median(summary["direct", size])
        print(f"{size} characters: suspend {suspend:.3f} ms, direct {direct:.3f} ms, ratio {suspend / direct:.2f}x")

    log = (directory / "logcat.txt").read_text(encoding="utf-8-sig")
    if "FAILED" in log:
        raise ValueError("Benchmark failure found in Logcat")
    pattern = re.compile(
        r"STRESS run=(stress[12]) mode=(suspend|direct) concurrency=(16|64) "
        r"count=(\d+) failures=(\d+) durationMs=([\d.]+) successQps=([\d.]+) "
        r"p50Ms=([\d.]+) p99Ms=([\d.]+)"
    )
    matches = pattern.findall(log)
    if len(matches) != 8:
        raise ValueError(f"Expected eight stress summaries, found {len(matches)}")
    print("\n| Run | Mode | Concurrency | Success | QPS | P50 ms | P99 ms |")
    print("| --- | --- | ---: | ---: | ---: | ---: | ---: |")
    for run, mode, concurrency, count, failures, duration, qps, p50, p99 in matches:
        if count != "1024" or failures != "0":
            raise ValueError(f"Unexpected stress result: {run} {mode} {concurrency}")
        load_samples(directory / f"modern-{mode}-{run}-c{concurrency}.csv", 1024)
        print(f"| {run} | {mode} | {concurrency} | {count} | {float(qps):.0f} | {float(p50):.3f} | {float(p99):.3f} |")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python analyze_direct.py <run-directory>")
    main(Path(sys.argv[1]))
