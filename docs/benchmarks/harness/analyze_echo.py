"""Summarize the saved on-device echo latency samples (nanoseconds)."""

from pathlib import Path
import statistics
import sys


def stats(path: Path) -> dict[str, float]:
    values = [int(line) for line in path.read_text().splitlines()]
    if len(values) != 1000 or any(value <= 0 for value in values):
        raise ValueError(f"{path}: expected 1000 positive nanosecond samples")
    ordered = sorted(values)
    return {
        "mean": statistics.mean(values) / 1_000_000,
        "p50": ordered[499] / 1_000_000,
        "p90": ordered[899] / 1_000_000,
        "p99": ordered[989] / 1_000_000,
    }


def main(directory: Path, first_run: int, last_run: int, include_async: bool) -> None:
    rows: dict[tuple[str, int], list[dict[str, float]]] = {}
    print("| Library | Bytes | Run | Mean ms | P50 ms | P90 ms | P99 ms |")
    print("| --- | ---: | ---: | ---: | ---: | ---: | ---: |")
    libraries = [("ModernIpc", "modern-m"), ("AndLinker sync", "andlinker-a")]
    if include_async:
        libraries.append(("AndLinker enqueue", "andlinker-async-a"))
    for library, prefix in libraries:
        for size in (16, 1024):
            rows[(library, size)] = []
            for run in range(first_run, last_run + 1):
                result = stats(directory / f"{prefix}{run}-{size}.csv")
                rows[(library, size)].append(result)
                print(
                    f"| {library} | {size} | {run} | {result['mean']:.3f} | "
                    f"{result['p50']:.3f} | {result['p90']:.3f} | {result['p99']:.3f} |"
                )
    print()
    print("| Bytes | Modern median of run P50 | AndLinker sync median | Ratio | AndLinker enqueue median | Ratio |")
    print("| ---: | ---: | ---: | ---: | ---: | ---: |")
    for size in (16, 1024):
        modern = statistics.median(row["p50"] for row in rows[("ModernIpc", size)])
        sync = statistics.median(row["p50"] for row in rows[("AndLinker sync", size)])
        if include_async:
            asynchronous = statistics.median(row["p50"] for row in rows[("AndLinker enqueue", size)])
            async_text = f"{asynchronous:.3f} ms | {modern / asynchronous:.2f}"
        else:
            async_text = "N/A | N/A"
        print(f"| {size} | {modern:.3f} ms | {sync:.3f} ms | {modern / sync:.2f} | {async_text} |")


if __name__ == "__main__":
    first_run = int(sys.argv[2]) if len(sys.argv) > 2 else 1
    last_run = int(sys.argv[3]) if len(sys.argv) > 3 else 4
    main(Path(sys.argv[1]), first_run, last_run, first_run >= 5)
