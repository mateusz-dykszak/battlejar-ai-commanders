#!/usr/bin/env python3
"""
phases.py — game-phase summary and death-cause breakdown
Outputs: local_history/<gameId>.phases.json

What it captures and why:

  1v1 phase (when exactly one enemy carrier remains):
    - first_1v1_s       Time (seconds) when live enemy carriers dropped to 1.
                        Null if we never eliminated a 2nd enemy. Knowing when 1v1
                        starts lets us measure how long we last in the decisive phase.
    - hp_at_1v1         Our carrier HP at the moment 1v1 began. High HP means we
                        entered clean; low HP means we were already bleeding.
    - ec_dist_at_1v1    Distance to enemy carrier at 1v1 start. < 150 means the
                        remaining enemy is already inside our formation radius and
                        fighter overshoot becomes the primary damage source.
    - survived_1v1_s    Seconds we lasted in 1v1 before dying (null if survived).

  Death cause (last 8 seconds before our carrier died — null fields if we survived):
    - death_peak_ef_near  Peak enemy-fighter count within 100 units of our carrier.
                          High values (> 10) indicate a fighter-laser swarm killed us,
                          not missiles.
    - death_peak_m80      Peak armed enemy missiles within 80 units.
    - death_peak_m200     Peak armed enemy missiles within 200 units.
    - death_ec_dist       Distance to nearest enemy carrier at moment of death.
    - death_live_ec       Number of live enemy carriers at death (1 = 1v1 death).
    - death_cause         Heuristic label: "fighters" if ef_near ≥ 8 and m80 < 3,
                          "missiles" if m80 ≥ 3 and ef_near < 8,
                          "combined" if both, "unknown" otherwise.

  These fields complement threats.csv (raw per-second) and stats.json (game-wide
  summary) by giving a concise per-game phase-level view without re-reading CSVs.
"""

import csv
import json
import sys
from pathlib import Path


def fv(row, key):
    try:
        return float(row.get(key) or 0)
    except (ValueError, TypeError):
        return 0.0


def iv(row, key):
    try:
        return int(row.get(key) or 0)
    except (ValueError, TypeError):
        return 0


def main(jsonl_path: Path):
    game_id = jsonl_path.stem
    out_dir = Path(__file__).parent.parent / "local_history"
    threats_path = out_dir / f"{game_id}.threats.csv"
    stats_path = out_dir / f"{game_id}.stats.json"
    out_path = out_dir / f"{game_id}.phases.json"

    if not threats_path.exists():
        print(f"phases: threats CSV not found for {game_id}, skipping")
        return
    if not stats_path.exists():
        print(f"phases: stats JSON not found for {game_id}, skipping")
        return

    with open(stats_path) as f:
        stats = json.load(f)

    our_death_s = stats["our_carrier"]["death_s"]

    with open(threats_path) as f:
        rows = list(csv.DictReader(f))

    # --- 1v1 phase ---
    first_1v1_s = None
    hp_at_1v1 = None
    ec_dist_at_1v1 = None

    for row in rows:
        if iv(row, "live_enemy_carriers") == 1:
            first_1v1_s = round(fv(row, "time_s"), 2)
            hp_at_1v1 = iv(row, "our_hp") or None
            d = fv(row, "nearest_enemy_carrier_dist")
            ec_dist_at_1v1 = round(d, 1) if d else None
            break

    survived_1v1_s = None
    if first_1v1_s is not None and our_death_s is not None:
        survived_1v1_s = round(our_death_s - first_1v1_s, 2)

    # --- death cause (last 8s) ---
    death_peak_ef_near = None
    death_peak_m80 = None
    death_peak_m200 = None
    death_ec_dist = None
    death_live_ec = None
    death_cause = None

    if our_death_s is not None:
        window = [r for r in rows
                  if fv(r, "time_s") >= our_death_s - 8
                  and fv(r, "time_s") < our_death_s]
        if window:
            death_peak_ef_near = max(iv(r, "enemy_fighters_near_100") for r in window)
            death_peak_m80 = max(iv(r, "armed_missiles_within_80") for r in window)
            death_peak_m200 = max(iv(r, "armed_missiles_within_200") for r in window)
            last = window[-1]
            d = fv(last, "nearest_enemy_carrier_dist")
            death_ec_dist = round(d, 1) if d else None
            death_live_ec = iv(last, "live_enemy_carriers")

            ef_high = death_peak_ef_near >= 8
            m_high = death_peak_m80 >= 3
            if ef_high and m_high:
                death_cause = "combined"
            elif ef_high:
                death_cause = "fighters"
            elif m_high:
                death_cause = "missiles"
            else:
                death_cause = "unknown"

    result = {
        "game_id": game_id,
        "1v1": {
            "first_1v1_s": first_1v1_s,
            "hp_at_1v1": hp_at_1v1,
            "ec_dist_at_1v1": ec_dist_at_1v1,
            "survived_1v1_s": survived_1v1_s,
        },
        "death": {
            "death_peak_ef_near": death_peak_ef_near,
            "death_peak_m80": death_peak_m80,
            "death_peak_m200": death_peak_m200,
            "death_ec_dist": death_ec_dist,
            "death_live_ec": death_live_ec,
            "death_cause": death_cause,
        },
    }

    with open(out_path, "w") as f:
        json.dump(result, f, indent=2)

    print(f"Written: {out_path}")
    cause_s = death_cause or "survived"
    print(f"  1v1_start={first_1v1_s}s hp@1v1={hp_at_1v1} ec@1v1={ec_dist_at_1v1} "
          f"survived_1v1={survived_1v1_s}s cause={cause_s}")


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
