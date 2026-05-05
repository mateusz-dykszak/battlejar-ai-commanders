#!/usr/bin/env python3
"""
deployment.py — our fighter deployment curve over time
Outputs: local_history/<gameId>.deployment.csv

What it captures and why:
  - Columns: time_s, active, docked, destroyed, total_deployed_ever
  - Sampled once per second (first frame at or after each integer second).
  - 'active'  = fighters in play (status is a number or non-C/D/A string).
                This is our effective screen size at that moment.
  - 'docked'  = fighters still in the carrier (status "C").
                High docked count late into the game = deployment failure
                (the bug we fixed where 0 fighters ever left the carrier).
  - 'destroyed' = fighters with status "D". Cumulative death count.
  - 'total_deployed_ever' = unique fighter IDs seen as active at any point.
                Measures total throughput, not just current screen.

  This data lets us answer:
    - Did we deploy fighters at all, and how fast?
    - Were fighters dying faster than we deployed them (active count falling)?
    - Was the carrier alone when it died (active=0 at death time)?
"""

import json
import math
import sys
from pathlib import Path


def parse_entity(raw):
    parts = raw.split("|")
    if len(parts) < 12:
        return None
    return {
        "id": parts[0],
        "type": parts[1],
        "color": parts[2],
        "status": parts[11],
    }


def main(jsonl_path: Path):
    game_id = jsonl_path.stem
    out_dir = Path(__file__).parent.parent / "local_history"
    out_dir.mkdir(exist_ok=True)
    out_path = out_dir / f"{game_id}.deployment.csv"

    with open(jsonl_path) as f:
        lines = f.readlines()

    players = json.loads(lines[0])
    our_color = next(
        (p["color"] for p in players if p["username"] == "Klaudia"), None
    )
    if not our_color:
        # Fall back to BLUE if username not found
        our_color = "BLUE"

    frames = [json.loads(l) for l in lines[1:]]
    if not frames:
        print("No frames")
        return

    def ts_to_sec(ts):
        from datetime import datetime
        ts = ts[:26].rstrip("Z") + "+00:00"
        return datetime.fromisoformat(ts).timestamp()

    t0 = ts_to_sec(frames[0]["timeStamp"])

    # Collect per-second snapshots
    rows = []
    next_second = 0
    ever_active = set()

    for frame in frames:
        frame_s = ts_to_sec(frame["timeStamp"]) - t0

        if frame_s < next_second:
            continue
        next_second = math.floor(frame_s) + 1

        entities = [parse_entity(e) for e in frame["entities"]]
        entities = [e for e in entities if e]

        our_fighters = [
            e for e in entities
            if e["type"] == "F" and e["color"] == our_color
        ]

        active = [f for f in our_fighters if f["status"] not in ("D", "C")]
        docked = [f for f in our_fighters if f["status"] == "C"]
        destroyed = [f for f in our_fighters if f["status"] == "D"]

        for f in active:
            ever_active.add(f["id"])

        rows.append({
            "time_s": round(frame_s, 1),
            "active": len(active),
            "docked": len(docked),
            "destroyed": len(destroyed),
            "total_deployed_ever": len(ever_active),
        })

    with open(out_path, "w") as f:
        f.write("time_s,active,docked,destroyed,total_deployed_ever\n")
        for row in rows:
            f.write(
                f"{row['time_s']},{row['active']},{row['docked']},"
                f"{row['destroyed']},{row['total_deployed_ever']}\n"
            )

    print(f"Written: {out_path} ({len(rows)} rows)")
    if rows:
        peak = max(r["active"] for r in rows)
        first_active = next((r for r in rows if r["active"] > 0), None)
        print(
            f"  peak_active={peak} "
            f"first_active_at={first_active['time_s'] if first_active else 'never'}s"
        )


if __name__ == "__main__":
    if len(sys.argv) > 1:
        path = Path(sys.argv[1])
    else:
        history = Path(__file__).parent.parent / "history"
        jsonl_files = sorted(history.glob("*.jsonl"), key=lambda p: p.stat().st_mtime)
        if not jsonl_files:
            print("No JSONL files found in history/")
            sys.exit(1)
        path = jsonl_files[-1]
        print(f"Using latest: {path.name}")

    main(path)
