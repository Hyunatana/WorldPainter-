package org.pepsoft.worldpainter.ai;

import java.net.URI;
import java.util.prefs.Preferences;

/** Nonsecret settings only. API keys and prompts have no serialization path here. */
public record AiProviderSettings(int provider, String endpoint, String model) {
    public AiProviderSettings {
        if (provider < 0 || provider > 3 || endpoint == null || endpoint.length() > 2048 || model == null
                || model.length() > 256 || model.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid provider settings");
        }
        // Even in offline mode, never persist credentials or query parameters embedded in a URL.
        OpenAiTerrainClient.endpoint(URI.create(endpoint));
    }

    public static AiProviderSettings load(Preferences preferences) {
        try {
            return new AiProviderSettings(preferences.getInt("provider", 0),
                    preferences.get("endpoint", "https://api.openai.com/v1"), preferences.get("model", ""));
        } catch (IllegalArgumentException | SecurityException e) {
            return new AiProviderSettings(0, "https://api.openai.com/v1", "");
        }
    }

    public void save(Preferences preferences) {
        preferences.putInt("provider", provider);
        preferences.put("endpoint", endpoint);
        preferences.put("model", model);
    }
}
