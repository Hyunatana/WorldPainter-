package org.pepsoft.worldpainter.ai;

import java.util.List;

public record TerrainPlan(List<TerrainCommand> commands) {
    public TerrainPlan {
        commands = List.copyOf(commands);
        if (commands.isEmpty() || commands.size() > 8
                || commands.stream().mapToLong(c -> c.region().columns()).sum() > EditRegion.MAX_COLUMNS) {
            throw new IllegalArgumentException("Use 1–8 commands totalling at most 262144 columns");
        }
    }
}
