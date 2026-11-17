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

/**
 * 知识库与文档生命周期管理服务。
 * <p>
 * 负责知识库的增删查、文档预览切分、文档入库（{@link #ingest}）、按新参数重新切分（{@link #rechunk}）以及文档删除。
 * 入库与重切分均在同一个数据库事务内完成：先写入或更新 {@code document} 主表记录，再将切分出的所有 {@code chunk} 分段写入子表，
 * 若启用了 {@link Embedder} 则同步生成并写入 pgvector 向量；任意一步失败时整篇文档与分段全部回滚。
 */
@Service
public class KnowledgeService {

    /** 默认分段最大字符数 */
    static final int DEFAULT_SIZE = 700;
    /** 默认相邻分段重叠字符数 */
    static final int DEFAULT_OVERLAP = 80;
    /** 允许配置的最小分段字符数 */
    private static final int MIN_SIZE = 200;
    /** 允许配置的最大分段字符数 */
    private static final int MAX_SIZE = 8000;
    /** 单篇文档允许入库的最大正文字符数（20 万字） */
    private static final int MAX_CONTENT = 200_000;

    /** 查询文档摘要列表的基础 SQL（关联知识库表并统计每篇文档的分段数与字符长度） */
    private static final String DOCUMENT_ROWS = """
            SELECT d.id::text AS id, d.title, d.source, count(c.id) AS chunks,
                   d.chunk_size, d.chunk_overlap, d.chunk_mode, d.chunk_delimiter,
                   length(d.content) AS chars,
                   to_char(d.created_at, 'YYYY-MM-DD HH24:MI') AS created_at
            FROM document d
            JOIN knowledge_base b ON b.id = d.base_id
            LEFT JOIN chunk c ON c.document_id = d.id
            """;

    /** 支持命名参数的 JDBC 模板，用于绝大多数 SQL 读写 */
    @Resource
    private NamedParameterJdbcTemplate jdbc;
    /** 标准 JDBC 模板，用于按主键删除等简单参数操作 */
    @Resource
    private JdbcTemplate template;
    /** 文本向量化器；开启时在入库和重新分段阶段为每个分段计算向量 */
    @Resource
    private Embedder embedder;

    /**
     * 查询所有知识库列表及其下属的文档数、总分段数和已生成向量的分段数。
     *
     * @return 按创建时间升序排列的知识库概览列表
     */
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

    /**
     * 创建新的知识库。名称只允许小写字母、数字和连字符，且不能与已有知识库重名。
     *
     * @param name        知识库唯一标识名称
     * @param description 知识库用途描述
     */
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

    /**
     * 根据名称删除指定知识库（数据库外键会自动级联删除其下的所有文档与分段）。
     *
     * @param name 知识库名称
     */
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

    /**
     * 查询指定知识库下的所有文档摘要列表，按创建时间倒序排列。
     *
     * @param base 知识库名称
     * @return 文档摘要视图列表
     */
    public List<DocumentView> documents(String base) {
        return jdbc.query(DOCUMENT_ROWS + """
                WHERE b.name = :name
                GROUP BY d.id
                ORDER BY d.created_at DESC
                """, new MapSqlParameterSource("name", normalizeName(base)), (rs, row) -> document(rs));
    }

    /**
     * 查询单篇文档的完整详情，包括原文、分段策略配置以及按序号排列的所有分段内容。
     *
     * @param documentId 文档 UUID 字符串
     * @return 文档详情及全部分段列表
     */
    public DocumentDetail detail(String documentId) {
        UUID id = parseId(documentId);
        // 先读取文档主表元数据与保存的全文
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
        // 再按 ordinal 顺序读取该文档切分出的全部 chunk
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

    /**
     * 按字数模式预览文档分段效果（不写库）。
     *
     * @param content      文档正文
     * @param chunkSize    目标分段字数（可为 null，默认 700）
     * @param chunkOverlap 相邻分段重叠字数（可为 null，默认 80）
     * @return 分段预览结果
     */
    public ChunkPreview preview(String content, Integer chunkSize, Integer chunkOverlap) {
        return preview(content, chunkSize, chunkOverlap, null, null);
    }

    /**
     * 按指定切分模式（字数或分隔符）预览文档分段效果（纯内存计算，不写库）。
     *
     * @param content      文档正文
     * @param chunkSize    分段字数（length 模式下生效）
     * @param chunkOverlap 重叠字数（length 模式下生效）
     * @param mode         分段模式：{@code length}（默认）或 {@code delimiter}
     * @param delimiter    字面分隔符（delimiter 模式下必填，支持 {@code \n} 等转义）
     * @return 包含分段参数与各段文本的预览结果
     */
    public ChunkPreview preview(String content, Integer chunkSize, Integer chunkOverlap, String mode, String delimiter) {
        String text = requireContent(content);
        ChunkPlan plan = plan(mode, chunkSize, chunkOverlap, delimiter);
        return previewOf(text, plan);
    }

    /**
     * 使用默认字数切分参数（700 字、80 字重叠）将文档入库。
     *
     * @param base    目标知识库名称
     * @param title   文档标题
     * @param content 文档正文
     * @param source  文档来源说明
     * @return 入库后的文档摘要视图
     */
    public DocumentView ingest(String base, String title, String content, String source) {
        return ingest(base, title, content, source, null, null, null, null);
    }

    /**
     * 使用指定的字数与重叠参数将文档切分并入库。
     * 文档和分段在同一事务写入，分段或向量写入失败时整篇回滚。
     *
     * @param base         目标知识库名称
     * @param title        文档标题
     * @param content      文档正文
     * @param source       文档来源说明
     * @param chunkSize    目标分段字数
     * @param chunkOverlap 相邻分段重叠字数
     * @return 入库后的文档摘要视图
     */
    // 文档和分段在同一事务写入，分段或向量写入失败时整篇回滚。
    @Transactional
    public DocumentView ingest(String base, String title, String content, String source, Integer chunkSize, Integer chunkOverlap) {
        return ingest(base, title, content, source, chunkSize, chunkOverlap, null, null);
    }

    /**
     * 完整参数的文档入库事务方法：校验参数与知识库存在性、写入 {@code document} 原文记录、切分并写入 {@code chunk} 分段（含可选向量）。
     *
     * @param base         目标知识库名称
     * @param title        文档标题（不能为空）
     * @param content      文档正文（不能为空且不超过 20 万字）
     * @param source       文档来源说明
     * @param chunkSize    分段长度（length 模式下生效）
     * @param chunkOverlap 分段重叠长度（length 模式下生效）
     * @param mode         切分模式：{@code length} 或 {@code delimiter}
     * @param delimiter    字面分隔符（delimiter 模式下生效）
     * @return 入库后的文档摘要视图
     */
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
        // 查找目标知识库主键 ID
        UUID baseId = jdbc.query("""
                SELECT id FROM knowledge_base WHERE name = :name
                """, new MapSqlParameterSource("name", baseName), rs -> rs.next() ? rs.getObject("id", UUID.class) : null);
        if (baseId == null) {
            throw new IllegalArgumentException("知识库不存在: " + baseName);
        }
        UUID documentId = UUID.randomUUID();
        // 第一步：写入文档主表，保存原始全文与本次使用的切分参数，支持后续重新分段
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
        // 第二步：按切分计划生成所有 chunk 并写入数据库（若开启向量则同步计算并更新向量列）
        writeChunks(baseId, documentId, text, plan);
        return loadView(documentId);
    }

    /**
     * 按新的字数与重叠参数对已有文档重新分段。
     *
     * @param documentId   文档 UUID 字符串
     * @param chunkSize    新的分段字数
     * @param chunkOverlap 新的重叠字数
     * @return 重新切分后的文档详情
     */
    @Transactional
    public DocumentDetail rechunk(String documentId, Integer chunkSize, Integer chunkOverlap) {
        return rechunk(documentId, chunkSize, chunkOverlap, null, null);
    }

    /**
     * 在单个事务内读取文档保存的原文，清空旧分段，按新的分段方案重新切分入库并更新文档上的分段元数据。
     *
     * @param documentId   文档 UUID 字符串
     * @param chunkSize    新的分段字数（length 模式下生效）
     * @param chunkOverlap 新的重叠字数（length 模式下生效）
     * @param mode         新的切分模式（{@code length} 或 {@code delimiter}）
     * @param delimiter    新的字面分隔符（delimiter 模式下生效）
     * @return 重新切分后的完整文档详情
     */
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
        // 先删除该文档原有的所有旧分段，再按新计划重新生成写入
        jdbc.update("DELETE FROM chunk WHERE document_id = :id", new MapSqlParameterSource("id", id));
        writeChunks((UUID) row[0], id, content, plan);
        // 同步更新文档表上记录的分段配置
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

    /**
     * 删除指定文档（其下属分段由数据库外键 {@code ON DELETE CASCADE} 自动级联清理）。
     *
     * @param documentId 文档 UUID 字符串
     */
    public void deleteDocument(String documentId) {
        UUID id = parseId(documentId);
        // 分段由数据库外键级联删除。
        int updated = template.update("DELETE FROM document WHERE id = ?", id);
        if (updated == 0) {
            throw new IllegalArgumentException("文档不存在");
        }
    }

    /**
     * 执行文档切分并将每个分段依次写入 {@code chunk} 表；若向量模型已开启，则为每段计算向量并更新 {@code embedding} 列。
     */
    private void writeChunks(UUID baseId, UUID documentId, String content, ChunkPlan plan) {
        List<String> chunks = plan.split(content);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("正文不能为空");
        }
        int ordinal = 0;
        for (String chunk : chunks) {
            UUID chunkId = UUID.randomUUID();
            // 写入分段文本，数据库触发器或生成列会自动维护全文检索向量 search_vector
            jdbc.update("""
                    INSERT INTO chunk (id, base_id, document_id, ordinal, content)
                    VALUES (:id, :baseId, :documentId, :ordinal, :content)
                    """, new MapSqlParameterSource()
                    .addValue("id", chunkId)
                    .addValue("baseId", baseId)
                    .addValue("documentId", documentId)
                    .addValue("ordinal", ordinal++)
                    .addValue("content", chunk));
            // 当配置开启了 Embedder 时，调用模型生成向量并转为 pgvector 文本格式写入
            if (embedder.enabled()) {
                jdbc.update("""
                        UPDATE chunk SET embedding = CAST(:embedding AS vector) WHERE id = :id
                        """, new MapSqlParameterSource()
                        .addValue("id", chunkId)
                        .addValue("embedding", PostgresVectors.of(embedder.embed(chunk))));
            }
        }
    }

    /**
     * 按文档主键重新加载聚合后的文档摘要视图。
     */
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

    /**
     * 根据切分方案对文本分段，并包装为带序号和字数的预览结果。
     */
    private static ChunkPreview previewOf(String text, ChunkPlan plan) {
        List<String> parts = plan.split(text);
        List<ChunkPiece> pieces = new ArrayList<>();
        for (int index = 0; index < parts.size(); index++) {
            pieces.add(new ChunkPiece(index, parts.get(index), parts.get(index).length()));
        }
        return new ChunkPreview(plan.size(), plan.overlap(), plan.mode(), plan.delimiter(), pieces);
    }

    /**
     * 校验正文非空且长度未超过 20 万字上限。
     */
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

    /**
     * 校验分段长度与重叠参数，并构造对应的 {@link Chunker} 实例。
     */
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

    /**
     * 解析并校验切分模式与参数，生成统一的内部切分计划 {@link ChunkPlan}。
     */
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

    /**
     * 将数据库查询行映射为 {@link DocumentView}。
     */
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

    /**
     * 校验并规范化知识库名称（仅限 1~63 位小写字母、数字和连字符，且首字符不能为连字符）。
     */
    static String normalizeName(String name) {
        if (name == null || !name.trim().matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("知识库名称只能用小写字母、数字和连字符");
        }
        return name.trim();
    }

    /**
     * 将字符串解析为 UUID，格式非法时抛出统一的中文提示异常。
     */
    private static UUID parseId(String documentId) {
        try {
            return UUID.fromString(documentId);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException("文档编号不合法");
        }
    }

    /**
     * 知识库概览视图。
     *
     * @param id          知识库 UUID
     * @param name        知识库名称
     * @param description 知识库描述
     * @param documents   包含的文档总数
     * @param chunks      包含的分段总数
     * @param embedded    已生成向量的分段数
     */
    public record BaseView(String id, String name, String description, int documents, int chunks, int embedded) {
    }

    /**
     * 文档列表摘要视图。
     *
     * @param id             文档 UUID
     * @param title          文档标题
     * @param source         文档来源
     * @param chunks         切分出的分段数量
     * @param chunkSize      分段长度配置
     * @param chunkOverlap   分段重叠长度配置
     * @param chunkMode      切分模式（{@code length} 或 {@code delimiter}）
     * @param chunkDelimiter 分隔符配置
     * @param chars          正文总字符数
     * @param createdAt      创建时间（格式化字符串）
     */
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

    /**
     * 单篇文档详情视图（含原文与全部分段列表）。
     *
     * @param id             文档 UUID
     * @param title          文档标题
     * @param source         文档来源
     * @param content        文档完整原文
     * @param chunkSize      分段长度配置
     * @param chunkOverlap   分段重叠长度配置
     * @param chunkMode      切分模式
     * @param chunkDelimiter 分隔符配置
     * @param createdAt      创建时间
     * @param chunks         按序号排列的分段视图列表
     */
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

    /**
     * 已入库的单个分段视图。
     *
     * @param id       分段 UUID
     * @param ordinal  分段在文档内的顺序号（从 0 开始）
     * @param content  分段文本内容
     * @param chars    分段字符数
     * @param embedded 是否已生成向量
     */
    public record ChunkView(String id, int ordinal, String content, int chars, boolean embedded) {
    }

    /**
     * 文档分段预览响应结果。
     *
     * @param chunkSize    分段长度
     * @param chunkOverlap 分段重叠长度
     * @param mode         切分模式
     * @param delimiter    实际生效的分隔符
     * @param chunks       预览切分出的各个片段
     */
    public record ChunkPreview(int chunkSize, int chunkOverlap, String mode, String delimiter, List<ChunkPiece> chunks) {
    }

    /**
     * 内部统一的切分计划封装，屏蔽按字数与按分隔符两种切分策略的调用差异。
     */
    private record ChunkPlan(String mode, int size, int overlap, String delimiter) {
        List<String> split(String text) {
            if ("delimiter".equals(mode)) {
                return Chunker.splitByDelimiter(text, delimiter);
            }
            return new Chunker(size, overlap).split(text);
        }
    }

    /**
     * 预览模式下的单个切分片段。
     *
     * @param ordinal 片段序号（从 0 开始）
     * @param content 片段文本内容
     * @param chars   片段字符数
     */
    public record ChunkPiece(int ordinal, String content, int chars) {
    }
}
