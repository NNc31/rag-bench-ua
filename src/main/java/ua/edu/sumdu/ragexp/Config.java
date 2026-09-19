package ua.edu.sumdu.ragexp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public final class Config {
    private final Properties p = new Properties();

    private Config(Path file) throws IOException {
        if (Files.exists(file)) {
            try (var r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
            }
        }
    }

    public static Config load() throws IOException {
        return new Config(Path.of("experiment.properties"));
    }

    private String get(String key, String def) {
        String env = System.getenv(key.toUpperCase().replace('.', '_'));
        if (env != null && !env.isBlank()) return env;
        return p.getProperty(key, def);
    }

    public String ollamaUrl() {
        return get("ollama.url", "http://localhost:11434");
    }
    public String dbUrl() {
        return get("db.url", "jdbc:postgresql://localhost:5433/ragexp");
    }

    public String dbUser() {
        return get("db.user", "ragexp");
    }

    public String dbPass() {
        return get("db.pass", "ragexp");
    }

    public String generatorModel() {
        return get("generator.model", "qwen2.5:7b");
    }

    public String judgeModel()    {
        return get("judge.model", "llama3.1:8b");
    }

    public List<String> embeddingModels() {
        return list(get("embedding.models", "nomic-embed-text,bge-m3"));
    }

    public List<Integer> chunkSizes() {
        return list(get("chunk.sizes", "800,1600,3200")).stream().map(Integer::parseInt).toList();
    }

    public double overlapRatio() {
        return Double.parseDouble(get("overlap.ratio", "0.15"));
    }

    public List<Integer> topKs() {
        return list(get("top.ks", "3,8")).stream().map(Integer::parseInt).toList();
    }

    public int questionsCount()  {
        return Integer.parseInt(get("questions.count", "40"));
    }

    public long seed() {
        return Long.parseLong(get("seed", "42"));
    }

    public int embedBatchSize() {
        return Integer.parseInt(get("embed.batch.size", "16"));
    }

    public int contextMaxChars() {
        return Integer.parseInt(get("context.max.chars", "20000"));
    }

    public Path dataDir() {
        return Path.of(get("data.dir", "data"));
    }

    public Path resultsDir() {
        return Path.of(get("results.dir", "results"));
    }

    public Path corpusDir() {
        return dataDir().resolve("corpus");
    }

    public Path qaFile() {
        return dataDir().resolve("qa.jsonl");
    }

    public Path runsFile() {
        return resultsDir().resolve("runs.jsonl");
    }

    public Path judgmentsFile() {
        return resultsDir().resolve("judgments.jsonl");
    }

    private static List<String> list(String s) {
        return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }
}
