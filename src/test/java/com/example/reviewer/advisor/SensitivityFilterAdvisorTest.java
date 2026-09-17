package com.example.reviewer.advisor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SensitivityFilterAdvisor 单元测试
 *
 * 重点验证：过滤后 system message 结构不被破坏，仅 USER 消息被替换
 */
class SensitivityFilterAdvisorTest {

    private static final List<String> PATTERNS = List.of(
            "(?i)(sk-[A-Za-z0-9]{16,})",
            "(?i)(password\\s*=\\s*['\"][^'\"]+['\"])",
            "(1[3-9]\\d{9})"
    );

    private ChatClientRequest requestWith(Prompt prompt) {
        return ChatClientRequest.builder()
                .prompt(prompt)
                .context(Map.of())
                .build();
    }

    @Test
    @DisplayName("无敏感内容时原样返回")
    void noSensitiveContent() {
        var advisor = new SensitivityFilterAdvisor(PATTERNS);
        Prompt prompt = new Prompt("请审查这段代码：int a = 1;");
        ChatClientRequest request = requestWith(prompt);

        ChatClientRequest result = advisor.before(request, null);

        assertSame(request, result);
    }

    @Test
    @DisplayName("密码被替换为 REDACTED")
    void redactsPassword() {
        var advisor = new SensitivityFilterAdvisor(PATTERNS);
        Prompt prompt = new Prompt("这是一个赋值：password = \"secret123\" 请检查");
        ChatClientRequest request = requestWith(prompt);

        ChatClientRequest result = advisor.before(request, null);

        String text = result.prompt().getContents();
        assertFalse(text.contains("secret123"), "原始密码不应残留");
        assertTrue(text.contains("***REDACTED***"));
    }

    @Test
    @DisplayName("关键：system message 结构必须保留，不能被并入 user message")
    void preservesSystemMessageStructure() {
        var advisor = new SensitivityFilterAdvisor(PATTERNS);
        SystemMessage system = new SystemMessage("你是代码审查专家，只输出 JSON。");
        UserMessage user = new UserMessage("apiKey = sk-abcdefghijklmnopqrst 请审查");
        Prompt prompt = new Prompt(List.of(system, user));
        ChatClientRequest request = requestWith(prompt);

        ChatClientRequest result = advisor.before(request, null);
        List<Message> messages = result.prompt().getInstructions();

        assertEquals(2, messages.size(), "消息条数不应变化");
        assertEquals(MessageType.SYSTEM, messages.get(0).getMessageType(),
                "第 1 条仍应为 system message");
        assertEquals("你是代码审查专家，只输出 JSON。", messages.get(0).getText(),
                "system message 内容不应被修改");
        assertEquals(MessageType.USER, messages.get(1).getMessageType());
        assertFalse(messages.get(1).getText().contains("sk-abcdefghijklmnopqrst"));
        assertTrue(messages.get(1).getText().contains("***REDACTED***"));
    }

    @Test
    @DisplayName("空模式列表时不做任何处理")
    void emptyPatterns() {
        var advisor = new SensitivityFilterAdvisor(List.of());
        Prompt prompt = new Prompt("password = \"x\"");
        ChatClientRequest request = requestWith(prompt);

        assertSame(request, advisor.before(request, null));
    }

    @Test
    @DisplayName("order 为 200，早于 TokenLimitAdvisor(300)")
    void orderIsBeforeTokenLimit() {
        var advisor = new SensitivityFilterAdvisor(PATTERNS);
        assertEquals(200, advisor.getOrder());
    }
}
