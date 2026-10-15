package org.pepsoft.worldpainter.ai;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.pepsoft.worldpainter.WorldIO;
import org.pepsoft.worldpainter.exporting.JavaMinecraftWorld;
import org.pepsoft.worldpainter.DefaultPlugin;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;
import static org.pepsoft.worldpainter.Dimension.Anchor.NORMAL_DETAIL;

public class WorldGenerationCliTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void publishesLoadableWorldPreviewAndReproduciblePlan() throws Exception {
        Path input = input(), output = temp.getRoot().toPath().resolve("result");
        WorldGenerationCli.create(input, output, false);
        assertEquals(WorldPlanCodec.parse(Files.readString(input)), WorldPlanCodec.parse(Files.readString(output.resolve("plan.json"))));
        assertEquals(128, ImageIO.read(output.resolve("preview.png").toFile()).getWidth());
        WorldIO io = new WorldIO();
        io.load(Files.newInputStream(output.resolve("world.world")));
        assertEquals(1, io.getWorld().getDimension(NORMAL_DETAIL).getTileCount());
        assertThrows(java.io.IOException.class, () -> WorldGenerationCli.create(input, output, false));
        assertTrue(Files.size(output.resolve("world.world")) > 0);
    }

    @Test public void invalidInputLeavesNoDestinationOrStagingFiles() throws Exception {
        Path input = temp.newFile("invalid.json").toPath(), output = temp.getRoot().toPath().resolve("result");
        Files.writeString(input, "{\"commands\":[]}");
        assertThrows(IllegalArgumentException.class, () -> WorldGenerationCli.create(input, output, false));
        assertFalse(Files.exists(output));
        try (var paths = Files.list(output.getParent())) { assertFalse(paths.anyMatch(p -> p.getFileName().toString().startsWith(".worldpainter-generation-"))); }
    }

    @Test public void minecraftExportWritesReadableAnvilChunksWithGroundAndAir() throws Exception {
        Path output = temp.getRoot().toPath().resolve("exported");
        WorldGenerationCli.create(input(), output, true);
        Path minecraft = output.resolve("minecraft");
        assertTrue(Files.isRegularFile(minecraft.resolve("level.dat")));
        try (var regions = Files.list(minecraft.resolve("region"))) { assertTrue(regions.anyMatch(p -> p.toString().endsWith(".mca"))); }
        WorldIO io = new WorldIO();
        io.load(Files.newInputStream(output.resolve("world.world")));
        var dimension = io.getWorld().getDimension(NORMAL_DETAIL);
        var spawn = io.getWorld().getSpawnPoint();
        int height = dimension.getIntHeightAt(spawn.x, spawn.y);
        try (JavaMinecraftWorld exported = new JavaMinecraftWorld(minecraft.toFile(), 0, -64, 320, DefaultPlugin.JAVA_ANVIL_1_19, true, 16)) {
            assertFalse(exported.getMaterialAt(spawn.x, spawn.y, height).air);
            assertTrue(exported.getMaterialAt(spawn.x, spawn.y, height + 8).air);
        }
    }

    private Path input() throws Exception {
        Path input = temp.newFile().toPath();
        Files.writeString(input, WorldPlanCodec.encode(WorldCreationTest.plan(WorldCreationPlan.Landscape.PLAINS, 42)));
        return input;
    }
}
