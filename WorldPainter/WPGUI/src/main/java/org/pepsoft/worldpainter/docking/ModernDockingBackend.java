package org.pepsoft.worldpainter.docking;

import io.github.andrewauclair.moderndocking.Dockable;
import io.github.andrewauclair.moderndocking.DockingRegion;
import io.github.andrewauclair.moderndocking.api.DockingAPI;
import io.github.andrewauclair.moderndocking.api.RootDockingPanelAPI;

import javax.swing.Icon;
import javax.swing.JFrame;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.IdentityHashMap;
import java.util.Map;

/** Initial backend. Third-party types stay entirely inside this adapter. */
final class ModernDockingBackend implements DockingBackend {
    ModernDockingBackend(JFrame window, JPanel container) {
        this.window = window;
        docking = new DockingAPI(window) { };
        // No classes may be instantiated from serialized layout metadata, including removed palettes.
        docking.setUserDynamicDockableCreationListener((id, type, title, tab, properties) -> {
            throw new IllegalArgumentException("Workspace panel no longer exists: " + id);
        });
        container.add(new RootDockingPanelAPI(docking, window) { }, BorderLayout.CENTER);
    }

    @Override public void registerDockable(DockablePanel panel) {
        PanelAdapter adapter = new PanelAdapter(panel);
        docking.registerDockable(adapter);
        adapters.put(panel, adapter);
    }

    @Override public void deregisterDockable(DockablePanel panel) {
        docking.deregisterDockable(adapters.remove(panel));
    }

    @Override public void deregisterAllDockables() { docking.deregisterAllDockables(); adapters.clear(); }
    @Override public boolean isDocked(DockablePanel panel) { return adapters.containsKey(panel) && docking.isDocked(adapters.get(panel)); }
    @Override public void dock(DockablePanel panel) { docking.dock(adapters.get(panel), window); }
    @Override public void dock(DockablePanel panel, DockablePanel relativeTo, Position position, double proportion) {
        docking.dock(adapters.get(panel), adapters.get(relativeTo), DockingRegion.valueOf(position.name()), proportion);
    }
    @Override public void bringToFront(DockablePanel panel) { docking.bringToFront(adapters.get(panel)); }
    @Override public void newWindow(DockablePanel panel) { docking.newWindow(adapters.get(panel)); }
    @Override public void updateTabInfo(DockablePanel panel) { docking.updateTabInfo(adapters.get(panel)); }

    @Override public byte[] saveLayout() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        docking.getLayoutPersistence().saveLayoutToOutputStream(out, docking.getDockingState().getApplicationLayout());
        return out.toByteArray();
    }

    @Override public void restoreLayout(byte[] xml) throws Exception {
        var layout = docking.getLayoutPersistence().loadApplicationLayoutFromInputStream(new ByteArrayInputStream(xml));
        if (layout.getMainFrameLayout() == null) throw new IllegalArgumentException("Missing main workspace");
        docking.getDockingState().restoreApplicationLayout(layout);
    }

    @Override public void close() { docking.uninitialize(); adapters.clear(); }

    private static final class PanelAdapter extends JPanel implements Dockable {
        PanelAdapter(DockablePanel panel) { super(new BorderLayout()); this.panel = panel; add(panel); }
        @Override public String getPersistentID() { return panel.getKey(); }
        @Override public String getTabText() { return panel.getTitle(); }
        @Override public Icon getIcon() { return panel.getIcon(); }
        @Override public String getTabTooltip() { return panel.getToolTipText(); }
        @Override public boolean isClosable() { return panel.isClosable(); }
        @Override public boolean isAutoHideAllowed() { return panel.isAutoHideAllowed(); }
        @Override public boolean isFloatingAllowed() { return panel.isFloatingAllowed(); }
        @Override public boolean isLimitedToWindow() { return panel.isLimitedToWindow(); }
        @Override public boolean isWrappableInScrollpane() { return false; }
        private final DockablePanel panel;
    }

    private final JFrame window;
    private final DockingAPI docking;
    private final Map<DockablePanel, PanelAdapter> adapters = new IdentityHashMap<>();
}
