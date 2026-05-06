package it.battlejar.commander.map;

import java.util.List;

public record ColorSectorStatus(
    boolean hasCarrier,
    FleetPresence presence,
    List<String> fighterNames,
    ThreatLevel threatLevel
) {
}
