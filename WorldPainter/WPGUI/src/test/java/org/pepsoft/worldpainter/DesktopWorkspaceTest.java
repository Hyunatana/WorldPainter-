package org.pepsoft.worldpainter;

import com.jidesoft.plaf.LookAndFeelFactory;
import org.junit.After;
import org.junit.Test;
import org.pepsoft.worldpainter.ai.WorldCreationPlan;
import org.pepsoft.worldpainter.ai.WorldGenerator;
import org.pepsoft.worldpainter.docking.WorkspacePreset;
import org.pepsoft.worldpainter.plugins.WPPluginManager;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JFrame;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;

/** Boots the real editor with a deterministic world; optional screenshots are CI artifacts. */
public class DesktopWorkspaceTest {
    @Test(timeout = 120_000) public void editorOpensWorldAndPreviewsAnAiPlanAcrossDockingLayouts() throws Exception {
        assumeFalse("Desktop smoke test requires a display", GraphicsEnvironment.isHeadless());
        edtFailures = new EdtFailureRecorder();
        Configuration configuration = new Configuration();
        configuration.setSafeMode(true);
        configuration.setAutosaveEnabled(false);
        configuration.setPingAllowed(false);
        configuration.setShowCalloutCount(0);
        Configuration.setInstance(configuration);
        System.setProperty("org.pepsoft.worldpainter.safeMode", "true");
        System.setProperty("org.pepsoft.worldpainter.size", "1400x1000");
        if (WPPluginManager.getInstance() == null) WPPluginManager.initialise(null, WPContext.INSTANCE);
        World2 world = WorldGenerator.generate(new WorldCreationPlan("WorldPainter+ Workspace Preview", 42, 512, 512,
                WorldCreationPlan.Landscape.ISLAND, 80, 62, 90, 255, List.of()), null);
        SwingUtilities.invokeAndWait(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
                LookAndFeelFactory.installJideExtension();
            } catch (Exception e) { throw new IllegalStateException(e); }
            app = App.getInstance();
            app.setWorld(world, false);
            app.setLocation(20, 20);
            app.setVisible(true);
            app.getDockingManager().applyPreset(WorkspacePreset.TERRAIN);
            assertTrue(app.getDockingManager().getWorkspace().isShowing());
            assertEquals(16, app.getDimension().getTileCount());
        });
        capture("01-terrain-workspace.png");
        SwingUtilities.invokeAndWait(() -> {
            app.getDockingManager().applyPreset(WorkspacePreset.AI);
            Container panel = app.getDockingManager().getFrame("terrainAssistant");
            ((JTextArea) find(panel, "assistant.prompt")).setText("Create a mountain at 0, 0 radius 64 height 40 seed 42");
            ((AbstractButton) find(panel, "assistant.generate")).doClick();
        });
        awaitEnabled("assistant.preview");
        SwingUtilities.invokeAndWait(() -> ((AbstractButton) find(app.getDockingManager().getFrame("terrainAssistant"), "assistant.preview")).doClick());
        awaitEnabled("assistant.apply");
        capture("02-ai-workspace-preview.png");
        SwingUtilities.invokeAndWait(() -> {
            assertTrue("AI preset must leave room for chat controls", app.getDockingManager().getFrame("terrainAssistant").getHeight() > 600);
            int width = app.getDockingManager().getWorkspace().getWidth();
            assertTrue("The world canvas must remain usable (width " + width + ")", width > 500);
        });
        SwingUtilities.invokeAndWait(() -> {
            app.getDockingManager().saveNamedLayout("AI review");
            app.getDockingManager().floatFrame("terrainAssistant", new Rectangle(1435, 100, 460, 950));
            Window floating = SwingUtilities.getWindowAncestor(app.getDockingManager().getFrame("terrainAssistant"));
            assertNotSame(app, floating);
            assertTrue(app.getDockingManager().loadNamedLayout("AI review"));
            assertSame(app, SwingUtilities.getWindowAncestor(app.getDockingManager().getFrame("terrainAssistant")));
            app.getDockingManager().floatFrame("terrainAssistant", new Rectangle(1435, 100, 460, 950));
        });
        capture("03-floating-assistant.png");
    }

    @After public void closeEditor() throws Exception {
        try {
            if (app != null) SwingUtilities.invokeAndWait(app::dispose);
        } finally {
            System.clearProperty("org.pepsoft.worldpainter.safeMode");
            System.clearProperty("org.pepsoft.worldpainter.size");
            if (edtFailures != null) edtFailures.close();
        }
    }

    private void awaitEnabled(String name) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        AtomicBoolean enabled = new AtomicBoolean();
        while (System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> enabled.set(find(app.getDockingManager().getFrame("terrainAssistant"), name).isEnabled()));
            if (enabled.get()) return;
            Thread.sleep(30);
        }
        fail("Assistant did not enable " + name);
    }

    private void capture(String name) throws Exception {
        if (!Boolean.getBoolean("worldpainter.captureScreenshots")) return;
        Robot robot = new Robot();
        robot.waitForIdle();
        Thread.sleep(1200); // Allow background terrain tiles to finish painting before capturing.
        robot.waitForIdle();
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        Path directory = Path.of("target/workspace-screenshots");
        Files.createDirectories(directory);
        assertTrue(ImageIO.write(robot.createScreenCapture(screen), "png", directory.resolve(name).toFile()));
    }

    private static Component find(Container parent, String name) {
        for (Component component : parent.getComponents()) {
            if (name.equals(component.getName())) return component;
            if (component instanceof Container child) {
                Component found = find(child, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private App app;
    private EdtFailureRecorder edtFailures;
}
