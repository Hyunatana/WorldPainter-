package org.pepsoft.worldpainter.ai;

import org.pepsoft.minecraft.SeededGenerator;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.history.HistoryEntry;
import org.pepsoft.worldpainter.layers.Biome;
import org.pepsoft.worldpainter.util.BiomeUtils;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.IntConsumer;

import static org.pepsoft.worldpainter.Dimension.Anchor.NORMAL_DETAIL;

/** Builds an isolated native world. Only native WorldPainter classes are stored in the resulting .world file. */
public final class WorldGenerator {
    private WorldGenerator() { }

    public static World2 generate(WorldCreationPlan plan, IntConsumer progress) {
        Set<Integer> biomes = new HashSet<>(BiomeUtils.getAllBiomes(DefaultPlugin.JAVA_ANVIL_1_19, null));
        biomes.add(255);
        if (!biomes.contains(plan.biomeId())) throw new IllegalArgumentException("Unknown biome for Java worlds: " + plan.biomeId());
        // Validate biome IDs and water levels before allocating tiles.
        for (TerrainCommand command : plan.commands()) {
            if (command instanceof TerrainCommand.PaintBiome b && !biomes.contains(b.biomeId())) {
                throw new IllegalArgumentException("Unknown biome: " + b.biomeId());
            }
            if (command instanceof TerrainCommand.Sculpt s && TerrainPlanCodec.usesWater(s.shape())
                    && (s.waterLevel() < WorldCreationPlan.MIN_HEIGHT || s.waterLevel() >= WorldCreationPlan.MAX_HEIGHT)) {
                throw new IllegalArgumentException("Command water level is outside the world's height range");
            }
        }
        checkCancelled();
        HeightMapTileFactory factory = TileFactoryFactory.createFlatTileFactory(plan.seed(), Terrain.GRASS,
                WorldCreationPlan.MIN_HEIGHT, WorldCreationPlan.MAX_HEIGHT, plan.baseHeight(), plan.waterLevel(), false, true);
        World2 world = new World2(DefaultPlugin.JAVA_ANVIL_1_19, plan.seed(), factory);
        world.setName(plan.name());
        world.addHistoryEntry(HistoryEntry.WORLD_CREATED);
        Dimension dimension = world.getDimension(NORMAL_DETAIL);
        dimension.setGenerator(new SeededGenerator(Generator.DEFAULT, plan.seed()));
        Rectangle bounds = plan.bounds();
        int tiles = plan.width() / 128 * (plan.height() / 128), completed = 0;
        for (int ty = bounds.y >> 7; ty < (bounds.y + bounds.height) >> 7; ty++) {
            for (int tx = bounds.x >> 7; tx < (bounds.x + bounds.width) >> 7; tx++) {
                checkCancelled();
                Tile tile = factory.createTile(tx, ty);
                tile.inhibitEvents();
                try {
                    for (int y = 0; y < 128; y++) {
                        checkCancelled();
                        for (int x = 0; x < 128; x++) {
                            tile.setHeight(x, y, (float) height(plan, bounds, tx * 128 + x, ty * 128 + y));
                            factory.applyTheme(tile, x, y);
                            if (plan.biomeId() != 255) tile.setLayerValue(Biome.INSTANCE, x, y, plan.biomeId());
                        }
                    }
                } finally { tile.releaseEvents(); }
                dimension.addTile(tile);
                if (progress != null) progress.accept(++completed * 80 / tiles);
            }
        }
        if (!plan.commands().isEmpty()) {
            dimension.registerUndoManager(new UndoManager(2));
            try {
                TerrainPlan edits = new TerrainPlan(plan.commands());
                TerrainEditEngine.Preview preview = TerrainEditEngine.prepare(TerrainEditEngine.capture(dimension, edits, List.of(), biomes));
                checkCancelled();
                TerrainEditEngine.apply(dimension, preview, List.of(), biomes);
                for (TerrainEditEngine.Change c : preview.changes()) {
                    if (c.before().height() != c.after().height()) dimension.applyTheme(c.x(), c.y());
                }
            } finally { dimension.unregisterUndoManager(); }
        }
        checkCancelled();
        // Choose a dry, reasonably level column close to the center. Never rewrite terrain to force a spawn.
        Point center = new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2), spawn = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int y = bounds.y + 2; y < bounds.y + bounds.height - 2; y += 4) {
            checkCancelled();
            for (int x = bounds.x + 2; x < bounds.x + bounds.width - 2; x += 4) {
                float h = dimension.getHeightAt(x, y);
                if (h <= dimension.getWaterLevelAt(x, y) || h > WorldCreationPlan.MAX_HEIGHT - 4) continue;
                double slope = Math.abs(dimension.getHeightAt(x + 1, y) - h) + Math.abs(dimension.getHeightAt(x, y + 1) - h);
                double score = center.distanceSq(x, y) + slope * 1024;
                if (score < bestScore) { bestScore = score; spawn = new Point(x, y); }
            }
        }
        if (spawn == null) throw new IllegalArgumentException("This plan produces no dry spawn; raise the base height or relief");
        world.setSpawnPoint(spawn);
        dimension.setLastViewPosition(spawn);
        if (progress != null) progress.accept(100);
        return world;
    }

    /** Full-resolution top-down height/water preview; no live editor data is accessed. */
    public static BufferedImage render(World2 world) {
        Dimension d = world.getDimension(NORMAL_DETAIL);
        Rectangle tiles = d.getExtent();
        int width = tiles.width * 128, height = tiles.height * 128;
        if (width < 1 || height < 1 || width > 1024 || height > 1024) throw new IllegalArgumentException("Preview exceeds world creation bounds");
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            checkCancelled();
            for (int x = 0; x < width; x++) {
                float h = d.getHeightAt(tiles.x * 128 + x, tiles.y * 128 + y);
                int water = d.getWaterLevelAt(tiles.x * 128 + x, tiles.y * 128 + y);
                int colour;
                if (h < water) {
                    int depth = Math.min(75, (int) (water - h));
                    colour = (25 << 16) | ((115 - depth) << 8) | (190 - depth);
                } else {
                    float t = Math.max(0, Math.min(1, (h - water) / 160f));
                    int r = (int) (70 + 165 * t), g = (int) (140 + 95 * t), b = (int) (55 + 180 * t);
                    colour = (r << 16) | (g << 8) | b;
                }
                image.setRGB(x, y, colour);
            }
        }
        return image;
    }

    private static double height(WorldCreationPlan p, Rectangle r, int x, int y) {
        double nx = (x - r.x - (r.width - 1) / 2.0) / ((r.width - 1) / 2.0);
        double ny = (y - r.y - (r.height - 1) / 2.0) / ((r.height - 1) / 2.0);
        double envelope = Math.max(0, 1 - nx * nx - ny * ny);
        double scale = Math.max(32, Math.min(p.width(), p.height()) / 5.0);
        double n = TerrainEditEngine.noise(x / scale, y / scale, p.seed());
        double detail = TerrainEditEngine.noise(x / 24.0, y / 24.0, p.seed() ^ 0x6a09e667f3bcc909L);
        double h = switch (p.landscape()) {
            case PLAINS -> p.baseHeight() + p.relief() * (0.2 * n + 0.04 * detail);
            case MOUNTAINS -> p.baseHeight() + p.relief() * (0.25 + 0.65 * (1 - Math.abs(n)) + 0.1 * detail);
            case ISLAND -> p.waterLevel() - 12 + (p.baseHeight() - p.waterLevel() + 12 + p.relief() * (0.8 + 0.2 * n)) * envelope * envelope;
            case CONTINENT -> p.waterLevel() - 16 + (p.baseHeight() - p.waterLevel() + 16 + p.relief() * (0.65 + 0.35 * n)) * envelope;
            case ARCHIPELAGO -> p.waterLevel() - 12 + Math.max(0, n + 0.25) * p.relief() * envelope
                    + Math.max(0, p.baseHeight() - p.waterLevel()) * envelope * envelope;
        };
        return Math.max(WorldCreationPlan.MIN_HEIGHT, Math.min(WorldCreationPlan.MAX_HEIGHT - 1, h));
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("World generation cancelled");
    }
}
