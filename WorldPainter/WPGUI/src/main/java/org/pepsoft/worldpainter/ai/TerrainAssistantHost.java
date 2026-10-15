package org.pepsoft.worldpainter.ai;

import org.pepsoft.worldpainter.ColourScheme;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.World2;
import org.pepsoft.worldpainter.biomeschemes.CustomBiomeManager;
import org.pepsoft.worldpainter.layers.CustomLayer;

import javax.swing.JOptionPane;
import java.awt.Component;
import java.awt.Point;
import java.util.List;
import java.util.prefs.Preferences;

/** The assistant's editor boundary. Keeping JIDE outside this interface permits real headless Swing tests. */
public interface TerrainAssistantHost {
    Dimension getDimension();
    World2 getWorld();
    ColourScheme getColourScheme();
    CustomBiomeManager getCustomBiomeManager();
    List<CustomLayer> getCustomLayers();
    Point getViewCentreInWorldCoords();
    void setTerrainPreview(TerrainPreviewOverlay preview);
    void clearTerrainPreview();
    void refreshTiles();
    boolean openGeneratedWorld(World2 world);

    default Preferences getProviderPreferences() {
        return Preferences.userNodeForPackage(TerrainAssistantHost.class).node("provider");
    }

    default boolean confirm(Component parent, String title, String message) {
        return JOptionPane.showConfirmDialog(parent, message, title, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION;
    }
}
