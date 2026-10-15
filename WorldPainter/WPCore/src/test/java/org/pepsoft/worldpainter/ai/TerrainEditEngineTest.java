package org.pepsoft.worldpainter.ai;

import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.DefaultPlugin;
import org.pepsoft.worldpainter.Terrain;
import org.pepsoft.worldpainter.TestData;
import org.pepsoft.worldpainter.TileFactoryFactory;
import org.pepsoft.worldpainter.World2;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.layers.Void;

import java.awt.Color;
import java.awt.Rectangle;
import java.util.*;

import static org.junit.Assert.*;
import static org.pepsoft.worldpainter.ai.TerrainCommand.Shape.*;

public class TerrainEditEngineTest {
    @Test
    public void previewIsReadOnlyAndApplyMatchesEveryPreviewedValue() {
        Dimension d = dimension(70);
        TerrainPlan plan = sculpt(MOUNTAIN, new EditRegion(20, 20, 65, 65), 60, 42, 0);
        long revision = d.getChangeNo();
        TerrainEditEngine.Preview p = preview(d, plan);
        assertFalse(p.changes().isEmpty());
        assertEquals(revision, d.getChangeNo());
        assertEquals(70, d.getHeightAt(52, 52), 0);
        assertEquals(p.changes().size(), TerrainEditEngine.apply(d, p, List.of(), BIOMES));
        for (TerrainEditEngine.Change c : p.changes()) {
            assertEquals(c.after().height(), d.getHeightAt(c.x(), c.y()), 0);
            assertTrue(c.x() >= 20 && c.x() < 85 && c.y() >= 20 && c.y() < 85);
        }
        assertEquals(70, d.getHeightAt(19, 52), 0);
        assertThrows(IllegalStateException.class, () -> TerrainEditEngine.apply(d, p, List.of(), BIOMES));
    }

    @Test
    public void seededGenerationIsReproducibleAndSeedChangesRuggedTerrain() {
        Dimension d = dimension(70);
        EditRegion r = new EditRegion(0, 0, 96, 96);
        TerrainEditEngine.Preview a = preview(d, sculpt(MOUNTAIN, r, 100, 1234, 0));
        assertEquals(a.changes(), preview(d, sculpt(MOUNTAIN, r, 100, 1234, 0)).changes());
        assertNotEquals(a.changes(), preview(d, sculpt(MOUNTAIN, r, 100, 1235, 0)).changes());
    }

    @Test
    public void nativeUndoAndRedoRestoreTerrainWaterLavaAndLayersAsOneStep() {
        Dimension d = dimension(70);
        UndoManager undo = new UndoManager(16);
        d.unregisterUndoManager();
        d.registerUndoManager(undo);
        d.armSavePoint();
        // An ordinary editor operation before the assistant must stay separate.
        d.setHeightAt(120, 120, 99);
        d.setBitLayerValueAt(FloodWithLava.INSTANCE, 20, 20, true);
        CustomLayer layer = new TestLayer(Layer.DataSize.NIBBLE);
        EditRegion r = new EditRegion(0, 0, 64, 64);
        TerrainPlan plan = new TerrainPlan(List.of(new TerrainCommand.Sculpt(r, ISLAND, 60, 42, 62),
                new TerrainCommand.PaintBiome(r, 1), new TerrainCommand.PaintLayer(r, layer.getId(), 12)));
        TerrainEditEngine.Preview p = TerrainEditEngine.prepare(TerrainEditEngine.capture(d, plan, List.of(layer), BIOMES));
        TerrainEditEngine.apply(d, p, List.of(layer), BIOMES);
        float after = d.getHeightAt(20, 20);
        assertFalse(d.getBitLayerValueAt(FloodWithLava.INSTANCE, 20, 20));
        assertEquals(12, d.getLayerValueAt(layer, 20, 20));
        assertTrue(undo.undo());
        assertEquals(70, d.getHeightAt(20, 20), 0);
        assertTrue(d.getBitLayerValueAt(FloodWithLava.INSTANCE, 20, 20));
        assertEquals(0, d.getLayerValueAt(layer, 20, 20));
        assertEquals(99, d.getHeightAt(120, 120), 0);
        assertTrue(undo.redo());
        assertEquals(after, d.getHeightAt(20, 20), 0);
        assertEquals(62, d.getWaterLevelAt(20, 20));
        assertEquals(1, d.getLayerValueAt(Biome.INSTANCE, 20, 20));
        assertEquals(12, d.getLayerValueAt(layer, 20, 20));
    }

    @Test
    public void staleDimensionOrDataNeverWrites() {
        Dimension d = dimension(70);
        TerrainEditEngine.Preview p = preview(d, sculpt(HILL, new EditRegion(0, 0, 32, 32), 20, 0, 0));
        Dimension other = dimension(70);
        assertThrows(IllegalStateException.class, () -> TerrainEditEngine.apply(other, p, List.of(), BIOMES));
        d.setHeightAt(120, 120, 71);
        assertThrows(IllegalStateException.class, () -> TerrainEditEngine.apply(d, p, List.of(), BIOMES));
        assertEquals(70, d.getHeightAt(16, 16), 0);
    }

    @Test
    public void suppressedTileEventsCannotBypassStalePreviewCheck() {
        Dimension d = dimension(70);
        TerrainEditEngine.Preview p = preview(d, sculpt(HILL, new EditRegion(0, 0, 32, 32), 20, 0, 0));
        d.getTile(0, 0).inhibitEvents();
        try {
            d.setHeightAt(16, 16, 71);
            assertEquals(p.revision(), d.getChangeNo());
            assertThrows(IllegalStateException.class, () -> TerrainEditEngine.apply(d, p, List.of(), BIOMES));
            assertEquals(71, d.getHeightAt(16, 16), 0);
        } finally { d.getTile(0, 0).releaseEvents(); }
    }

    @Test
    public void missingProtectedAndVoidColumnsAreSkippedWithoutCreatingTiles() {
        Dimension d = dimension(70);
        d.setBitLayerValueAt(ReadOnly.INSTANCE, 0, 0, true);
        d.setBitLayerValueAt(NotPresent.INSTANCE, 16, 0, true);
        d.setBitLayerValueAt(Void.INSTANCE, 32, 0, true);
        int tiles = d.getTileCount();
        TerrainEditEngine.Preview p = preview(d, sculpt(ISLAND, new EditRegion(-8, -8, 64, 64), 50, 0, 62));
        assertTrue(p.skippedColumns() > 0);
        TerrainEditEngine.apply(d, p, List.of(), BIOMES);
        assertEquals(tiles, d.getTileCount());
        assertNull(d.getTile(-1, -1));
        assertEquals(70, d.getHeightAt(0, 0), 0);
        assertEquals(70, d.getHeightAt(16, 0), 0);
        assertEquals(70, d.getHeightAt(32, 0), 0);
        assertNotEquals(70, d.getHeightAt(33, 0), 0);
    }

    @Test
    public void terrainClampsToDimensionHeightAndEmptyEditAddsNoChanges() {
        Dimension d = dimension(TestData.MAX_HEIGHT - 1);
        assertTrue(preview(d, sculpt(HILL, new EditRegion(0, 0, 32, 32), 256, 0, 0)).changes().isEmpty());
        Dimension low = dimension(TestData.MIN_HEIGHT + 2);
        TerrainEditEngine.Preview p = preview(low, sculpt(VALLEY, new EditRegion(0, 0, 33, 33), 256, 0, 0));
        TerrainEditEngine.apply(low, p, List.of(), BIOMES);
        assertEquals(TestData.MIN_HEIGHT, low.getHeightAt(16, 16), 0);
        for (TerrainEditEngine.Change c : p.changes()) assertTrue(c.after().height() >= TestData.MIN_HEIGHT);
    }

    @Test
    public void riverCarvesWetMeanderingChannelAndClearsLavaOnlyInsideIt() {
        Dimension d = dimension(90);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 128; x++) d.setBitLayerValueAt(FloodWithLava.INSTANCE, x, y, true);
        TerrainEditEngine.Preview p = preview(d, sculpt(RIVER, new EditRegion(0, 0, 128, 64), 10, 7, 62));
        assertTrue(p.changes().size() > 128);
        assertTrue(p.changes().size() < 128 * 64 / 2);
        TerrainEditEngine.apply(d, p, List.of(), BIOMES);
        for (TerrainEditEngine.Change c : p.changes()) {
            assertTrue(c.after().height() <= 62);
            assertEquals(62, d.getWaterLevelAt(c.x(), c.y()));
            assertFalse(d.getBitLayerValueAt(FloodWithLava.INSTANCE, c.x(), c.y()));
        }
        assertEquals(90, d.getHeightAt(64, 0), 0);
        assertTrue(d.getBitLayerValueAt(FloodWithLava.INSTANCE, 64, 0));
    }

    @Test
    public void islandsAndContinentsHaveDryCentersAndSubmergedEdges() {
        for (TerrainCommand.Shape shape : List.of(ISLAND, CONTINENT)) {
            Dimension d = dimension(70);
            TerrainEditEngine.apply(d, preview(d, sculpt(shape, new EditRegion(0, 0, 65, 65), 60, 42, 62)), List.of(), BIOMES);
            assertTrue(d.getHeightAt(32, 32) > 62);
            assertTrue(d.getHeightAt(0, 0) < 62);
            assertEquals(62, d.getWaterLevelAt(0, 0));
            assertEquals(70, d.getHeightAt(65, 32), 0);
        }
    }

    @Test
    public void layerAndBiomeValidationRejectsInvalidPlansBeforeEditing() {
        Dimension d = dimension(70);
        EditRegion r = new EditRegion(0, 0, 4, 4);
        assertThrows(IllegalArgumentException.class, () -> preview(d, new TerrainPlan(List.of(new TerrainCommand.PaintBiome(r, 254)))));
        assertThrows(IllegalArgumentException.class, () -> preview(d, sculpt(RIVER, r, 10, 0, TestData.MAX_HEIGHT)));
        CustomLayer layer = new TestLayer(Layer.DataSize.NIBBLE);
        TerrainPlan invalid = new TerrainPlan(List.of(new TerrainCommand.PaintLayer(r, layer.getId(), 16)));
        assertThrows(IllegalArgumentException.class, () -> TerrainEditEngine.capture(d, invalid, List.of(layer), BIOMES));
        assertThrows(IllegalArgumentException.class, () -> TerrainEditEngine.capture(d, invalid, List.of(), BIOMES));
        CustomLayer chunky = new TestLayer(Layer.DataSize.BIT_PER_CHUNK);
        TerrainPlan chunkPlan = new TerrainPlan(List.of(new TerrainCommand.PaintLayer(r, chunky.getId(), 1)));
        assertThrows(IllegalArgumentException.class, () -> TerrainEditEngine.capture(d, chunkPlan, List.of(chunky), BIOMES));
    }

    @Test
    public void formatsWithoutBiomeExportCannotAcceptBiomePlans() {
        var factory = TileFactoryFactory.createFlatTileFactory(42, Terrain.GRASS, 0, 128, 64, 62, false, false);
        Dimension legacy = new Dimension(new World2(DefaultPlugin.JAVA_MCREGION, 0, 128), "Surface", 42,
                factory, Dimension.Anchor.NORMAL_DETAIL);
        legacy.addTile(factory.createTile(0, 0));
        assertFalse(TerrainEditEngine.supportsBiomes(legacy));
        assertTrue(TerrainEditEngine.supportsBiomes(dimension(70)));
        TerrainPlan plan = new TerrainPlan(List.of(new TerrainCommand.PaintBiome(new EditRegion(0, 0, 4, 4), 1)));
        assertThrows(IllegalArgumentException.class, () -> TerrainEditEngine.capture(legacy, plan, List.of(), BIOMES));
    }

    @Test
    public void bitLayerPaintingStaysWithinRegionAndRemovedLayerInvalidatesPreview() {
        Dimension d = dimension(70);
        CustomLayer layer = new TestLayer(Layer.DataSize.BIT);
        TerrainPlan plan = new TerrainPlan(List.of(new TerrainCommand.PaintLayer(new EditRegion(15, 15, 2, 2), layer.getId(), 1)));
        TerrainEditEngine.Preview p = TerrainEditEngine.prepare(TerrainEditEngine.capture(d, plan, List.of(layer), BIOMES));
        assertThrows(IllegalArgumentException.class, () -> TerrainEditEngine.apply(d, p, List.of(), BIOMES));
        TerrainEditEngine.apply(d, p, List.of(layer), BIOMES);
        assertTrue(d.getBitLayerValueAt(layer, 15, 15));
        assertTrue(d.getBitLayerValueAt(layer, 16, 16));
        assertFalse(d.getBitLayerValueAt(layer, 14, 15));
        assertFalse(d.getBitLayerValueAt(layer, 17, 16));
    }

    @Test
    public void coordinatesNearIntegerLimitAndNegativeTilesStayBounded() {
        Dimension d = dimension(70);
        d.addTile(d.getTileFactory().createTile(-1, -1));
        TerrainEditEngine.Preview p = preview(d, sculpt(HILL, new EditRegion(-32, -32, 33, 33), 20, 0, 0));
        TerrainEditEngine.apply(d, p, List.of(), BIOMES);
        assertTrue(d.getHeightAt(-16, -16) > 70);
        assertTrue(preview(d, sculpt(HILL, new EditRegion(Integer.MAX_VALUE - 3, Integer.MAX_VALUE - 3, 4, 4), 20, 0, 0)).changes().isEmpty());
    }

    @Test
    public void unavailableUndoAndConcurrentEditAreRefused() {
        Dimension d = dimension(70);
        TerrainEditEngine.Preview p = preview(d, sculpt(HILL, new EditRegion(0, 0, 32, 32), 20, 0, 0));
        d.setEventsInhibited(true);
        try {
            assertThrows(IllegalStateException.class, () -> TerrainEditEngine.apply(d, p, List.of(), BIOMES));
            assertThrows(IllegalStateException.class, () -> preview(d, p.plan()));
        } finally { d.setEventsInhibited(false); }
        d.unregisterUndoManager();
        assertThrows(IllegalStateException.class, () -> TerrainEditEngine.apply(d, p, List.of(), BIOMES));
    }

    @Test
    public void failedApplyRollsBackWithoutRevertingThePreviousEdit() {
        FaultyDimension d = new FaultyDimension();
        d.setHeightAt(120, 120, 99);
        TerrainEditEngine.Preview p = preview(d, sculpt(HILL, new EditRegion(0, 0, 32, 32), 20, 0, 0));
        d.fail = true;
        assertThrows(IllegalStateException.class, () -> TerrainEditEngine.apply(d, p, List.of(), BIOMES));
        assertFalse(d.isEventsInhibited());
        assertEquals(99, d.getHeightAt(120, 120), 0);
        for (TerrainEditEngine.Change c : p.changes()) assertEquals(c.before().height(), d.getHeightAt(c.x(), c.y()), 0);
        assertTrue(d.undo.undo());
        assertEquals(70, d.getHeightAt(120, 120), 0);
    }

    private static Dimension dimension(int height) {
        Dimension d = TestData.createDimension(new Rectangle(0, 0, 128, 128), height);
        d.registerUndoManager(new UndoManager(16));
        d.armSavePoint();
        return d;
    }

    private static TerrainEditEngine.Preview preview(Dimension d, TerrainPlan plan) {
        return TerrainEditEngine.prepare(TerrainEditEngine.capture(d, plan, List.of(), BIOMES));
    }

    private static TerrainPlan sculpt(TerrainCommand.Shape shape, EditRegion region, int magnitude, long seed, int water) {
        return new TerrainPlan(List.of(new TerrainCommand.Sculpt(region, shape, magnitude, seed, water)));
    }

    private static class TestLayer extends CustomLayer {
        TestLayer(DataSize size) { super("Test", "Test custom layer", size, 10, Color.GREEN); }
    }

    private static class FaultyDimension extends Dimension {
        FaultyDimension() {
            super(TestData.WORLD, "Surface", TestData.SEED, TestData.createTileFactory(70), Dimension.Anchor.NORMAL_DETAIL);
            addTile(getTileFactory().createTile(0, 0));
            registerUndoManager(undo);
            armSavePoint();
        }
        @Override public void setHeightAt(int x, int y, float height) {
            if (fail && writes++ == 3) throw new IllegalStateException("Injected write failure");
            super.setHeightAt(x, y, height);
        }
        final UndoManager undo = new UndoManager(16);
        boolean fail;
        int writes;
    }

    private static final Set<Integer> BIOMES = Set.of(1, 2, 4, 255);
}
