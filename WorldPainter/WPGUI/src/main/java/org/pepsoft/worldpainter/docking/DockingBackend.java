package org.pepsoft.worldpainter.docking;

/** Private implementation boundary; editor code only depends on WorldPainter panel/workspace APIs. */
interface DockingBackend extends AutoCloseable {
    enum Position { CENTER, WEST, EAST, SOUTH }
    void registerDockable(DockablePanel panel);
    void deregisterDockable(DockablePanel panel);
    void deregisterAllDockables();
    boolean isDocked(DockablePanel panel);
    void dock(DockablePanel panel);
    void dock(DockablePanel panel, DockablePanel relativeTo, Position position, double proportion);
    void bringToFront(DockablePanel panel);
    void newWindow(DockablePanel panel);
    void updateTabInfo(DockablePanel panel);
    byte[] saveLayout() throws Exception;
    void restoreLayout(byte[] xml) throws Exception;
    @Override void close();
}
