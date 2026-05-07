#!/usr/bin/env python3
"""
spacecraft_orders.py — per-spacecraft order log for our fleet

Outputs: local_history/<gameId>_spacecraft/<entityId>.jsonl
  One file per entity that received at least one order. Typically:
    <COLOR>-00.jsonl  — carrier
    <COLOR>-04.jsonl, <COLOR>-05.jsonl, … — fighters

What it captures and why:
  Each output line records one order sent to one of our spacecraft alongside
  the spacecraft's state at that tick. This lets you replay exactly what each
  entity was told to do and where it was — useful for debugging tactic
  decisions (e.g. why did the carrier overshoot the corner? what order did
  fighter -07 receive when it fired into our carrier?).

Output line format (JSON, one per order-receiving tick):
  position — raw entity fields with id and type-char stripped:
               color|px|py|vx|vy||sx|sy|missiles|status
             Matches the format of the hand-crafted _carrier.jsonl reference.
  order    — {type, details}
             type    e.g. "MOVE", "PATROL", "ATTACK", "FIRE_MISSILE"
             details parameter string (e.g. "22|62" for MOVE), or null when
                     the order has no parameters (PATROL, bare ATTACK, etc.)

Caveats:
  - Only ticks where the entity received an order produce a line. Ticks where
    the cooldown blocked all orders for that entity are absent.
  - Missile entities (type M) are never owned by us and never appear here.
  - The skip sentinel is local_history/<gameId>.stats.json (written by
    stats.py). This script runs inside the same postprocess.sh loop and
    therefore processes each game exactly once.
"""

import json
import sys
from pathlib import Path


def main(jsonl_path: Path):
    game_id = jsonl_path.stem
    out_dir = Path(__file__).parent.parent / "local_history" / f"{game_id}_spacecraft"

    with open(jsonl_path) as f:
        lines = f.readlines()

    if not lines:
        return

    players = json.loads(lines[0])
    our_color = next((p["color"] for p in players if p["username"] == "Klaudia"), None)
    if our_color is None:
        return

    prefix = our_color + "-"
    out_dir.mkdir(exist_ok=True)
    handles = {}  # entity_id -> open file handle

    try:
        for line in lines[1:]:
            tick = json.loads(line)
            orders = tick.get("orders", [])
            if not orders:
                continue

            # Build id → position-string map for this tick.
            # Entity raw format: "id|type_char|color|px|py|vx|vy||sx|sy|missiles|status"
            # Position (as in the reference _carrier.jsonl): everything after "id|type_char|"
            entity_pos = {}
            for raw in tick.get("entities", []):
                # Split only on the first two pipes so the rest is kept intact.
                parts = raw.split("|", 2)
                if len(parts) == 3:
                    entity_pos[parts[0]] = parts[2]

            for order in orders:
                eid = order.get("id", "")
                if not eid.startswith(prefix):
                    continue
                position = entity_pos.get(eid)
                if position is None:
                    continue

                record = {
                    "position": position,
                    "order": {
                        "type": order["type"],
                        "details": order.get("details"),
                    },
                }

                if eid not in handles:
                    handles[eid] = open(out_dir / f"{eid}.jsonl", "w")
                handles[eid].write(json.dumps(record, separators=(',', ':')) + "\n")
    finally:
        for fh in handles.values():
            fh.close()


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} <game.jsonl>", file=sys.stderr)
        sys.exit(1)
    main(Path(sys.argv[1]))
