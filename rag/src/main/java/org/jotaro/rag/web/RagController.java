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

/** 提供知识库管理与检索 API，供管理页面和 Agent 的 HTTP 客户端使用。 */
@RestController
public class RagController {

    @Resource
    private KnowledgeService knowledge;
    @Resource
    private HybridSearch search;

    @GetMapping("/api/routes")
    public List<HybridSearch.RouteStatus> routes() {
        return search.routes();
    }

    @GetMapping("/api/bases")
    public List<KnowledgeService.BaseView> bases() {
        return knowledge.bases();
    }

    @PostMapping("/api/bases")
    public void createBase(@RequestBody BaseRequest request) {
        knowledge.createBase(request.name(), request.description());
    }

    @DeleteMapping("/api/bases/{name}")
    public void deleteBase(@PathVariable String name) {
        knowledge.deleteBase(name);
    }

    @GetMapping("/api/bases/{name}/documents")
    public List<KnowledgeService.DocumentView> documents(@PathVariable String name) {
        return knowledge.documents(name);
    }

    @GetMapping("/api/documents/{id}")
    public KnowledgeService.DocumentDetail detail(@PathVariable String id) {
        return knowledge.detail(id);
    }

    @PostMapping("/api/bases/{name}/documents")
    public KnowledgeService.DocumentView ingest(@PathVariable String name, @RequestBody IngestRequest request) {
        return knowledge.ingest(
                name, request.title(), request.content(), request.source(),
                request.chunkSize(), request.chunkOverlap(), request.mode(), request.delimiter()
        );
    }

    @PostMapping("/api/documents/{id}/rechunk")
    public KnowledgeService.DocumentDetail rechunk(@PathVariable String id, @RequestBody ChunkRequest request) {
        return knowledge.rechunk(id, request.chunkSize(), request.chunkOverlap(), request.mode(), request.delimiter());
    }

    @DeleteMapping("/api/documents/{id}")
    public void delete(@PathVariable String id) {
        knowledge.deleteDocument(id);
    }

    @PostMapping("/api/chunks/preview")
    public KnowledgeService.ChunkPreview preview(@RequestBody PreviewRequest request) {
        return knowledge.preview(request.content(), request.chunkSize(), request.chunkOverlap(), request.mode(), request.delimiter());
    }

    @PostMapping("/api/search")
    public SearchResponse search(@RequestBody SearchRequest request) {
        List<RetrievalHit> hits = search.search(query(request, 20));
        return new SearchResponse(hits);
    }

    @PostMapping("/api/search/explain")
    public HybridSearch.Explanation explain(@RequestBody SearchRequest request) {
        return search.explain(query(request, 50));
    }

    private static RetrievalQuery query(SearchRequest request, int max) {
        if (request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("查询不能为空");
        }
        int limit = request.limit() == null ? 5 : Math.max(1, Math.min(request.limit(), max));
        String base = request.knowledgeBase() == null ? "" : request.knowledgeBase().trim();
        return new RetrievalQuery(request.query().trim(), base, limit);
    }

    public record BaseRequest(String name, String description) {
    }

    public record IngestRequest(String title, String content, String source, Integer chunkSize, Integer chunkOverlap, String mode, String delimiter) {
    }

    public record ChunkRequest(Integer chunkSize, Integer chunkOverlap, String mode, String delimiter) {
    }

    public record PreviewRequest(String content, Integer chunkSize, Integer chunkOverlap, String mode, String delimiter) {
    }

    public record SearchRequest(String query, String knowledgeBase, Integer limit) {
    }

    public record SearchResponse(List<RetrievalHit> hits) {
    }
}
