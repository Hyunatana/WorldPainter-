package org.pepsoft.worldpainter.ai;

import org.junit.Test;
import org.pepsoft.worldpainter.DefaultPlugin;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.HeightMapTileFactory;
import org.pepsoft.worldpainter.Terrain;
import org.pepsoft.worldpainter.TileFactoryFactory;
import org.pepsoft.worldpainter.World2;
import org.pepsoft.worldpainter.layers.ReadOnly;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;
import static org.pepsoft.worldpainter.Dimension.Anchor.NORMAL_DETAIL;

public class TerrainPreviewOverlayTest {
    @Test public void paintsExactlyChangedColumnsAndLeavesProtectedColumnsTransparent() {
        Dimension dimension = dimension();
        dimension.setBitLayerValueAt(ReadOnly.INSTANCE, 2, 2, true);
        TerrainPlan plan = new TerrainPlan(List.of(new TerrainCommand.PaintBiome(new EditRegion(0, 0, 32, 16), 1)));
        TerrainEditEngine.Preview preview = TerrainEditEngine.prepare(TerrainEditEngine.capture(dimension, plan, List.of(), Set.of(1)));
        assertTrue(preview.changes().size() > 0);
        long revision = dimension.getChangeNo();
        TerrainPreviewOverlay overlay = TerrainPreviewOverlay.create(preview);
        BufferedImage image = render(overlay);
        int pixels = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) if ((image.getRGB(x, y) >>> 24) != 0) pixels++;
        }
        assertEquals(preview.changes().size(), pixels);
        for (TerrainEditEngine.Change change : preview.changes()) assertTrue((image.getRGB(change.x(), change.y()) >>> 24) != 0);
        assertEquals(0, image.getRGB(2, 2));
        assertEquals(0, image.getRGB(16, 16));
        assertEquals(revision, dimension.getChangeNo());
        assertTrue(overlay.matches(dimension));
    }

    @Test public void overlappingCommandsDoNotDoublePaintAndRevisionChangesInvalidate() {
        Dimension dimension = dimension();
        EditRegion region = new EditRegion(0, 0, 8, 8);
        TerrainPlan plan = new TerrainPlan(List.of(new TerrainCommand.PaintBiome(region, 1), new TerrainCommand.PaintBiome(region, 2)));
        TerrainEditEngine.Preview preview = TerrainEditEngine.prepare(TerrainEditEngine.capture(dimension, plan, List.of(), Set.of(1, 2)));
        TerrainPreviewOverlay overlay = TerrainPreviewOverlay.create(preview);
        BufferedImage image = render(overlay);
        assertEquals(0xb0, image.getRGB(4, 4) >>> 24);
        assertFalse(overlay.matches(dimension()));
        dimension.setHeightAt(4, 4, dimension.getHeightAt(4, 4) + 1);
        assertFalse(overlay.matches(dimension));
    }

    private static Dimension dimension() {
        HeightMapTileFactory factory = TileFactoryFactory.createFlatTileFactory(42, Terrain.GRASS, 0, 256, 64, 62, false, false);
        Dimension dimension = new Dimension(new World2(DefaultPlugin.JAVA_ANVIL, 0, 256), "Surface", 42, factory, NORMAL_DETAIL);
        dimension.addTile(factory.createTile(0, 0));
        return dimension;
    }

    private static BufferedImage render(TerrainPreviewOverlay overlay) {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { overlay.paint(graphics); }
        finally { graphics.dispose(); }
        return image;
    }
}
