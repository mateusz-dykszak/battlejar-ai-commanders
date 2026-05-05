#!/usr/bin/env python3
"""
all_carriers.py — per-carrier HP and position timeline (all players)
Outputs: local_history/<gameId>.all_carriers.csv

One row per carrier per second (long format). Covers all players including us.

Columns:
  time_s          — seconds since game start
  color           — carrier color (player identity)
  name            — player username
  hp              — carrier HP (blank once destroyed)
  dist_to_us      — distance from this carrier to our carrier (0 for us; blank if our carrier dead)
  active_fighters — undocked, living fighters belonging to this carrier's color
  x               — carrier x position
  y               — carrier y position

Useful for:
  - Tracking when each enemy took damage (hp drops) and from what direction
  - Seeing whether enemies fight each other vs focusing on us
  - Correlating enemy carrier approach (dist_to_us) with our HP loss
  - Identifying which enemy is weakest at any moment (for kill-focus decisions)

NOTE: rows stop appearing for a carrier once it is destroyed (status "D").
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
        return int(entity["status"])
    except (ValueError, TypeError):
        return None


def main(jsonl_path: Path):
    game_id = jsonl_path.stem
    out_dir = Path(__file__).parent.parent / "local_history"
    out_dir.mkdir(exist_ok=True)
    out_path = out_dir / f"{game_id}.all_carriers.csv"

    with open(jsonl_path) as f:
        lines = f.readlines()

    players = json.loads(lines[0])
    color_to_name = {p["color"]: p["username"] for p in players}
    our_color = next(
        (p["color"] for p in players if p["username"] == "Klaudia"), None
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

        carriers = [e for e in entities if e["type"] == "C" and e["status"] != "D"]
        fighters = [e for e in entities if e["type"] == "F"]

        our_carrier = next((c for c in carriers if c["color"] == our_color), None)

        for carrier in carriers:
            color = carrier["color"]
            name = color_to_name.get(color, color)
            hp_val = carrier_hp(carrier)

            if color == our_color:
                dist_val = 0.0
            elif our_carrier is not None:
                dist_val = round(dist2(carrier, our_carrier), 1)
            else:
                dist_val = None

            active_fighters = sum(
                1 for f in fighters
                if f["color"] == color and f["status"] not in ("D", "C")
            )

            rows.append({
                "time_s": round(frame_s, 1),
                "color": color,
                "name": name,
                "hp": hp_val,
                "dist_to_us": dist_val,
                "active_fighters": active_fighters,
                "x": round(carrier["px"], 1),
                "y": round(carrier["py"], 1),
            })

    def fmt(v):
        return "" if v is None else str(v)

    with open(out_path, "w") as f:
        f.write("time_s,color,name,hp,dist_to_us,active_fighters,x,y\n")
        for row in rows:
            f.write(
                f"{row['time_s']},"
                f"{row['color']},"
                f"{row['name']},"
                f"{fmt(row['hp'])},"
                f"{fmt(row['dist_to_us'])},"
                f"{row['active_fighters']},"
                f"{row['x']},"
                f"{row['y']}\n"
            )

    print(f"Written: {out_path} ({len(rows)} rows)")
    if rows:
        colors = sorted(set(r["color"] for r in rows))
        for color in colors:
            color_rows = [r for r in rows if r["color"] == color]
            name = color_rows[0]["name"]
            last_hp = next((r["hp"] for r in reversed(color_rows) if r["hp"] is not None), None)
            death_row = next((r for r in color_rows if r["hp"] == 0), None)
            death_str = f"died@{death_row['time_s']}s" if death_row else "survived"
            print(f"  {color}({name}): {len(color_rows)} rows last_hp={last_hp} {death_str}")


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
