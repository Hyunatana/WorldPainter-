package org.pepsoft.worldpainter.ai;

import java.awt.Rectangle;
import java.util.List;

/** A separate, finite Java world; it never allocates tiles in an existing world. */
public record WorldCreationPlan(String name, long seed, int width, int height, Landscape landscape,
                                int baseHeight, int waterLevel, int relief, int biomeId,
                                List<TerrainCommand> commands) {
    public enum Landscape { PLAINS, MOUNTAINS, ISLAND, CONTINENT, ARCHIPELAGO }
    public static final int MIN_HEIGHT = -64, MAX_HEIGHT = 320, MAX_SIDE = 1024;

    public WorldCreationPlan {
        if (name == null || name.isBlank() || name.length() > 80 || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("World name must contain 1–80 printable characters");
        }
        name = name.trim();
        if (width < 128 || height < 128 || width > MAX_SIDE || height > MAX_SIDE || width % 128 != 0 || height % 128 != 0) {
            throw new IllegalArgumentException("World sides must be multiples of 128 between 128 and 1024 blocks");
        }
        if (landscape == null || baseHeight < MIN_HEIGHT + 1 || baseHeight >= MAX_HEIGHT
                || waterLevel < MIN_HEIGHT + 1 || waterLevel >= MAX_HEIGHT || relief < 1 || relief > 192) {
            throw new IllegalArgumentException("Use valid world heights (−63–319), relief 1–192, and a landscape");
        }
        if (biomeId < 0 || biomeId > 255) throw new IllegalArgumentException("Biome ID must be 0–255");
        commands = List.copyOf(commands);
        if (!commands.isEmpty()) new TerrainPlan(commands); // Same edit budget and closed vocabulary.
        Rectangle bounds = bounds(width, height);
        for (TerrainCommand command : commands) {
            EditRegion r = command.region();
            if (!bounds.contains(new Rectangle(r.x(), r.y(), r.width(), r.height()))) {
                throw new IllegalArgumentException("Creation commands must stay inside the new world's bounds");
            }
            if (command instanceof TerrainCommand.PaintLayer) {
                throw new IllegalArgumentException("A new world has no configured custom layers to paint");
            }
        }
    }

    public Rectangle bounds() { return bounds(width, height); }

    private static Rectangle bounds(int width, int height) {
        return new Rectangle(-(width / 128 / 2) * 128, -(height / 128 / 2) * 128, width, height);
    }
}
