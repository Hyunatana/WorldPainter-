package org.pepsoft.worldpainter.ai;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict JSON and a small offline grammar feed the same validated command types. */
public final class TerrainPlanCodec {
    private TerrainPlanCodec() { }

    public static String encode(TerrainPlan plan) {
        List<Map<String, Object>> commands = new ArrayList<>();
        for (TerrainCommand command : plan.commands()) {
            EditRegion r = command.region();
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("region", Map.of("x", r.x(), "y", r.y(), "width", r.width(), "height", r.height()));
            if (command instanceof TerrainCommand.Sculpt s) {
                c.put("type", s.shape().name().toLowerCase(Locale.ROOT));
                c.put("magnitude", s.magnitude());
                c.put("seed", s.seed());
                if (usesWater(s.shape())) c.put("waterLevel", s.waterLevel());
            } else if (command instanceof TerrainCommand.PaintBiome b) {
                c.put("type", "biome");
                c.put("biomeId", b.biomeId());
            } else if (command instanceof TerrainCommand.PaintLayer l) {
                c.put("type", "layer");
                c.put("layerId", l.layerId());
                c.put("value", l.value());
            }
            commands.add(c);
        }
        return JsonProtocol.write(Map.of("version", 1, "commands", commands));
    }

    public static TerrainPlan parse(String text) {
        if (text == null || text.length() > 65_536) {
            throw new IllegalArgumentException("Plan exceeds 64 KiB");
        }
        try {
            Object parsed = JsonProtocol.read(text);
            Map<?, ?> root = object(parsed);
            fields(root, "version", "commands");
            if (integer(root, "version") != 1 || !(root.get("commands") instanceof List<?> list)) {
                throw new IllegalArgumentException("Expected version 1 and commands array");
            }
            if (list.size() > 8) throw new IllegalArgumentException("At most eight commands are allowed");
            List<TerrainCommand> commands = new ArrayList<>();
            for (Object item : list) {
                Map<?, ?> c = object(item);
                String type = string(c, "type");
                Map<?, ?> r = object(c.get("region"));
                fields(r, "x", "y", "width", "height");
                EditRegion region = new EditRegion(integer(r, "x"), integer(r, "y"), integer(r, "width"), integer(r, "height"));
                switch (type) {
                    case "biome" -> {
                        fields(c, "type", "region", "biomeId");
                        commands.add(new TerrainCommand.PaintBiome(region, integer(c, "biomeId")));
                    }
                    case "layer" -> {
                        fields(c, "type", "region", "layerId", "value");
                        commands.add(new TerrainCommand.PaintLayer(region, string(c, "layerId"), integer(c, "value")));
                    }
                    default -> {
                        TerrainCommand.Shape shape;
                        try { shape = TerrainCommand.Shape.valueOf(type.toUpperCase(Locale.ROOT)); }
                        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Unsupported command: " + type); }
                        boolean water = usesWater(shape);
                        fields(c, water ? new String[]{"type", "region", "magnitude", "seed", "waterLevel"}
                                : new String[]{"type", "region", "magnitude", "seed"});
                        commands.add(new TerrainCommand.Sculpt(region, shape, integer(c, "magnitude"), number(c, "seed"),
                                water ? integer(c, "waterLevel") : 0));
                    }
                }
            }
            return new TerrainPlan(commands);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Expected a JSON plan without Markdown or explanatory text", e);
        }
    }

    public static TerrainPlan parseOffline(String text) {
        if (text != null && text.stripLeading().startsWith("{")) return parse(text);
        Matcher m = OFFLINE.matcher(text == null ? "" : text.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("Try: Create a mountain at 0, 0 radius 64 height 40 seed 42. Or paste a JSON plan.");
        }
        try {
            int x = Integer.parseInt(m.group(2)), y = Integer.parseInt(m.group(3));
            int radius = Integer.parseInt(m.group(4));
            if (radius < 1 || radius > 128) throw new IllegalArgumentException("Offline radius must be 1–128");
            int originX = Math.toIntExact((long) x - radius), originY = Math.toIntExact((long) y - radius);
            TerrainCommand.Shape shape = TerrainCommand.Shape.valueOf(m.group(1).toUpperCase(Locale.ROOT));
            if (usesWater(shape) && m.group(7) == null) throw new IllegalArgumentException("Add water <level> for rivers, islands and continents");
            if (!usesWater(shape) && m.group(7) != null) throw new IllegalArgumentException("Water level is only used for rivers, islands and continents");
            return new TerrainPlan(List.of(new TerrainCommand.Sculpt(new EditRegion(originX, originY, radius * 2 + 1, radius * 2 + 1),
                    shape, Integer.parseInt(m.group(5)), m.group(6) == null ? 0 : Long.parseLong(m.group(6)),
                    m.group(7) == null ? 0 : Integer.parseInt(m.group(7)))));
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("Number outside supported range", e);
        }
    }

    public static boolean usesWater(TerrainCommand.Shape shape) {
        return shape == TerrainCommand.Shape.RIVER || shape == TerrainCommand.Shape.ISLAND || shape == TerrainCommand.Shape.CONTINENT;
    }

    static Map<?, ?> object(Object value) {
        if (value instanceof Map<?, ?> map) return map;
        throw new IllegalArgumentException("Expected a JSON object");
    }

    static void fields(Map<?, ?> map, String... expected) {
        if (!map.keySet().equals(Set.of(expected))) throw new IllegalArgumentException("Expected exactly these fields: " + String.join(", ", expected));
    }

    static long number(Map<?, ?> map, String key) {
        if (map.get(key) instanceof Long number) return number;
        throw new IllegalArgumentException(key + " must be an integer");
    }

    static int integer(Map<?, ?> map, String key) {
        long n = number(map, key);
        if (n < Integer.MIN_VALUE || n > Integer.MAX_VALUE) throw new IllegalArgumentException(key + " is out of range");
        return (int) n;
    }

    static String string(Map<?, ?> map, String key) {
        if (map.get(key) instanceof String value) return value;
        throw new IllegalArgumentException(key + " must be text");
    }

    private static final Pattern OFFLINE = Pattern.compile("^create a (hill|mountain|valley|river|island|continent) at (-?\\d+),?\\s+(-?\\d+) radius (\\d+) height (\\d+)(?: seed (-?\\d+))?(?: water (-?\\d+))?$", Pattern.CASE_INSENSITIVE);
}
