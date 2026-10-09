"""Plot each qualified guard run separately; CPU/allocation cover only the pre-send step."""
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / ".gradle/plot-deps"))
os.environ.setdefault("MPLCONFIGDIR", str(ROOT / ".gradle/plot-config"))
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt


def main(directory: Path) -> None:
    data = json.loads((directory / "analysis.json").read_text())
    assert data["foreground"]["valid"]
    fig, axes = plt.subplots(2, 2, figsize=(13.5, 8.5))
    colors = {"lookup": "#677889", "guard": "#007fb5"}
    for axis, metric, scale, ylabel, title in (
        (axes[0, 0], "threadCpuNsPerStep", 1e3, "Thread CPU: microseconds / step", "Pre-send step only: thread CPU"),
        (axes[0, 1], "processBytesPerStep", 1, "Approximate bytes / step", "Pre-send step only: process allocation"),
    ):
        summary = next(row for row in data["localSummary"] if row["metric"] == metric)
        values = [summary[mode] / scale for mode in ("lookup", "guard")]
        axis.bar(["Repeated lookup", "Captured Binder guard"], values, color=list(colors.values()), width=0.55)
        axis.set_ylim(0, max(values) * 1.24)
        for index, value in enumerate(values):
            axis.text(index, value + max(values) * 0.035, f"{value:.3f}", ha="center")
        axis.set_ylabel(ylabel)
        axis.set_title(title)
        axis.grid(axis="y", alpha=0.2)
    for axis, size in zip(axes[1], (16, 1024)):
        for mode in ("lookup", "guard"):
            rows = [row for row in data["ipc"] if row["size"] == size and row["mode"] == mode]
            axis.plot([row["round"] for row in rows], [row["p50Ms"] for row in rows],
                      "o-", color=colors[mode], label="Repeated lookup" if mode == "lookup" else "Captured Binder guard")
        axis.set_title(f"Full same-wire Async RTT: {size} characters")
        axis.set_ylabel("P50: milliseconds")
        axis.set_xlabel("Paired round (ABBA order)")
        axis.set_xticks((1, 2, 3, 4))
        axis.grid(alpha=0.2)
    axes[1, 0].legend()
    fig.suptitle(f"Xiaomi 14 Ultra | Same APK | {data['run']} | Foreground observations qualified", fontsize=15)
    fig.text(0.5, 0.012, "Allocation is a process counter estimate; zero delta does not prove zero allocation. Local cost and full RTT are separate.",
             ha="center", fontsize=9)
    fig.tight_layout(rect=(0, 0.035, 1, 0.955))
    fig.savefig(directory / "guard-performance.png", dpi=150)
    plt.close(fig)
    print(directory / "guard-performance.png")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
