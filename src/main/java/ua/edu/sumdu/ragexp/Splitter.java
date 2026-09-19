package ua.edu.sumdu.ragexp;

import java.util.ArrayList;
import java.util.List;

public final class Splitter {

    public record Chunk(String docId, int ix, String text) {}

    private Splitter() {}

    public static List<Chunk> split(String docId, String text, int maxChars, int overlapChars) {
        List<String> units = new ArrayList<>();
        for (String para : text.split("\\n\\s*\\n")) {
            String p = para.strip();
            if (p.isEmpty()) continue;
            if (p.length() <= maxChars) {
                units.add(p);
            } else {
                units.addAll(splitLong(p, maxChars));
            }
        }

        List<Chunk> chunks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int ix = 0;
        for (String u : units) {
            if (buf.length() > 0 && buf.length() + u.length() + 1 > maxChars) {
                String chunkText = buf.toString().strip();
                if (!chunkText.isEmpty()) chunks.add(new Chunk(docId, ix++, chunkText));
                buf = new StringBuilder(tail(chunkText, overlapChars));
            }
            if (buf.length() > 0) buf.append('\n');
            buf.append(u);
        }
        String rest = buf.toString().strip();
        if (!rest.isEmpty()) chunks.add(new Chunk(docId, ix, rest));
        return chunks;
    }

    private static List<String> splitLong(String para, int maxChars) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String sentence : para.split("(?<=[.!?…])\\s+")) {
            if (sentence.length() > maxChars) {
                if (buf.length() > 0) {
                    out.add(buf.toString());
                    buf = new StringBuilder();
                }
                for (int i = 0; i < sentence.length(); i += maxChars) {
                    out.add(sentence.substring(i, Math.min(sentence.length(), i + maxChars)));
                }
                continue;
            }
            if (buf.length() + sentence.length() + 1 > maxChars && buf.length() > 0) {
                out.add(buf.toString());
                buf = new StringBuilder();
            }
            if (buf.length() > 0) buf.append(' ');
            buf.append(sentence);
        }
        if (buf.length() > 0) out.add(buf.toString());
        return out;
    }

    /** Хвіст тексту довжиною ~n символів, вирівняний по межі слова. */
    private static String tail(String s, int n) {
        if (n <= 0) return "";
        if (s.length() <= n) return s;
        String t = s.substring(s.length() - n);
        int sp = t.indexOf(' ');
        return sp >= 0 ? t.substring(sp + 1) : t;
    }
}
