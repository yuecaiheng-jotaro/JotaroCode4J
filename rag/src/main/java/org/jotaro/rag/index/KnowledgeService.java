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
import java.util.List;
import java.util.UUID;

@Service
public class KnowledgeService {

    private final Chunker chunker = new Chunker();

    @Resource
    private NamedParameterJdbcTemplate jdbc;
    @Resource
    private JdbcTemplate template;
    @Resource
    private Embedder embedder;

    public List<BaseView> bases() {
        return jdbc.query("""
                SELECT b.id::text AS id, b.name, b.description,
                       (SELECT count(*) FROM document d WHERE d.base_id = b.id) AS documents
                FROM knowledge_base b
                ORDER BY b.created_at
                """, (rs, row) -> new BaseView(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getInt("documents")
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

    public List<DocumentView> documents(String base) {
        return jdbc.query("""
                SELECT d.id::text AS id, d.title, d.source, count(c.id) AS chunks
                FROM document d
                JOIN knowledge_base b ON b.id = d.base_id
                LEFT JOIN chunk c ON c.document_id = d.id
                WHERE b.name = :name
                GROUP BY d.id
                ORDER BY d.created_at DESC
                """, new MapSqlParameterSource("name", normalizeName(base)), (rs, row) -> document(rs));
    }

    @Transactional
    public DocumentView ingest(String base, String title, String content, String source) {
        String baseName = normalizeName(base);
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("标题不能为空");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("正文不能为空");
        }
        if (content.length() > 200_000) {
            throw new IllegalArgumentException("正文超过 20 万字");
        }
        UUID baseId = jdbc.query("""
                SELECT id FROM knowledge_base WHERE name = :name
                """, new MapSqlParameterSource("name", baseName), rs -> rs.next() ? rs.getObject("id", UUID.class) : null);
        if (baseId == null) {
            throw new IllegalArgumentException("知识库不存在: " + baseName);
        }
        UUID documentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO document (id, base_id, title, source)
                VALUES (:id, :baseId, :title, :source)
                """, new MapSqlParameterSource()
                .addValue("id", documentId)
                .addValue("baseId", baseId)
                .addValue("title", title.strip())
                .addValue("source", source == null ? "" : source.strip()));
        List<String> chunks = chunker.split(content);
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
        return new DocumentView(documentId.toString(), title.strip(), source == null ? "" : source.strip(), chunks.size());
    }

    public void deleteDocument(String documentId) {
        UUID id = parseId(documentId);
        int updated = template.update("DELETE FROM document WHERE id = ?", id);
        if (updated == 0) {
            throw new IllegalArgumentException("文档不存在");
        }
    }

    private static DocumentView document(ResultSet rs) throws SQLException {
        return new DocumentView(rs.getString("id"), rs.getString("title"), rs.getString("source"), rs.getInt("chunks"));
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

    public record BaseView(String id, String name, String description, int documents) {
    }

    public record DocumentView(String id, String title, String source, int chunks) {
    }
}
