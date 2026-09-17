package com.example.reviewer.controller;

import com.example.reviewer.config.ReviewerProperties;
import com.example.reviewer.service.agent.CodeReviewService;
import com.example.reviewer.service.diff.DiffChunker;
import com.example.reviewer.service.git.GiteaService;
import com.example.reviewer.model.dto.DiffChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Webhook 接收 - 处理 Git 平台事件
 *
 * 关键设计：
 * 1. 接收 webhook 后立即返回 accepted（避免 Git 10s 超时）
 * 2. dev 模式：无 RabbitMQ，同步处理（拉 diff → 审查 → 回写评论）
 * 3. 生产模式：投递到 RabbitMQ，由消费者异步处理
 * 4. 通过 X-Gitlab-Token / X-Gitea-Signature 校验
 */
@Slf4j
@RestController
@RequestMapping("/api/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final ObjectProvider<RabbitTemplate> rabbitTemplateProvider;
    private final ObjectProvider<GiteaService> giteaServiceProvider;
    private final ObjectProvider<CodeReviewService> codeReviewServiceProvider;
    private final ObjectProvider<DiffChunker> diffChunkerProvider;
    private final ReviewerProperties properties;


    /**
     * Gitea Webhook 入口
     */
    @PostMapping("/gitea")
    public ResponseEntity<String> giteaWebhook(
            @RequestHeader(value = "X-Gitea-Signature", required = false) String signature,
            @RequestHeader(value = "X-Gitea-Event", required = false) String eventType,
            @RequestHeader(value = "X-Gitea-Token", required = false) String token,
            @RequestBody String body) {
        String secret = properties.getGit().getWebhookSecret();
        if (secret != null && !secret.isEmpty() && !secret.equals("dev-token")
                && !secret.equals(signature) && !secret.equals(token)) {
            log.warn("Gitea webhook 签名校验失败");
            return ResponseEntity.status(401).body("Unauthorized");
        }

        if (eventType != null && !"pull_request".equals(eventType)) {
            log.debug("忽略非 pull_request 事件: {}", eventType);
            return ResponseEntity.ok("ignored");
        }

        return handleEvent(body, "gitea");
    }

    private ResponseEntity<String> handleEvent(String body, String platform) {
        try {
            log.info("收到 webhook: platform={}, body 长度={}", platform, body.length());

            RabbitTemplate rabbitTemplate = rabbitTemplateProvider.getIfAvailable();
            if (rabbitTemplate != null) {
                // 生产模式：投递到 RabbitMQ 异步处理
                rabbitTemplate.convertAndSend("review.exchange", "review.mr", body);
                log.info("webhook 已投递到队列");
                return ResponseEntity.accepted().body("queued");
            }

            // dev 模式：同步处理（Gitea 真实接入路径）
            if ("gitea".equals(platform)) {
                return processGiteaEventSync(body);
            }

            log.info("dev 模式：非 Gitea 事件，仅记录");
            return ResponseEntity.accepted().body("accepted");
        } catch (Exception e) {
            log.error("处理 webhook 失败", e);
            return ResponseEntity.internalServerError().body("error: " + e.getMessage());
        }
    }

    /**
     * dev 模式同步处理 Gitea PR 事件
     * 流程：解析 payload → 拉 diff → 分块 → 审查 → 回写评论
     */
    private ResponseEntity<String> processGiteaEventSync(String payload) {
        GiteaService giteaService = giteaServiceProvider.getIfAvailable();
        if (giteaService == null) {
            return ResponseEntity.status(500).body("GiteaService 未启用");
        }

        // 1. 解析 webhook payload
        GiteaService.PullRequestInfo prInfo = giteaService.parsePullRequestPayload(payload);
        if (prInfo == null) {
            log.info("非 PR 事件或非 opened/updated action，跳过");
            return ResponseEntity.ok("skipped");
        }
        log.info("PR 事件: repo={}, #{}, action={}, title={}",
                prInfo.repoFullName(), prInfo.prNumber(), prInfo.action(), prInfo.title());

        // 2. 拉取 PR diff
        String diff = giteaService.fetchPrDiff(prInfo.repoFullName(), prInfo.prNumber());
        if (diff == null || diff.isBlank()) {
            return ResponseEntity.ok("empty diff");
        }

        // 3. 分块
        DiffChunker chunker = diffChunkerProvider.getIfAvailable();
        if (chunker == null) {
            return ResponseEntity.status(500).body("DiffChunker 未启用");
        }
        List<DiffChunk> chunks = chunker.chunk(diff);
        if (chunks.isEmpty()) {
            return ResponseEntity.ok("no chunks");
        }

        // 4. 同步审查并拼接结果
        CodeReviewService reviewService = codeReviewServiceProvider.getIfAvailable();
        if (reviewService == null) {
            return ResponseEntity.status(500).body("CodeReviewService 未启用");
        }

        StringBuilder report = new StringBuilder();
        report.append("## 🤖 CodeReviewer 智能审查报告\n\n");
        report.append(String.format("- **PR**: #%d %s\n", prInfo.prNumber(), prInfo.title()));
        report.append(String.format("- **分支**: %s → %s\n", prInfo.sourceBranch(), prInfo.targetBranch()));
        report.append(String.format("- **分块数**: %d\n\n", chunks.size()));

        for (int i = 0; i < chunks.size(); i++) {
            DiffChunk chunk = chunks.get(i);
            report.append(String.format("### 📄 %s (chunk %d/%d)\n\n",
                    chunk.filePath(), i + 1, chunks.size()));

            String reviewResult = reviewService.reviewChunkSync(chunk, "gitea-" + prInfo.prNumber());
            report.append(reviewResult).append("\n\n---\n\n");
        }
        report.append("_由 CodeReviewer Agent 自动生成_");

        // 5. 回写评论到 PR
        Long commentId = giteaService.postReviewComment(
                prInfo.repoFullName(), prInfo.prNumber(), report.toString());

        return ResponseEntity.ok(String.format(
                "reviewed: %d chunks, commentId=%d", chunks.size(), commentId));
    }
}
