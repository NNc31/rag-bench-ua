package ua.edu.sumdu.ragexp;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public final class Pg implements AutoCloseable {
    private final Connection c;

    public Pg(Config cfg) throws SQLException {
        c = DriverManager.getConnection(cfg.dbUrl(), cfg.dbUser(), cfg.dbPass());
        exec("CREATE EXTENSION IF NOT EXISTS vector");
    }

    public record Retrieved(String docId, int chunkIx, String content, double score) {}

    public static String tableName(String embeddingModel, int chunkSize) {
        return "rag_" + embeddingModel.toLowerCase().replaceAll("[^a-z0-9]", "_") + "_" + chunkSize;
    }

    public void recreateTable(String table, int dim) throws SQLException {
        exec("DROP TABLE IF EXISTS " + table);
        exec("CREATE TABLE " + table + " (id serial PRIMARY KEY, doc_id text NOT NULL, chunk_ix int NOT NULL, " +
                "content text NOT NULL, embedding vector(" + dim + ") NOT NULL)");
    }

    public void insertBatch(String table, List<Splitter.Chunk> chunks, List<float[]> embeddings) throws SQLException {
        String sql = "INSERT INTO " + table + " (doc_id, chunk_ix, content, embedding) VALUES (?, ?, ?, ?::vector)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < chunks.size(); i++) {
                Splitter.Chunk ch = chunks.get(i);
                ps.setString(1, ch.docId());
                ps.setInt(2, ch.ix());
                ps.setString(3, ch.text());
                ps.setString(4, vectorLiteral(embeddings.get(i)));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public void createHnswIndex(String table) {
        try {
            exec("CREATE INDEX ON " + table + " USING hnsw (embedding vector_cosine_ops)");
        } catch (SQLException e) {
            System.out.println("  ! HNSW-індекс не створено (" + e.getMessage()
                    + ") - пошук повним переглядом, для наших обсягів це прийнятно.");
        }
    }

    public List<Retrieved> search(String table, float[] query, int k) throws SQLException {
        String vec = vectorLiteral(query);
        String sql = "SELECT doc_id, chunk_ix, content, 1 - (embedding <=> ?::vector) AS score FROM " + table + " ORDER BY embedding <=> ?::vector LIMIT ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, vec);
            ps.setString(2, vec);
            ps.setInt(3, k);
            try (ResultSet rs = ps.executeQuery()) {
                List<Retrieved> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new Retrieved(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getDouble(4)));
                }
                return out;
            }
        }
    }

    private void exec(String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static String vectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    @Override
    public void close() throws SQLException {
        c.close();
    }
}
