package org.pepsoft.worldpainter.ai;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.biomeschemes.CustomBiomeManager;
import org.pepsoft.worldpainter.layers.CustomLayer;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;
import static org.pepsoft.worldpainter.Dimension.Anchor.NORMAL_DETAIL;

/** Exercises the real panel and SwingWorker/EDT interaction, with only the outer editor boundary supplied. */
public class TerrainAssistantPanelTest {
    @Before public void setup() throws Exception {
        if (Configuration.getInstance() == null) Configuration.setInstance(new Configuration());
        Configuration.getInstance().setUndoEnabled(true);
        host = new Host();
        edt(() -> panel = new TerrainAssistantPanel(host));
    }

    @After public void teardown() throws Exception {
        edt(() -> button("cancel").doClick());
        host.preferences.removeNode();
    }

    @Test public void offlineEditPreviewDoesNotWriteUntilConfirmationAndIsUndoable() throws Exception {
        edt(() -> {
            area("prompt").setText("Create a mountain at 64, 64 radius 24 height 30 seed 7");
            button("generate").doClick();
        });
        await(() -> button("preview").isEnabled());
        edt(() -> button("preview").doClick());
        await(() -> button("apply").isEnabled());
        float before = host.dimension().getHeightAt(64, 64);
        assertEquals(80, before, 0);
        assertNotNull(host.overlay);
        host.approve = false;
        edt(() -> button("apply").doClick());
        assertEquals(before, host.dimension().getHeightAt(64, 64), 0);
        host.approve = true;
        edt(() -> button("apply").doClick());
        assertTrue(host.dimension().getHeightAt(64, 64) > before);
        assertTrue(host.undo.undo());
        assertEquals(before, host.dimension().getHeightAt(64, 64), 0);
    }

    @Test public void inputChangesDuringConfirmationCannotApplyTheOldPreview() throws Exception {
        edt(() -> button("generate").doClick());
        await(() -> button("preview").isEnabled());
        edt(() -> button("preview").doClick());
        await(() -> button("apply").isEnabled());
        host.duringConfirmation = () -> area("prompt").setText("Changed request");
        host.approve = true;
        edt(() -> button("apply").doClick());
        assertEquals(80, host.dimension().getHeightAt(0, 0), 0);
        assertNull(host.overlay);
    }

    @Test public void creationWorksWithoutAnOpenWorldAndOnlyOpensAfterApproval() throws Exception {
        host.world = null;
        edt(() -> {
            mode().setSelectedIndex(1);
            button("generate").doClick();
        });
        await(() -> button("preview").isEnabled());
        edt(() -> button("preview").doClick());
        await(() -> button("apply").isEnabled());
        assertNull(host.world);
        host.approve = false;
        edt(() -> button("apply").doClick());
        assertNull(host.world);
        host.approve = true;
        edt(() -> button("apply").doClick());
        assertNotNull(host.world);
        assertEquals(4, host.dimension().getTileCount());
        assertEquals("Emerald Isles", host.world.getName());
        assertEquals(1, host.opened);
    }

    @Test public void cancelledCreationCannotOpenOrRetainALateResult() throws Exception {
        World2 original = host.world;
        edt(() -> {
            mode().setSelectedIndex(1);
            button("generate").doClick();
        });
        await(() -> button("preview").isEnabled());
        edt(() -> { button("preview").doClick(); button("cancel").doClick(); });
        // Flush the SwingWorker done callback after the canceled background work has had time to exit.
        Thread.sleep(200);
        edt(() -> {
            assertFalse(button("apply").isEnabled());
            assertTrue(area("json").getText().isEmpty());
        });
        assertSame(original, host.world);
        assertEquals(0, host.opened);
    }

    @Test public void declinedSaveGuardKeepsTheCurrentWorldAndGeneratedPreview() throws Exception {
        World2 original = host.world;
        edt(() -> { mode().setSelectedIndex(1); button("generate").doClick(); });
        await(() -> button("preview").isEnabled());
        edt(() -> button("preview").doClick());
        await(() -> button("apply").isEnabled());
        host.approve = true;
        host.allowOpen = false;
        edt(() -> button("apply").doClick());
        assertSame(original, host.world);
        edt(() -> assertTrue(button("apply").isEnabled()));
    }

    private JButton button(String id) { return (JButton) find(panel, "assistant." + id); }
    private JTextArea area(String id) { return (JTextArea) find(panel, "assistant." + id); }
    private JComboBox<?> mode() { return (JComboBox<?>) find(panel, "assistant.mode"); }

    private static Component find(Component c, String name) {
        if (name.equals(c.getName())) return c;
        if (c instanceof Container container) for (Component child : container.getComponents()) {
            Component found = find(child, name);
            if (found != null) return found;
        }
        return null;
    }

    private static void edt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            AtomicBoolean ready = new AtomicBoolean();
            edt(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return;
            Thread.sleep(20);
        }
        fail("Assistant operation did not complete");
    }

    private static class Host implements TerrainAssistantHost {
        Host() {
            var factory = TileFactoryFactory.createFlatTileFactory(42, Terrain.GRASS, -64, 320, 80, 62, false, false);
            world = new World2(DefaultPlugin.JAVA_ANVIL_1_19, 42, factory);
            dimension().addTile(factory.createTile(0, 0));
            dimension().registerUndoManager(undo);
            dimension().armSavePoint();
        }
        Dimension dimension() { return world.getDimension(NORMAL_DETAIL); }
        public Dimension getDimension() { return world == null ? null : dimension(); }
        public World2 getWorld() { return world; }
        public ColourScheme getColourScheme() { return ColourScheme.DEFAULT; }
        public CustomBiomeManager getCustomBiomeManager() { return new CustomBiomeManager(); }
        public List<CustomLayer> getCustomLayers() { return List.of(); }
        public Point getViewCentreInWorldCoords() { return new Point(64, 64); }
        public void setTerrainPreview(TerrainPreviewOverlay preview) { overlay = preview; }
        public void clearTerrainPreview() { overlay = null; }
        public void refreshTiles() { }
        public boolean openGeneratedWorld(World2 world) { if (!allowOpen) return false; this.world = world; opened++; return true; }
        public boolean confirm(Component parent, String title, String message) { if (duringConfirmation != null) duringConfirmation.run(); return approve; }
        public java.util.prefs.Preferences getProviderPreferences() { return preferences; }
        final java.util.prefs.Preferences preferences = java.util.prefs.Preferences.userRoot().node("/worldpainter-ai-tests/" + java.util.UUID.randomUUID());
        final UndoManager undo = new UndoManager(16);
        World2 world;
        TerrainPreviewOverlay overlay;
        boolean approve, allowOpen = true;
        int opened;
        Runnable duringConfirmation;
    }
    private Host host;
    private TerrainAssistantPanel panel;
}
