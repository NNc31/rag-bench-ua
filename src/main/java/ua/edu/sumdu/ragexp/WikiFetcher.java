package ua.edu.sumdu.ragexp;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class WikiFetcher {
    private static final String API = "https://uk.wikipedia.org/w/api.php";
    private static final int MIN_CHARS = 3000;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final Config cfg;

    public WikiFetcher(Config cfg) { this.cfg = cfg; }

    public void run() throws IOException, InterruptedException {
        Path titlesFile = Path.of("corpus-titles.txt");
        if (!Files.exists(titlesFile)) {
            throw new IOException("Не знайдено corpus-titles.txt у поточній директорії");
        }
        Files.createDirectories(cfg.corpusDir());
        List<String> titles = Files.readAllLines(titlesFile, StandardCharsets.UTF_8).stream()
                .map(String::strip)
                .filter(t -> !t.isEmpty() && !t.startsWith("#"))
                .toList();
        int ok = 0, skipped = 0;
        for (String title : titles) {
            Path out = cfg.corpusDir().resolve(safeFileName(title) + ".txt");
            if (Files.exists(out)) {
                ok++;
                continue;
            }
            String extract = fetchExtract(title);
            if (extract == null || extract.length() < MIN_CHARS) {
                System.out.println("  ! Пропущено «" + title + "» (сторінки немає або текст закороткий)");
                skipped++;
            } else {
                Files.writeString(out, extract, StandardCharsets.UTF_8);
                System.out.println("  + «" + title + "»: " + extract.length() + " симв.");
                ok++;
            }
            Thread.sleep(300);
        }
        System.out.println("Корпус готовий: " + ok + " документів, пропущено " + skipped + ". Каталог: " + cfg.corpusDir());
        if (ok < 15) {
            System.out.println("! Документів менше 15 - додай назв у corpus-titles.txt для представнішого корпусу.");
        }
    }

    private String fetchExtract(String title) throws IOException, InterruptedException {
        String url = API + "?action=query&format=json&formatversion=2&prop=extracts&explaintext=1&redirects=1&titles="
                + URLEncoder.encode(title, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "RagUkrExperiment/0.1 (academic research)")
                .GET().build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() != 200) return null;
        JsonNode pages = Util.JSON.readTree(r.body()).path("query").path("pages");
        if (!pages.isArray() || pages.isEmpty()) return null;
        JsonNode page = pages.get(0);
        if (page.has("missing")) return null;
        String extract = page.path("extract").asText("");
        return extract.isBlank() ? null : extract;
    }

    static String safeFileName(String title) {
        return title.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }
}
