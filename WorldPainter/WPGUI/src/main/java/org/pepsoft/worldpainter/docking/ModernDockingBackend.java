package org.pepsoft.worldpainter.docking;

import io.github.andrewauclair.moderndocking.Dockable;
import io.github.andrewauclair.moderndocking.DockingRegion;
import io.github.andrewauclair.moderndocking.api.DockingAPI;
import io.github.andrewauclair.moderndocking.api.RootDockingPanelAPI;
import io.github.andrewauclair.moderndocking.internal.DockingInternal;
import io.github.andrewauclair.moderndocking.internal.FloatingFrame;
import io.github.andrewauclair.moderndocking.internal.floating.FloatUtilsFrame;
import io.github.andrewauclair.moderndocking.internal.floating.Floating;

import javax.swing.Icon;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Initial backend. Third-party types stay entirely inside this adapter. */
final class ModernDockingBackend implements DockingBackend {
    ModernDockingBackend(JFrame window, JPanel container) {
        this.window = window;
        this.container = container;
        managedWindows.add(window);
        docking = new DockingAPI(window) {
            @Override public void bringToFront(Dockable panel) {
                // Native floating queues focus callbacks; a subsequent reset may invalidate them.
                if (isDockableRegistered(panel.getPersistentID()) && isDocked(panel)) super.bringToFront(panel);
            }
            @Override public void undock(Dockable panel) {
                var wrapper = DockingInternal.get(this).getWrapper(panel);
                // Swing removes the root before WINDOW_CLOSED is delivered. Its remaining wrappers
                // must be released without asking the already removed root to undock them again.
                if (getRootPanels().containsKey(wrapper.getWindow())) super.undock(panel);
                else { wrapper.setParent(null); wrapper.setWindow(null); wrapper.setHidden(false); }
            }
            @Override public void newWindow(Dockable panel) {
                Dimension size = ((JPanel) panel).getPreferredSize();
                newWindow(panel, new Point(window.getX() + 80, window.getY() + 80),
                        new Dimension(Math.max(300, size.width), Math.max(400, size.height)));
            }
            @Override public void newWindow(Dockable panel, Point location, Dimension size) {
                // The dockable-taking native constructor treats location as a drag cursor and
                // queues a resize against a wrapper which a later reset can invalidate.
                FloatingFrame floating = new FloatingFrame(this, location, size, Frame.NORMAL);
                dock(panel, floating);
                bringToFront(panel);
            }
            @Override public void deregisterDockingPanel(Window parent) {
                if (parent != null) managedWindows.add(parent);
                super.deregisterDockingPanel(parent);
                cleanUtilitiesLater();
            }
        };
        // No classes may be instantiated from serialized layout metadata, including removed palettes.
        docking.setUserDynamicDockableCreationListener((id, type, title, tab, properties) -> {
            throw new IllegalArgumentException("Workspace panel no longer exists: " + id);
        });
        root = new RootDockingPanelAPI(docking, window) { };
        container.add(root, BorderLayout.CENTER);
        cleanUtilitiesLater();
    }

    @Override public void registerDockable(DockablePanel panel) {
        PanelAdapter adapter = new PanelAdapter(panel);
        docking.registerDockable(adapter);
        adapters.put(panel, adapter);
    }

    @Override public void deregisterDockable(DockablePanel panel) {
        docking.deregisterDockable(adapters.remove(panel));
    }

    @Override public void deregisterAllDockables() {
        managedWindows.addAll(docking.getRootPanels().keySet());
        docking.deregisterAllDockables();
        // Rebuild the native tree, retaining the API and attached root. Native drag utilities
        // queue work against that root; detaching it would invalidate their pending callbacks.
        docking.deregisterDockingPanel(window);
        root.removeAll();
        docking.registerDockingPanel(root, window);
        adapters.clear();
        container.revalidate();
        container.repaint();
        cleanUtilitiesLater();
    }
    @Override public boolean isDocked(DockablePanel panel) {
        PanelAdapter adapter = adapters.get(panel);
        return adapter != null && (docking.isHidden(adapter)
                || (docking.isDocked(adapter) && SwingUtilities.getWindowAncestor(adapter) != null));
    }
    @Override public void dock(DockablePanel panel) { docking.dock(adapters.get(panel), window); }
    @Override public void dock(DockablePanel panel, DockablePanel relativeTo, Position position, double proportion) {
        docking.dock(adapters.get(panel), adapters.get(relativeTo), DockingRegion.valueOf(position.name()), proportion);
    }
    @Override public void bringToFront(DockablePanel panel) {
        PanelAdapter adapter = adapters.get(panel);
        if (docking.isHidden(adapter)) docking.autoShowDockable(adapter);
        docking.bringToFront(adapter);
        Window owner = SwingUtilities.getWindowAncestor(adapter);
        if (owner != null) owner.toFront();
    }
    @Override public void newWindow(DockablePanel panel, Rectangle bounds) {
        docking.newWindow(adapters.get(panel), bounds.getLocation(), bounds.getSize());
    }
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

    @Override public void close() {
        if (closed) return;
        closed = true;
        managedWindows.addAll(docking.getRootPanels().keySet());
        docking.uninitialize();
        adapters.clear();
        cleanUtilitiesLater();
    }

    private void cleanUtilitiesLater() {
        // Native 1.4.8 creates drag utility windows asynchronously and does not remove their
        // listeners when they are disposed. Remove retired listeners after creation has run.
        SwingUtilities.invokeLater(() -> {
            for (Window managed : Set.copyOf(managedWindows)) {
                boolean active = !closed && docking.getRootPanels().containsKey(managed);
                if (!active) Floating.deregisterDockingWindow(managed);
                FloatUtilsFrame current = active ? Floating.frameForWindow(managed) : null;
                for (var listener : managed.getComponentListeners()) {
                    if (listener instanceof FloatUtilsFrame frame && frame != current) {
                        managed.removeComponentListener(frame);
                        managed.removeWindowListener(frame);
                        frame.dispose();
                    }
                }
                if (!active) managedWindows.remove(managed);
            }
        });
    }

    private static final class PanelAdapter extends JPanel implements Dockable {
        PanelAdapter(DockablePanel panel) {
            super(new BorderLayout());
            this.panel = panel;
            // One large table or hidden tab must not impose its minimum width on the canvas.
            // The framework owns dock geometry; content still receives its preferred size when floating.
            setMinimumSize(new Dimension(100, 80));
            add(panel);
        }
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
    private final JPanel container;
    private final RootDockingPanelAPI root;
    private final DockingAPI docking;
    private final Map<DockablePanel, PanelAdapter> adapters = new IdentityHashMap<>();
    private final Set<Window> managedWindows = new HashSet<>();
    private boolean closed;
}
