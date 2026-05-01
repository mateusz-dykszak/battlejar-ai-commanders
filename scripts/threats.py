#!/usr/bin/env python3
"""
threats.py — carrier threat timeline
Outputs: history/<gameId>.threats.csv

What it captures and why:
  - Columns: time_s, our_hp, our_active_fighters, nearest_enemy_carrier_dist,
             nearest_armed_missile_dist, armed_missiles_within_200,
             armed_missiles_within_80
  - Sampled once per second.

  - 'our_hp': our carrier's health. NOTE: in the current game data carriers report
    1000 (full) until instant destruction (no intermediate HP values are emitted).
    This column is therefore 1000 until the last row (0). Kept for completeness and
    in case the server changes behavior. Use the row count / last time_s to infer
    survival time instead.
  - 'our_active_fighters': screen size at each moment — correlate with HP loss
    to see whether a larger screen slows damage intake.
  - 'nearest_enemy_carrier_dist': how close we are to the enemy carrier over
    time. Useful for tuning PATROL vs MOVE thresholds; also tells us if we're
    staying in our spawn quadrant as intended.
  - 'nearest_armed_missile_dist': minimum distance any armed enemy missile
    reached toward our carrier. Values < 80 (formation ring radius) mean
    missiles are passing through our screen — direct indicator of intercept
    failure (the active missile intercept task).
  - 'armed_missiles_within_200': count of armed missiles that triggered
    our TARGET "M" logic (200-unit threshold in current code).
  - 'armed_missiles_within_80': count that penetrated the formation ring —
    these are the ones that actually damage the carrier.
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
        "px": float(parts[3]),
        "py": float(parts[4]),
        "status": parts[11],
    }


def dist2(a, b):
    return math.sqrt((a["px"] - b["px"]) ** 2 + (a["py"] - b["py"]) ** 2)


def carrier_hp(entity):
    try:
        v = int(entity["status"])
        return v
    except (ValueError, TypeError):
        return None


def main(jsonl_path: Path):
    game_id = jsonl_path.stem
    out_path = jsonl_path.parent / f"{game_id}.threats.csv"

    with open(jsonl_path) as f:
        lines = f.readlines()

    players = json.loads(lines[0])
    our_color = next(
        (p["color"] for p in players if p["username"] == "Klaudiusz"), None
    ) or "BLUE"

    frames = [json.loads(l) for l in lines[1:]]
    if not frames:
        print("No frames")
        return

    def ts_to_sec(ts):
        from datetime import datetime
        ts = ts[:26].rstrip("Z") + "+00:00"
        return datetime.fromisoformat(ts).timestamp()

    t0 = ts_to_sec(frames[0]["timeStamp"])

    rows = []
    next_second = 0

    for frame in frames:
        frame_s = ts_to_sec(frame["timeStamp"]) - t0

        if frame_s < next_second:
            continue
        next_second = math.floor(frame_s) + 1

        entities = [parse_entity(e) for e in frame["entities"]]
        entities = [e for e in entities if e]

        our_carrier = next(
            (e for e in entities if e["type"] == "C" and e["color"] == our_color),
            None,
        )
        if our_carrier is None or our_carrier["status"] == "D":
            # Carrier dead — record final row and stop
            rows.append({
                "time_s": round(frame_s, 1),
                "our_hp": 0,
                "our_active_fighters": 0,
                "nearest_enemy_carrier_dist": None,
                "nearest_armed_missile_dist": None,
                "armed_missiles_within_200": 0,
                "armed_missiles_within_80": 0,
            })
            break

        hp = carrier_hp(our_carrier)

        our_fighters_active = [
            e for e in entities
            if e["type"] == "F" and e["color"] == our_color
            and e["status"] not in ("D", "C")
        ]

        enemy_carriers = [
            e for e in entities
            if e["type"] == "C" and e["color"] != our_color and e["status"] != "D"
        ]
        if enemy_carriers:
            nearest_enemy_carrier_dist = round(
                min(dist2(ec, our_carrier) for ec in enemy_carriers), 1
            )
        else:
            nearest_enemy_carrier_dist = None

        enemy_missiles = [
            e for e in entities
            if e["type"] == "M" and e["color"] != our_color and e["status"] == "A"
        ]
        if enemy_missiles:
            dists = [dist2(m, our_carrier) for m in enemy_missiles]
            nearest_armed_missile_dist = round(min(dists), 1)
            within_200 = sum(1 for d in dists if d < 200)
            within_80 = sum(1 for d in dists if d < 80)
        else:
            nearest_armed_missile_dist = None
            within_200 = 0
            within_80 = 0

        rows.append({
            "time_s": round(frame_s, 1),
            "our_hp": hp,
            "our_active_fighters": len(our_fighters_active),
            "nearest_enemy_carrier_dist": nearest_enemy_carrier_dist,
            "nearest_armed_missile_dist": nearest_armed_missile_dist,
            "armed_missiles_within_200": within_200,
            "armed_missiles_within_80": within_80,
        })

    with open(out_path, "w") as f:
        f.write(
            "time_s,our_hp,our_active_fighters,nearest_enemy_carrier_dist,"
            "nearest_armed_missile_dist,armed_missiles_within_200,armed_missiles_within_80\n"
        )
        for row in rows:
            f.write(
                f"{row['time_s']},"
                f"{row['our_hp'] if row['our_hp'] is not None else ''},"
                f"{row['our_active_fighters']},"
                f"{row['nearest_enemy_carrier_dist'] if row['nearest_enemy_carrier_dist'] is not None else ''},"
                f"{row['nearest_armed_missile_dist'] if row['nearest_armed_missile_dist'] is not None else ''},"
                f"{row['armed_missiles_within_200']},"
                f"{row['armed_missiles_within_80']}\n"
            )

    print(f"Written: {out_path} ({len(rows)} rows)")
    if rows:
        penetrations = sum(r["armed_missiles_within_80"] for r in rows)
        max_fighters = max(r["our_active_fighters"] for r in rows)
        print(f"  max_active_fighters={max_fighters} total_penetrations(within_80)={penetrations}")


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
