package org.pepsoft.worldpainter.ai;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Official Sign in with ChatGPT for OSS apps. No borrowed client IDs or private ChatGPT endpoints. */
public final class ChatGptClient implements ChatGptProvider {
    public ChatGptClient(Path directory) { this(directory, Routes.PRODUCTION, Clock.systemUTC(), Duration.ofMinutes(5)); }

    ChatGptClient(Path directory, Routes routes, Clock clock, Duration loginDeadline) {
        store = new ChatGptSessionStore(directory);
        this.routes = routes;
        this.clock = clock;
        this.loginDeadline = loginDeadline;
        responses = new ChatGptResponsesClient(routes.apiBase, this::accessToken);
    }

    @Override public Account account() throws IOException, InterruptedException {
        return store.locked(() -> account(store.load()));
    }

    @Override public Account signIn(Browser browser) throws IOException, InterruptedException {
        Map<?, ?> saved = store.locked(() -> { store.hostId(); return store.load(); });
        String originalGeneration = saved.get("generation") instanceof String value ? value : "";
        String savedClient = optional(saved, "client_id");
        String expectedSubject = optional(saved, "subject");
        String host = store.locked(store::hostId);
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("ChatGPT sign-in canceled");
        try (LoginAttempt attempt = new LoginAttempt(savedClient, host, optional(saved, "email"), optional(saved, "id_token"))) {
            synchronized (loginLock) {
                if (activeLogin != null) throw new IOException("A ChatGPT sign-in is already in progress");
                activeLogin = attempt;
            }
            try {
                if (attempt.canceled || Thread.currentThread().isInterrupted()) throw new InterruptedException("ChatGPT sign-in canceled");
                try { browser.open(attempt.authorization); }
                catch (IOException | RuntimeException e) { throw new IOException("Could not open the system browser for ChatGPT sign-in"); }
                Map<String, String> callback = attempt.await();
                String clientId = savedClient.isEmpty() ? callback.get("client_id") : savedClient;
                if (!validClient(clientId) || (!savedClient.isEmpty() && callback.containsKey("client_id")
                        && !savedClient.equals(callback.get("client_id")))) throw new IOException("ChatGPT registration did not return the expected client ID");
                Map<?, ?> token;
                try {
                    token = postForm(routes.token, Map.of("grant_type", "authorization_code", "client_id", clientId,
                            "code", callback.get("code"), "code_verifier", attempt.verifier, "redirect_uri", attempt.redirect.toString(), "resource", RESOURCE));
                } catch (InvalidGrant e) {
                    if (savedClient.isEmpty()) store.locked(() -> {
                        synchronized (loginLock) {
                            if (!attempt.canceled && originalGeneration.equals(optional(store.load(), "generation"))) {
                                store.save(Map.of("version", 1, "client_id", clientId, "generation", UUID.randomUUID().toString()));
                            }
                        }
                        return null;
                    });
                    throw new IOException("ChatGPT sign-in code expired or was already used; start a fresh sign-in");
                }
                Map<String, Object> state = credentials(token, clientId, attempt.nonce,
                        expectedSubject.isEmpty() ? null : expectedSubject, null);
                return store.locked(() -> {
                    synchronized (loginLock) {
                        if (attempt.canceled || !originalGeneration.equals(optional(store.load(), "generation"))) {
                            throw new IOException("ChatGPT sign-in was canceled or replaced");
                        }
                        state.put("generation", UUID.randomUUID().toString());
                        store.save(state);
                        return account(state);
                    }
                });
            } finally {
                synchronized (loginLock) { if (activeLogin == attempt) activeLogin = null; }
            }
        }
    }

    @Override public void cancelSignIn() {
        synchronized (loginLock) { if (activeLogin != null) activeLogin.close(); }
    }

    @Override public boolean signOut(boolean forgetRegistration) throws IOException, InterruptedException {
        cancelSignIn();
        return store.locked(() -> {
            Map<?, ?> state;
            try { state = store.load(); }
            catch (IOException e) { store.forget(); return false; }
            boolean confirmed = optional(state, "refresh_token").isEmpty();
            try {
                if (!confirmed) {
                    Map<?, ?> discovery = getJson(routes.discovery);
                    URI endpoint = URI.create(ChatGptIdentity.text(discovery, "revocation_endpoint", 2048));
                    if (!sameOrigin(endpoint, routes.token) || !routes.issuer.equals(discovery.get("issuer"))) {
                        throw new IOException("ChatGPT revocation endpoint could not be verified");
                    }
                    for (int retry = 0; retry < 2; retry++) {
                        try {
                            AiHttp.Reply result = transport.exchange(formRequest(endpoint, Map.of("client_id", optional(state, "client_id"),
                                    "token", optional(state, "refresh_token"), "token_type_hint", "refresh_token")));
                            confirmed = result.status() == 200;
                            if (result.status() < 500) break;
                        } catch (IOException e) { if (retry == 1) throw e; }
                        if (!confirmed && retry == 0) Thread.sleep(250);
                    }
                }
                return confirmed;
            } catch (IOException | IllegalArgumentException e) { return false; }
            finally {
                Map<String, Object> cleared = new LinkedHashMap<>();
                cleared.put("version", 1);
                cleared.put("generation", UUID.randomUUID().toString());
                if (!forgetRegistration) for (String key : List.of("client_id", "subject", "email")) {
                    String value = optional(state, key);
                    if (!value.isEmpty()) cleared.put(key, value);
                }
                store.save(cleared);
            }
        });
    }

    @Override public List<Model> listModels() throws IOException, InterruptedException { return responses.listModels(); }
    @Override public String generate(boolean creation, String model, String context, List<OpenAiTerrainClient.Message> history, String prompt)
            throws IOException, InterruptedException { return responses.generate(creation, model, context, history, prompt); }

    String accessToken() throws IOException, InterruptedException {
        return store.locked(() -> {
            Map<?, ?> state = store.load();
            if (!account(state).signedIn()) throw new IOException("Continue with ChatGPT before requesting a plan");
            Object expiry = state.get("expires_at");
            if (!(expiry instanceof Long seconds)) throw new IOException("ChatGPT sign-in has invalid expiry information");
            if (seconds > clock.instant().getEpochSecond() + 30) return bearer(state, "access_token");
            Map<?, ?> token;
            try {
                token = postForm(routes.token, Map.of("grant_type", "refresh_token", "client_id", optional(state, "client_id"),
                        "refresh_token", bearer(state, "refresh_token"), "resource", RESOURCE));
            } catch (InvalidGrant e) {
                Map<String, Object> cleared = new LinkedHashMap<>();
                cleared.put("version", 1);
                cleared.put("generation", UUID.randomUUID().toString());
                for (String key : List.of("client_id", "subject", "email")) cleared.put(key, optional(state, key));
                store.save(cleared);
                throw new IOException("ChatGPT sign-in expired or was revoked; sign in again");
            }
            Map<String, Object> updated = credentials(token, optional(state, "client_id"), null, optional(state, "subject"), state);
            updated.put("generation", optional(state, "generation"));
            store.save(updated);
            return bearer(updated, "access_token");
        });
    }

    private Map<String, Object> credentials(Map<?, ?> token, String clientId, String nonce, String expectedSubject, Map<?, ?> previous)
            throws IOException, InterruptedException {
        try {
            String scope = token.get("scope") instanceof String value ? value : previous == null ? "" : optional(previous, "scope");
            if (!Set.of(scope.split(" +")).containsAll(Set.of("openid", "resource.invoke", "chatgpt.tokens.use.direct"))) {
                throw new IOException("ChatGPT plan use was not authorized. Enable it in ChatGPT settings and sign in again.");
            }
            if (!"Bearer".equalsIgnoreCase(ChatGptIdentity.text(token, "token_type", 32))
                    || !(token.get("expires_in") instanceof Long expiry) || expiry < 1 || expiry > 86_400) throw new IllegalArgumentException();
            String idToken = token.get("id_token") instanceof String value ? value : previous == null ? "" : optional(previous, "id_token");
            ChatGptIdentity.Identity identity;
            if (previous != null && !token.containsKey("id_token")) {
                identity = new ChatGptIdentity.Identity(expectedSubject, optional(previous, "email"));
            } else {
                identity = ChatGptIdentity.verify(idToken, getJson(routes.jwks), routes.issuer, clientId, nonce, expectedSubject, clock.instant());
            }
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("version", 1);
            state.put("client_id", clientId);
            state.put("subject", identity.subject());
            state.put("email", identity.label());
            state.put("scope", scope);
            state.put("id_token", idToken);
            state.put("access_token", bearer(token, "access_token"));
            state.put("refresh_token", bearer(token, "refresh_token"));
            state.put("expires_at", clock.instant().getEpochSecond() + expiry);
            return state;
        } catch (IllegalArgumentException e) { throw new IOException("ChatGPT returned invalid sign-in credentials"); }
    }

    private Map<?, ?> postForm(URI endpoint, Map<String, String> fields) throws IOException, InterruptedException {
        AiHttp.Reply reply = transport.exchange(formRequest(endpoint, fields));
        if (reply.status() != 200) {
            try {
                if ("invalid_grant".equals(json(reply.body()).get("error"))) throw new InvalidGrant();
            } catch (InvalidGrant e) { throw e; } catch (IOException ignored) { }
            throw new IOException("ChatGPT authorization returned HTTP " + reply.status());
        }
        return json(reply.body());
    }
    private Map<?, ?> getJson(URI endpoint) throws IOException, InterruptedException {
        AiHttp.Reply reply = transport.exchange(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30)).GET().build());
        if (reply.status() != 200) throw new IOException("ChatGPT authentication service returned HTTP " + reply.status());
        return json(reply.body());
    }
    private static HttpRequest formRequest(URI uri, Map<String, String> fields) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form(fields))).build();
    }
    private static Map<?, ?> json(byte[] bytes) throws IOException {
        try {
            Object value = JsonProtocol.read(new String(bytes, StandardCharsets.UTF_8));
            if (value instanceof Map<?, ?> map) return map;
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException ignored) { }
        throw new IOException("ChatGPT authentication returned an invalid response");
    }
    private static Account account(Map<?, ?> state) throws IOException {
        boolean connected = !optional(state, "access_token").isEmpty();
        String client = optional(state, "client_id");
        if (connected && (!validClient(client) || optional(state, "subject").isEmpty())) throw new IOException("Stored ChatGPT identity is invalid");
        return new Account(connected, optional(state, "email"), client);
    }
    private static String bearer(Map<?, ?> state, String name) {
        String value = ChatGptIdentity.text(state, name, 32_768);
        if (value.chars().anyMatch(c -> c < 33 || c > 126)) throw new IllegalArgumentException();
        return value;
    }
    private static String optional(Map<?, ?> values, String key) { return values.get(key) instanceof String value ? value : ""; }
    private static boolean validClient(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{8,256}") && !value.equals("dynamic_agent_client");
    }
    static String form(Map<String, String> fields) {
        return fields.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
    }
    static Map<String, String> query(String query) {
        if (query == null || query.length() > 16_384) throw new IllegalArgumentException();
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length != 2 || values.putIfAbsent(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts[1], StandardCharsets.UTF_8)) != null) throw new IllegalArgumentException();
        }
        return values;
    }
    private static boolean sameOrigin(URI a, URI b) {
        return !a.isOpaque() && a.getRawUserInfo() == null && a.getRawQuery() == null && a.getRawFragment() == null
                && b.getScheme().equals(a.getScheme()) && b.getHost().equals(a.getHost()) && a.getPort() == b.getPort();
    }

    private final class LoginAttempt implements AutoCloseable {
        LoginAttempt(String clientId, String host, String email, String hint) throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8);
            redirect = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/auth/callback");
            Map<String, String> values = new LinkedHashMap<>();
            values.put("client_id", clientId.isEmpty() ? "dynamic_agent_client" : clientId);
            if (clientId.isEmpty()) values.put("agent_name_hint", "WorldPainter+");
            else {
                if (!hint.isEmpty()) values.put("id_token_hint", hint);
                if (!email.isEmpty()) values.put("login_hint", email);
            }
            values.putAll(Map.of("ext_agent_host_id", host, "response_type", "code", "redirect_uri", redirect.toString(),
                    "scope", "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct", "resource", RESOURCE,
                    "state", state, "nonce", nonce, "code_challenge_method", "S256", "code_challenge", challenge(verifier)));
            authorization = URI.create(routes.authorize + "?" + form(values));
            server.createContext("/auth/callback", exchange -> {
                boolean accepted = false;
                try {
                    if (!"GET".equals(exchange.getRequestMethod()) || !"/auth/callback".equals(exchange.getRequestURI().getPath())
                            || !redirect.getRawAuthority().equals(exchange.getRequestHeaders().getFirst("Host"))) throw new IllegalArgumentException();
                    Map<String, String> callback = query(exchange.getRequestURI().getRawQuery());
                    if (!ChatGptIdentity.same(state, callback.getOrDefault("state", "")) || canceled) throw new IllegalArgumentException();
                    if (callback.containsKey("error")) {
                        accepted = completion.completeExceptionally(new IOException("ChatGPT sign-in was declined or could not complete"));
                    } else {
                        String code = callback.get("code");
                        if (code == null || code.isBlank() || code.length() > 8192) throw new IllegalArgumentException();
                        accepted = completion.complete(callback);
                    }
                } catch (IllegalArgumentException ignored) { }
                byte[] message = (accepted ? "Sign-in received. Return to WorldPainter+." : "This sign-in callback is invalid or has already been used.")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                exchange.sendResponseHeaders(accepted ? 200 : 400, message.length);
                try (var output = exchange.getResponseBody()) { output.write(message); }
            });
            executor = Executors.newSingleThreadExecutor(r -> { Thread thread = new Thread(r, "WorldPainter ChatGPT callback"); thread.setDaemon(true); return thread; });
            server.setExecutor(executor);
            server.start();
        }
        Map<String, String> await() throws IOException, InterruptedException {
            try { return completion.get(loginDeadline.toMillis(), TimeUnit.MILLISECONDS); }
            catch (TimeoutException e) { throw new IOException("ChatGPT sign-in timed out; try again"); }
            catch (ExecutionException e) { throw new IOException("ChatGPT sign-in was declined or canceled"); }
        }
        @Override public synchronized void close() {
            if (canceled) return;
            canceled = true;
            completion.completeExceptionally(new IOException("Sign-in canceled"));
            server.stop(0);
            executor.shutdownNow();
        }
        final String state = random(), nonce = random(), verifier = random();
        final URI redirect, authorization;
        final HttpServer server;
        final java.util.concurrent.ExecutorService executor;
        final CompletableFuture<Map<String, String>> completion = new CompletableFuture<>();
        volatile boolean canceled;
    }

    private static String random() { byte[] value = new byte[32]; RANDOM.nextBytes(value); return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private static String challenge(String value) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII))); }
        catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("PKCE is unavailable"); }
    }
    static final class InvalidGrant extends IOException { }
    record Routes(URI authorize, URI token, URI jwks, URI discovery, URI apiBase, String issuer) {
        static final Routes PRODUCTION = new Routes(URI.create("https://auth.openai.com/api/accounts/authorize"),
                URI.create("https://auth.openai.com/api/accounts/oauth/token"), URI.create("https://auth.openai.com/.well-known/jwks.json"),
                URI.create("https://auth.openai.com/.well-known/openid-configuration"), URI.create(RESOURCE), "https://auth.openai.com");
    }
    private final ChatGptSessionStore store;
    private final Routes routes;
    private final Clock clock;
    private final Duration loginDeadline;
    private final AiHttp transport = new AiHttp(Duration.ofSeconds(10), Duration.ofSeconds(30), 65_536);
    private final ChatGptResponsesClient responses;
    private final Object loginLock = new Object();
    private LoginAttempt activeLogin;
    private static final String RESOURCE = "https://api.openai.com/v1";
    private static final SecureRandom RANDOM = new SecureRandom();
}
