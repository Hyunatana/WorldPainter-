package org.pepsoft.worldpainter.docking;

import java.util.List;

/** Task-oriented defaults; custom panels remain registered and available in every preset. */
public enum WorkspacePreset {
    TERRAIN("Terrain editing", List.of("tools", "terrain", "brushes", "brushSettings", "infoPanel")),
    BIOMES("Biome painting", List.of("tools", "biomes", "brushes", "brushSettings", "infoPanel")),
    AI("AI planning", List.of("tools", "layers", "brushes", "brushSettings", "terrainAssistant"));

    WorkspacePreset(String title, List<String> activePanels) { this.title = title; this.activePanels = activePanels; }
    public String getTitle() { return title; }
    public List<String> getActivePanels() { return activePanels; }
    private final String title;
    private final List<String> activePanels;
}
