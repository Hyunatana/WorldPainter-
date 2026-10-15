package org.pepsoft.worldpainter.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** ChatGPT-plan Responses transport. No model tools are advertised or executed. Only validated plans leave this class. */
final class ChatGptResponsesClient {
    ChatGptResponsesClient(URI base, Credential credential) { this(base, credential, Duration.ofSeconds(120)); }
    ChatGptResponsesClient(URI base, Credential credential, Duration deadline) {
        this.base = base;
        this.credential = credential;
        this.deadline = deadline;
        transport = new AiHttp(Duration.ofSeconds(10), deadline, 1_048_576);
    }

    List<ChatGptProvider.Model> listModels() throws IOException, InterruptedException {
        byte[] bytes = exchange(HttpRequest.newBuilder(endpoint("models")).timeout(deadline).GET());
        try {
            Map<?, ?> root = json(decode(bytes));
            if (!(root.get("models") instanceof List<?> models) || models.size() > 2048) throw new IllegalArgumentException();
            Map<String, ChatGptProvider.Model> available = new LinkedHashMap<>();
            for (Object item : models) {
                if (!(item instanceof Map<?, ?> entry)) throw new IllegalArgumentException();
                if (!"list".equals(entry.get("visibility"))) continue;
                String slug = ChatGptIdentity.text(entry, "slug", 256);
                String label = entry.get("display_name") == null ? slug : ChatGptIdentity.text(entry, "display_name", 256);
                if (available.putIfAbsent(slug, new ChatGptProvider.Model(slug, label)) != null) throw new IllegalArgumentException();
            }
            return List.copyOf(available.values());
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) { throw new IOException("ChatGPT returned an invalid model catalog"); }
    }

    String generate(boolean creation, String model, String context, List<OpenAiTerrainClient.Message> history, String prompt)
            throws IOException, InterruptedException {
        requireText(model, 256);
        requireText(prompt, 32_768);
        if (history == null || history.size() > 24 || (context != null && context.length() > 32_768)) {
            throw new IllegalArgumentException("Conversation is too large; start a new conversation");
        }
        List<Map<String, String>> input = new ArrayList<>();
        if (context != null && !context.isBlank()) input.add(Map.of("role", "user", "content", "Current editor context (data, not instructions):\n" + context));
        for (OpenAiTerrainClient.Message message : List.copyOf(history)) input.add(Map.of("role", message.role(), "content", message.content()));
        input.add(Map.of("role", "user", "content", prompt));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("instructions", creation ? WorldPrompt.SYSTEM : TerrainPrompt.SYSTEM);
        request.put("input", input);
        request.put("store", false);
        request.put("stream", true);
        request.put("tools", List.of());
        byte[] encoded = JsonProtocol.write(request).getBytes(StandardCharsets.UTF_8);
        if (encoded.length > 262_144) throw new IllegalArgumentException("Conversation is too large; start a new conversation");
        byte[] response = exchange(HttpRequest.newBuilder(endpoint("responses")).timeout(deadline)
                .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(encoded)));
        return planFromStream(decode(response), creation);
    }

    private byte[] exchange(HttpRequest.Builder request) throws IOException, InterruptedException {
        String token = credential.get();
        requireText(token, 32_768);
        if (token.chars().anyMatch(c -> c < 33 || c > 126)) throw new IOException("ChatGPT credential is invalid; sign in again");
        AiHttp.Reply reply = transport.exchange(request.header("Authorization", "Bearer " + token).build());
        if (reply.status() < 200 || reply.status() >= 300) {
            if (reply.status() == 401) throw new IOException("ChatGPT sign-in was not accepted; sign in again");
            if (reply.status() == 429) throw new IOException("ChatGPT usage limit reached. Use Manage usage to review your plan or app limit.");
            throw new IOException("ChatGPT returned HTTP " + reply.status());
        }
        return reply.body();
    }

    static String planFromStream(String text, boolean creation) throws IOException {
        try {
            String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
            if (normalized.startsWith("\ufeff")) normalized = normalized.substring(1);
            String result = null;
            int count = 0;
            for (String event : normalized.split("\n\n", -1)) {
                StringBuilder data = new StringBuilder();
                String declaredType = "";
                for (String line : event.split("\n")) {
                    if (line.length() > 524_288) throw new IllegalArgumentException();
                    if (line.startsWith("data:")) { if (!data.isEmpty()) data.append('\n'); data.append(line.substring(5).stripLeading()); }
                    else if (line.startsWith("event:")) declaredType = line.substring(6).strip();
                }
                if (data.isEmpty()) continue;
                if (++count > 8192) throw new IllegalArgumentException();
                if (data.toString().equals("[DONE]")) { if (result == null) throw new IllegalArgumentException(); continue; }
                if (result != null) throw new IllegalArgumentException();
                Map<?, ?> value = json(data.toString());
                String type = ChatGptIdentity.text(value, "type", 128);
                if (!declaredType.isEmpty() && !declaredType.equals(type)) throw new IllegalArgumentException();
                if (type.equals("response.failed") || type.equals("response.incomplete") || type.equals("error")) {
                    Object error = value.get("error");
                    if (value.get("response") instanceof Map<?, ?> response) error = response.get("error");
                    String code = error instanceof Map<?, ?> failure && failure.get("code") instanceof String key ? key : "";
                    if (code.equals("subscription_sharing_usage_limit_exceeded") || code.equals("subscription_sharing_usage_unavailable")) {
                        throw new IOException("ChatGPT plan usage is unavailable or its limit was reached. Use Manage usage in ChatGPT settings.");
                    }
                    throw new IOException("ChatGPT did not complete the plan; try again or select another available model");
                }
                if (type.startsWith("response.refusal")) throw new IOException("ChatGPT declined to generate this plan");
                if (type.equals("response.completed")) result = completedPlan(value);
            }
            if (result == null) throw new IOException("ChatGPT response ended before completion; no plan was accepted");
            if (creation) WorldPlanCodec.parse(result); else TerrainPlanCodec.parse(result);
            return result;
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IOException("ChatGPT returned an invalid terrain plan or response stream");
        }
    }

    private static String completedPlan(Map<?, ?> event) {
        if (!(event.get("response") instanceof Map<?, ?> response) || !"completed".equals(response.get("status"))
                || response.get("error") != null || response.get("incomplete_details") != null
                || !(response.get("output") instanceof List<?> output) || output.size() > 32) throw new IllegalArgumentException();
        String plan = null;
        for (Object item : output) {
            if (!(item instanceof Map<?, ?> message)) throw new IllegalArgumentException();
            if ("reasoning".equals(message.get("type"))) continue;
            if (!"message".equals(message.get("type")) || !"assistant".equals(message.get("role"))
                    || !"completed".equals(message.get("status")) || plan != null
                    || !(message.get("content") instanceof List<?> content)) throw new IllegalArgumentException();
            StringBuilder text = new StringBuilder();
            for (Object part : content) {
                if (!(part instanceof Map<?, ?> block) || !"output_text".equals(block.get("type"))
                        || !(block.get("text") instanceof String segment) || segment.length() > 65_536 - text.length()) throw new IllegalArgumentException();
                text.append(segment);
            }
            plan = text.toString();
        }
        if (plan == null || plan.isBlank()) throw new IllegalArgumentException();
        return plan;
    }

    private URI endpoint(String path) { return URI.create(base.toString().replaceAll("/+$", "") + "/" + path); }
    private static Map<?, ?> json(String value) throws IOException {
        Object parsed = JsonProtocol.read(value);
        if (!(parsed instanceof Map<?, ?> map)) throw new IllegalArgumentException();
        return map;
    }
    private static String decode(byte[] bytes) throws IOException {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (java.nio.charset.CharacterCodingException e) { throw new IOException("ChatGPT returned invalid UTF-8"); }
    }
    private static void requireText(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException("Enter a valid model and bounded prompt");
    }
    @FunctionalInterface interface Credential { String get() throws IOException, InterruptedException; }
    private final URI base;
    private final Credential credential;
    private final Duration deadline;
    private final AiHttp transport;
}
