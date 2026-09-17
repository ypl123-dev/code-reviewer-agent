package com.example.reviewer.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感词过滤 Advisor
 *
 * 职责：在请求阶段过滤敏感词，避免敏感内容传给 LLM
 *
 * 设计动机：
 * - 代码 diff 中可能包含密码、密钥、个人信息
 * - 通过 Advisor 链统一处理，避免散落业务代码
 *
 * 实现要点（关键）：
 * 只对 USER 类型的消息做替换，其余消息（system / 历史对话）原样保留。
 * 早期版本用 prompt.getContents() 取全文再整体塞回 user message，
 * 会破坏 system prompt 与多轮历史的结构，属于典型误用。
 */
@Slf4j
public class SensitivityFilterAdvisor implements BaseAdvisor {

    /** 请求阶段 order：需早于 TokenLimitAdvisor(300)，保证过滤先于截断 */
    private static final int ORDER = 200;

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
        return ORDER;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        Prompt prompt = request.prompt();
        if (prompt == null || patterns.isEmpty()) {
            return request;
        }

        List<Message> instructions = prompt.getInstructions();
        List<Message> replaced = new ArrayList<>(instructions.size());
        int totalReplaced = 0;
        boolean changed = false;

        for (Message message : instructions) {
            // 只处理用户消息，保留 system / assistant / tool 消息结构不变
            if (message.getMessageType() != MessageType.USER) {
                replaced.add(message);
                continue;
            }
            FilterOutcome outcome = redact(message.getText());
            totalReplaced += outcome.count();
            if (outcome.count() > 0) {
                changed = true;
                replaced.add(new UserMessage(outcome.text()));
            } else {
                replaced.add(message);
            }
        }

        if (!changed) {
            return request;
        }

        log.info("敏感词过滤触发，共替换 {} 处", totalReplaced);
        Prompt newPrompt = new Prompt(replaced, prompt.getOptions());
        return request.mutate().prompt(newPrompt).build();
    }

    /**
     * 对所有正则依次替换，返回替换后的文本与命中次数
     */
    private FilterOutcome redact(String text) {
        if (text == null || text.isEmpty()) {
            return new FilterOutcome(text, 0);
        }
        String filtered = text;
        int count = 0;
        for (Pattern p : patterns) {
            Matcher m = p.matcher(filtered);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement("***REDACTED***"));
                count++;
            }
            m.appendTail(sb);
            filtered = sb.toString();
        }
        return new FilterOutcome(filtered, count);
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    /** 单次过滤结果 */
    private record FilterOutcome(String text, int count) {
    }
}
