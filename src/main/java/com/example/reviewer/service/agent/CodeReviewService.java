package com.example.reviewer.service.agent;

import com.example.reviewer.common.ReviewerException;
import com.example.reviewer.common.TokenCounter;
import com.example.reviewer.config.ReviewerProperties;
import com.example.reviewer.model.dto.DiffChunk;
import com.example.reviewer.model.dto.ReviewRequest;
import com.example.reviewer.model.dto.ReviewResult;
import com.example.reviewer.model.entity.CodeReviewRecord;
import com.example.reviewer.repository.CodeReviewRecordRepository;
import com.example.reviewer.service.diff.DiffChunker;
import com.example.reviewer.service.git.GitIntegrationService;
import com.example.reviewer.service.prompt.PromptBuilder;
import com.example.reviewer.service.ratelimit.RateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 代码审查 Agent 核心服务
 *
 * 流程：
 * 1. Webhook 触发 -> 投递到 RabbitMQ
 * 2. 消费消息 -> 拉取 MR diff
 * 3. DiffChunker 切分多个 chunk
 * 4. 对每个 chunk 调用 ChatClient（流式）
 * 5. 汇总结果，回写到 PR 评论
 *
 * 简历亮点：
 * - Advisor 链 + Function Calling + RAG 一体化
 * - Observation 监控 + 自定义指标
 * - 限流保护 LLM 调用
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeReviewService {

    private final ChatClient chatClient;
    private final GitIntegrationService gitIntegrationService;
    private final DiffChunker diffChunker;
    private final PromptBuilder promptBuilder;
    private final TokenCounter tokenCounter;
    private final RateLimiter rateLimiter;
    private final CodeReviewRecordRepository reviewRecordRepository;
    private final ReviewerProperties properties;
    private final MeterRegistry meterRegistry;

    /**
     * 对一个 MR 进行完整代码审查
     */
    public ReviewResult reviewMergeRequest(ReviewRequest request) {
        long start = System.currentTimeMillis();
        Timer.Sample timer = Timer.start(meterRegistry);

        String rateLimitKey = request.getProjectKey() + ":" + request.getMrIid();
        if (!rateLimiter.tryAcquire(rateLimitKey)) {
            throw new ReviewerException("触发限流，请稍后重试");
        }

        try {
            log.info("开始审查 MR: {}/{}", request.getProjectKey(), request.getMrIid());

            // 1. 拉取 diff
            String diff = gitIntegrationService.fetchMergeRequestDiff(
                    request.getProjectId(), request.getMrIid());

            // 2. 切分
            List<DiffChunk> chunks = diffChunker.chunk(diff);
            if (chunks.isEmpty()) {
                log.info("未检测到代码变更");
                return ReviewResult.builder()
                        .status("SUCCESS")
                        .content("本次提交未检测到代码变更")
                        .durationMs(System.currentTimeMillis() - start)
                        .build();
            }

            // 3. 逐 chunk 审查（同步聚合，便于后续回写）
            StringBuilder reviewContent = new StringBuilder();
            int totalTokens = 0;
            int maxFiles = properties.getReview().getMaxFilesPerReview();
            int processed = 0;

            for (DiffChunk chunk : chunks) {
                if (processed >= maxFiles) {
                    reviewContent.append("\n\n## 已达审查上限，剩余文件未审查\n");
                    break;
                }
                String review = reviewChunk(chunk, request);
                reviewContent.append(review).append("\n\n---\n\n");
                totalTokens += tokenCounter.count(review);
                processed++;
            }

            // 4. 持久化记录
            CodeReviewRecord record = new CodeReviewRecord();
            record.setProjectKey(request.getProjectKey());
            record.setMrIid(request.getMrIid());
            record.setCommitSha(request.getCommitSha());
            record.setFilePath("(multiple)");
            record.setReviewStatus("SUCCESS");
            record.setTotalTokens(totalTokens);
            record.setLlmCalls(processed);
            record.setDurationMs(System.currentTimeMillis() - start);
            record.setReviewResult(reviewContent.toString());
            reviewRecordRepository.save(record);

            // 5. 回写到 PR 评论
            try {
                gitIntegrationService.postPRComment(
                        request.getProjectId(),
                        request.getMrIid(),
                        formatComment(reviewContent.toString()));
            } catch (Exception e) {
                log.warn("回写 PR 评论失败（不影响审查结果）: {}", e.getMessage());
            }

            // 6. 指标上报
            meterRegistry.counter("review.success").increment();
            timer.stop(meterRegistry.timer("review.duration"));

            return ReviewResult.builder()
                    .reviewId(record.getId())
                    .content(reviewContent.toString())
                    .totalTokens(totalTokens)
                    .llmCalls(processed)
                    .durationMs(System.currentTimeMillis() - start)
                    .status("SUCCESS")
                    .build();
        } catch (Exception e) {
            meterRegistry.counter("review.failure").increment();
            log.error("代码审查失败: {}", e.getMessage(), e);
            return ReviewResult.builder()
                    .status("FAILED")
                    .errorMessage(e.getMessage())
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }
    }

    /**
     * 对单个 chunk 进行流式审查
     */
    public Flux<String> reviewChunkStream(DiffChunk chunk, String sessionId) {
        String prompt = promptBuilder.buildReviewPrompt(chunk);
        return chatClient.prompt()
                .user(prompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .stream()
                .content()
                .onErrorResume(e -> {
                    log.error("审查流式输出失败: {}", e.getMessage());
                    return Flux.just("\n[审查失败: " + e.getMessage() + "]\n");
                });
    }

    /**
     * 同步审查一个 chunk（用于完整 MR 流程）
     */
    private String reviewChunk(DiffChunk chunk, ReviewRequest request) {
        String prompt = promptBuilder.buildReviewPrompt(chunk);
        String sessionId = request.getProjectKey() + ":" + request.getMrIid() + ":" + chunk.filePath();
        try {
            return chatClient.prompt()
                    .user(prompt)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("审查 chunk 失败: file={}", chunk.filePath(), e);
            return "## " + chunk.filePath() + "\n审查失败：" + e.getMessage();
        }
    }

    /**
     * 同步审查一个 chunk（公开接口，供 WebhookController dev 模式调用）
     *
     * @param chunk     diff 分块
     * @param sessionId 会话 ID（用于 ChatMemory 隔离）
     * @return 审查结果文本
     */
    public String reviewChunkSync(DiffChunk chunk, String sessionId) {
        String prompt = promptBuilder.buildReviewPrompt(chunk);
        try {
            return chatClient.prompt()
                    .user(prompt)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("同步审查 chunk 失败: file={}", chunk.filePath(), e);
            return "## " + chunk.filePath() + "\n审查失败：" + e.getMessage();
        }
    }

    private String formatComment(String content) {
        return "## 🤖 CodeReviewer 自动审查结果\n\n" + content
                + "\n\n---\n*由 SpringAI + DeepSeek 驱动*";
    }
}
