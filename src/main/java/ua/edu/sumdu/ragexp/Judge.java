package ua.edu.sumdu.ragexp;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.*;

public final class Judge {

    private static final String PROMPT = """
            Ти - суворий екзаменатор. Оціни, чи правильно відповідь кандидата відповідає на запитання,
            порівнюючи її з еталонною відповіддю. Оцінюй лише фактичну відповідність, а не стиль.

            Запитання: %s

            Еталонна відповідь: %s

            Відповідь кандидата: %s

            Поверни СТРОГО лише JSON без пояснень:
            {"verdict": "correct"} - якщо відповідь кандидата фактично правильна;
            {"verdict": "partial"} - якщо правильна частково або неповна;
            {"verdict": "incorrect"} - якщо неправильна, суперечить еталону або відповіді немає.
            """;

    private final Config cfg;
    private final OllamaClient ollama;

    public Judge(Config cfg, OllamaClient ollama) {
        this.cfg = cfg;
        this.ollama = ollama;
    }

    public void run() throws IOException {
        List<ObjectNode> runs = Util.readJsonl(cfg.runsFile());
        if (runs.isEmpty()) throw new IllegalStateException("Немає " + cfg.runsFile() + " - спочатку команда run.");

        Map<String, String> goldById = new HashMap<>();
        for (ObjectNode q : Util.readJsonl(cfg.qaFile())) {
            goldById.put(q.path("qa_id").asText(), q.path("gold_answer").asText());
        }

        Set<String> done = new HashSet<>();
        for (ObjectNode j : Util.readJsonl(cfg.judgmentsFile())) done.add(j.path("key").asText());
        if (!done.isEmpty()) System.out.println("Уже оцінено: " + done.size() + " записів - пропускаю (відновлення).");

        System.out.println("Прогрів судді (" + cfg.judgeModel() + ")...");
        ollama.chat(cfg.judgeModel(), null, "Скажи одне слово: готовий", 2048);

        int i = 0, parseErrors = 0;
        for (ObjectNode run : runs) {
            i++;
            String key = run.path("config_id").asText() + "::" + run.path("qa_id").asText();
            if (done.contains(key)) continue;

            String gold = goldById.getOrDefault(run.path("qa_id").asText(), "");
            String prompt = PROMPT.formatted(run.path("question").asText(), gold, run.path("answer").asText());
            OllamaClient.ChatResult res = ollama.chat(cfg.judgeModel(), null, prompt, 4096);
            ObjectNode json = Util.extractJson(res.content());
            String verdict = json == null ? "parse_error" : json.path("verdict").asText("parse_error").toLowerCase().strip();
            double score;
            switch (verdict) {
                case "correct" -> score = 1.0;
                case "partial" -> score = 0.5;
                case "incorrect" -> score = 0.0;
                default -> {
                    verdict = "parse_error";
                    score = -1.0;
                    parseErrors++;
                }
            }
            ObjectNode rec = Util.JSON.createObjectNode();
            rec.put("key", key);
            rec.put("config_id", run.path("config_id").asText());
            rec.put("qa_id", run.path("qa_id").asText());
            rec.put("verdict", verdict);
            rec.put("score", score);
            Util.appendJsonl(cfg.judgmentsFile(), rec);
            if (i % 20 == 0) System.out.println("  ..." + i + "/" + runs.size());
        }
        System.out.println("Суддівство завершено (записів: " + runs.size() + ", помилок парсингу: " + parseErrors + "). Наступна команда: report.");
    }
}
