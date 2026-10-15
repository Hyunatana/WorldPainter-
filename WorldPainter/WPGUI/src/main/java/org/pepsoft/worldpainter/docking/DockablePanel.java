package org.pepsoft.worldpainter.docking;

import javax.swing.Icon;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.util.Objects;

/** Application-owned Swing tool panel, independent of the docking backend. */
public class DockablePanel extends JPanel {
    public DockablePanel(String key, String title, DockSide side, int index) {
        super(new BorderLayout());
        this.key = Objects.requireNonNull(key);
        this.title = Objects.requireNonNull(title);
        this.side = Objects.requireNonNull(side);
        this.index = index;
    }

    public String getKey() { return key; }
    public String getPersistentID() { return key; }
    public String getTitle() { return title; }
    public Icon getIcon() { return icon; }
    public String getTabTooltip() { return getToolTipText(); }
    public boolean isClosable() { return false; }
    public boolean isAutoHideAllowed() { return true; }
    public boolean isFloatingAllowed() { return true; }
    public boolean isLimitedToWindow() { return false; }

    public void setTitle(String title) {
        this.title = Objects.requireNonNull(title);
        if (owner != null) owner.updateTitle(this);
    }

    public void setKey(String key) {
        Objects.requireNonNull(key);
        if (this.key.equals(key)) return;
        if (owner == null) this.key = key;
        else owner.rename(this, key);
    }

    public void setFrameIcon(Icon icon) {
        this.icon = icon;
        if (owner != null) owner.updateTitle(this);
    }

    DockSide side;
    int index;
    WorkspaceDocking owner;
    String key;
    private String title;
    private Icon icon;
}
