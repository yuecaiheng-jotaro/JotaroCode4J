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

    @GetMapping("/api/bases/{name}/documents")
    public List<KnowledgeService.DocumentView> documents(@PathVariable String name) {
        return knowledge.documents(name);
    }

    @PostMapping("/api/bases/{name}/documents")
    public KnowledgeService.DocumentView ingest(@PathVariable String name, @RequestBody IngestRequest request) {
        return knowledge.ingest(name, request.title(), request.content(), request.source());
    }

    @DeleteMapping("/api/documents/{id}")
    public void delete(@PathVariable String id) {
        knowledge.deleteDocument(id);
    }

    @PostMapping("/api/search")
    public SearchResponse search(@RequestBody SearchRequest request) {
        if (request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("查询不能为空");
        }
        int limit = request.limit() == null ? 5 : Math.max(1, Math.min(request.limit(), 20));
        String base = request.knowledgeBase() == null ? "" : request.knowledgeBase().trim();
        List<RetrievalHit> hits = search.search(new RetrievalQuery(request.query().trim(), base, limit));
        return new SearchResponse(hits);
    }

    public record BaseRequest(String name, String description) {
    }

    public record IngestRequest(String title, String content, String source) {
    }

    public record SearchRequest(String query, String knowledgeBase, Integer limit) {
    }

    public record SearchResponse(List<RetrievalHit> hits) {
    }
}
