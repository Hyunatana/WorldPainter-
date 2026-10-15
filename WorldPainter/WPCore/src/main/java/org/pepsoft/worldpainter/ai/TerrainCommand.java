package org.pepsoft.worldpainter.ai;

import java.util.Objects;

/** Closed command vocabulary. Model output never names Java classes or scripts. */
public sealed interface TerrainCommand {
    EditRegion region();

    enum Shape { HILL, MOUNTAIN, VALLEY, RIVER, ISLAND, CONTINENT }

    record Sculpt(EditRegion region, Shape shape, int magnitude, long seed, int waterLevel) implements TerrainCommand {
        public Sculpt {
            Objects.requireNonNull(region);
            Objects.requireNonNull(shape);
            if (magnitude < 1 || magnitude > 256) {
                throw new IllegalArgumentException("Magnitude must be 1–256 blocks");
            }
        }
    }

    record PaintBiome(EditRegion region, int biomeId) implements TerrainCommand {
        public PaintBiome {
            Objects.requireNonNull(region);
            if (biomeId < 0 || biomeId > 255) {
                throw new IllegalArgumentException("Biome ID must be 0–255");
            }
        }
    }

    record PaintLayer(EditRegion region, String layerId, int value) implements TerrainCommand {
        public PaintLayer {
            Objects.requireNonNull(region);
            if (layerId == null || layerId.isBlank() || layerId.length() > 256 || value < 0 || value > 255) {
                throw new IllegalArgumentException("Layer requires a known ID and a value of 0–255");
            }
        }
    }
}
