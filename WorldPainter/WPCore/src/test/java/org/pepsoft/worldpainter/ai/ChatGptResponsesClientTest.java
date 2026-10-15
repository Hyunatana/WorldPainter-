package org.pepsoft.worldpainter.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class ChatGptResponsesClientTest {
    @Before public void setup() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = java.util.concurrent.Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/", e -> handler.get().handle(e)); server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        client = new ChatGptResponsesClient(base, () -> "oauth-test-token", Duration.ofSeconds(3));
    }
    @After public void teardown() { server.stop(0); executor.shutdownNow(); }

    @Test public void sendsTheOfficialResponsesContractAndAcceptsOnlyTheCompletedPlan() throws Exception {
        AtomicReference<Map<?, ?>> sent = new AtomicReference<>();
        handler.set(e -> {
            assertEquals("/v1/responses", e.getRequestURI().getPath());
            assertEquals("Bearer oauth-test-token", e.getRequestHeaders().getFirst("Authorization"));
            sent.set((Map<?, ?>) JsonProtocol.read(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            reply(e, 200, ": heartbeat\r\n\r\nevent: response.output_text.delta\r\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"untrusted partial text\"}\r\n\r\n" + completed(PLAN));
        });
        assertEquals(PLAN, client.generate(false, "account-model", "editor-data", List.of(new OpenAiTerrainClient.Message("user", "Earlier request")), "Create a mountain"));
        Map<?, ?> body = sent.get();
        assertEquals(false, body.get("store")); assertEquals(true, body.get("stream")); assertEquals(List.of(), body.get("tools"));
        assertEquals(TerrainPrompt.SYSTEM, body.get("instructions")); assertEquals("account-model", body.get("model"));
        assertEquals(3, ((List<?>) body.get("input")).size());
        for (String forbidden : List.of("messages", "previous_response_id", "temperature", "max_output_tokens", "background")) assertFalse(body.containsKey(forbidden));
    }

    @Test public void createsWorldsUsingTheSeparateSchemaAndPrompt() throws Exception {
        String world = WorldPlanCodec.encode(WorldCreationTest.plan(WorldCreationPlan.Landscape.ISLAND, 42));
        handler.set(e -> {
            Map<?, ?> body = (Map<?, ?>) JsonProtocol.read(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals(WorldPrompt.SYSTEM, body.get("instructions")); reply(e, 200, completed(world));
        });
        assertEquals(world, client.generate(true, "model", "", List.of(), "An island"));
        handler.set(e -> reply(e, 200, completed(PLAN)));
        assertThrows(IOException.class, () -> client.generate(true, "model", "", List.of(), "An island"));
    }

    @Test public void catalogsUseVisibilityDisplayLabelsAndServerOrdering() throws Exception {
        handler.set(e -> {
            assertEquals("GET", e.getRequestMethod()); assertEquals("/v1/models", e.getRequestURI().getPath());
            assertEquals("Bearer oauth-test-token", e.getRequestHeaders().getFirst("Authorization"));
            reply(e, 200, JsonProtocol.write(Map.of("models", List.of(Map.of("slug", "z-model", "display_name", "Visible Z", "visibility", "list"),
                    Map.of("slug", "hidden", "visibility", "hidden"), Map.of("slug", "a-model", "display_name", "Visible A", "visibility", "list")))));
        });
        assertEquals(List.of(new ChatGptProvider.Model("z-model", "Visible Z"), new ChatGptProvider.Model("a-model", "Visible A")), client.listModels());
        handler.set(e -> reply(e, 200, "{\"models\":[{\"visibility\":\"list\",\"slug\":\"test-secret\",\"slug\":\"duplicate\"}]}"));
        IOException error = assertThrows(IOException.class, client::listModels); assertFalse(error.toString().contains("test-secret"));
    }

    @Test public void rejectsPartialFailedRefusedToolAndMalformedPlansWithoutEchoingData() throws Exception {
        String tool = "data: " + JsonProtocol.write(Map.of("type", "response.completed", "response", Map.of("status", "completed", "output",
                List.of(Map.of("type", "function_call", "name", "execute", "arguments", "test-secret"))))) + "\n\n";
        for (String response : List.of("data: {\"type\":\"response.output_text.delta\",\"delta\":\"test-secret\"}\n\n", "data: [DONE]\n\n", tool,
                completed("```json\n" + PLAN + "\n```"), completed("{\"version\":1,\"commands\":[]}"),
                "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"message\":\"test-secret\"}}}\n\n",
                "data: {\"type\":\"response.incomplete\"}\n\n", "data: {\"type\":\"response.refusal.delta\",\"delta\":\"test-secret\"}\n\n",
                "event: error\ndata: {\"type\":\"response.completed\"}\n\n", completed(PLAN) + completed(PLAN))) {
            handler.set(e -> reply(e, 200, response));
            IOException error = assertThrows(IOException.class, () -> client.generate(false, "model", "", List.of(), "Mountain"));
            assertFalse(error.toString().contains("test-secret")); assertNull(error.getCause());
        }
    }

    @Test public void surfacesUsageLimitsEvenAfterAStreamHasStarted() throws Exception {
        handler.set(e -> reply(e, 200, "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\",\"message\":\"test-secret\"}}}\n\n"));
        IOException error = assertThrows(IOException.class, () -> client.generate(false, "model", "", List.of(), "Mountain"));
        assertTrue(error.getMessage().contains("Manage usage")); assertFalse(error.getMessage().contains("test-secret"));
    }

    @Test public void refusesRedirectsAndBoundsStreamingBodies() throws Exception {
        handler.set(e -> { e.getResponseHeaders().set("Location", base + "/elsewhere"); reply(e, 302, "test-secret"); });
        IOException redirect = assertThrows(IOException.class, () -> client.generate(false, "model", "", List.of(), "Mountain"));
        assertEquals("ChatGPT returned HTTP 302", redirect.getMessage());
        handler.set(e -> reply(e, 200, "x".repeat(1_048_577)));
        assertThrows(IOException.class, () -> client.generate(false, "model", "", List.of(), "Mountain"));
    }

    @Test public void streamDeadlineCoversAServerThatDoesNotCloseItsBody() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        handler.set(e -> {
            e.sendResponseHeaders(200, 0);
            try (var out = e.getResponseBody()) { out.write("data: ".getBytes(StandardCharsets.UTF_8)); out.flush(); release.await(4, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        try {
            var bounded = new ChatGptResponsesClient(base, () -> "oauth-test-token", Duration.ofMillis(150));
            assertThrows(IOException.class, () -> bounded.generate(false, "model", "", List.of(), "Mountain"));
        } finally { release.countDown(); }
    }

    static String completed(String plan) {
        return "event: response.completed\ndata: " + JsonProtocol.write(Map.of("type", "response.completed", "response", Map.of("status", "completed",
                "output", List.of(Map.of("type", "reasoning", "summary", List.of()), Map.of("type", "message", "role", "assistant", "status", "completed",
                        "content", List.of(Map.of("type", "output_text", "text", plan))))))) + "\n\n";
    }
    private static void reply(HttpExchange e, int status, String text) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().set("Content-Type", "text/event-stream"); e.sendResponseHeaders(status, data.length);
        try (var out = e.getResponseBody()) { out.write(data); }
    }
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private final AtomicReference<com.sun.net.httpserver.HttpHandler> handler = new AtomicReference<>();
    private URI base;
    private ChatGptResponsesClient client;
    private static final String PLAN = TerrainPlanCodec.encode(TerrainPlanCodec.parseOffline("Create a mountain at 0, 0 radius 16 height 20 seed 42"));
}
