package org.pepsoft.worldpainter.ai;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.pepsoft.worldpainter.ai.TerrainPlanCodec.*;

/** Creation has an explicit root, so an edit response cannot accidentally create/replace a world. */
public final class WorldPlanCodec {
    private WorldPlanCodec() { }

    public static WorldCreationPlan parse(String text) {
        if (text == null || text.length() > 65_536) throw new IllegalArgumentException("Creation plan exceeds 64 KiB");
        try {
            Map<?, ?> root = object(JsonProtocol.read(text));
            fields(root, "version", "world", "commands");
            if (integer(root, "version") != 1 || !(root.get("commands") instanceof List<?> commands)) {
                throw new IllegalArgumentException("Expected version 1 and a commands array");
            }
            Map<?, ?> w = object(root.get("world"));
            fields(w, "name", "seed", "width", "height", "landscape", "baseHeight", "waterLevel", "relief", "biomeId");
            WorldCreationPlan.Landscape landscape;
            try { landscape = WorldCreationPlan.Landscape.valueOf(string(w, "landscape").toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("Unknown world landscape"); }
            List<TerrainCommand> edits = commands.isEmpty() ? List.of()
                    : TerrainPlanCodec.parse(JsonProtocol.write(Map.of("version", 1, "commands", commands))).commands();
            return new WorldCreationPlan(string(w, "name"), number(w, "seed"), integer(w, "width"), integer(w, "height"),
                    landscape, integer(w, "baseHeight"), integer(w, "waterLevel"), integer(w, "relief"), integer(w, "biomeId"), edits);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Expected a JSON world creation plan without Markdown or extra text", e);
        }
    }

    public static String encode(WorldCreationPlan p) {
        try {
            Object commands = p.commands().isEmpty() ? List.of()
                    : object(JsonProtocol.read(TerrainPlanCodec.encode(new TerrainPlan(p.commands())))).get("commands");
            return JsonProtocol.write(Map.of("version", 1,
                    "world", Map.of("name", p.name(), "seed", p.seed(), "width", p.width(), "height", p.height(),
                            "landscape", p.landscape().name().toLowerCase(Locale.ROOT), "baseHeight", p.baseHeight(),
                            "waterLevel", p.waterLevel(), "relief", p.relief(), "biomeId", p.biomeId()), "commands", commands));
        } catch (JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
    }
}
