package org.jotaro.rag.index;

import org.jotaro.rag.embed.Embedder;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 维护知识库和文档，并在入库时按指定长度生成检索分段。 */
@Service
public class KnowledgeService {

    static final int DEFAULT_SIZE = 700;
    static final int DEFAULT_OVERLAP = 80;
    private static final int MIN_SIZE = 200;
    private static final int MAX_SIZE = 8000;
    private static final int MAX_CONTENT = 200_000;

    private static final String DOCUMENT_ROWS = """
            SELECT d.id::text AS id, d.title, d.source, count(c.id) AS chunks,
                   d.chunk_size, d.chunk_overlap, d.chunk_mode, d.chunk_delimiter,
                   length(d.content) AS chars,
                   to_char(d.created_at, 'YYYY-MM-DD HH24:MI') AS created_at
            FROM document d
            JOIN knowledge_base b ON b.id = d.base_id
            LEFT JOIN chunk c ON c.document_id = d.id
            """;

    @Resource
    private NamedParameterJdbcTemplate jdbc;
    @Resource
    private JdbcTemplate template;
    @Resource
    private Embedder embedder;

    public List<BaseView> bases() {
        return jdbc.query("""
                SELECT b.id::text AS id, b.name, b.description,
                       (SELECT count(*) FROM document d WHERE d.base_id = b.id) AS documents,
                       (SELECT count(*) FROM chunk c WHERE c.base_id = b.id) AS chunks,
                       (SELECT count(*) FROM chunk c WHERE c.base_id = b.id AND c.embedding IS NOT NULL) AS embedded
                FROM knowledge_base b
                ORDER BY b.created_at
                """, (rs, row) -> new BaseView(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getInt("documents"),
                rs.getInt("chunks"),
                rs.getInt("embedded")
        ));
    }

    public void createBase(String name, String description) {
        String normalized = normalizeName(name);
        Integer existing = jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_base WHERE name = :name",
                new MapSqlParameterSource("name", normalized),
                Integer.class
        );
        if (existing != null && existing > 0) {
            throw new IllegalArgumentException("知识库已存在: " + normalized);
        }
        jdbc.update("""
                INSERT INTO knowledge_base (id, name, description)
                VALUES (:id, :name, :description)
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("name", normalized)
                .addValue("description", description == null ? "" : description.strip()));
    }

    public void deleteBase(String name) {
        String normalized = normalizeName(name);
        int updated = jdbc.update(
                "DELETE FROM knowledge_base WHERE name = :name",
                new MapSqlParameterSource("name", normalized)
        );
        if (updated == 0) {
            throw new IllegalArgumentException("知识库不存在: " + normalized);
        }
    }

    public List<DocumentView> documents(String base) {
        return jdbc.query(DOCUMENT_ROWS + """
                WHERE b.name = :name
                GROUP BY d.id
                ORDER BY d.created_at DESC
                """, new MapSqlParameterSource("name", normalizeName(base)), (rs, row) -> document(rs));
    }

    public DocumentDetail detail(String documentId) {
        UUID id = parseId(documentId);
        DocumentDetail head = jdbc.query("""
                SELECT id::text AS id, title, source, content, chunk_size, chunk_overlap,
                       chunk_mode, chunk_delimiter,
                       to_char(created_at, 'YYYY-MM-DD HH24:MI') AS created_at
                FROM document
                WHERE id = :id
                """, new MapSqlParameterSource("id", id), rs -> {
            if (!rs.next()) {
                return null;
            }
            return new DocumentDetail(
                    rs.getString("id"),
                    rs.getString("title"),
                    rs.getString("source"),
                    rs.getString("content") == null ? "" : rs.getString("content"),
                    rs.getInt("chunk_size"),
                    rs.getInt("chunk_overlap"),
                    rs.getString("chunk_mode"),
                    rs.getString("chunk_delimiter") == null ? "" : rs.getString("chunk_delimiter"),
                    rs.getString("created_at"),
                    List.of()
            );
        });
        if (head == null) {
            throw new IllegalArgumentException("文档不存在");
        }
        List<ChunkView> chunks = jdbc.query("""
                SELECT id::text AS id, ordinal, content, length(content) AS chars,
                       embedding IS NOT NULL AS embedded
                FROM chunk
                WHERE document_id = :id
                ORDER BY ordinal
                """, new MapSqlParameterSource("id", id), (rs, row) -> new ChunkView(
                rs.getString("id"),
                rs.getInt("ordinal"),
                rs.getString("content"),
                rs.getInt("chars"),
                rs.getBoolean("embedded")
        ));
        return new DocumentDetail(
                head.id(), head.title(), head.source(), head.content(),
                head.chunkSize(), head.chunkOverlap(), head.chunkMode(), head.chunkDelimiter(),
                head.createdAt(), chunks
        );
    }

    public ChunkPreview preview(String content, Integer chunkSize, Integer chunkOverlap) {
        return preview(content, chunkSize, chunkOverlap, null, null);
    }

    public ChunkPreview preview(String content, Integer chunkSize, Integer chunkOverlap, String mode, String delimiter) {
        String text = requireContent(content);
        ChunkPlan plan = plan(mode, chunkSize, chunkOverlap, delimiter);
        return previewOf(text, plan);
    }

    public DocumentView ingest(String base, String title, String content, String source) {
        return ingest(base, title, content, source, null, null, null, null);
    }

    // 文档和分段在同一事务写入，分段或向量写入失败时整篇回滚。
    @Transactional
    public DocumentView ingest(String base, String title, String content, String source, Integer chunkSize, Integer chunkOverlap) {
        return ingest(base, title, content, source, chunkSize, chunkOverlap, null, null);
    }

    @Transactional
    public DocumentView ingest(
            String base, String title, String content, String source,
            Integer chunkSize, Integer chunkOverlap, String mode, String delimiter
    ) {
        String baseName = normalizeName(base);
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("标题不能为空");
        }
        String text = requireContent(content);
        ChunkPlan plan = plan(mode, chunkSize, chunkOverlap, delimiter);
        UUID baseId = jdbc.query("""
                SELECT id FROM knowledge_base WHERE name = :name
                """, new MapSqlParameterSource("name", baseName), rs -> rs.next() ? rs.getObject("id", UUID.class) : null);
        if (baseId == null) {
            throw new IllegalArgumentException("知识库不存在: " + baseName);
        }
        UUID documentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO document (id, base_id, title, source, content, chunk_size, chunk_overlap, chunk_mode, chunk_delimiter)
                VALUES (:id, :baseId, :title, :source, :content, :chunkSize, :chunkOverlap, :chunkMode, :chunkDelimiter)
                """, new MapSqlParameterSource()
                .addValue("id", documentId)
                .addValue("baseId", baseId)
                .addValue("title", title.strip())
                .addValue("source", source == null ? "" : source.strip())
                .addValue("content", text)
                .addValue("chunkSize", plan.size())
                .addValue("chunkOverlap", plan.overlap())
                .addValue("chunkMode", plan.mode())
                .addValue("chunkDelimiter", plan.delimiter()));
        writeChunks(baseId, documentId, text, plan);
        return loadView(documentId);
    }

    @Transactional
    public DocumentDetail rechunk(String documentId, Integer chunkSize, Integer chunkOverlap) {
        return rechunk(documentId, chunkSize, chunkOverlap, null, null);
    }

    @Transactional
    public DocumentDetail rechunk(String documentId, Integer chunkSize, Integer chunkOverlap, String mode, String delimiter) {
        UUID id = parseId(documentId);
        var row = jdbc.query("""
                SELECT base_id, content FROM document WHERE id = :id
                """, new MapSqlParameterSource("id", id), rs -> {
            if (!rs.next()) {
                return null;
            }
            return new Object[]{rs.getObject("base_id", UUID.class), rs.getString("content")};
        });
        if (row == null) {
            throw new IllegalArgumentException("文档不存在");
        }
        String content = row[1] == null ? "" : (String) row[1];
        if (content.isBlank()) {
            throw new IllegalArgumentException("这篇文档没有保存原文，不能重新分段");
        }
        ChunkPlan plan = plan(mode, chunkSize, chunkOverlap, delimiter);
        jdbc.update("DELETE FROM chunk WHERE document_id = :id", new MapSqlParameterSource("id", id));
        writeChunks((UUID) row[0], id, content, plan);
        jdbc.update("""
                UPDATE document
                SET chunk_size = :chunkSize, chunk_overlap = :chunkOverlap,
                    chunk_mode = :chunkMode, chunk_delimiter = :chunkDelimiter
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("chunkSize", plan.size())
                .addValue("chunkOverlap", plan.overlap())
                .addValue("chunkMode", plan.mode())
                .addValue("chunkDelimiter", plan.delimiter()));
        return detail(documentId);
    }

    public void deleteDocument(String documentId) {
        UUID id = parseId(documentId);
        // 分段由数据库外键级联删除。
        int updated = template.update("DELETE FROM document WHERE id = ?", id);
        if (updated == 0) {
            throw new IllegalArgumentException("文档不存在");
        }
    }

    private void writeChunks(UUID baseId, UUID documentId, String content, ChunkPlan plan) {
        List<String> chunks = plan.split(content);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("正文不能为空");
        }
        int ordinal = 0;
        for (String chunk : chunks) {
            UUID chunkId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO chunk (id, base_id, document_id, ordinal, content)
                    VALUES (:id, :baseId, :documentId, :ordinal, :content)
                    """, new MapSqlParameterSource()
                    .addValue("id", chunkId)
                    .addValue("baseId", baseId)
                    .addValue("documentId", documentId)
                    .addValue("ordinal", ordinal++)
                    .addValue("content", chunk));
            if (embedder.enabled()) {
                jdbc.update("""
                        UPDATE chunk SET embedding = CAST(:embedding AS vector) WHERE id = :id
                        """, new MapSqlParameterSource()
                        .addValue("id", chunkId)
                        .addValue("embedding", PostgresVectors.of(embedder.embed(chunk))));
            }
        }
    }

    private DocumentView loadView(UUID documentId) {
        List<DocumentView> rows = jdbc.query(DOCUMENT_ROWS + """
                WHERE d.id = :id
                GROUP BY d.id
                """, new MapSqlParameterSource("id", documentId), (rs, row) -> document(rs));
        if (rows.isEmpty()) {
            throw new IllegalStateException("文档写入后读不回来");
        }
        return rows.get(0);
    }

    private static ChunkPreview previewOf(String text, ChunkPlan plan) {
        List<String> parts = plan.split(text);
        List<ChunkPiece> pieces = new ArrayList<>();
        for (int index = 0; index < parts.size(); index++) {
            pieces.add(new ChunkPiece(index, parts.get(index), parts.get(index).length()));
        }
        return new ChunkPreview(plan.size(), plan.overlap(), plan.mode(), plan.delimiter(), pieces);
    }

    private static String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("正文不能为空");
        }
        String text = content.strip();
        if (text.length() > MAX_CONTENT) {
            throw new IllegalArgumentException("正文超过 20 万字");
        }
        return text;
    }

    private static Chunker chunker(Integer size, Integer overlap) {
        int resolvedSize = size == null ? DEFAULT_SIZE : size;
        int resolvedOverlap = overlap == null ? DEFAULT_OVERLAP : overlap;
        if (resolvedSize < MIN_SIZE || resolvedSize > MAX_SIZE) {
            throw new IllegalArgumentException("分段长度要在 200 到 8000 之间");
        }
        if (resolvedOverlap < 0 || resolvedOverlap > resolvedSize / 2) {
            throw new IllegalArgumentException("重叠不能超过分段长度的一半");
        }
        return new Chunker(resolvedSize, resolvedOverlap);
    }

    private static ChunkPlan plan(String mode, Integer size, Integer overlap, String delimiter) {
        if (mode == null || mode.isBlank() || "length".equals(mode)) {
            Chunker splitter = chunker(size, overlap);
            return new ChunkPlan("length", splitter.size(), splitter.overlap(), "");
        }
        if (!"delimiter".equals(mode)) {
            throw new IllegalArgumentException("分段方式只能是按字数或按分隔符");
        }
        String resolved = Chunker.unescape(delimiter == null ? "" : delimiter);
        if (resolved.isEmpty()) {
            throw new IllegalArgumentException("分隔符不能为空");
        }
        if (resolved.length() > 200) {
            throw new IllegalArgumentException("分隔符不能超过 200 个字符");
        }
        return new ChunkPlan("delimiter", 0, 0, resolved);
    }

    private static DocumentView document(ResultSet rs) throws SQLException {
        return new DocumentView(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("source"),
                rs.getInt("chunks"),
                rs.getInt("chunk_size"),
                rs.getInt("chunk_overlap"),
                rs.getString("chunk_mode"),
                rs.getString("chunk_delimiter") == null ? "" : rs.getString("chunk_delimiter"),
                rs.getInt("chars"),
                rs.getString("created_at")
        );
    }

    static String normalizeName(String name) {
        if (name == null || !name.trim().matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("知识库名称只能用小写字母、数字和连字符");
        }
        return name.trim();
    }

    private static UUID parseId(String documentId) {
        try {
            return UUID.fromString(documentId);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException("文档编号不合法");
        }
    }

    public record BaseView(String id, String name, String description, int documents, int chunks, int embedded) {
    }

    public record DocumentView(
            String id,
            String title,
            String source,
            int chunks,
            int chunkSize,
            int chunkOverlap,
            String chunkMode,
            String chunkDelimiter,
            int chars,
            String createdAt
    ) {
    }

    public record DocumentDetail(
            String id,
            String title,
            String source,
            String content,
            int chunkSize,
            int chunkOverlap,
            String chunkMode,
            String chunkDelimiter,
            String createdAt,
            List<ChunkView> chunks
    ) {
    }

    public record ChunkView(String id, int ordinal, String content, int chars, boolean embedded) {
    }

    public record ChunkPreview(int chunkSize, int chunkOverlap, String mode, String delimiter, List<ChunkPiece> chunks) {
    }

    private record ChunkPlan(String mode, int size, int overlap, String delimiter) {
        List<String> split(String text) {
            if ("delimiter".equals(mode)) {
                return Chunker.splitByDelimiter(text, delimiter);
            }
            return new Chunker(size, overlap).split(text);
        }
    }

    public record ChunkPiece(int ordinal, String content, int chars) {
    }
}
