package com.example.reviewer.service.rag;

import com.example.reviewer.common.TextSplitter;
import com.example.reviewer.config.ReviewerProperties;
import com.example.reviewer.model.dto.TextChunk;
import com.example.reviewer.model.entity.CodeStandardDoc;
import com.example.reviewer.model.entity.ReviewHistoryIssue;
import com.example.reviewer.repository.CodeStandardDocRepository;
import com.example.reviewer.repository.ReviewHistoryIssueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库服务
 *
 * 职责：
 * 1. 团队规范文档的导入、切分、向量化
 * 2. 向量检索 + 重排序的 RAG 流程
 *
 * 关键技术点（面试）：
 * - 文档切分：自实现 TextSplitter (512 token / 100 overlap)
 * - 向量化：调用 SpringAI Embedding 模型
 * - 检索：向量相似度 Top N
 * - 重排：BGE-Reranker 二次排序
 *
 * 设计点：VectorStore 通过 ObjectProvider 注入，
 * dev 模式（无 PgVector）下检索方法降级返回空。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final RerankService rerankService;
    private final CodeStandardDocRepository standardRepository;
    private final ReviewHistoryIssueRepository issueRepository;
    private final ReviewerProperties properties;
    private final TextSplitter textSplitter;

    /**
     * 获取 VectorStore（可能为 null - dev 模式下）
     */
    private VectorStore getVectorStore() {
        return vectorStoreProvider.getIfAvailable();
    }

    /**
     * 导入规范文档资源（PDF/MD/Word）
     */
    public int importDocuments(List<Resource> resources) {
        VectorStore vs = getVectorStore();
        if (vs == null) {
            log.warn("VectorStore 未启用，无法导入文档（dev 模式）");
            return 0;
        }
        int total = 0;
        for (Resource resource : resources) {
            try {
                TikaDocumentReader reader = new TikaDocumentReader(resource);
                List<Document> docs = reader.get();

                int imported = 0;
                for (Document doc : docs) {
                    String content = doc.getText();
                    if (content == null || content.isBlank()) continue;

                    List<TextChunk> chunks = textSplitter.split(content, 512, 100);
                    for (TextChunk chunk : chunks) {
                        Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
                        metadata.put("source", resource.getFilename());
                        metadata.put("chunk_index", chunk.index());

                        Document chunkDoc = new Document(chunk.content(), metadata);
                        vs.add(List.of(chunkDoc));
                        imported++;
                    }
                }
                total += imported;
                log.info("导入文档 {} 切分为 {} 个 chunk", resource.getFilename(), imported);
            } catch (Exception e) {
                log.error("导入文档失败: {}", resource.getFilename(), e);
            }
        }
        log.info("知识库导入完成，共 {} 个 chunk", total);
        return total;
    }

    /**
     * RAG 检索：向量检索 + 重排序
     *
     * @param query 用户查询
     * @return Top K 相关文档
     */
    public List<String> retrieve(String query) {
        VectorStore vs = getVectorStore();
        if (vs == null) {
            log.debug("VectorStore 未启用，RAG 检索降级返回空");
            return Collections.emptyList();
        }
        int candidateCount = properties.getRag().getCandidateCount();
        int topK = properties.getRag().getTopK();

        List<Document> candidates = vs.similaritySearch(
                SearchRequest.builder().query(query).topK(candidateCount).build()
        );
        if (candidates == null || candidates.isEmpty()) {
            log.info("RAG 检索无候选: query={}", query);
            return List.of();
        }

        List<String> candidateTexts = candidates.stream()
                .map(Document::getText)
                .collect(Collectors.toList());

        List<String> reranked = rerankService.rerank(query, candidateTexts, topK);
        log.info("RAG 检索完成: 候选 {} -> Top {}", candidates.size(), reranked.size());
        return reranked;
    }

    /**
     * 检索历史类似 Issue
     */
    public List<ReviewHistoryIssue> searchSimilarIssues(String codeSnippet, int topK) {
        try {
            List<String> relevant = retrieve(codeSnippet);
            if (relevant.isEmpty()) {
                // 降级：返回最新的 topK 历史 Issue
                return issueRepository.findAll().stream().limit(topK).collect(Collectors.toList());
            }

            // 用文本反查历史 Issue
            List<ReviewHistoryIssue> matched = new ArrayList<>();
            List<ReviewHistoryIssue> all = issueRepository.findAll();
            for (String text : relevant) {
                all.stream()
                        .filter(i -> i.getTitle() != null
                                && text.contains(i.getTitle().substring(0, Math.min(20, i.getTitle().length()))))
                        .findFirst()
                        .ifPresent(matched::add);
            }
            return matched.stream().limit(topK).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("历史 Issue 检索失败，降级返回: {}", e.getMessage());
            return issueRepository.findAll().stream().limit(topK).collect(Collectors.toList());
        }
    }

    /**
     * 索引团队规范到向量库
     */
    public int indexStandards() {
        VectorStore vs = getVectorStore();
        if (vs == null) {
            log.warn("VectorStore 未启用，无法索引到向量库（dev 模式）");
            return 0;
        }
        List<CodeStandardDoc> docs = standardRepository.findAll();
        if (docs.isEmpty()) {
            log.info("无规范文档可索引");
            return 0;
        }

        List<Document> documents = new ArrayList<>();
        for (CodeStandardDoc d : docs) {
            String content = d.getCategory() + ": " + d.getTitle() + "\n" + d.getContent();
            List<TextChunk> chunks = textSplitter.split(content, 512, 100);
            for (TextChunk chunk : chunks) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("category", d.getCategory());
                metadata.put("title", d.getTitle());
                metadata.put("docId", d.getId());
                documents.add(new Document(chunk.content(), metadata));
            }
        }

        vs.add(documents);
        log.info("团队规范索引完成: {} 个 chunk", documents.size());
        return documents.size();
    }
}
