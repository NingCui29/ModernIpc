"""Qualify foreground observation coverage without changing captured CSV data.

Predeclared rule: target 500 ms, maximum BEGIN/SAMPLE/END gap 1,000 ms.
Runs lasting at least 1,000 ms need floor(duration_ms / 1,000) SAMPLE rows.
Activity events never substitute for periodic samples. Subsecond functional
probes may have no SAMPLE rows. Both captured monotonic clocks must be valid.
"""

import csv
import hashlib
import json
import sys
from pathlib import Path


TARGET_SAMPLE_PERIOD_MS = 500
MAX_SAMPLE_GAP_MS = 1000
RULE_VERSION = "foreground-coverage-v1"
REQUIRED_COLUMNS = (
    "elapsedRealtimeMs", "uptimeMs", "event", "lifecycle", "focused",
    "interactive", "keyguardLocked", "deviceLocked", "valid",
)
EXPECTED_STATE = {"lifecycle": "RESUMED", "focused": "true", "interactive": "true",
                  "keyguardLocked": "false", "deviceLocked": "false", "valid": "true"}


def qualify_rows(states: list[dict]) -> dict:
    result = dict(valid=False, ruleVersion=RULE_VERSION,
                  targetSamplePeriodMs=TARGET_SAMPLE_PERIOD_MS,
                  permittedMaxGapMs=MAX_SAMPLE_GAP_MS,
                  scope="activity_events_with_bounded_periodic_sample_coverage",
                  observations=0, sampleCount=0, requiredSampleCount=None,
                  durationMs=None, maxGapMs=None, observationMaxGapMs=None,
                  errors=[])
    errors = result["errors"]
    if not states:
        errors.append("Foreground CSV has no observation rows")
        return result
    if any(any(column not in row or row[column] is None for column in REQUIRED_COLUMNS) for row in states):
        errors.append("Foreground CSV is missing required columns or row values")
        return result
    timestamps = []
    for index, row in enumerate(states):
        try:
            elapsed, uptime = int(row["elapsedRealtimeMs"]), int(row["uptimeMs"])
        except (ValueError, TypeError):
            errors.append(f"Invalid clock timestamp at CSV row {index + 2}")
            return result
        if elapsed < 0 or uptime < 0:
            errors.append(f"Negative clock timestamp at CSV row {index + 2}")
        timestamps.append((elapsed, uptime))
    for index, (before, after) in enumerate(zip(timestamps, timestamps[1:])):
        if after[0] < before[0] or after[1] < before[1]:
            errors.append(f"Non-monotonic clock timestamp at CSV row {index + 3}")
    begin = [index for index, row in enumerate(states) if row["event"] == "BEGIN"]
    end = [index for index, row in enumerate(states) if row["event"] == "END"]
    if len(begin) != 1 or len(end) != 1 or begin[0] >= end[0] or end[0] != len(states) - 1:
        errors.append("Expected exactly one BEGIN followed by exactly one final END")
        return result
    start, finish = begin[0], end[0]
    measuring = states[start:finish + 1]
    result["observations"] = len(measuring)
    for index, row in enumerate(measuring, start + 2):
        if (any(row[column] != expected for column, expected in EXPECTED_STATE.items()) or
                row["event"] in ("ON_PAUSE", "ON_STOP", "FOCUS_LOSS")):
            errors.append(f"Invalid foreground state at CSV row {index}: event={row['event']}")
    duration = timestamps[finish][0] - timestamps[start][0]
    result["durationMs"] = duration
    if duration < 0:
        errors.append("BEGIN-to-END duration is negative")
        return result
    result["requiredSampleCount"] = duration // MAX_SAMPLE_GAP_MS if duration >= 1000 else 0
    result["sampleCount"] = sum(row["event"] == "SAMPLE" for row in measuring)
    if result["sampleCount"] < result["requiredSampleCount"]:
        errors.append(f"Insufficient periodic SAMPLE rows: {result['sampleCount']} < {result['requiredSampleCount']}")
    periodic = [timestamps[index][0] for index in range(start, finish + 1)
                if states[index]["event"] in ("BEGIN", "SAMPLE", "END")]
    gaps = [after - before for before, after in zip(periodic, periodic[1:])]
    result["maxGapMs"] = max(gaps, default=0)
    result["observationMaxGapMs"] = max(
        (timestamps[index + 1][0] - timestamps[index][0] for index in range(start, finish)), default=0)
    if any(gap < 0 or gap > MAX_SAMPLE_GAP_MS for gap in gaps):
        errors.append(f"BEGIN/SAMPLE/END coverage gap exceeds {MAX_SAMPLE_GAP_MS} ms or is negative")
    result["valid"] = not errors
    return result


def qualify_foreground(directory: Path, run: str, additional_errors: list[str] | None = None) -> dict:
    """Write a qualification, including read/parse failures; leave original records intact."""
    safe_run = "".join(
        character if character.isascii() and (character.isalnum() or character in "_.-") else "_" for character in run)
    path = directory / f"foreground-{safe_run}.csv"
    try:
        data = path.read_bytes()
        with path.open(encoding="utf-8-sig", newline="") as source:
            states = list(csv.DictReader(source))
        result = qualify_rows(states)
        result["sourceSha256"] = hashlib.sha256(data).hexdigest().upper()
    except (OSError, ValueError, UnicodeError, csv.Error) as error:
        result = qualify_rows([])
        result["errors"] = [f"Cannot read foreground CSV: {error}"]
    result.update(run=run, csv=path.name)
    if additional_errors:
        result["errors"].extend(additional_errors)
    result["valid"] = not result["errors"]
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "qualification.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                                                  encoding="utf-8")
    return result


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("Usage: python qualify_foreground.py <run-directory> <run-id>")
    qualification = qualify_foreground(Path(sys.argv[1]), sys.argv[2])
    print(json.dumps(qualification, ensure_ascii=False, indent=2))
    if not qualification["valid"]:
        raise SystemExit(1)
