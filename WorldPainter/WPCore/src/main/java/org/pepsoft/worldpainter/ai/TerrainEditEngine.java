package org.pepsoft.worldpainter.ai;

import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.Platform;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.layers.Biome;
import org.pepsoft.worldpainter.layers.CustomLayer;
import org.pepsoft.worldpainter.layers.FloodWithLava;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.layers.NotPresent;
import org.pepsoft.worldpainter.layers.ReadOnly;
import org.pepsoft.worldpainter.layers.Void;

import java.util.*;
import java.util.concurrent.CancellationException;

/**
 * Snapshot -> deterministic pure computation -> atomic editor transaction.
 * Capture and apply belong to the editor thread; prepare never reads the live world.
 */
public final class TerrainEditEngine {
    private TerrainEditEngine() { }

    /** Uses the same platform/ceiling policy as the editor's native biome controls. */
    public static boolean supportsBiomes(Dimension dimension) {
        if (dimension == null || dimension.getWorld() == null) return false;
        Set<Platform.Capability> capabilities = dimension.getWorld().getPlatform().capabilities;
        return (!dimension.getAnchor().invert && capabilities.contains(Platform.Capability.BIOMES))
                || capabilities.contains(Platform.Capability.BIOMES_3D)
                || capabilities.contains(Platform.Capability.NAMED_BIOMES);
    }

    public record Column(float height, int water, boolean lava, Map<Layer, Integer> values) {
        public Column { values = Map.copyOf(values); }
    }

    public record Change(int x, int y, Column before, Column after) { }

    public static final class Snapshot {
        private Snapshot(Dimension source, TerrainPlan plan, Map<Long, Column> columns, Map<String, CustomLayer> layers,
                         Map<Long, Tile> tiles, int skippedColumns) {
            this.source = source;
            this.revision = source.getChangeNo();
            this.minHeight = source.getMinHeight();
            this.maxHeight = source.getMaxHeight();
            this.plan = plan;
            this.columns = Collections.unmodifiableMap(columns);
            this.layers = Map.copyOf(layers);
            this.tiles = Map.copyOf(tiles);
            this.skippedColumns = skippedColumns;
        }
        private final Dimension source;
        private final long revision;
        private final int minHeight, maxHeight, skippedColumns;
        private final TerrainPlan plan;
        private final Map<Long, Column> columns;
        private final Map<String, CustomLayer> layers;
        private final Map<Long, Tile> tiles;
    }

    public static final class Preview {
        private Preview(Snapshot snapshot, List<Change> changes) {
            this.snapshot = snapshot;
            this.changes = List.copyOf(changes);
        }
        public Dimension source() { return snapshot.source; }
        public long revision() { return snapshot.revision; }
        public TerrainPlan plan() { return snapshot.plan; }
        public List<Change> changes() { return changes; }
        /** Counts skipped command-column visits, so overlapping commands may count a column more than once. */
        public int skippedColumns() { return snapshot.skippedColumns; }
        private final Snapshot snapshot;
        private final List<Change> changes;
    }

    public static Snapshot capture(Dimension dimension, TerrainPlan plan, Collection<CustomLayer> customLayers, Set<Integer> validBiomes) {
        Objects.requireNonNull(dimension, "Open a dimension first");
        Objects.requireNonNull(plan);
        if (dimension.isEventsInhibited()) throw new IllegalStateException("Another edit is in progress");
        Map<String, CustomLayer> layers = validate(dimension, plan, customLayers, validBiomes);
        Set<Layer> channels = new LinkedHashSet<>();
        for (TerrainCommand c : plan.commands()) {
            if (c instanceof TerrainCommand.PaintBiome) channels.add(Biome.INSTANCE);
            if (c instanceof TerrainCommand.PaintLayer l) channels.add(layers.get(l.layerId()));
        }
        Map<Long, Column> columns = new LinkedHashMap<>();
        Map<Long, Tile> tiles = new HashMap<>();
        int skipped = 0;
        for (TerrainCommand command : plan.commands()) {
            EditRegion r = command.region();
            for (int dy = 0; dy < r.height(); dy++) {
                for (int dx = 0; dx < r.width(); dx++) {
                    int x = r.x() + dx, y = r.y() + dy;
                    Tile tile = dimension.getTile(x >> 7, y >> 7);
                    if (tile == null || blocked(dimension, x, y)) {
                        skipped++;
                        continue;
                    }
                    tiles.put(key(x >> 7, y >> 7), tile);
                    long key = key(x, y);
                    if (!columns.containsKey(key)) columns.put(key, read(dimension, x, y, channels));
                }
            }
        }
        return new Snapshot(dimension, plan, columns, layers, tiles, skipped);
    }

    /** Computes a preview from immutable data; safe to run in a cancellable worker. */
    public static Preview prepare(Snapshot snapshot) {
        Map<Long, Column> result = new LinkedHashMap<>(snapshot.columns);
        for (TerrainCommand command : snapshot.plan.commands()) {
            EditRegion r = command.region();
            for (int dy = 0; dy < r.height(); dy++) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                for (int dx = 0; dx < r.width(); dx++) {
                    int x = r.x() + dx, y = r.y() + dy;
                    long key = key(x, y);
                    Column old = result.get(key);
                    if (old == null) continue;
                    Column next;
                    if (command instanceof TerrainCommand.Sculpt sculpt) {
                        next = sculpt(sculpt, x, y, old, snapshot.minHeight, snapshot.maxHeight);
                    } else {
                        Map<Layer, Integer> values = new LinkedHashMap<>(old.values());
                        if (command instanceof TerrainCommand.PaintBiome b) values.put(Biome.INSTANCE, b.biomeId());
                        if (command instanceof TerrainCommand.PaintLayer l) values.put(snapshot.layers.get(l.layerId()), l.value());
                        next = new Column(old.height(), old.water(), old.lava(), values);
                    }
                    result.put(key, next);
                }
            }
        }
        List<Change> changes = new ArrayList<>();
        for (Map.Entry<Long, Column> e : result.entrySet()) {
            Column before = snapshot.columns.get(e.getKey());
            if (!before.equals(e.getValue())) {
                changes.add(new Change((int) (e.getKey() >> 32), (int) (long) e.getKey(), before, e.getValue()));
            }
        }
        return new Preview(snapshot, changes);
    }

    /** Applies exactly the preview, as one native undo step. Refuses all stale previews before any writes. */
    public static int apply(Dimension dimension, Preview preview, Collection<CustomLayer> customLayers, Set<Integer> validBiomes) {
        if (dimension != preview.source() || dimension.getChangeNo() != preview.revision()) {
            throw new IllegalStateException("The dimension changed; generate a new preview");
        }
        if (!dimension.isUndoAvailable() || dimension.isEventsInhibited()) {
            throw new IllegalStateException("Native undo must be available and no other edit may be running");
        }
        Map<String, CustomLayer> layers = validate(dimension, preview.plan(), customLayers, validBiomes);
        for (Map.Entry<String, CustomLayer> e : preview.snapshot.layers.entrySet()) {
            if (layers.get(e.getKey()) != e.getValue()) throw new IllegalStateException("Custom layers changed; generate a new preview");
        }
        for (Map.Entry<Long, Tile> e : preview.snapshot.tiles.entrySet()) {
            if (dimension.getTile((int) (e.getKey() >> 32), (int) (long) e.getKey()) != e.getValue()) {
                throw new IllegalStateException("Tiles changed; generate a new preview");
            }
        }
        // Check actual data too: tile events may be temporarily suppressed by another editor operation.
        for (Map.Entry<Long, Column> e : preview.snapshot.columns.entrySet()) {
            int x = (int) (e.getKey() >> 32), y = (int) (long) e.getKey();
            if (blocked(dimension, x, y) || !e.getValue().equals(read(dimension, x, y, e.getValue().values().keySet()))) {
                throw new IllegalStateException("Terrain changed; generate a new preview");
            }
        }
        if (preview.changes().isEmpty()) return 0;
        dimension.rememberChanges();
        dimension.setEventsInhibited(true);
        try {
            for (Change change : preview.changes()) write(dimension, change);
        } catch (RuntimeException | Error failure) {
            try {
                if (dimension.undoChanges()) dimension.clearRedo();
                else failure.addSuppressed(new IllegalStateException("Undo rollback was unavailable"));
            } catch (RuntimeException | Error rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        } finally {
            dimension.armSavePoint();
            dimension.setEventsInhibited(false);
        }
        return preview.changes().size();
    }

    private static Map<String, CustomLayer> validate(Dimension dimension, TerrainPlan plan, Collection<CustomLayer> customLayers, Set<Integer> validBiomes) {
        Map<String, CustomLayer> available = new HashMap<>();
        for (CustomLayer layer : customLayers) available.put(layer.getId(), layer);
        Map<String, CustomLayer> used = new HashMap<>();
        for (TerrainCommand command : plan.commands()) {
            if (command instanceof TerrainCommand.Sculpt s && TerrainPlanCodec.usesWater(s.shape())
                    && (s.waterLevel() < dimension.getMinHeight() || s.waterLevel() >= dimension.getMaxHeight())) {
                throw new IllegalArgumentException("Water level is outside this dimension's height range");
            }
            if (command instanceof TerrainCommand.PaintBiome b) {
                if (!supportsBiomes(dimension)) throw new IllegalArgumentException("Biome painting is not supported by this format/dimension");
                if (!validBiomes.contains(b.biomeId())) throw new IllegalArgumentException("Unknown biome ID for this world: " + b.biomeId());
            }
            if (command instanceof TerrainCommand.PaintLayer l) {
                CustomLayer layer = available.get(l.layerId());
                if (layer == null) throw new IllegalArgumentException("Unknown custom layer ID: " + l.layerId());
                if (layer.getDataSize() != Layer.DataSize.BIT && layer.getDataSize() != Layer.DataSize.NIBBLE
                        && layer.getDataSize() != Layer.DataSize.BYTE) {
                    throw new IllegalArgumentException("Only per-column BIT, NIBBLE and BYTE custom layers can be painted");
                }
                if (l.value() > layer.getDataSize().maxValue) throw new IllegalArgumentException("Value exceeds layer range");
                used.put(layer.getId(), layer);
            }
        }
        return used;
    }

    private static boolean blocked(Dimension dimension, int x, int y) {
        return dimension.getBitLayerValueAt(ReadOnly.INSTANCE, x, y)
                || dimension.getBitLayerValueAt(NotPresent.INSTANCE, x, y)
                || dimension.getBitLayerValueAt(Void.INSTANCE, x, y);
    }

    private static Column read(Dimension dimension, int x, int y, Collection<Layer> channels) {
        Map<Layer, Integer> values = new LinkedHashMap<>();
        for (Layer layer : channels) values.put(layer, layer.getDataSize() == Layer.DataSize.BIT
                ? (dimension.getBitLayerValueAt(layer, x, y) ? 1 : 0) : dimension.getLayerValueAt(layer, x, y));
        return new Column(dimension.getHeightAt(x, y), dimension.getWaterLevelAt(x, y),
                dimension.getBitLayerValueAt(FloodWithLava.INSTANCE, x, y), values);
    }

    private static void write(Dimension dimension, Change change) {
        Column a = change.before(), b = change.after();
        if (a.height() != b.height()) dimension.setHeightAt(change.x(), change.y(), b.height());
        if (a.water() != b.water()) dimension.setWaterLevelAt(change.x(), change.y(), b.water());
        if (a.lava() != b.lava()) dimension.setBitLayerValueAt(FloodWithLava.INSTANCE, change.x(), change.y(), b.lava());
        for (Map.Entry<Layer, Integer> e : b.values().entrySet()) {
            if (Objects.equals(a.values().get(e.getKey()), e.getValue())) continue;
            if (e.getKey().getDataSize() == Layer.DataSize.BIT) {
                dimension.setBitLayerValueAt(e.getKey(), change.x(), change.y(), e.getValue() != 0);
            } else dimension.setLayerValueAt(e.getKey(), change.x(), change.y(), e.getValue());
        }
    }

    private static Column sculpt(TerrainCommand.Sculpt s, int x, int y, Column old, int minHeight, int maxHeight) {
        EditRegion r = s.region();
        double nx = (x - (double) r.x() - (r.width() - 1) / 2.0) / Math.max(1, (r.width() - 1) / 2.0);
        double ny = (y - (double) r.y() - (r.height() - 1) / 2.0) / Math.max(1, (r.height() - 1) / 2.0);
        double radius = StrictMath.sqrt(nx * nx + ny * ny), weight = Math.max(0, 1 - radius * radius);
        double noise = noise(x / 32.0, y / 32.0, s.seed());
        double target = old.height();
        int water = old.water();
        boolean lava = old.lava();
        switch (s.shape()) {
            case HILL -> target += s.magnitude() * weight * weight;
            case MOUNTAIN -> target += s.magnitude() * StrictMath.pow(Math.max(0, 1 - radius), 1.4) * (0.8 + 0.2 * noise);
            case VALLEY -> target -= s.magnitude() * weight * weight;
            case RIVER -> {
                double along = r.width() >= r.height() ? nx : ny;
                double across = r.width() >= r.height() ? ny : nx;
                double phase = hash(0, 0, s.seed()) * StrictMath.PI;
                double distance = Math.abs(across - 0.25 * StrictMath.sin(along * 3 + phase));
                double channel = Math.max(0, 1 - distance / 0.20);
                if (channel > 0) {
                    target = Math.min(target, s.waterLevel() - s.magnitude() * channel * channel);
                    water = s.waterLevel();
                    lava = false;
                }
            }
            case ISLAND -> {
                double depth = Math.max(2, s.magnitude() * 0.2);
                target = s.waterLevel() - depth + (s.magnitude() + depth) * weight * weight * (0.9 + 0.1 * noise);
                water = s.waterLevel();
                lava = false;
            }
            case CONTINENT -> {
                double coast = weight + 0.2 * noise * weight;
                target = s.waterLevel() + s.magnitude() * (coast - 0.25);
                water = s.waterLevel();
                lava = false;
            }
        }
        // WorldPainter stores 1/256-block fixed-point heights; match that quantisation in the preview.
        double clamped = Math.max(minHeight, Math.min(maxHeight - 1, target));
        float height = minHeight + Math.round((clamped - minHeight) * 256) / 256.0f;
        return new Column(height, water, lava, old.values());
    }

    private static double noise(double x, double y, long seed) {
        long ix = (long) StrictMath.floor(x), iy = (long) StrictMath.floor(y);
        double fx = x - ix, fy = y - iy;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        double a = hash(ix, iy, seed) * (1 - fx) + hash(ix + 1, iy, seed) * fx;
        double b = hash(ix, iy + 1, seed) * (1 - fx) + hash(ix + 1, iy + 1, seed) * fx;
        return a * (1 - fy) + b * fy;
    }

    private static double hash(long x, long y, long seed) {
        long n = seed ^ (x * 0x9e3779b97f4a7c15L) ^ (y * 0xc2b2ae3d27d4eb4fL);
        n = (n ^ (n >>> 30)) * 0xbf58476d1ce4e5b9L;
        n = (n ^ (n >>> 27)) * 0x94d049bb133111ebL;
        n ^= n >>> 31;
        return ((n >>> 11) * 0x1.0p-53) * 2 - 1;
    }

    private static long key(int x, int y) { return ((long) x << 32) | (y & 0xffffffffL); }
}
