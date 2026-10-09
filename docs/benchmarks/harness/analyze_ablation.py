"""Recompute per-run latency percentiles from ModernIpc ablation CSV files."""

import argparse
from pathlib import Path
from statistics import mean


def summarize(path: Path) -> tuple[int, float, float, float, float]:
    values = sorted(int(line) for line in path.read_text().splitlines())
    if len(values) != 1000 or values[0] <= 0:
        raise ValueError(f"Expected 1000 positive nanosecond samples: {path}")
    return (
        len(values),
        mean(values) / 1_000_000,
        values[499] / 1_000_000,
        values[899] / 1_000_000,
        values[989] / 1_000_000,
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("directories", nargs="+", type=Path)
    args = parser.parse_args()
    print("| Group | Run | Characters | Count | Mean ms | P50 ms | P90 ms | P99 ms |")
    print("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |")
    for directory in args.directories:
        paths = sorted(directory.glob("modern-*-*.csv"))
        if not paths:
            raise FileNotFoundError(directory)
        for path in paths:
            run, size = path.stem.removeprefix("modern-").rsplit("-", 1)
            count, avg, p50, p90, p99 = summarize(path)
            print(
                f"| {directory.name} | {run} | {size} | {count} | "
                f"{avg:.3f} | {p50:.3f} | {p90:.3f} | {p99:.3f} |"
            )


if __name__ == "__main__":
    main()
