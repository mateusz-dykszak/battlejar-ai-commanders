import json
import os
import sys
from datetime import datetime

def parse_iso_timestamp(ts_str):
    # Handle the 'Z' and nanoseconds if present
    ts_str = ts_str.replace('Z', '+00:00')
    try:
        # Python 3.11+ can handle more decimal places, but older might not.
        # Let's try to truncate to 6 decimal places for microseconds.
        if '.' in ts_str:
            base, offset = ts_str.split('+')
            main, fraction = base.split('.')
            fraction = fraction[:6]
            ts_str = f"{main}.{fraction}+{offset}"
        return datetime.fromisoformat(ts_str)
    except ValueError:
        # Fallback for simpler formats
        return datetime.fromisoformat(ts_str.split('.')[0])

def analyze_game(file_path):
    game_id = os.path.basename(file_path).replace('.jsonl', '')
    
    with open(file_path, 'r') as f:
        lines = f.readlines()
        
    if not lines:
        return None
    
    try:
        players = json.loads(lines[0])
    except json.JSONDecodeError:
        return None

    # Identify our color - AGENTS.md says "Agentic Junie" is us
    my_color = None
    for player in players:
        if player.get('username') == 'Agentic Junie':
            my_color = player.get('color')
            break
            
    if not my_color:
        # Fallback or maybe we are always one color?
        # Let's check the logs or assume for now we might be able to find it.
        # If not found, we can't easily calculate our specific K/D
        pass

    start_time = None
    end_time = None
    
    kills = 0
    deaths = 0
    
    # Track entity status to count deaths
    # entity_id -> last_status
    entity_statuses = {}
    
    # We want to count how many of OUR units died (deaths)
    # and how many ENEMY units died (kills) - though technically kills are harder to attribute
    # In this game, maybe we just count enemy deaths as our potential kills if we want to be simple,
    # or just report total deaths per color.
    
    deaths_by_color = {p['color']: 0 for p in players}
    carrier_alive = {p['color']: True for p in players}
    carrier_death_time = {p['color']: None for p in players}

    for line in lines[1:]:
        try:
            snapshot = json.loads(line)
        except json.JSONDecodeError:
            continue
            
        ts = parse_iso_timestamp(snapshot['timeStamp'])
        if start_time is None:
            start_time = ts
        end_time = ts
        
        entities = snapshot.get('entities', [])
        for ent_str in entities:
            parts = ent_str.split('|')
            if len(parts) < 12:
                continue
            
            ent_id = parts[0]
            ent_type = parts[1] # C, F, M
            ent_color = parts[2]
            status = parts[11]
            
            if ent_id not in entity_statuses:
                entity_statuses[ent_id] = status
            else:
                prev_status = entity_statuses[ent_id]
                if status == 'D' and prev_status != 'D':
                    deaths_by_color[ent_color] = deaths_by_color.get(ent_color, 0) + 1
                    if ent_type == 'C':
                        carrier_alive[ent_color] = False
                        if carrier_death_time[ent_color] is None:
                            carrier_death_time[ent_color] = ts
                entity_statuses[ent_id] = status

    survival_time = 0
    if start_time and end_time:
        if my_color and carrier_death_time[my_color]:
            survival_time = (carrier_death_time[my_color] - start_time).total_seconds()
        else:
            survival_time = (end_time - start_time).total_seconds()

    stats = {
        "game_id": game_id,
        "my_color": my_color,
        "survival_time_seconds": survival_time,
        "deaths_by_color": deaths_by_color,
        "carrier_alive": carrier_alive
    }
    
    return stats

def main():
    history_dir = 'history'
    if len(sys.argv) > 1:
        history_dir = sys.argv[1]
        
    if not os.path.exists(history_dir):
        print(f"Directory {history_dir} does not exist.")
        return

    all_stats = []
    for filename in os.listdir(history_dir):
        if filename.endswith('.jsonl'):
            file_path = os.path.join(history_dir, filename)
            game_stats = analyze_game(file_path)
            if game_stats:
                all_stats.append(game_stats)

    if not all_stats:
        print("No valid game logs found.")
        return

    # Print summary
    print(f"{'Game ID':<40} | {'Color':<7} | {'Survival':<10} | {'My Deaths':<9} | {'Enemy Deaths'}")
    print("-" * 90)
    for s in all_stats:
        my_color = s['my_color']
        my_deaths = s['deaths_by_color'].get(my_color, 0) if my_color else 0
        enemy_deaths = sum(v for k, v in s['deaths_by_color'].items() if k != my_color)
        
        print(f"{s['game_id']:<40} | {str(my_color):<7} | {s['survival_time_seconds']:>8.1f}s | {my_deaths:>9} | {enemy_deaths:>12}")

if __name__ == "__main__":
    main()
