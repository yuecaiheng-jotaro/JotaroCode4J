package org.jotaro.rag.web;

import org.jotaro.rag.index.KnowledgeService;
import org.jotaro.rag.retrieval.HybridSearch;
import org.jotaro.rag.retrieval.RetrievalHit;
import org.jotaro.rag.retrieval.RetrievalQuery;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库管理与混合检索 REST 控制器。
 * <p>
 * 同时服务于两类调用方：
 * <ul>
 *   <li>知识库 Web 管理控制台（查看检索路由、管理知识库与文档、预览分段、重切分、执行检索与 Explain 诊断）；</li>
 *   <li>Agent 模块的远程知识库检索工具客户端（调用 {@code /api/bases} 与 {@code /api/search}）。</li>
 * </ul>
 */
@RestController
public class RagController {

    /** 知识库与文档入库管理服务 */
    @Resource
    private KnowledgeService knowledge;
    /** 多路混合检索编排服务 */
    @Resource
    private HybridSearch search;

    /**
     * 查询所有已注册检索通路的名称及启用状态。
     */
    @GetMapping("/api/routes")
    public List<HybridSearch.RouteStatus> routes() {
        return search.routes();
    }

    /**
     * 获取所有知识库列表及其文档/分段统计信息。
     */
    @GetMapping("/api/bases")
    public List<KnowledgeService.BaseView> bases() {
        return knowledge.bases();
    }

    /**
     * 创建新的知识库。
     */
    @PostMapping("/api/bases")
    public void createBase(@RequestBody BaseRequest request) {
        knowledge.createBase(request.name(), request.description());
    }

    /**
     * 按名称删除指定知识库及其下所有文档和分段。
     */
    @DeleteMapping("/api/bases/{name}")
    public void deleteBase(@PathVariable String name) {
        knowledge.deleteBase(name);
    }

    /**
     * 查询指定知识库下的文档列表。
     */
    @GetMapping("/api/bases/{name}/documents")
    public List<KnowledgeService.DocumentView> documents(@PathVariable String name) {
        return knowledge.documents(name);
    }

    /**
     * 查询单篇文档的完整原文与分段详情。
     */
    @GetMapping("/api/documents/{id}")
    public KnowledgeService.DocumentDetail detail(@PathVariable String id) {
        return knowledge.detail(id);
    }

    /**
     * 向指定知识库导入新文档并按配置切分入库。
     */
    @PostMapping("/api/bases/{name}/documents")
    public KnowledgeService.DocumentView ingest(@PathVariable String name, @RequestBody IngestRequest request) {
        return knowledge.ingest(
                name, request.title(), request.content(), request.source(),
                request.chunkSize(), request.chunkOverlap(), request.mode(), request.delimiter()
        );
    }

    /**
     * 按新的切分参数对已入库文档重新分段。
     */
    @PostMapping("/api/documents/{id}/rechunk")
    public KnowledgeService.DocumentDetail rechunk(@PathVariable String id, @RequestBody ChunkRequest request) {
        return knowledge.rechunk(id, request.chunkSize(), request.chunkOverlap(), request.mode(), request.delimiter());
    }

    /**
     * 删除指定文档及其分段。
     */
    @DeleteMapping("/api/documents/{id}")
    public void delete(@PathVariable String id) {
        knowledge.deleteDocument(id);
    }

    /**
     * 在不入库的情况下预览文档切分效果。
     */
    @PostMapping("/api/chunks/preview")
    public KnowledgeService.ChunkPreview preview(@RequestBody PreviewRequest request) {
        return knowledge.preview(request.content(), request.chunkSize(), request.chunkOverlap(), request.mode(), request.delimiter());
    }

    /**
     * 执行多路混合检索（单次最多返回 20 条命中）。
     */
    @PostMapping("/api/search")
    public SearchResponse search(@RequestBody SearchRequest request) {
        List<RetrievalHit> hits = search.search(query(request, 20));
        return new SearchResponse(hits);
    }

    /**
     * 执行带全阶段诊断信息的混合检索（单次最多返回 50 条命中），用于管理台展示各通路召回与 RRF 融合过程。
     */
    @PostMapping("/api/search/explain")
    public HybridSearch.Explanation explain(@RequestBody SearchRequest request) {
        return search.explain(query(request, 50));
    }

    /**
     * 校验并规范化检索请求参数，将返回条数限制在 {@code [1, max]} 区间内（未传时默认 5 条）。
     */
    private static RetrievalQuery query(SearchRequest request, int max) {
        if (request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("查询不能为空");
        }
        int limit = request.limit() == null ? 5 : Math.max(1, Math.min(request.limit(), max));
        String base = request.knowledgeBase() == null ? "" : request.knowledgeBase().trim();
        return new RetrievalQuery(request.query().trim(), base, limit);
    }

    /** 创建知识库请求体 */
    public record BaseRequest(String name, String description) {
    }

    /** 文档入库请求体 */
    public record IngestRequest(String title, String content, String source, Integer chunkSize, Integer chunkOverlap, String mode, String delimiter) {
    }

    /** 文档重新分段请求体 */
    public record ChunkRequest(Integer chunkSize, Integer chunkOverlap, String mode, String delimiter) {
    }

    /** 分段预览请求体 */
    public record PreviewRequest(String content, Integer chunkSize, Integer chunkOverlap, String mode, String delimiter) {
    }

    /** 检索与检索诊断请求体 */
    public record SearchRequest(String query, String knowledgeBase, Integer limit) {
    }

    /** 检索结果响应体 */
    public record SearchResponse(List<RetrievalHit> hits) {
    }
}
