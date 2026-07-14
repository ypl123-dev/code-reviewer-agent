package com.example.reviewer.advisor;

import com.example.reviewer.common.TokenCounter;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

/**
 * Token 限制 Advisor
 *
 * 职责：在请求前估算 prompt token 数，超过限制则截断用户输入
 *
 * 设计动机：
 * - LLM 上下文窗口有限（DeepSeek 64K）
 * - 多轮对话历史累积可能超出限制
 * - 在 Advisor 链中前置处理，避免调用失败
 */
public class TokenLimitAdvisor implements BaseAdvisor {

    private final int maxTokens;
    private final TokenCounter tokenCounter;

    public TokenLimitAdvisor(int maxTokens, TokenCounter tokenCounter) {
        this.maxTokens = maxTokens;
        this.tokenCounter = tokenCounter;
    }

    @Override
    public String getName() {
        return "TokenLimitAdvisor";
    }

    @Override
    public int getOrder() {
        // 在 QuestionAnswerAdvisor 之后执行
        return 300;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        Prompt prompt = request.prompt();
        if (prompt == null) {
            return request;
        }
        String userText = prompt.getContents();
        if (userText == null) {
            return request;
        }
        int userTokens = tokenCounter.count(userText);
        if (userTokens <= maxTokens) {
            return request;
        }

        // 截断用户输入：保留头部，预留 20% 给响应
        int allowed = (int) (maxTokens * 0.8);
        String truncated = tokenCounter.truncate(userText, allowed)
                + "\n\n[注意：因 token 限制，输入已截断]";

        // 用 augmentUserMessage 构造新 Prompt，保留其他消息（系统/历史）
        Prompt newPrompt = prompt.augmentUserMessage(truncated);
        return request.mutate()
                .prompt(newPrompt)
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }
}
