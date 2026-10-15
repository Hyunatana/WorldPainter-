package org.pepsoft.worldpainter.ai;

import java.io.IOException;
import java.net.URI;
import java.util.List;

/** Native assistant boundary. Credentials never cross into Swing controls or model prompts. */
public interface ChatGptProvider {
    Account account() throws IOException, InterruptedException;
    Account signIn(Browser browser) throws IOException, InterruptedException;
    void cancelSignIn();
    boolean signOut(boolean forgetRegistration) throws IOException, InterruptedException;
    List<Model> listModels() throws IOException, InterruptedException;
    String generate(boolean creation, String model, String context, List<OpenAiTerrainClient.Message> history, String prompt)
            throws IOException, InterruptedException;

    record Account(boolean signedIn, String label, String clientId) { }
    record Model(String slug, String displayName) {
        @Override public String toString() { return displayName + " (" + slug + ")"; }
    }
    @FunctionalInterface interface Browser { void open(URI uri) throws IOException; }
}
