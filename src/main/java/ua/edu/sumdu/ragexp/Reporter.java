package ua.edu.sumdu.ragexp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

public final class Reporter {
    private static final double HIT_THRESHOLD = 0.3;
    private static final int BOOTSTRAP_ITERATIONS = 10000;

    private final Config cfg;

    public Reporter(Config cfg) {
        this.cfg = cfg;
    }

    private static final class Agg {
        String embedding;
        int chunkSize;
        int k;
        int n = 0;
        int hits = 0;
        final List<Double> rr = new ArrayList<>();
        final List<Double> scores = new ArrayList<>();
        int notJudged = 0;
        final List<Long> tEmbed = new ArrayList<>();
        final List<Long> tSearch = new ArrayList<>();
        final List<Long> tGen = new ArrayList<>();
        final List<Long> tTotal = new ArrayList<>();
        final List<Long> outTokens = new ArrayList<>();
        double[] hitCI = {0, 0};
        double[] mrrCI = {0, 0};
        double[] sansCI = {0, 0};

        double hitRate() { return n == 0 ? 0 : (double) hits / n; }
        double mrr() { return Util.mean(rr); }
        double sAns() { return Util.mean(scores); }
    }

    public void run() throws IOException {
        List<ObjectNode> runs = Util.readJsonl(cfg.runsFile());
        if (runs.isEmpty()) throw new IllegalStateException("Немає " + cfg.runsFile() + " - спочатку команда run.");

        Map<String, String> goldChunkById = new HashMap<>();
        for (ObjectNode q : Util.readJsonl(cfg.qaFile())) {
            goldChunkById.put(q.path("qa_id").asText(), q.path("gold_chunk").asText());
        }
        Map<String, Double> scoreByKey = new HashMap<>();
        Map<String, String> verdictByKey = new HashMap<>();
        for (ObjectNode j : Util.readJsonl(cfg.judgmentsFile())) {
            scoreByKey.put(j.path("key").asText(), j.path("score").asDouble());
            verdictByKey.put(j.path("key").asText(), j.path("verdict").asText());
        }

        Map<String, Agg> byConfig = new LinkedHashMap<>();
        for (ObjectNode run : runs) {
            String configId = run.path("config_id").asText();
            Agg a = byConfig.computeIfAbsent(configId, x -> new Agg());
            a.embedding = run.path("embedding").asText();
            a.chunkSize = run.path("chunk_size").asInt();
            a.k = run.path("k").asInt();
            a.n++;

            String gold = goldChunkById.getOrDefault(run.path("qa_id").asText(), "");
            int rank = 0, pos = 0;
            for (JsonNode r : run.path("retrieved")) {
                pos++;
                if (Util.shingleContainment(gold, r.path("text").asText()) >= HIT_THRESHOLD) {
                    rank = pos;
                    break;
                }
            }
            if (rank > 0) {
                a.hits++;
                a.rr.add(1.0 / rank);
            } else {
                a.rr.add(0.0);
            }

            String key = configId + "::" + run.path("qa_id").asText();
            Double score = scoreByKey.get(key);
            if (score == null || score < 0 || "parse_error".equals(verdictByKey.get(key))) {
                a.notJudged++;
            } else {
                a.scores.add(score);
            }

            a.tEmbed.add(run.path("t_embed_ms").asLong());
            a.tSearch.add(run.path("t_search_ms").asLong());
            a.tGen.add(run.path("t_gen_ms").asLong());
            a.tTotal.add(run.path("t_total_ms").asLong());
            a.outTokens.add(run.path("out_tokens").asLong());
        }

        List<Agg> sorted = new ArrayList<>(byConfig.values());
        sorted.sort(Comparator.comparing((Agg a) -> a.embedding)
                .thenComparingInt(a -> a.chunkSize)
                .thenComparingInt(a -> a.k));

        Random rnd = new Random(cfg.seed());
        for (Agg a : sorted) {
            List<Double> hitsList = new ArrayList<>();
            for (double v : a.rr) hitsList.add(v > 0 ? 1.0 : 0.0);
            a.hitCI = bootstrapCI(hitsList, rnd);
            a.mrrCI = bootstrapCI(a.rr, rnd);
            a.sansCI = bootstrapCI(a.scores, rnd);
        }

        Files.createDirectories(cfg.resultsDir());
        writeCsv(sorted);
        writeSummary(sorted);
        System.out.println("Готово: " + cfg.resultsDir().resolve("results.csv") + " та " + cfg.resultsDir().resolve("summary.md"));
    }

    private void writeCsv(List<Agg> aggs) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("embedding,chunk_size,k,n,hit_at_k,mrr,answer_score,judged,not_judged,")
          .append("p50_embed_ms,p50_search_ms,p50_gen_ms,p50_total_ms,p95_total_ms,mean_out_tokens,")
          .append("hit_lo,hit_hi,mrr_lo,mrr_hi,sans_lo,sans_hi\n");
        for (Agg a : aggs) {
            sb.append(String.format(Locale.US, "%s,%d,%d,%d,%.4f,%.4f,%.4f,%d,%d,%.0f,%.0f,%.0f,%.0f,%.0f,%.1f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f%n",
                    a.embedding, a.chunkSize, a.k, a.n, a.hitRate(), a.mrr(), a.sAns(),
                    a.scores.size(), a.notJudged,
                    Util.percentile(a.tEmbed, 50), Util.percentile(a.tSearch, 50), Util.percentile(a.tGen, 50),
                    Util.percentile(a.tTotal, 50), Util.percentile(a.tTotal, 95),
                    Util.meanLong(a.outTokens),
                    a.hitCI[0], a.hitCI[1], a.mrrCI[0], a.mrrCI[1], a.sansCI[0], a.sansCI[1]));
        }
        Files.writeString(cfg.resultsDir().resolve("results.csv"), sb.toString(), StandardCharsets.UTF_8);
    }

    private static double[] bootstrapCI(List<Double> values, Random rnd) {
        if (values.isEmpty()) return new double[]{0, 0};
        int n = values.size();
        double[] means = new double[BOOTSTRAP_ITERATIONS];
        for (int it = 0; it < BOOTSTRAP_ITERATIONS; it++) {
            double sum = 0;
            for (int j = 0; j < n; j++) sum += values.get(rnd.nextInt(n));
            means[it] = sum / n;
        }
        Arrays.sort(means);
        int lo = (int) (BOOTSTRAP_ITERATIONS * 0.025);
        int hi = (int) (BOOTSTRAP_ITERATIONS * 0.975) - 1;
        return new double[]{means[lo], means[hi]};
    }

    private void writeSummary(List<Agg> aggs) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Результати експерименту\n\n");

        sb.append("## Таблиця 1. Характеристики корпусу\n\n");
        appendCorpusStats(sb);

        sb.append("\n## Таблиця 2. Результати за конфігураціями (у дужках - 95% довірчі інтервали)\n\n");
        sb.append("| Модель E_θ | σ, симв. | k | Hit@k [95% CI] | MRR | S_ans [95% CI] | L_p50, мс | L_p95, мс | Оцінено / не оцінено |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (Agg a : aggs) {
            sb.append(String.format(Locale.US, "| %s | %d | %d | %.3f [%.2f; %.2f] | %.3f | %.3f [%.2f; %.2f] | %.0f | %.0f | %d / %d |%n",
                    a.embedding, a.chunkSize, a.k,
                    a.hitRate(), a.hitCI[0], a.hitCI[1],
                    a.mrr(),
                    a.sAns(), a.sansCI[0], a.sansCI[1],
                    Util.percentile(a.tTotal, 50), Util.percentile(a.tTotal, 95),
                    a.scores.size(), a.notJudged));
        }

        sb.append("\n## Таблиця 3. Усереднення за моделями подань\n\n");
        sb.append("| Модель E_θ | Hit@k (сер.) | MRR (сер.) | S_ans (сер.) |\n|---|---|---|---|\n");
        Map<String, List<Agg>> byEmb = new LinkedHashMap<>();
        for (Agg a : aggs) byEmb.computeIfAbsent(a.embedding, x -> new ArrayList<>()).add(a);
        for (Map.Entry<String, List<Agg>> e : byEmb.entrySet()) {
            double hit = e.getValue().stream().mapToDouble(Agg::hitRate).average().orElse(0);
            double mrr = e.getValue().stream().mapToDouble(Agg::mrr).average().orElse(0);
            double sans = e.getValue().stream().mapToDouble(Agg::sAns).average().orElse(0);
            sb.append(String.format(Locale.US, "| %s | %.3f | %.3f | %.3f |%n", e.getKey(), hit, mrr, sans));
        }

        aggs.stream().max(Comparator.comparingDouble(Agg::sAns)).ifPresent(best -> sb.append(String.format(Locale.US,
                "%nНайкраща конфігурація за S_ans: **%s, σ=%d, k=%d** (S_ans=%.3f, Hit@k=%.3f, MRR=%.3f, L_p95=%.0f мс).%n",
                best.embedding, best.chunkSize, best.k, best.sAns(), best.hitRate(), best.mrr(),
                Util.percentile(best.tTotal, 95))));

        sb.append("\n## Деталізація затримок (p50, мс)\n\n");
        sb.append("| Конфігурація | Ембединг запиту | Пошук | Генерація | Разом | Токенів у відповіді (сер.) |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (Agg a : aggs) {
            sb.append(String.format(Locale.US, "| %s_%d_k%d | %.0f | %.0f | %.0f | %.0f | %.1f |%n",
                    a.embedding, a.chunkSize, a.k,
                    Util.percentile(a.tEmbed, 50), Util.percentile(a.tSearch, 50), Util.percentile(a.tGen, 50),
                    Util.percentile(a.tTotal, 50), Util.meanLong(a.outTokens)));
        }

        Files.writeString(cfg.resultsDir().resolve("summary.md"), sb.toString(), StandardCharsets.UTF_8);
    }

    private void appendCorpusStats(StringBuilder sb) throws IOException {
        if (!Files.isDirectory(cfg.corpusDir())) {
            sb.append("(корпус не знайдено)\n");
            return;
        }
        Map<String, String> corpus = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(cfg.corpusDir())) {
            for (Path f : files.filter(p -> p.toString().endsWith(".txt")).sorted().toList()) {
                corpus.put(f.getFileName().toString(), Files.readString(f, StandardCharsets.UTF_8));
            }
        }
        long totalChars = corpus.values().stream().mapToLong(String::length).sum();
        sb.append("| Показник | Значення |\n|---|---|\n");
        sb.append("| Кількість документів | ").append(corpus.size()).append(" |\n");
        sb.append(String.format(Locale.US, "| Загальний обсяг, тис. симв. | %.1f |%n", totalChars / 1000.0));
        for (int chunkSize : cfg.chunkSizes()) {
            int overlap = (int) Math.round(chunkSize * cfg.overlapRatio());
            int count = 0;
            for (Map.Entry<String, String> e : corpus.entrySet()) {
                count += Splitter.split(e.getKey(), e.getValue(), chunkSize, overlap).size();
            }
            sb.append("| Фрагментів за σ = ").append(chunkSize).append(" симв. | ").append(count).append(" |\n");
        }
    }
}
