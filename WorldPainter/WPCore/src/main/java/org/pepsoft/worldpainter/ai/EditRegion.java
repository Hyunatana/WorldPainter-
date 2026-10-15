package org.pepsoft.worldpainter.ai;

/** A bounded rectangle in WorldPainter X/Y coordinates (Minecraft X/Z). */
public record EditRegion(int x, int y, int width, int height) {
    public static final int MAX_COLUMNS = 262_144;

    public EditRegion {
        if (width < 1 || height < 1 || width > 1024 || height > 1024
                || (long) width * height > MAX_COLUMNS) {
            throw new IllegalArgumentException("Region must be 1–1024 blocks per side and at most 262144 columns");
        }
        if ((long) x + width - 1 > Integer.MAX_VALUE || (long) y + height - 1 > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Region exceeds coordinate range");
        }
    }

    public int columns() {
        return width * height;
    }
}
