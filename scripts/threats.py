#!/usr/bin/env python3
"""
threats.py — carrier threat timeline
Outputs: local_history/<gameId>.threats.csv

Columns (sampled once per second):
  time_s                    — seconds since game start
  our_hp                    — our carrier HP (1000 until dead → 0)
  our_active_fighters       — our undocked, living fighters
  nearest_enemy_carrier_dist — distance to closest enemy carrier (blank if none)
  nearest_armed_missile_dist — closest armed enemy missile to our carrier (blank if none)
  armed_missiles_within_200  — armed enemy missiles within 200 units
  armed_missiles_within_80   — armed enemy missiles within 80 units (inside formation ring)
  live_enemy_carriers        — count of non-dead enemy carriers
  nearest_ec_hp              — HP of the nearest enemy carrier (blank if none)
  enemy_fighters_total       — all active (undocked, living) enemy fighters
  enemy_fighters_near_100    — enemy fighters within 100 units of our carrier
  our_hp_delta               — our HP change vs previous second (negative = damage taken; blank for first row)

NOTE: missile color is encoded in the missile ID (e.g. "M10-BLUE-01"), not in
the entity color field (which is always "NONE"). This file correctly attributes
missiles to their owner by parsing the ID.
"""

import json
import math
import sys
from pathlib import Path


def parse_entity(raw):
    parts = raw.split("|")
    if len(parts) < 12:
        return None
    entity_type = parts[1]
    # Missiles always have color="NONE" in field[2]; real owner is in the ID.
    if entity_type == "M":
        id_parts = parts[0].split("-")
        color = id_parts[1] if len(id_parts) >= 3 else "NONE"
    else:
        color = parts[2]
    return {
        "id": parts[0],
        "type": entity_type,
        "color": color,
        "px": float(parts[3]),
        "py": float(parts[4]),
        "status": parts[11],
    }


def dist2(a, b):
    return math.sqrt((a["px"] - b["px"]) ** 2 + (a["py"] - b["py"]) ** 2)


def carrier_hp(entity):
    try:
        return int(entity["status"])
    except (ValueError, TypeError):
        return None


def main(jsonl_path: Path):
    game_id = jsonl_path.stem
    out_dir = Path(__file__).parent.parent / "local_history"
    out_dir.mkdir(exist_ok=True)
    out_path = out_dir / f"{game_id}.threats.csv"

    with open(jsonl_path) as f:
        lines = f.readlines()

    players = json.loads(lines[0])
    our_color = next(
        (p["color"] for p in players if p["username"] == "Klaudiusz"), None
    )
    if our_color is None:
        print("Klaudiusz not in this game, skipping")
        return

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
    prev_hp = None

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
            rows.append({
                "time_s": round(frame_s, 1),
                "our_hp": 0,
                "our_active_fighters": 0,
                "nearest_enemy_carrier_dist": None,
                "nearest_armed_missile_dist": None,
                "armed_missiles_within_200": 0,
                "armed_missiles_within_80": 0,
                "live_enemy_carriers": None,
                "nearest_ec_hp": None,
                "enemy_fighters_total": None,
                "enemy_fighters_near_100": None,
                "our_hp_delta": None,
            })
            break

        hp = carrier_hp(our_carrier)
        hp_delta = (hp - prev_hp) if (prev_hp is not None and hp is not None) else None
        prev_hp = hp

        our_fighters_active = [
            e for e in entities
            if e["type"] == "F" and e["color"] == our_color
            and e["status"] not in ("D", "C")
        ]

        enemy_carriers = [
            e for e in entities
            if e["type"] == "C" and e["color"] != our_color and e["status"] != "D"
        ]
        live_enemy_carriers = len(enemy_carriers)

        nearest_enemy_carrier = None
        nearest_enemy_carrier_dist = None
        nearest_ec_hp_val = None
        if enemy_carriers:
            nearest_enemy_carrier = min(enemy_carriers, key=lambda c: dist2(c, our_carrier))
            nearest_enemy_carrier_dist = round(dist2(nearest_enemy_carrier, our_carrier), 1)
            nearest_ec_hp_val = carrier_hp(nearest_enemy_carrier)

        enemy_fighters = [
            e for e in entities
            if e["type"] == "F" and e["color"] != our_color
            and e["status"] not in ("D", "C")
        ]
        enemy_fighters_total = len(enemy_fighters)
        enemy_fighters_near_100 = sum(1 for f in enemy_fighters if dist2(f, our_carrier) < 100)

        enemy_missiles = [
            e for e in entities
            if e["type"] == "M" and e["color"] != our_color and e["status"] == "A"
        ]
        nearest_armed_missile_dist = None
        within_200 = 0
        within_80 = 0
        if enemy_missiles:
            dists = [dist2(m, our_carrier) for m in enemy_missiles]
            nearest_armed_missile_dist = round(min(dists), 1)
            within_200 = sum(1 for d in dists if d < 200)
            within_80 = sum(1 for d in dists if d < 80)

        rows.append({
            "time_s": round(frame_s, 1),
            "our_hp": hp,
            "our_active_fighters": len(our_fighters_active),
            "nearest_enemy_carrier_dist": nearest_enemy_carrier_dist,
            "nearest_armed_missile_dist": nearest_armed_missile_dist,
            "armed_missiles_within_200": within_200,
            "armed_missiles_within_80": within_80,
            "live_enemy_carriers": live_enemy_carriers,
            "nearest_ec_hp": nearest_ec_hp_val,
            "enemy_fighters_total": enemy_fighters_total,
            "enemy_fighters_near_100": enemy_fighters_near_100,
            "our_hp_delta": hp_delta,
        })

    header = (
        "time_s,our_hp,our_active_fighters,nearest_enemy_carrier_dist,"
        "nearest_armed_missile_dist,armed_missiles_within_200,armed_missiles_within_80,"
        "live_enemy_carriers,nearest_ec_hp,enemy_fighters_total,"
        "enemy_fighters_near_100,our_hp_delta\n"
    )

    def fmt(v):
        return "" if v is None else str(v)

    with open(out_path, "w") as f:
        f.write(header)
        for row in rows:
            f.write(
                f"{row['time_s']},"
                f"{fmt(row['our_hp'])},"
                f"{row['our_active_fighters']},"
                f"{fmt(row['nearest_enemy_carrier_dist'])},"
                f"{fmt(row['nearest_armed_missile_dist'])},"
                f"{row['armed_missiles_within_200']},"
                f"{row['armed_missiles_within_80']},"
                f"{fmt(row['live_enemy_carriers'])},"
                f"{fmt(row['nearest_ec_hp'])},"
                f"{fmt(row['enemy_fighters_total'])},"
                f"{fmt(row['enemy_fighters_near_100'])},"
                f"{fmt(row['our_hp_delta'])}\n"
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
