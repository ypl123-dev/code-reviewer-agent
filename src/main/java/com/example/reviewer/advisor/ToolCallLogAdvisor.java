package com.example.reviewer.advisor;

import com.example.reviewer.model.entity.ToolCallLog;
import com.example.reviewer.repository.ToolCallLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Map;

/**
 * 工具调用日志 Advisor
 *
 * 职责：追踪 LLM 自主调用的 Function Calling 工具，记录到数据库
 *
 * 设计说明：
 * - 通过 Advisor 模式无侵入地采集工具调用链，业务代码无需感知
 * - 落库后可支撑"LLM 决策链路"的可视化与问题回溯
 */
@Slf4j
@RequiredArgsConstructor
public class ToolCallLogAdvisor implements BaseAdvisor {

    private final ToolCallLogRepository toolCallLogRepository;

    @Override
    public String getName() {
        return "ToolCallLogAdvisor";
    }

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        if (response == null || response.chatResponse() == null) {
            return response;
        }
        logToolCalls(response);
        return response;
    }

    private void logToolCalls(ChatClientResponse response) {
        try {
            ChatResponse chatResponse = response.chatResponse();
            List<Generation> generations = chatResponse.getResults();
            if (generations == null || generations.isEmpty()) {
                return;
            }

            for (Generation gen : generations) {
                if (gen.getOutput() == null) continue;
                AssistantMessage output = gen.getOutput();

                // 检查是否有工具调用
                List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();
                if (toolCalls == null || toolCalls.isEmpty()) {
                    continue;
                }

                for (AssistantMessage.ToolCall call : toolCalls) {
                    ToolCallLog logEntry = new ToolCallLog();
                    logEntry.setToolName(call.name());
                    logEntry.setToolInput(call.arguments());
                    logEntry.setToolOutput(output.getText());
                    toolCallLogRepository.save(logEntry);
                    log.info("[ToolCall] {} | args={}", call.name(), call.arguments());
                }
            }
        } catch (Exception e) {
            log.warn("记录工具调用日志失败: {}", e.getMessage());
        }
    }
}
