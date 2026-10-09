"""Copy numeric benchmark outputs byte-for-byte from the Xiaomi debug application's private files."""
import subprocess
import sys
from pathlib import Path

SERIAL = "925c23bb"


def main(directory: Path, run: str) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    names = [f"pending-local-{run}.csv"] + [
        f"pending-ipc-{run}-r{round_number}-{mode}-{size}.csv"
        for round_number in range(1, 5) for mode in ("legacy", "optimized") for size in (16, 1024)
    ]
    for name in names:
        data = subprocess.check_output(["adb", "-s", SERIAL, "exec-out", "run-as", "com.cn.ipc.demo", "cat", f"files/{name}"])
        (directory / name).write_bytes(data)
    print(f"Copied {len(names)} CSV files from {SERIAL}")


if __name__ == "__main__":
    main(Path(sys.argv[1]), sys.argv[2])
