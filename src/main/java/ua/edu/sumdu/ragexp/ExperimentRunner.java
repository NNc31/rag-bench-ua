package ua.edu.sumdu.ragexp;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Stream;

public final class ExperimentRunner {

    private static final String GEN_SYSTEM = """
            Ти - асистент корпоративної платформи даних. Відповідай українською мовою, стисло і по суті.
            Використовуй ЛИШЕ надану контекстну інформацію.
            Якщо відповіді в контексті немає, напиши: «У наданих даних відповіді немає».""";

    private final Config cfg;
    private final OllamaClient ollama;
    private final boolean quick;

    public ExperimentRunner(Config cfg, OllamaClient ollama, boolean quick) {
        this.cfg = cfg;
        this.ollama = ollama;
        this.quick = quick;
    }

    public void run() throws IOException, SQLException {
        List<ObjectNode> qa = Util.readJsonl(cfg.qaFile());
        if (qa.isEmpty()) throw new IllegalStateException("Немає " + cfg.qaFile() + " - спочатку команди fetch і qa.");

        List<String> embeddings = quick
                ? List.of(cfg.embeddingModels().get(cfg.embeddingModels().size() - 1))
                : cfg.embeddingModels();
        List<Integer> chunkSizes = quick ? List.of(1600) : cfg.chunkSizes();
        List<Integer> ks = quick ? List.of(3) : cfg.topKs();
        if (quick && qa.size() > 10) qa = qa.subList(0, 10);

        Set<String> done = new HashSet<>();
        for (ObjectNode r : Util.readJsonl(cfg.runsFile())) {
            done.add(r.path("config_id").asText() + "::" + r.path("qa_id").asText());
        }
        if (!done.isEmpty()) {
            System.out.println("Знайдено " + done.size() + " готових записів - їх буде пропущено (відновлення після перерви).");
        }

        Map<String, String> corpus = loadCorpus();
        System.out.println("Корпус: " + corpus.size() + " документів. Запитань: " + qa.size() + ". Конфігурацій: "
                + (embeddings.size() * chunkSizes.size() * ks.size()) + (quick ? " (режим --quick)" : "") + ".");

        try (Pg pg = new Pg(cfg)) {
            for (String emb : embeddings) {
                warmup(emb);
                for (int chunkSize : chunkSizes) {
                    String table = Pg.tableName(emb, chunkSize);
                    indexCorpus(pg, corpus, emb, chunkSize, table);
                    for (int k : ks) {
                        String configId = emb + "_" + chunkSize + "_k" + k;
                        System.out.println("=== Конфігурація " + configId + " ===");
                        long t0 = System.currentTimeMillis();
                        int i = 0;
                        for (ObjectNode item : qa) {
                            i++;
                            String key = configId + "::" + item.path("qa_id").asText();
                            if (done.contains(key)) continue;
                            runOne(pg, table, configId, emb, chunkSize, k, item);
                            System.out.println("  [" + i + "/" + qa.size() + "] " + item.path("qa_id").asText()
                                    + " (минуло " + (System.currentTimeMillis() - t0) / 1000 + " с)");
                        }
                    }
                }
            }
        }
        System.out.println("Прогін завершено. Сирі записи: " + cfg.runsFile() + ". Наступна команда: judge.");
    }

    private void warmup(String embeddingModel) {
        System.out.println("Прогрів моделей (" + embeddingModel + ", " + cfg.generatorModel() + ")...");
        ollama.embed(embeddingModel, List.of("прогрів"));
        ollama.chat(cfg.generatorModel(), null, "Скажи одне слово: готовий", 2048);
    }

    private Map<String, String> loadCorpus() throws IOException {
        Map<String, String> corpus = new LinkedHashMap<>();
        if (Files.isDirectory(cfg.corpusDir())) {
            try (Stream<Path> files = Files.list(cfg.corpusDir())) {
                for (Path f : files.filter(p -> p.toString().endsWith(".txt")).sorted().toList()) {
                    corpus.put(f.getFileName().toString().replaceFirst("\\.txt$", ""),
                            Files.readString(f, StandardCharsets.UTF_8));
                }
            }
        }
        if (corpus.isEmpty()) throw new IllegalStateException("Порожній корпус " + cfg.corpusDir() + " - виконай команду fetch.");
        return corpus;
    }

    private void indexCorpus(Pg pg, Map<String, String> corpus, String emb, int chunkSize, String table)
            throws SQLException {
        int overlap = (int) Math.round(chunkSize * cfg.overlapRatio());
        List<Splitter.Chunk> chunks = new ArrayList<>();
        for (Map.Entry<String, String> e : corpus.entrySet()) {
            chunks.addAll(Splitter.split(e.getKey(), e.getValue(), chunkSize, overlap));
        }
        System.out.println("Індексація " + table + ": " + chunks.size() + " фрагментів...");
        boolean first = true;
        for (int from = 0; from < chunks.size(); from += cfg.embedBatchSize()) {
            List<Splitter.Chunk> batch = chunks.subList(from, Math.min(chunks.size(), from + cfg.embedBatchSize()));
            List<float[]> vectors = ollama.embed(emb, batch.stream().map(Splitter.Chunk::text).toList());
            if (first) {
                pg.recreateTable(table, vectors.get(0).length);
                first = false;
            }
            pg.insertBatch(table, batch, vectors);
            if ((from / cfg.embedBatchSize()) % 10 == 0) {
                System.out.println("  ..." + Math.min(chunks.size(), from + cfg.embedBatchSize()) + "/" + chunks.size());
            }
        }
        pg.createHnswIndex(table);
    }

    private void runOne(Pg pg, String table, String configId, String emb, int chunkSize, int k, ObjectNode item)
            throws SQLException {
        String question = item.path("question").asText();

        long t0 = System.nanoTime();
        float[] qVec = ollama.embed(emb, List.of(question)).get(0);
        long tEmbed = (System.nanoTime() - t0) / 1_000_000;

        long t1 = System.nanoTime();
        List<Pg.Retrieved> retrieved = pg.search(table, qVec, k);
        long tSearch = (System.nanoTime() - t1) / 1_000_000;

        StringBuilder ctx = new StringBuilder();
        for (int i = 0; i < retrieved.size(); i++) {
            ctx.append("[Джерело ").append(i + 1).append(", документ: ").append(retrieved.get(i).docId()).append("]\n")
                    .append(retrieved.get(i).content()).append("\n\n");
        }
        String context = ctx.length() > cfg.contextMaxChars() ? ctx.substring(0, cfg.contextMaxChars()) : ctx.toString();
        String user = "Контекст:\n---\n" + context + "---\n\nЗапитання: " + question + "\nВідповідь:";

        long t2 = System.nanoTime();
        OllamaClient.ChatResult gen = ollama.chat(cfg.generatorModel(), GEN_SYSTEM, user, pickNumCtx(context.length()));
        long tGen = (System.nanoTime() - t2) / 1_000_000;

        ObjectNode rec = Util.JSON.createObjectNode();
        rec.put("config_id", configId);
        rec.put("embedding", emb);
        rec.put("chunk_size", chunkSize);
        rec.put("k", k);
        rec.put("qa_id", item.path("qa_id").asText());
        rec.put("question", question);
        rec.put("answer", gen.content());
        ArrayNode retr = rec.putArray("retrieved");
        for (Pg.Retrieved r : retrieved) {
            ObjectNode rn = retr.addObject();
            rn.put("doc_id", r.docId());
            rn.put("chunk_ix", r.chunkIx());
            rn.put("score", r.score());
            rn.put("text", r.content());
        }
        rec.put("t_embed_ms", tEmbed);
        rec.put("t_search_ms", tSearch);
        rec.put("t_gen_ms", tGen);
        rec.put("t_total_ms", tEmbed + tSearch + tGen);
        rec.put("prompt_tokens", gen.promptTokens());
        rec.put("out_tokens", gen.outTokens());
        try {
            Util.appendJsonl(cfg.runsFile(), rec);
        } catch (IOException e) {
            throw new RuntimeException("Не вдалося дописати " + cfg.runsFile(), e);
        }
    }

    private static int pickNumCtx(int contextChars) {
        int estimatedTokens = contextChars / 2 + 700;
        if (estimatedTokens <= 4096) return 4096;
        if (estimatedTokens <= 8192) return 8192;
        return 16384;
    }
}
