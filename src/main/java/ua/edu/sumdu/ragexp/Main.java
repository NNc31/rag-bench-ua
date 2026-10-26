package ua.edu.sumdu.ragexp;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public final class Main {

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));

        List<String> a = Arrays.asList(args);
        boolean quick = a.contains("--quick");
        int more = 0;
        int mi = a.indexOf("--more");
        if (mi >= 0 && mi + 1 < a.size()) more = Integer.parseInt(a.get(mi + 1));
        String cmd = "help";
        for (int i = 0; i < a.size(); i++) {
            if (mi >= 0 && i == mi + 1) continue;
            if (!a.get(i).startsWith("--")) {
                cmd = a.get(i);
                break;
            }
        }

        Config cfg = Config.load();
        OllamaClient ollama = new OllamaClient(cfg.ollamaUrl());

        switch (cmd) {
            case "fetch" -> new WikiFetcher(cfg).run();
            case "qa" -> new QaGenerator(cfg, ollama).run(more);
            case "prepare" -> {
                new WikiFetcher(cfg).run();
                new QaGenerator(cfg, ollama).run(0);
            }
            case "run" -> new ExperimentRunner(cfg, ollama, quick).run();
            case "judge" -> new Judge(cfg, ollama).run();
            case "report" -> new Reporter(cfg).run();
        }
    }
}
