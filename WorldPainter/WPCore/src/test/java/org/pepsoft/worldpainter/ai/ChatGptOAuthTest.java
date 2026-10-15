package org.pepsoft.worldpainter.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/** All credentials are generated test data; no real account, browser session or provider request is used. */
public class ChatGptOAuthTest {
    @BeforeClass public static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); pair = generator.generateKeyPair();
        RSAPublicKey key = (RSAPublicKey) pair.getPublic();
        jwks = Map.of("keys", List.of(Map.of("kty", "RSA", "kid", "test-key", "use", "sig", "alg", "RS256",
                "n", unsigned(key.getModulus()), "e", unsigned(key.getPublicExponent()))));
    }

    @Before public void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/keys", e -> reply(e, 200, JsonProtocol.write(jwks)));
        server.createContext("/discovery", e -> reply(e, 200, JsonProtocol.write(Map.of("issuer", ISSUER, "revocation_endpoint", origin + "/revoke"))));
        server.createContext("/revoke", e -> {
            revocations.incrementAndGet(); lastRevocation.set(ChatGptClient.query(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            reply(e, revokeStatus, "");
        });
        server.createContext("/token", e -> {
            Map<String, String> fields = ChatGptClient.query(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastExchange.set(fields); tokenCalls.incrementAndGet();
            if (invalidGrant) { reply(e, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"test-secret\"}"); return; }
            boolean refresh = fields.get("grant_type").equals("refresh_token");
            if (refresh) refreshes.incrementAndGet();
            Map<String, Object> claims = new LinkedHashMap<>(Map.of("iss", ISSUER, "aud", CLIENT, "sub", "subject-one", "email", "test@example.invalid",
                    "iat", clock.instant().getEpochSecond(), "exp", clock.instant().getEpochSecond() + 3600));
            if (!refresh) claims.put("nonce", authorization.get().get("nonce"));
            alterClaims.accept(claims);
            Map<String, Object> token = new LinkedHashMap<>(Map.of("token_type", "Bearer", "access_token", refresh ? "test-access-two" : "test-access-one",
                    "refresh_token", refresh ? "test-refresh-two" : "test-refresh-one", "expires_in", 3600,
                    "scope", scope));
            try { token.put("id_token", jwt(Map.of("alg", "RS256", "kid", "test-key"), claims)); }
            catch (Exception error) { throw new IOException(error); }
            reply(e, 200, JsonProtocol.write(token));
        });
        server.start(); origin = "http://127.0.0.1:" + server.getAddress().getPort();
        routes = new ChatGptClient.Routes(URI.create(origin + "/authorize"), URI.create(origin + "/token"), URI.create(origin + "/keys"),
                URI.create(origin + "/discovery"), URI.create(origin + "/v1"), ISSUER);
        directory = temporary.getRoot().toPath().resolve("credentials");
        client = new ChatGptClient(directory, routes, clock, Duration.ofSeconds(3));
    }

    @After public void teardown() { client.cancelSignIn(); server.stop(0); executor.shutdownNow(); }

    @Test public void registersWithPkceAndSavesPrivateCredentialsThenReusesTheIssuedClientAndHost() throws Exception {
        ChatGptProvider.Account account = login();
        assertTrue(account.signedIn()); assertEquals("test@example.invalid", account.label());
        Map<String, String> first = authorization.get();
        assertEquals("dynamic_agent_client", first.get("client_id"));
        assertEquals("WorldPainter+", first.get("agent_name_hint"));
        assertTrue(first.get("ext_agent_host_id").startsWith("urn:uuid:"));
        assertEquals("S256", first.get("code_challenge_method"));
        assertEquals(b64(MessageDigest.getInstance("SHA-256").digest(lastExchange.get().get("code_verifier").getBytes(StandardCharsets.US_ASCII))), first.get("code_challenge"));
        assertEquals(first.get("redirect_uri"), lastExchange.get().get("redirect_uri"));
        assertEquals("https://api.openai.com/v1", lastExchange.get().get("resource"));
        assertEquals(CLIENT, lastExchange.get().get("client_id"));
        assertFalse(lastExchange.get().containsKey("client_secret"));
        assertEquals("test-access-one", client.accessToken());
        if (Files.getFileAttributeView(directory, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
            for (String name : List.of("host-id", "session.json", "session.lock")) assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(directory.resolve(name)));
        }
        assertTrue(client.signOut(false)); assertFalse(client.account().signedIn());
        assertEquals("test-refresh-one", lastRevocation.get().get("token"));
        assertEquals("refresh_token", lastRevocation.get().get("token_type_hint"));
        assertFalse(Files.readString(directory.resolve("session.json")).contains("test-refresh"));
        login();
        assertEquals(CLIENT, authorization.get().get("client_id"));
        assertFalse(authorization.get().containsKey("agent_name_hint"));
        assertFalse(authorization.get().containsKey("id_token_hint"));
        assertEquals(first.get("ext_agent_host_id"), authorization.get().get("ext_agent_host_id"));
        assertNotEquals(first.get("state"), authorization.get().get("state"));
        assertNotEquals(first.get("nonce"), authorization.get().get("nonce"));
    }

    @Test public void rejectsWrongStateDuplicateParametersAndWrongPathsWithoutConsumingTheValidAttempt() throws Exception {
        client.signIn(uri -> {
            Map<String, String> values = ChatGptClient.query(uri.getRawQuery()); authorization.set(values);
            assertEquals(400, callback(values, "code=code-one&state=wrong&client_id=" + CLIENT));
            assertEquals(400, callback(values, "code=code-one&code=again&state=" + values.get("state") + "&client_id=" + CLIENT));
            try {
                assertEquals(400, HTTP.send(HttpRequest.newBuilder(URI.create(values.get("redirect_uri") + "/wrong?state=" + values.get("state"))).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted test callback"); }
            assertEquals(200, callback(values, "code=code-one&state=" + values.get("state") + "&client_id=" + CLIENT));
        });
        assertEquals(1, tokenCalls.get()); assertTrue(client.account().signedIn());
    }

    @Test public void declinedConsentAndMissingPlanScopeCannotCreateASession() throws Exception {
        assertThrows(IOException.class, () -> client.signIn(uri -> {
            Map<String, String> values = ChatGptClient.query(uri.getRawQuery());
            callback(values, "error=access_denied&state=" + values.get("state"));
        }));
        assertEquals(0, tokenCalls.get()); assertFalse(client.account().signedIn());
        scope = "openid profile email";
        assertThrows(IOException.class, this::login);
        assertFalse(client.account().signedIn());
    }

    @Test public void verifiesSignedIdentityNonceIssuerAudienceExpiryAndSelectedAccount() throws Exception {
        for (String field : List.of("iss", "aud", "nonce", "exp", "iat", "sub")) {
            alterClaims = claims -> claims.put(field, field.equals("exp") ? clock.instant().minusSeconds(30).getEpochSecond()
                    : field.equals("iat") ? clock.instant().plusSeconds(30).getEpochSecond() : "wrong");
            if (field.equals("sub")) continue; // A new registration has no selected identity yet.
            assertThrows(IOException.class, this::login); assertFalse(client.account().signedIn());
        }
        alterClaims = claims -> { }; login(); client.signOut(false);
        alterClaims = claims -> claims.put("sub", "different-account");
        assertThrows(IOException.class, this::login); assertFalse(client.account().signedIn());
    }

    @Test public void returningCallbackCannotReplaceAnIssuedClient() throws Exception {
        login(); client.signOut(false);
        int calls = tokenCalls.get();
        assertThrows(IOException.class, () -> client.signIn(uri -> {
            Map<String, String> values = ChatGptClient.query(uri.getRawQuery()); authorization.set(values);
            callback(values, "code=code-one&state=" + values.get("state") + "&client_id=oaiapp_other_test");
        }));
        assertEquals(calls, tokenCalls.get()); assertFalse(client.account().signedIn());
    }

    @Test public void refreshRotationIsSerializedAcrossClientInstances() throws Exception {
        login(); clock.advance(Duration.ofHours(2));
        ChatGptClient second = new ChatGptClient(directory, routes, clock, Duration.ofSeconds(3));
        var first = executor.submit(client::accessToken); var next = executor.submit(second::accessToken);
        assertEquals("test-access-two", first.get(5, TimeUnit.SECONDS));
        assertEquals("test-access-two", next.get(5, TimeUnit.SECONDS));
        assertEquals(1, refreshes.get());
        assertEquals("test-refresh-one", lastExchange.get().get("refresh_token"));
        String state = Files.readString(directory.resolve("session.json"));
        assertTrue(state.contains("test-refresh-two")); assertFalse(state.contains("test-refresh-one"));
    }

    @Test public void revokedRefreshClearsTokensAndRetainsTheRegistration() throws Exception {
        login(); clock.advance(Duration.ofHours(2)); invalidGrant = true;
        IOException failure = assertThrows(IOException.class, client::accessToken);
        assertFalse(failure.toString().contains("test-secret"));
        assertFalse(client.account().signedIn()); assertEquals(CLIENT, client.account().clientId());
        assertFalse(Files.readString(directory.resolve("session.json")).contains("test-refresh"));
    }

    @Test public void expiredInitialCodeRetainsTheIssuedClientForTheNextFreshSignIn() throws Exception {
        invalidGrant = true; assertThrows(IOException.class, this::login);
        assertFalse(client.account().signedIn()); assertEquals(CLIENT, client.account().clientId());
        invalidGrant = false; login(); assertEquals(CLIENT, authorization.get().get("client_id"));
    }

    @Test public void cancelClosesTheListenerAndCannotCommitALateResult() throws Exception {
        CountDownLatch opened = new CountDownLatch(1);
        var pending = executor.submit(() -> client.signIn(uri -> {
            authorization.set(ChatGptClient.query(uri.getRawQuery())); opened.countDown();
        }));
        assertTrue(opened.await(3, TimeUnit.SECONDS)); client.cancelSignIn();
        assertThrows(java.util.concurrent.ExecutionException.class, () -> pending.get(3, TimeUnit.SECONDS));
        assertFalse(client.account().signedIn());
        assertThrows(IOException.class, () -> callback(authorization.get(), "state=x&code=x"));
        assertEquals(0, tokenCalls.get());
    }

    @Test public void browserFailuresAndUnconfirmedRevocationNeverEchoCredentials() throws Exception {
        IOException failure = assertThrows(IOException.class, () -> client.signIn(uri -> { throw new IOException("test-secret " + uri); }));
        assertFalse(failure.toString().contains("test-secret")); assertNull(failure.getCause());
        login(); String host = authorization.get().get("ext_agent_host_id"); revokeStatus = 503;
        assertFalse(client.signOut(true)); assertEquals(2, revocations.get());
        assertFalse(client.account().signedIn()); assertEquals("", client.account().clientId());
        login(); assertEquals("dynamic_agent_client", authorization.get().get("client_id"));
        assertEquals(host, authorization.get().get("ext_agent_host_id"));
    }

    @Test public void refusesSymbolicLinksInCredentialStorage() throws Exception {
        login(); Path record = directory.resolve("session.json"); Path target = temporary.getRoot().toPath().resolve("sentinel");
        Files.writeString(target, "unchanged"); Files.delete(record);
        try { Files.createSymbolicLink(record, target); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException e) { org.junit.Assume.assumeNoException(e); }
        assertThrows(IOException.class, client::account); assertEquals("unchanged", Files.readString(target));
    }

    private ChatGptProvider.Account login() throws Exception {
        return client.signIn(uri -> {
            Map<String, String> values = ChatGptClient.query(uri.getRawQuery()); authorization.set(values);
            callback(values, "code=code-one&state=" + values.get("state") + "&client_id=" + CLIENT);
        });
    }
    private static int callback(Map<String, String> values, String query) throws IOException {
        try { return HTTP.send(HttpRequest.newBuilder(URI.create(values.get("redirect_uri") + "?" + query)).timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted test callback"); }
    }
    static String jwt(Map<String, Object> header, Map<String, Object> claims) throws Exception {
        String input = b64(JsonProtocol.write(header).getBytes(StandardCharsets.UTF_8)) + "." + b64(JsonProtocol.write(claims).getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("SHA256withRSA"); signer.initSign(pair.getPrivate()); signer.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + b64(signer.sign());
    }
    private static String unsigned(java.math.BigInteger value) { byte[] bytes = value.toByteArray(); return b64(bytes[0] == 0 ? java.util.Arrays.copyOfRange(bytes, 1, bytes.length) : bytes); }
    private static String b64(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(status, data.length == 0 ? -1 : data.length);
        if (data.length == 0) exchange.close();
        else try (var output = exchange.getResponseBody()) { output.write(data); }
    }
    static final class MutableClock extends Clock {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current.get(); }
        void advance(Duration duration) { current.updateAndGet(value -> value.plus(duration)); }
        final AtomicReference<Instant> current = new AtomicReference<>(Instant.parse("2026-10-15T05:00:00Z"));
    }
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final MutableClock clock = new MutableClock();
    private final AtomicReference<Map<String, String>> authorization = new AtomicReference<>(), lastExchange = new AtomicReference<>(), lastRevocation = new AtomicReference<>();
    private final AtomicInteger tokenCalls = new AtomicInteger(), refreshes = new AtomicInteger(), revocations = new AtomicInteger();
    private Consumer<Map<String, Object>> alterClaims = claims -> { };
    private String scope = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
    private volatile boolean invalidGrant;
    private int revokeStatus = 200;
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private Path directory;
    private String origin;
    private ChatGptClient client;
    private ChatGptClient.Routes routes;
    static KeyPair pair;
    static Map<?, ?> jwks;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final String ISSUER = "https://auth.openai.com", CLIENT = "oaiapp_worldpainter_test";
}
