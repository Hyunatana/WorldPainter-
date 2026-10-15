package org.pepsoft.worldpainter.ai;

import org.pepsoft.worldpainter.ColourScheme;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.World2;
import org.pepsoft.worldpainter.biomeschemes.CustomBiomeManager;
import org.pepsoft.worldpainter.layers.CustomLayer;

import javax.swing.JOptionPane;
import java.awt.Component;
import java.awt.Point;
import java.net.URI;
import java.io.IOException;
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

    default ChatGptProvider createChatGptProvider() {
        return new ChatGptClient(org.pepsoft.worldpainter.Configuration.getConfigDir().toPath().resolve("ai-chatgpt"));
    }

    default void openBrowser(URI uri) throws IOException {
        if (!java.awt.Desktop.isDesktopSupported() || !java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
            throw new IOException("A system browser is required to sign in with ChatGPT");
        }
        java.awt.Desktop.getDesktop().browse(uri);
    }

    default void showInformation(Component parent, String title, String message) {
        JOptionPane.showMessageDialog(parent, message, title, JOptionPane.INFORMATION_MESSAGE);
    }

    default Object chooseModel(Component parent, Object[] choices, Object selected) {
        return JOptionPane.showInputDialog(parent, "Choose an available model", "AI models",
                JOptionPane.PLAIN_MESSAGE, null, choices, selected);
    }

    default boolean confirm(Component parent, String title, String message) {
        return JOptionPane.showConfirmDialog(parent, message, title, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION;
    }
}
