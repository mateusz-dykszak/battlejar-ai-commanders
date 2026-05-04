#!/usr/bin/env python3
"""
stats.py — per-game summary statistics
Outputs: local_history/<gameId>.stats.json

What it captures and why:
  - Player roster (color → username): identifies which color is ours so every
    other metric can be split into "us vs them".
  - Game duration: absolute elapsed seconds. Short games (<20 s) mean early
    carrier death; helps flag regression vs improvement.
  - Carrier death order and timestamps: who died first/last. Dying first means
    our opening was wrong; dying last (or surviving) is the goal.
  - Our carrier HP trajectory (start, min reached, frame it dropped to 50 %,
    death frame/time): reveals how quickly we took damage and whether fighters
    provided any meaningful shield.
  - Our fighter stats:
      first_deployment_s  — seconds until first fighter left the carrier.
                           Zero deployments = critical bug (seen in past).
      peak_active         — maximum simultaneous undocked fighters. Low peak
                           means fighters die faster than we deploy or we
                           never deploy enough.
      total_ever_active   — unique fighter IDs that ever left dock. Reveals
                           total throughput for the game.
  - Closest enemy missile approach to our carrier: the minimum distance any
    armed enemy missile ever reached. Values < 80 (inside formation ring)
    mean missiles are penetrating our screen — directly tied to carrier deaths
    at 13–20 s we observed in earlier analysis.
  - Winner: which color survived (or last standing).
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
    # Missiles always have color="NONE" in field[2]; real owner is encoded in the ID.
    if entity_type == "M":
        id_parts = parts[0].split("-")
        color = id_parts[1] if len(id_parts) >= 3 else "NONE"
    else:
        color = parts[2]
    return {
        "id": parts[0],
        "type": entity_type,   # C=Carrier, F=Fighter, M=Missile
        "color": color,
        "px": float(parts[3]),
        "py": float(parts[4]),
        "missiles": int(parts[10]) if parts[10] else 0,
        "status": parts[11],
    }


def dist(a, b):
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
    out_path = out_dir / f"{game_id}.stats.json"

    with open(jsonl_path) as f:
        lines = f.readlines()

    if not lines:
        print(f"Empty file: {jsonl_path}")
        return

    players = json.loads(lines[0])  # [{id, color, username}]
    color_to_name = {p["color"]: p["username"] for p in players}

    # Identify our color (Klaudiusz)
    our_color = next(
        (p["color"] for p in players if p["username"] == "Klaudiusz"), None
    )

    frames = [json.loads(l) for l in lines[1:]]
    if not frames:
        print("No frames")
        return

    t0_str = frames[0]["timeStamp"]
    tN_str = frames[-1]["timeStamp"]

    def ts_to_sec(ts):
        from datetime import datetime, timezone
        # Handle nanosecond precision by truncating to microseconds
        ts = ts[:26].rstrip("Z") + "+00:00"
        return datetime.fromisoformat(ts).timestamp()

    t0 = ts_to_sec(t0_str)
    tN = ts_to_sec(tN_str)
    duration_s = round(tN - t0, 2)

    # Carrier deaths: first frame where a carrier turns D
    carrier_alive = {}  # color → True while alive
    carrier_death_times = {}  # color → seconds

    # Our carrier HP tracking
    our_hp_start = None
    our_hp_min = None
    our_hp_50pct_s = None  # when HP first dropped to ≤ half initial
    our_death_s = None

    # Fighter tracking
    our_first_deploy_s = None
    our_ever_active = set()  # unique fighter IDs seen not docked/destroyed
    our_peak_active = 0

    # Missile threat: min distance of armed enemy missile to our carrier
    min_missile_dist = float("inf")

    # Winner detection: last color with living carrier
    last_alive_colors = set()

    for frame in frames:
        frame_s = round(ts_to_sec(frame["timeStamp"]) - t0, 2)
        entities = [parse_entity(e) for e in frame["entities"]]
        entities = [e for e in entities if e]

        carriers = {e["color"]: e for e in entities if e["type"] == "C"}
        fighters = [e for e in entities if e["type"] == "F"]
        missiles = [e for e in entities if e["type"] == "M"]

        # Carrier death tracking
        for color, carrier in carriers.items():
            if carrier["status"] == "D":
                if color not in carrier_death_times:
                    carrier_death_times[color] = frame_s
            else:
                carrier_alive[color] = True

        # Our carrier HP
        if our_color and our_color in carriers:
            our_carrier = carriers[our_color]
            hp = carrier_hp(our_carrier)
            if hp is not None and our_carrier["status"] not in ("D", "C"):
                if our_hp_start is None:
                    our_hp_start = hp
                if our_hp_min is None or hp < our_hp_min:
                    our_hp_min = hp
                if our_hp_start and our_hp_50pct_s is None and hp <= our_hp_start * 0.5:
                    our_hp_50pct_s = frame_s

        if our_color and our_color in carrier_death_times and our_death_s is None:
            our_death_s = carrier_death_times[our_color]

        # Our fighter deployment
        if our_color:
            our_fighters = [
                f for f in fighters
                if f["color"] == our_color and f["status"] not in ("D", "C")
            ]
            active_count = len(our_fighters)
            if active_count > our_peak_active:
                our_peak_active = active_count
            for f in our_fighters:
                if f["id"] not in our_ever_active:
                    our_ever_active.add(f["id"])
                    if our_first_deploy_s is None:
                        our_first_deploy_s = frame_s

        # Missile threat
        if our_color and our_color in carriers:
            our_carrier = carriers[our_color]
            if our_carrier["status"] not in ("D",):
                for m in missiles:
                    if m["color"] != our_color and m["status"] == "A":
                        d = dist(m, our_carrier)
                        if d < min_missile_dist:
                            min_missile_dist = d

        # Track last living carriers
        living = {
            color for color, c in carriers.items() if c["status"] not in ("D",)
        }
        if living:
            last_alive_colors = living

    winner_color = next(iter(last_alive_colors)) if len(last_alive_colors) == 1 else None
    winner_name = color_to_name.get(winner_color) if winner_color else None

    # Build ordered death list
    death_order = sorted(carrier_death_times.items(), key=lambda x: x[1])

    result = {
        "game_id": game_id,
        "duration_s": duration_s,
        "players": color_to_name,
        "our_color": our_color,
        "carrier_deaths": [
            {"color": c, "name": color_to_name.get(c, c), "time_s": t}
            for c, t in death_order
        ],
        "our_carrier": {
            "hp_start": our_hp_start,
            "hp_min": our_hp_min,
            "hp_50pct_at_s": our_hp_50pct_s,
            "death_s": our_death_s,
        },
        "our_fighters": {
            "first_deploy_s": our_first_deploy_s,
            "peak_active": our_peak_active,
            "total_ever_active": len(our_ever_active),
        },
        "min_enemy_missile_dist_to_our_carrier": (
            round(min_missile_dist, 1) if min_missile_dist < float("inf") else None
        ),
        "winner": {"color": winner_color, "name": winner_name},
    }

    with open(out_path, "w") as f:
        json.dump(result, f, indent=2)

    print(f"Written: {out_path}")
    # Print brief summary to stdout
    our = result["our_carrier"]
    ftr = result["our_fighters"]
    print(
        f"  us={our_color}({color_to_name.get(our_color,'?')}) "
        f"death={our['death_s']}s "
        f"first_deploy={ftr['first_deploy_s']}s "
        f"peak_fighters={ftr['peak_active']} "
        f"min_missile_dist={result['min_enemy_missile_dist_to_our_carrier']}"
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
