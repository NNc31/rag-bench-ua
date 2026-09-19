package ua.edu.sumdu.ragexp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class OllamaClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final String baseUrl;

    public OllamaClient(String baseUrl) { this.baseUrl = baseUrl; }

    public record ChatResult(String content, long promptTokens, long outTokens, long totalDurationNs) {}

    public List<float[]> embed(String model, List<String> texts) {
        ObjectNode body = Util.JSON.createObjectNode();
        body.put("model", model);
        ArrayNode input = body.putArray("input");
        texts.forEach(input::add);
        JsonNode resp = postWithRetry("/api/embed", body, Duration.ofMinutes(10));
        JsonNode embs = resp.get("embeddings");
        if (embs == null || !embs.isArray()) {
            throw new IllegalStateException("Ollama /api/embed: немає поля embeddings. Відповідь: " + abbrev(resp.toString()));
        }
        List<float[]> out = new ArrayList<>(embs.size());
        for (JsonNode e : embs) {
            float[] v = new float[e.size()];
            for (int i = 0; i < e.size(); i++) v[i] = (float) e.get(i).asDouble();
            out.add(v);
        }
        return out;
    }

    public ChatResult chat(String model, String system, String user, int numCtx) {
        ObjectNode body = Util.JSON.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        ArrayNode messages = body.putArray("messages");
        if (system != null && !system.isBlank()) {
            ObjectNode s = messages.addObject();
            s.put("role", "system");
            s.put("content", system);
        }
        ObjectNode u = messages.addObject();
        u.put("role", "user");
        u.put("content", user);
        ObjectNode options = body.putObject("options");
        options.put("temperature", 0.0);
        options.put("num_ctx", numCtx);
        JsonNode resp = postWithRetry("/api/chat", body, Duration.ofMinutes(20));
        String content = resp.path("message").path("content").asText("");
        return new ChatResult(content,
                resp.path("prompt_eval_count").asLong(0),
                resp.path("eval_count").asLong(0),
                resp.path("total_duration").asLong(0));
    }

    private JsonNode postWithRetry(String path, ObjectNode body, Duration timeout) {
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return post(path, body, timeout);
            } catch (IOException e) {
                last = e;
                System.out.println("  ! Помилка Ollama (спроба " + attempt + "/3): " + e.getMessage());
                try {
                    Thread.sleep(2000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new RuntimeException("Ollama недоступна після 3 спроб (" + baseUrl + path + "): " + last.getMessage(), last);
    }

    private JsonNode post(String path, ObjectNode body, Duration timeout) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Util.JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " " + path + ": " + abbrev(r.body()));
        return Util.JSON.readTree(r.body());
    }

    private static String abbrev(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
