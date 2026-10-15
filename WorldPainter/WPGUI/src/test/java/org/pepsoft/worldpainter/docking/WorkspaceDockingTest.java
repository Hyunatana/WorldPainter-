package org.pepsoft.worldpainter.docking;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.pepsoft.worldpainter.Configuration;
import org.pepsoft.worldpainter.EdtFailureRecorder;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;

/** Run under xvfb-run on CI; exercises real windows and the production docking backend. */
public class WorkspaceDockingTest {
    @Before public void setUp() throws Exception {
        assumeFalse("Docking tests require a display (use xvfb-run)", GraphicsEnvironment.isHeadless());
        edtFailures = new EdtFailureRecorder();
        SwingUtilities.invokeAndWait(() -> {
            window = new JFrame("Docking test");
            window.setSize(1200, 800);
            JPanel container = new JPanel(new BorderLayout());
            window.add(container);
            docking = new WorkspaceDocking(window, container);
            docking.getWorkspace().add(new JPanel(), BorderLayout.CENTER);
            docking.addFrame(panel("tools", DockSide.WEST, 1));
            docking.addFrame(panel("layers", DockSide.WEST, 2));
            docking.addFrame(panel("biomes", DockSide.WEST, 2));
            docking.addFrame(panel("assistant", DockSide.EAST, 1));
            docking.resetToDefault();
            window.setVisible(true);
        });
        flushEdt();
    }

    @After public void tearDown() throws Exception {
        try {
            if (window != null) { SwingUtilities.invokeAndWait(window::dispose); flushEdt(); }
        } finally {
            if (edtFailures != null) edtFailures.close();
        }
    }

    @Test public void defaultsKeepCanvasVisibleAndGroupPanelsInTabs() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(docking.getWorkspace().isShowing());
            assertSame(window, SwingUtilities.getWindowAncestor(docking.getFrame("tools")));
            assertSame(window, SwingUtilities.getWindowAncestor(docking.getFrame("assistant")));
            assertEquals(2, largestTabCount(window));
            docking.showFrame("biomes");
            assertTrue(docking.getFrame("biomes").isShowing());
            assertFalse(docking.getFrame("biomes").isClosable());
        });
    }

    @Test public void floatingLayoutRoundTripsAndResetClosesFloatingWindow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            docking.floatFrame("assistant");
            Window floating = SwingUtilities.getWindowAncestor(docking.getFrame("assistant"));
            assertNotSame(window, floating);
            assertTrue(floating.isDisplayable());
            byte[] saved = docking.getLayoutRawData();
            docking.resetToDefault();
            assertFalse(floating.isDisplayable());
            assertSame(window, SwingUtilities.getWindowAncestor(docking.getFrame("assistant")));
            assertTrue(docking.loadLayoutFrom(new ByteArrayInputStream(saved)));
            assertNotSame(window, SwingUtilities.getWindowAncestor(docking.getFrame("assistant")));
            docking.showFrame("assistant");
            assertTrue(docking.getFrame("assistant").isShowing());
            docking.resetToDefault();
            assertSame(window, SwingUtilities.getWindowAncestor(docking.getFrame("assistant")));
        });
        flushEdt();
    }

    @Test public void dynamicPalettesRenameAndRemoveWithoutDuplicateRegistrations() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DockablePanel palette = panel("palette.old", DockSide.WEST, 2);
            docking.addFrame(palette);
            docking.addFrame(palette);
            palette.setTitle("Renamed palette");
            palette.setKey("palette.new");
            assertThrows(IllegalArgumentException.class, () -> docking.getFrame("palette.old"));
            assertSame(palette, docking.getFrame("palette.new"));
            assertTrue(palette.isShowing());
            assertThrows(IllegalArgumentException.class, () -> docking.addFrame(panel("palette.new", DockSide.WEST, 2)));
            docking.removeFrame("palette.new");
            assertNull(SwingUtilities.getWindowAncestor(palette));
            assertThrows(IllegalArgumentException.class, () -> docking.getFrame("palette.new"));
        });
    }

    @Test public void legacyMalformedAndEntityLayoutsFallBackToWorkingDefaults() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (String data : new String[] { "old JIDE data", "WorldPainter-ModernDocking:1\n<invalid>",
                    "WorldPainter-ModernDocking:1\n<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///nonexistent'>]><app-layout>&e;</app-layout>" }) {
                assertFalse(docking.loadLayoutFrom(new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8))));
                assertTrue(docking.getWorkspace().isShowing());
                assertSame(window, SwingUtilities.getWindowAncestor(docking.getFrame("assistant")));
            }
        });
    }

    @Test public void savedLayoutsDoNotInstantiateMissingPanelClassesAndNewPanelsStayAvailable() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String saved = new String(docking.getLayoutRawData(), StandardCharsets.UTF_8);
            docking.addFrame(panel("newPanel", DockSide.EAST, 2));
            assertTrue(docking.loadLayoutFrom(new ByteArrayInputStream(saved.getBytes(StandardCharsets.UTF_8))));
            assertSame(window, SwingUtilities.getWindowAncestor(docking.getFrame("newPanel")));
            docking.removeFrame("assistant");
            // Removed panels must not be reconstructed from serialized class names.
            String unknown = saved.replace(DockablePanel.class.getName(), "java.lang.ProcessBuilder");
            assertFalse(docking.loadLayoutFrom(new ByteArrayInputStream(unknown.getBytes(StandardCharsets.UTF_8))));
            assertTrue(docking.getWorkspace().isShowing());
        });
    }

    @Test public void newConfigurationLayoutsRemainSeparateFromLegacyData() {
        Configuration configuration = new Configuration();
        configuration.setDefaultJideLayoutData(new byte[] {1});
        configuration.setJideLayoutData(Map.of("old-world", new byte[] {2}));
        assertNull(configuration.getDefaultDockLayoutData());
        assertNull(configuration.getDockLayoutData());
        configuration.setDefaultDockLayoutData(new byte[] {3});
        configuration.setDockLayoutData(Map.of("new-world", new byte[] {4}));
        assertArrayEquals(new byte[] {1}, configuration.getDefaultJideLayoutData());
        assertArrayEquals(new byte[] {4}, configuration.getDockLayoutData().get("new-world"));
    }

    @Test public void namedLayoutsAreIndependentSnapshotsAndPresetsKeepAllPanels() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            docking.saveNamedLayout("Desk");
            docking.floatFrame("assistant");
            docking.saveNamedLayout("Two windows");
            assertTrue(docking.loadNamedLayout("Desk"));
            assertSame(window, SwingUtilities.getWindowAncestor(docking.getFrame("assistant")));
            assertTrue(docking.loadNamedLayout("Two windows"));
            assertNotSame(window, SwingUtilities.getWindowAncestor(docking.getFrame("assistant")));
            Map<String, byte[]> saved = docking.getNamedLayouts();
            saved.get("Desk")[0] = 0;
            assertTrue(docking.loadNamedLayout("Desk"));
            int count = docking.getPanelKeys().size();
            for (WorkspacePreset preset : WorkspacePreset.values()) {
                docking.applyPreset(preset);
                assertEquals(count, docking.getPanelKeys().size());
                assertTrue(docking.getWorkspace().isShowing());
            }
            assertThrows(IllegalArgumentException.class, () -> docking.saveNamedLayout("\n"));
            docking.removeNamedLayout("Desk");
            assertFalse(docking.getNamedLayouts().containsKey("Desk"));
        });
        flushEdt();
    }

    private static DockablePanel panel(String key, DockSide side, int index) {
        DockablePanel panel = new DockablePanel(key, key, side, index);
        panel.add(new JPanel());
        return panel;
    }

    private static int largestTabCount(Container container) {
        int result = container instanceof JTabbedPane tabs ? tabs.getTabCount() : 0;
        for (Component c : container.getComponents()) if (c instanceof Container child) result = Math.max(result, largestTabCount(child));
        return result;
    }

    private static void flushEdt() throws Exception { SwingUtilities.invokeAndWait(() -> { }); }
    private JFrame window;
    private WorkspaceDocking docking;
    private EdtFailureRecorder edtFailures;
}
