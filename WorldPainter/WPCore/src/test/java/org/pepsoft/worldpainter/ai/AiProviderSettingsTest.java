package org.pepsoft.worldpainter.ai;

import org.junit.Test;

import java.util.Set;
import java.util.prefs.Preferences;

import static org.junit.Assert.*;

public class AiProviderSettingsTest {
    @Test public void preferencesRoundTripOnlyNonsecretFields() throws Exception {
        Preferences node = Preferences.userRoot().node("/worldpainter-ai-settings-tests/" + java.util.UUID.randomUUID());
        try {
            AiProviderSettings settings = new AiProviderSettings(2, "http://localhost:11434/v1", "installed-model");
            settings.save(node);
            assertEquals(settings, AiProviderSettings.load(node));
            assertEquals(Set.of("provider", "endpoint", "model"), Set.of(node.keys()));
            node.put("endpoint", "https://user:secret@example.com/v1");
            assertEquals(0, AiProviderSettings.load(node).provider());
            assertFalse(AiProviderSettings.load(node).endpoint().contains("secret"));
        } finally { node.removeNode(); }
    }

    @Test public void credentialsCannotBePersistedInsideEndpointSettings() {
        for (String endpoint : new String[]{"https://host/v1?api_key=secret", "https://user:secret@host/v1", "file:///tmp/x"}) {
            assertThrows(IllegalArgumentException.class, () -> new AiProviderSettings(1, endpoint, "model"));
        }
    }
}
