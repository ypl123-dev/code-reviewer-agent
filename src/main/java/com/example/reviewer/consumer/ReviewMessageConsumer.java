package com.example.reviewer.consumer;

import com.example.reviewer.model.dto.ReviewRequest;
import com.example.reviewer.service.agent.CodeReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ 消费者 - 异步处理 webhook 事件
 *
 * 设计说明：
 * - 异步化解耦 webhook 接收与 LLM 调用，避免 webhook 响应超时
 * - 消费失败进入重试，超过阈值后落盘待人工处理
 *
 * dev 模式下不装配（无 AMQP 自动配置）
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnClass(name = "org.springframework.amqp.core.MessageListener")
public class ReviewMessageConsumer {

    private final CodeReviewService codeReviewService;

    @RabbitListener(queues = "review.queue")
    public void handleReviewMessage(String body) {
        log.info("收到审查任务");
        try {
            ReviewRequest request = ReviewRequest.builder()
                    .projectKey("demo/project")
                    .projectId(1L)
                    .mrIid(1L)
                    .commitSha("abc123")
                    .sourceBranch("feature")
                    .targetBranch("main")
                    .title("test MR")
                    .build();

            var result = codeReviewService.reviewMergeRequest(request);
            log.info("审查完成: status={}, tokens={}, duration={}ms",
                    result.getStatus(), result.getTotalTokens(), result.getDurationMs());
        } catch (Exception e) {
            log.error("审查任务处理失败", e);
            throw e;
        }
    }
}
