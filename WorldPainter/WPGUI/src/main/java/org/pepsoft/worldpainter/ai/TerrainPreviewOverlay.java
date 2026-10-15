package org.pepsoft.worldpainter.ai;

import org.pepsoft.worldpainter.Dimension;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/** Temporary, read-only map decoration. Never becomes part of a saved world. */
public final class TerrainPreviewOverlay {
    private TerrainPreviewOverlay(Dimension source, long revision, List<Patch> patches) {
        this.source = source;
        this.revision = revision;
        this.patches = List.copyOf(patches);
    }

    /** Uses only the immutable preview, so rasterisation may run on a worker. */
    public static TerrainPreviewOverlay create(TerrainEditEngine.Preview preview) {
        List<Patch> patches = new ArrayList<>();
        for (TerrainCommand command : preview.plan().commands()) {
            EditRegion region = command.region();
            patches.add(new Patch(region, new BufferedImage(region.width(), region.height(), BufferedImage.TYPE_INT_ARGB)));
        }
        for (TerrainEditEngine.Change change : preview.changes()) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            int colour = change.after().height() > change.before().height() ? RAISED
                    : change.after().height() < change.before().height() ? LOWERED
                    : change.after().water() != change.before().water() || change.after().lava() != change.before().lava() ? WATER : LAYER;
            // Assign each column once, including when command regions overlap.
            for (Patch patch : patches) {
                long x = (long) change.x() - patch.region.x(), y = (long) change.y() - patch.region.y();
                if (x >= 0 && x < patch.region.width() && y >= 0 && y < patch.region.height()) {
                    patch.image.setRGB((int) x, (int) y, colour);
                    break;
                }
            }
        }
        return new TerrainPreviewOverlay(preview.source(), preview.revision(), patches);
    }

    /** Call on the EDT, where live dimension revisions are read. */
    public boolean matches(Dimension dimension) {
        return dimension == source && dimension != null && dimension.getChangeNo() == revision;
    }

    /** Graphics must already be in WorldPainter's world coordinate system. */
    public void paint(Graphics2D graphics) {
        for (Patch patch : patches) graphics.drawImage(patch.image, patch.region.x(), patch.region.y(), null);
    }

    private record Patch(EditRegion region, BufferedImage image) { }
    private final Dimension source;
    private final long revision;
    private final List<Patch> patches;
    private static final int RAISED = 0xb0ff982b, LOWERED = 0xb04477ff, WATER = 0xb029dce8, LAYER = 0xb0c15bef;
}
