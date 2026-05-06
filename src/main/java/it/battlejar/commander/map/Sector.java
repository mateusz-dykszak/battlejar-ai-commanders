package it.battlejar.commander.map;

import it.battlejar.api.Color;
import java.util.Map;

public record Sector(
    Map<Color, ColorSectorStatus> colorStatuses
) {
}
