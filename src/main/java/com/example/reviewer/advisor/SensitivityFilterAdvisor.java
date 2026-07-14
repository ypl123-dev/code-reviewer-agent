package com.example.reviewer.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 敏感词过滤 Advisor
 *
 * 职责：在请求阶段过滤敏感词，避免敏感内容传给 LLM
 *
 * 设计动机：
 * - 代码 diff 中可能包含密码、密钥、个人信息
 * - 通过 Advisor 链统一处理，避免散落业务代码
 */
@Slf4j
public class SensitivityFilterAdvisor implements BaseAdvisor {

    private final List<Pattern> patterns;

    public SensitivityFilterAdvisor(List<String> regexPatterns) {
        this.patterns = regexPatterns != null
                ? regexPatterns.stream().map(Pattern::compile).toList()
                : List.of();
    }

    @Override
    public String getName() {
        return "SensitivityFilterAdvisor";
    }

    @Override
    public int getOrder() {
        return 200;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        Prompt prompt = request.prompt();
        if (prompt == null) return request;
        String text = prompt.getContents();
        if (text == null || patterns.isEmpty()) return request;

        String filtered = text;
        int totalReplaced = 0;
        for (Pattern p : patterns) {
            java.util.regex.Matcher m = p.matcher(filtered);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                m.appendReplacement(sb, "***REDACTED***");
                totalReplaced++;
            }
            m.appendTail(sb);
            filtered = sb.toString();
        }
        if (totalReplaced > 0) {
            log.info("敏感词过滤触发，共替换 {} 处", totalReplaced);
            Prompt newPrompt = prompt.augmentUserMessage(filtered);
            return request.mutate().prompt(newPrompt).build();
        }
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }
}
