#!/usr/bin/env python3
"""Convert a third-party plant-tracker JSON export into a YAPT `.yapt` backup.

Usage:
    python3 scripts/convert_third_party_log.py SOURCE.json [OUTPUT.yapt]

If OUTPUT is omitted it defaults to `<project-root>/backup.yapt`.
The result is a ZIP containing a single `backup.json`, importable from
YAPT's Settings -> Restore.

Mapping rules:
    action_type 1  -> WATER
    action_type 6  -> FERTILIZE
    action_type 3  -> REPOT
    action_type 5  -> photo (skipped)
    other types    -> skipped

Schedules (`wateringIntervalDays`, `fertilizingIntervalDays`) are left null.
The source's `interval_days` field does not reliably equal the user-facing
reminder interval, so schedules must be re-entered inside YAPT.

Timestamps in the source are Unix seconds; YAPT expects milliseconds.
"""
from __future__ import annotations

import json
import sys
import time
import zipfile
from pathlib import Path

ACTION_WATER = {1}
ACTION_FERTILIZE = {6}
ACTION_REPOT = {3}


def build_backup(src: Path) -> dict:
    raw = json.loads(src.read_text())
    plants_in = json.loads(raw["plants"])
    actions_in = json.loads(raw["actions"])

    plant_id_map: dict[str, int] = {}
    plants_out: list[dict] = []
    for idx, p in enumerate(plants_in, start=1):
        plant_id_map[p["id"]] = idx
        created = int(p.get("create_time") or 0) * 1000
        updated = int(p.get("update_time") or p.get("create_time") or 0) * 1000
        plants_out.append({
            "id": idx,
            "name": p["name"],
            "species": None,
            "room": None,
            "coverPhotoUri": None,
            "notes": None,
            "wateringIntervalDays": None,
            "fertilizingIntervalDays": None,
            "createdAt": created,
            "updatedAt": updated,
        })

    care_logs: list[dict] = []
    next_id = 1
    for a in actions_in:
        plant_str = a["plant_id"]
        if plant_str not in plant_id_map:
            continue
        atype = a["action_type"]
        if atype in ACTION_WATER:
            care_type = "WATER"
        elif atype in ACTION_FERTILIZE:
            care_type = "FERTILIZE"
        elif atype in ACTION_REPOT:
            care_type = "REPOT"
        else:
            continue

        care_logs.append({
            "id": next_id,
            "plantId": plant_id_map[plant_str],
            "careType": care_type,
            "loggedAt": int(a["timestamp"]) * 1000,
            "notes": None,
            "photoUri": None,
            "amount": None,
            "wateringFeedback": None,
        })
        next_id += 1

    return {
        "schemaVersion": 1,
        "exportedAt": int(time.time() * 1000),
        "appVersion": "1.0",
        "plants": plants_out,
        "careLogs": care_logs,
        "settings": {
            "notificationsEnabled": True,
            "reminderHour": 8,
            "reminderMinute": 0,
        },
    }


def main(src: Path, dst: Path) -> None:
    backup = build_backup(src)
    payload = json.dumps(backup, separators=(",", ":"))

    with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("backup.json", payload)

    counts = {"WATER": 0, "FERTILIZE": 0, "REPOT": 0}
    for c in backup["careLogs"]:
        counts[c["careType"]] += 1
    print(f"Wrote {dst}")
    print(f"  plants:   {len(backup['plants'])}")
    print(f"  careLogs: {len(backup['careLogs'])}  ({counts})")


if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] in ("-h", "--help"):
        print(__doc__)
        sys.exit(0 if len(sys.argv) >= 2 else 1)
    root = Path(__file__).resolve().parent.parent
    src = Path(sys.argv[1])
    dst = Path(sys.argv[2]) if len(sys.argv) > 2 else root / "backup.yapt"
    main(src, dst)
