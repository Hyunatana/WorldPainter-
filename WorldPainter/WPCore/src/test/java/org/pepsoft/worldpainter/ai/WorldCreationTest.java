package org.pepsoft.worldpainter.ai;

import org.junit.Test;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.World2;
import org.pepsoft.worldpainter.WorldIO;
import org.pepsoft.worldpainter.layers.Biome;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.Assert.*;
import static org.pepsoft.worldpainter.Dimension.Anchor.NORMAL_DETAIL;

public class WorldCreationTest {
    @Test public void creationProtocolIsStrictAndDistinctFromEditing() {
        WorldCreationPlan plan = plan(WorldCreationPlan.Landscape.ISLAND, 42);
        assertEquals(plan, WorldPlanCodec.parse(WorldPlanCodec.encode(plan)));
        assertThrows(IllegalArgumentException.class, () -> TerrainPlanCodec.parse(WorldPlanCodec.encode(plan)));
        String json = WorldPlanCodec.encode(plan);
        for (String invalid : List.of(json.replace("\"width\":128", "\"width\":129"),
                json.replace("\"width\":128", "\"width\":2048"),
                json.replace("\"seed\":42", "\"seed\":42.0"), json.replace("\"version\":1", "\"version\":2"),
                json.replace("\"island\"", "\"execute_java\""), json.replace("\"world\":", "\"script\":null,\"world\":"),
                json + " {}", "{\"version\":1,\"world\":{},\"commands\":[]}")) {
            assertThrows(invalid, IllegalArgumentException.class, () -> WorldPlanCodec.parse(invalid));
        }
        String example = WorldPrompt.SYSTEM.substring(WorldPrompt.SYSTEM.indexOf("Example response:\n") + "Example response:\n".length()).trim();
        assertEquals(256, WorldPlanCodec.parse(example).width());
    }

    @Test public void localEditsCannotEscapeTheNewWorldOrReferToMissingLayers() {
        for (TerrainCommand c : List.of(new TerrainCommand.PaintBiome(new EditRegion(-1, 0, 4, 4), 1),
                new TerrainCommand.PaintLayer(new EditRegion(0, 0, 4, 4), "missing", 1))) {
            assertThrows(IllegalArgumentException.class, () -> new WorldCreationPlan("Test", 0, 128, 128,
                    WorldCreationPlan.Landscape.PLAINS, 80, 62, 12, 255, List.of(c)));
        }
        assertThrows(IllegalArgumentException.class, () -> WorldGenerator.generate(new WorldCreationPlan("Test", 0, 128, 128,
                WorldCreationPlan.Landscape.PLAINS, 80, 62, 12, 200, List.of()), null));
    }

    @Test public void everyLandscapeBuildsCompleteTilesWithDrySpawnAndBoundedHeights() {
        for (WorldCreationPlan.Landscape landscape : WorldCreationPlan.Landscape.values()) {
            List<Integer> progress = new ArrayList<>();
            WorldCreationPlan p = plan(landscape, 42);
            World2 world = WorldGenerator.generate(p, progress::add);
            Dimension d = world.getDimension(NORMAL_DETAIL);
            assertEquals(1, d.getTileCount());
            assertEquals(p.bounds(), new java.awt.Rectangle(d.getLowestX() * 128, d.getLowestY() * 128, 128, 128));
            assertEquals(WorldCreationPlan.MIN_HEIGHT, world.getMinHeight());
            assertEquals(WorldCreationPlan.MAX_HEIGHT, world.getMaxHeight());
            var spawn = world.getSpawnPoint();
            assertTrue(p.bounds().contains(spawn));
            assertTrue(d.getHeightAt(spawn.x, spawn.y) > d.getWaterLevelAt(spawn.x, spawn.y));
            assertEquals(Integer.valueOf(100), progress.get(progress.size() - 1));
            for (int y = 0; y < 128; y += 11) for (int x = 0; x < 128; x += 11) {
                assertTrue(d.getHeightAt(x, y) >= WorldCreationPlan.MIN_HEIGHT);
                assertTrue(d.getHeightAt(x, y) < WorldCreationPlan.MAX_HEIGHT);
                assertEquals(62, d.getWaterLevelAt(x, y));
            }
        }
    }

    @Test public void reproducibilityAndTileSeamsUseWorldCoordinates() {
        WorldCreationPlan p = new WorldCreationPlan("Test", 73, 256, 128, WorldCreationPlan.Landscape.PLAINS, 80, 62, 32, 255, List.of());
        Dimension a = WorldGenerator.generate(p, null).getDimension(NORMAL_DETAIL);
        Dimension b = WorldGenerator.generate(p, null).getDimension(NORMAL_DETAIL);
        Dimension changed = WorldGenerator.generate(new WorldCreationPlan("Test", 74, 256, 128,
                p.landscape(), 80, 62, 32, 255, List.of()), null).getDimension(NORMAL_DETAIL);
        boolean different = false;
        for (int y = 0; y < 128; y += 9) for (int x = -128; x < 128; x += 9) {
            assertEquals(a.getHeightAt(x, y), b.getHeightAt(x, y), 0);
            different |= a.getHeightAt(x, y) != changed.getHeightAt(x, y);
        }
        assertTrue(different);
        assertTrue(Math.abs(a.getHeightAt(-1, 64) - a.getHeightAt(0, 64)) < 2);
    }

    @Test public void creationComposesExistingCommandsAndNativeSerializationRoundTrips() throws Exception {
        WorldCreationPlan p = new WorldCreationPlan("Emerald 雪", Long.MIN_VALUE, 128, 128, WorldCreationPlan.Landscape.PLAINS,
                80, 62, 12, 255, List.of(new TerrainCommand.Sculpt(new EditRegion(0, 0, 65, 65), TerrainCommand.Shape.MOUNTAIN, 60, 7, 0),
                new TerrainCommand.PaintBiome(new EditRegion(0, 0, 65, 65), 1)));
        World2 world = WorldGenerator.generate(p, null);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new WorldIO(world).save(bytes);
        WorldIO io = new WorldIO();
        io.load(new ByteArrayInputStream(bytes.toByteArray()));
        World2 loaded = io.getWorld();
        assertEquals(p.name(), loaded.getName());
        assertEquals(world.getSpawnPoint(), loaded.getSpawnPoint());
        assertEquals(Long.MIN_VALUE, loaded.getDimension(NORMAL_DETAIL).getSeed());
        assertEquals(1, loaded.getDimension(NORMAL_DETAIL).getLayerValueAt(Biome.INSTANCE, 32, 32));
        assertEquals(world.getDimension(NORMAL_DETAIL).getHeightAt(32, 32), loaded.getDimension(NORMAL_DETAIL).getHeightAt(32, 32), 0);
        assertFalse(loaded.getDimension(NORMAL_DETAIL).getTileFactory().getClass().getName().contains(".ai."));
        assertFalse(loaded.getDimension(NORMAL_DETAIL).isUndoAvailable());
    }

    @Test public void cancellationAndFloodedWorldsNeverReturnPartialResults() {
        Thread.currentThread().interrupt();
        try { assertThrows(CancellationException.class, () -> WorldGenerator.generate(plan(WorldCreationPlan.Landscape.ISLAND, 0), null)); }
        finally { Thread.interrupted(); }
        assertThrows(CancellationException.class, () -> WorldGenerator.generate(plan(WorldCreationPlan.Landscape.ISLAND, 0), n -> Thread.currentThread().interrupt()));
        Thread.interrupted();
        WorldCreationPlan flooded = new WorldCreationPlan("Flooded", 0, 128, 128, WorldCreationPlan.Landscape.PLAINS, 0, 62, 1, 255, List.of());
        assertThrows(IllegalArgumentException.class, () -> WorldGenerator.generate(flooded, null));
    }

    static WorldCreationPlan plan(WorldCreationPlan.Landscape landscape, long seed) {
        return new WorldCreationPlan("Test", seed, 128, 128, landscape, 96, 62, 60, 255, List.of());
    }
}
