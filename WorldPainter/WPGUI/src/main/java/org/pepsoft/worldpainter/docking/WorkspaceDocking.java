package org.pepsoft.worldpainter.docking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.pepsoft.worldpainter.docking.DockingBackend.Position;

/** Application docking boundary. All calls, including layout capture/restore, belong on the EDT. */
public final class WorkspaceDocking {
    public WorkspaceDocking(JFrame window, JPanel container) {
        checkEdt();
        this.window = window;
        docking = new ModernDockingBackend(window, container);
        workspace = new DockablePanel("worldEditor", "World", DockSide.WEST, 0) {
            @Override public boolean isFloatingAllowed() { return false; }
            @Override public boolean isLimitedToWindow() { return true; }
            @Override public boolean isAutoHideAllowed() { return false; }
        };
        window.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) { docking.close(); }
        });
    }

    public JPanel getWorkspace() { return workspace; }

    public DockablePanel getFrame(String key) {
        DockablePanel panel = panels.get(key);
        if (panel == null) throw new IllegalArgumentException("Unknown workspace panel: " + key);
        return panel;
    }

    public void addFrame(DockablePanel panel) {
        checkEdt();
        if (panels.get(panel.getKey()) == panel) return;
        if (panels.containsKey(panel.getKey()) || panel.owner != null || panel.getKey().equals(workspace.getKey())) {
            throw new IllegalArgumentException("Duplicate or already registered workspace panel");
        }
        panels.put(panel.getKey(), panel);
        panel.owner = this;
        docking.registerDockable(panel);
        if (started) dockDefault(panel);
    }

    public void removeFrame(String key) {
        checkEdt();
        DockablePanel panel = panels.remove(key);
        if (panel == null) return;
        docking.deregisterDockable(panel);
        panel.owner = null;
    }

    public void dockFrame(String key, DockSide side, int index) {
        checkEdt();
        DockablePanel panel = getFrame(key);
        panel.side = side;
        panel.index = index;
        if (!docking.isDocked(panel)) dockDefault(panel);
    }

    public void showFrame(String key) {
        checkEdt();
        DockablePanel panel = getFrame(key);
        if (!docking.isDocked(panel)) dockDefault(panel);
        docking.bringToFront(panel);
    }

    public void activateFrame(String key) { showFrame(key); }

    /** Stable panel IDs allow task presets to work even when optional panels are absent. */
    public void applyPreset(WorkspacePreset preset) {
        checkEdt();
        currentPreset = Objects.requireNonNull(preset);
        rebuildDefaults();
        for (String key : preset.getActivePanels()) if (panels.containsKey(key)) showFrame(key);
    }

    public List<String> getPanelKeys() { return List.copyOf(panels.keySet()); }

    public void saveNamedLayout(String name) {
        checkEdt();
        name = checkedName(name);
        if (!namedLayouts.containsKey(name) && namedLayouts.size() >= 16) throw new IllegalArgumentException("At most 16 named layouts can be saved");
        namedLayouts.put(name, getLayoutRawData());
    }

    public boolean loadNamedLayout(String name) {
        checkEdt();
        byte[] data = namedLayouts.get(checkedName(name));
        if (data == null) throw new IllegalArgumentException("Unknown named layout");
        return loadLayoutFrom(new ByteArrayInputStream(data));
    }

    public void removeNamedLayout(String name) { checkEdt(); namedLayouts.remove(checkedName(name)); }

    public Map<String, byte[]> getNamedLayouts() {
        checkEdt();
        Map<String, byte[]> copy = new java.util.TreeMap<>();
        namedLayouts.forEach((name, data) -> copy.put(name, data.clone()));
        return java.util.Collections.unmodifiableMap(copy);
    }

    public void setNamedLayouts(Map<String, byte[]> layouts) {
        checkEdt();
        Map<String, byte[]> checked = new java.util.TreeMap<>();
        if (layouts != null) {
            if (layouts.size() > 16) throw new IllegalArgumentException("At most 16 named layouts can be saved");
            layouts.forEach((name, data) -> {
                if (data == null || data.length > WorkspaceLayoutCodec.MAX_BYTES) throw new IllegalArgumentException("Invalid layout size");
                checked.put(checkedName(name), data.clone());
            });
        }
        namedLayouts.clear();
        namedLayouts.putAll(checked);
    }

    /** Also available through ModernDocking's drag handles and header menu. */
    public void floatFrame(String key) {
        checkEdt();
        DockablePanel panel = getFrame(key);
        java.awt.Dimension preferred = panel.getPreferredSize();
        Rectangle screen = window.getGraphicsConfiguration().getBounds();
        int width = Math.min(Math.max(300, preferred.width), screen.width - 40);
        int height = Math.min(Math.max(400, preferred.height), screen.height - 60);
        floatFrame(key, new Rectangle(Math.max(screen.x, Math.min(window.getX() + 80, screen.x + screen.width - width)),
                Math.max(screen.y, Math.min(window.getY() + 80, screen.y + screen.height - height)), width, height));
    }

    public void floatFrame(String key, Rectangle bounds) {
        checkEdt();
        if (bounds == null || bounds.width < 80 || bounds.height < 80) throw new IllegalArgumentException("Invalid floating panel bounds");
        docking.newWindow(getFrame(key), new Rectangle(bounds));
    }

    public void resetToDefault() {
        checkEdt();
        currentPreset = WorkspacePreset.TERRAIN;
        rebuildDefaults();
    }

    private void registerPanels() {
        docking.deregisterAllDockables();
        docking.registerDockable(workspace);
        for (DockablePanel panel : panels.values()) docking.registerDockable(panel);
        started = true;
    }

    private void rebuildDefaults() {
        registerPanels();
        docking.dock(workspace);
        panels.values().stream().sorted(Comparator.comparing((DockablePanel p) -> p.side).thenComparingInt(this::group))
                .forEach(this::dockDefault);
    }

    public byte[] getLayoutRawData() {
        checkEdt();
        try {
            return WorkspaceLayoutCodec.encode(docking.saveLayout());
        } catch (Exception e) {
            throw new IllegalStateException("Could not save workspace layout", e);
        }
    }

    /** Invalid/legacy layouts fall back to a usable workspace. World data is never involved. */
    public boolean loadLayoutFrom(InputStream in) {
        checkEdt();
        try {
            byte[] xml = WorkspaceLayoutCodec.decode(in);
            Rectangle bounds = window.getBounds();
            int state = window.getExtendedState();
            // Restore into a clean backend so panels absent from the saved tree have no stale parents.
            registerPanels();
            docking.restoreLayout(xml);
            window.setBounds(bounds);
            window.setExtendedState(state);
            if (!docking.isDocked(workspace)) throw new IllegalArgumentException("Layout is missing the world editor");
            // Panels introduced after this layout was saved must remain accessible.
            for (DockablePanel panel : panels.values()) if (!docking.isDocked(panel)) dockDefault(panel);
            return true;
        } catch (Exception e) {
            LOGGER.warn("Could not restore workspace layout; using defaults", e);
            resetToDefault();
            return false;
        }
    }

    void rename(DockablePanel panel, String key) {
        checkEdt();
        if (panels.containsKey(key) || workspace.getKey().equals(key)) throw new IllegalArgumentException("Duplicate workspace panel ID");
        // Re-register the new persistent ID; a renamed palette returns to its default group.
        docking.deregisterDockable(panel);
        panels.remove(panel.getKey());
        panel.key = key;
        panels.put(key, panel);
        docking.registerDockable(panel);
        if (started) dockDefault(panel);
    }

    void updateTitle(DockablePanel panel) {
        checkEdt();
        docking.updateTabInfo(panel);
    }

    private void dockDefault(DockablePanel panel) {
        DockablePanel sameGroup = panels.values().stream().filter(p -> p != panel && p.side == panel.side && group(p) == group(panel)
                && docking.isDocked(p) && SwingUtilities.getWindowAncestor(p) == window).findFirst().orElse(null);
        if (sameGroup != null) {
            docking.dock(panel, sameGroup, Position.CENTER, 0.5);
            return;
        }
        DockablePanel previous = panels.values().stream().filter(p -> p != panel && p.side == panel.side
                && group(p) < group(panel) && docking.isDocked(p) && SwingUtilities.getWindowAncestor(p) == window)
                .max(Comparator.comparingInt(this::group)).orElse(null);
        if (previous != null) docking.dock(panel, previous, Position.SOUTH, 0.5);
        else docking.dock(panel, workspace, panel.side == DockSide.WEST ? Position.WEST : Position.EAST,
                panel.side == DockSide.EAST ? 0.35 : 0.20);
    }

    private int group(DockablePanel panel) {
        return currentPreset == WorkspacePreset.AI && panel.side == DockSide.EAST ? 1 : panel.index;
    }

    private static void checkEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Workspace docking requires the Swing event thread");
    }

    private static String checkedName(String name) {
        if (name == null || name.isBlank() || name.length() > 80 || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Workspace name must contain 1–80 printable characters");
        }
        return name.trim();
    }

    private final JFrame window;
    private final DockingBackend docking;
    private final DockablePanel workspace;
    private final Map<String, DockablePanel> panels = new LinkedHashMap<>();
    private final Map<String, byte[]> namedLayouts = new java.util.TreeMap<>();
    private boolean started;
    private WorkspacePreset currentPreset = WorkspacePreset.TERRAIN;
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceDocking.class);
}
