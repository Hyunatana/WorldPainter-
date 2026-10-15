package org.pepsoft.worldpainter.ai;

import org.pepsoft.worldpainter.Configuration;
import org.pepsoft.worldpainter.WPContext;
import org.pepsoft.worldpainter.World2;
import org.pepsoft.worldpainter.WorldIO;
import org.pepsoft.worldpainter.exporting.JavaWorldExporter;
import org.pepsoft.worldpainter.exporting.WorldExportSettings;
import org.pepsoft.worldpainter.plugins.WPPluginManager;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;

/** JIDE-free creation and optional Minecraft export. Every run requires a new destination directory. */
public final class WorldGenerationCli {
    private WorldGenerationCli() { }

    public static void main(String[] args) {
        try {
            if (args.length < 2 || args.length > 3 || (args.length == 3 && !args[2].equals("--minecraft"))) {
                throw new IllegalArgumentException("Usage: WorldGenerationCli PLAN.json NEW_OUTPUT_DIRECTORY [--minecraft]");
            }
            create(Path.of(args[0]), Path.of(args[1]), args.length == 3);
            System.out.println("Created world.world, preview.png and plan.json in " + Path.of(args[1]).toAbsolutePath());
        } catch (Exception e) {
            System.err.println("World generation failed: " + e.getMessage());
            System.exit(1);
        }
    }

    public static Path create(Path input, Path destination, boolean minecraft) throws IOException {
        Path target = destination.toAbsolutePath().normalize();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Output directory already exists");
        // Bound the on-disk document before decoding it, including multi-byte UTF-8.
        final byte[] planBytes;
        try (var stream = Files.newInputStream(input)) { planBytes = stream.readNBytes(262_145); }
        if (planBytes.length > 262_144) throw new IllegalArgumentException("Creation plan file is too large");
        WorldCreationPlan plan = WorldPlanCodec.parse(new String(planBytes, java.nio.charset.StandardCharsets.UTF_8));
        World2 world = WorldGenerator.generate(plan, null);
        if (!Files.isDirectory(target.getParent())) throw new IOException("Output parent directory does not exist");
        Path staging = Files.createTempDirectory(target.getParent(), ".worldpainter-generation-");
        try {
            Files.writeString(staging.resolve("plan.json"), WorldPlanCodec.encode(plan));
            new WorldIO(world).save(Files.newOutputStream(staging.resolve("world.world")));
            if (!ImageIO.write(WorldGenerator.render(world), "png", staging.resolve("preview.png").toFile())) {
                throw new IOException("PNG writer is unavailable");
            }
            if (minecraft) {
                if (Configuration.getInstance() == null) Configuration.setInstance(new Configuration());
                if (WPPluginManager.getInstance() == null) WPPluginManager.initialise(null, WPContext.INSTANCE);
                try {
                    new JavaWorldExporter(world, WorldExportSettings.EXPORT_EVERYTHING).export(staging.toFile(), "minecraft", null, null);
                } catch (org.pepsoft.util.ProgressReceiver.OperationCancelled e) {
                    throw new IOException("Minecraft export cancelled", e);
                }
            }
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            // No replacement option: existing directories/files, including symlinks, are never overwritten.
            Files.move(staging, target);
            return target;
        } finally {
            if (Files.exists(staging)) {
                try (var paths = Files.walk(staging)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        }
    }
}
