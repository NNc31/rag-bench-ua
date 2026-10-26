package ua.edu.sumdu.ragexp;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

public final class QaGenerator {

    private static final String PROMPT = """
            Ти складаєш тестові запитання для оцінювання пошуково-генеративної системи.
            На основі наведеного фрагмента тексту сформулюй ОДНЕ конкретне фактичне запитання українською мовою,
            відповідь на яке міститься БЕЗПОСЕРЕДНЬО в цьому фрагменті, а також коротку еталонну відповідь (1-2 речення).
            Запитання має бути самодостатнім (зрозумілим без фрагмента перед очима) і не тривіальним.
            Поверни СТРОГО лише JSON без пояснень у форматі:
            {"question": "...", "answer": "..."}

            Фрагмент:
            ---
            %s
            ---
            """;

    private final Config cfg;
    private final OllamaClient ollama;

    public QaGenerator(Config cfg, OllamaClient ollama) {
        this.cfg = cfg;
        this.ollama = ollama;
    }

    public void run(int more) throws IOException {
        if (more > 0) {
            runMore(more);
            return;
        }
        if (Files.exists(cfg.qaFile())) {
            System.out.println("Файл " + cfg.qaFile() + " вже існує - НЕ перезаписую, щоб не втратити ручну валідацію.");
            System.out.println("Якщо хочеш перегенерувати з нуля - видали файл вручну і запусти команду ще раз.");
            return;
        }
        List<Splitter.Chunk> candidates = referenceChunks();
        if (candidates.isEmpty()) throw new IllegalStateException("Немає корпусу - спочатку команда fetch.");
        if (candidates.size() < cfg.questionsCount()) {
            System.out.println("! Кандидатних фрагментів лише " + candidates.size() + " - згенерую скільки вийде.");
        }
        Collections.shuffle(candidates, new Random(cfg.seed()));

        System.out.println("Генерація запитань моделлю " + cfg.generatorModel() + "...");
        int made = 0;
        for (Splitter.Chunk chunk : candidates) {
            if (made >= cfg.questionsCount()) break;
            OllamaClient.ChatResult res = ollama.chat(cfg.generatorModel(), null, PROMPT.formatted(chunk.text()), 4096);
            ObjectNode json = Util.extractJson(res.content());
            if (json == null) continue;
            String q = json.path("question").asText("").strip();
            String a = json.path("answer").asText("").strip();
            if (q.length() < 10 || a.isEmpty()) continue;

            ObjectNode rec = Util.JSON.createObjectNode();
            rec.put("qa_id", "q%03d".formatted(made + 1));
            rec.put("doc_id", chunk.docId());
            rec.put("gold_chunk", chunk.text());
            rec.put("question", q);
            rec.put("gold_answer", a);
            Util.appendJsonl(cfg.qaFile(), rec);
            made++;
            System.out.println("  [" + made + "/" + cfg.questionsCount() + "] " + q);
        }
        System.out.println();
        System.out.println("Згенеровано " + made + " пар у " + cfg.qaFile());
    }

    private void runMore(int more) throws IOException {
        List<ObjectNode> existing = Util.readJsonl(cfg.qaFile());
        if (existing.isEmpty()) {
            throw new IllegalStateException("Немає " + cfg.qaFile() + " - режим --more додає запитання до наявного набору.");
        }
        Set<String> usedChunks = new HashSet<>();
        int maxId = 0;
        for (ObjectNode r : existing) {
            usedChunks.add(Util.normalize(r.path("gold_chunk").asText()));
            String digits = r.path("qa_id").asText().replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) maxId = Math.max(maxId, Integer.parseInt(digits));
        }
        for (ObjectNode r : Util.readJsonl(cfg.dataDir().resolve("qa_orig.jsonl"))) {
            usedChunks.add(Util.normalize(r.path("gold_chunk").asText()));
            String digits = r.path("qa_id").asText().replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) maxId = Math.max(maxId, Integer.parseInt(digits));
        }

        List<Splitter.Chunk> candidates = referenceChunks();
        if (candidates.isEmpty()) throw new IllegalStateException("Немає корпусу - спочатку команда fetch.");
        Collections.shuffle(candidates, new Random(cfg.seed()));

        System.out.println("Догенерація " + more + " запитань моделлю " + cfg.generatorModel()
                + " (пропускаю " + usedChunks.size() + " вже використаних фрагментів)...");
        int made = 0;
        for (Splitter.Chunk chunk : candidates) {
            if (made >= more) break;
            if (usedChunks.contains(Util.normalize(chunk.text()))) continue;
            OllamaClient.ChatResult res = ollama.chat(cfg.generatorModel(), null, PROMPT.formatted(chunk.text()), 4096);
            ObjectNode json = Util.extractJson(res.content());
            if (json == null) continue;
            String q = json.path("question").asText("").strip();
            String ans = json.path("answer").asText("").strip();
            if (q.length() < 10 || ans.isEmpty()) continue;

            ObjectNode rec = Util.JSON.createObjectNode();
            rec.put("qa_id", "q%03d".formatted(maxId + made + 1));
            rec.put("doc_id", chunk.docId());
            rec.put("gold_chunk", chunk.text());
            rec.put("question", q);
            rec.put("gold_answer", ans);
            Util.appendJsonl(cfg.qaFile(), rec);
            usedChunks.add(Util.normalize(chunk.text()));
            made++;
            System.out.println("  [+" + made + "/" + more + "] " + q);
        }
        System.out.println();
        System.out.println("Додано " + made + " пар (q" + "%03d".formatted(maxId + 1) + "...q" + "%03d".formatted(maxId + made)
                + ") у " + cfg.qaFile() + ". Нові пари потребують ручної валідації.");
    }

    private List<Splitter.Chunk> referenceChunks() throws IOException {
        List<Splitter.Chunk> all = new ArrayList<>();
        if (!Files.isDirectory(cfg.corpusDir())) return all;
        try (Stream<Path> files = Files.list(cfg.corpusDir())) {
            for (Path f : files.filter(p -> p.toString().endsWith(".txt")).sorted().toList()) {
                String docId = f.getFileName().toString().replaceFirst("\\.txt$", "");
                String text = Files.readString(f, StandardCharsets.UTF_8);
                for (Splitter.Chunk c : Splitter.split(docId, text, 1200, 0)) {
                    if (c.text().length() >= 400) all.add(c);
                }
            }
        }
        return all;
    }
}
