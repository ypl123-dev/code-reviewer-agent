package com.example.reviewer.controller;

import com.example.reviewer.model.entity.CodeStandardDoc;
import com.example.reviewer.repository.CodeStandardDocRepository;
import com.example.reviewer.repository.CodeReviewRecordRepository;
import com.example.reviewer.repository.ToolCallLogRepository;
import com.example.reviewer.service.git.GitIntegrationService;
import com.example.reviewer.service.rag.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理接口 - 知识库管理、健康检查、记录查询
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final CodeStandardDocRepository standardRepository;
    private final CodeReviewRecordRepository reviewRecordRepository;
    private final ToolCallLogRepository toolCallLogRepository;
    private final KnowledgeBaseService knowledgeBaseService;
    private final GitIntegrationService gitIntegrationService;

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "gitConnected", gitIntegrationService.ping()
        ));
    }

    /**
     * 添加团队规范文档
     */
    @PostMapping("/standards")
    public ResponseEntity<Map<String, Object>> addStandard(@RequestBody CodeStandardDoc doc) {
        CodeStandardDoc saved = standardRepository.save(doc);
        return ResponseEntity.ok(Map.of(
                "id", saved.getId(),
                "message", "规范添加成功"
        ));
    }

    /**
     * 触发知识库向量化索引
     */
    @PostMapping("/knowledge/index")
    public ResponseEntity<Map<String, Object>> indexKnowledge() {
        int count = knowledgeBaseService.indexStandards();
        return ResponseEntity.ok(Map.of(
                "indexedChunks", count,
                "message", "索引完成"
        ));
    }

    /**
     * 查询历史审查记录
     */
    @GetMapping("/reviews")
    public ResponseEntity<List<?>> listReviews(@RequestParam(required = false) String projectKey) {
        if (projectKey != null) {
            return ResponseEntity.ok(reviewRecordRepository.findAll().stream()
                    .filter(r -> projectKey.equals(r.getProjectKey()))
                    .toList());
        }
        return ResponseEntity.ok(reviewRecordRepository.findAll());
    }

    /**
     * 查询工具调用日志
     *
     * 支持按审查记录 ID 过滤，不传则返回全部。
     */
    @GetMapping("/tool-calls")
    public ResponseEntity<List<?>> listToolCalls(@RequestParam(required = false) Long reviewId) {
        if (reviewId != null) {
            return ResponseEntity.ok(toolCallLogRepository.findByReviewId(reviewId));
        }
        return ResponseEntity.ok(toolCallLogRepository.findAll());
    }

    /**
     * RAG 检索测试
     */
    @GetMapping("/rag/search")
    public ResponseEntity<Map<String, Object>> testRagSearch(@RequestParam String query) {
        List<String> results = knowledgeBaseService.retrieve(query);
        return ResponseEntity.ok(Map.of(
                "query", query,
                "results", results,
                "count", results.size()
        ));
    }
}
