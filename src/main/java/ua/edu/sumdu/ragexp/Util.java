package ua.edu.sumdu.ragexp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public final class Util {
    public static final ObjectMapper JSON = new ObjectMapper();

    private Util() {}

    public static List<ObjectNode> readJsonl(Path file) throws IOException {
        List<ObjectNode> out = new ArrayList<>();
        if (!Files.exists(file)) return out;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            out.add((ObjectNode) JSON.readTree(line));
        }
        return out;
    }

    public static void appendJsonl(Path file, ObjectNode obj) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, JSON.writeValueAsString(obj) + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public static ObjectNode extractJson(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JsonNode n = JSON.readTree(text.substring(start, end + 1));
            return n instanceof ObjectNode on ? on : null;
        } catch (IOException e) {
            return null;
        }
    }

    public static String normalize(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    public static double shingleContainment(String gold, String candidate) {
        return shingleContainment(gold, candidate, 30, 10);
    }

    public static double shingleContainment(String gold, String candidate, int size, int step) {
        String g = normalize(gold);
        String c = normalize(candidate);
        if (g.isEmpty() || c.isEmpty()) return 0.0;
        if (g.length() <= size) return c.contains(g) ? 1.0 : 0.0;
        int total = 0, hits = 0;
        for (int i = 0; i + size <= g.length(); i += step) {
            total++;
            if (c.contains(g.substring(i, i + size))) hits++;
        }
        return total == 0 ? 0.0 : (double) hits / total;
    }

    public static double percentile(List<Long> values, double p) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compare);
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    public static double mean(List<Double> values) {
        return values.isEmpty() ? 0 : values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    public static double meanLong(List<Long> values) {
        return values.isEmpty() ? 0 : values.stream().mapToLong(Long::longValue).average().orElse(0);
    }
}
