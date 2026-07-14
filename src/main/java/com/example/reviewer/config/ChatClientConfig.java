package com.example.reviewer.config;

import com.example.reviewer.advisor.SensitivityFilterAdvisor;
import com.example.reviewer.advisor.TokenLimitAdvisor;
import com.example.reviewer.advisor.ToolCallLogAdvisor;
import com.example.reviewer.tool.CodeReviewTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * ChatClient 配置 - Advisor 责任链装配
 *
 * Advisor 执行顺序：
 *   请求 → SimpleLoggerAdvisor(基础日志)
 *        → ToolCallLogAdvisor(工具调用追踪)
 *        → MessageChatMemoryAdvisor(注入对话历史)
 *        → [QuestionAnswerAdvisor](RAG 检索，VectorStore 存在时启用)
 *        → SensitivityFilterAdvisor(敏感词过滤)
 *        → TokenLimitAdvisor(token 截断)
 *        → ChatModel 调用
 *        → 输出
 *
 * 设计点：VectorStore 可选（dev 模式无 PgVector 时仍能运行）
 */
@Configuration
public class ChatClientConfig {

    @Value("classpath:prompts/review-system.st")
    private Resource systemPromptResource;

    @Bean
    public ChatMemory chatMemory() {
        ChatMemoryRepository repository = new InMemoryChatMemoryRepository();
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(20)
                .build();
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                 ChatMemory chatMemory,
                                 ObjectProvider<VectorStore> vectorStoreProvider,
                                 CodeReviewTools codeReviewTools,
                                 SensitivityFilterAdvisor sensitivityFilterAdvisor,
                                 TokenLimitAdvisor tokenLimitAdvisor,
                                 ToolCallLogAdvisor toolCallLogAdvisor) throws IOException {

        String systemPrompt = systemPromptResource.getContentAsString(StandardCharsets.UTF_8);

        // 收集 Advisor 链
        List<Advisor> advisors = new ArrayList<>();
        advisors.add(new SimpleLoggerAdvisor());
        advisors.add(toolCallLogAdvisor);
        advisors.add(MessageChatMemoryAdvisor.builder(chatMemory).build());

        // RAG Advisor 仅在 VectorStore 存在时加入
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore != null) {
            advisors.add(QuestionAnswerAdvisor.builder(vectorStore).build());
        }

        advisors.add(sensitivityFilterAdvisor);
        advisors.add(tokenLimitAdvisor);

        return builder
                .defaultSystem(systemPrompt)
                .defaultAdvisors(advisors.toArray(new Advisor[0]))
                .defaultTools(codeReviewTools)
                .build();
    }
}
