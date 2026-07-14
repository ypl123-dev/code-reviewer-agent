package com.example.reviewer.service.prompt;

import com.example.reviewer.model.dto.DiffChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Prompt 模板构建器
 *
 * 使用模板文件 + 变量替换的方式构造 Prompt
 * 简历亮点：Prompt 工程化，避免硬编码
 */
@Service
public class PromptBuilder {

    @Value("classpath:prompts/review-user.st")
    private Resource userPromptResource;

    public String buildReviewPrompt(DiffChunk chunk) {
        String template = readResource(userPromptResource);
        Map<String, Object> vars = new HashMap<>();
        vars.put("filePath", chunk.filePath());
        vars.put("functionName", chunk.functionName().isEmpty() ? "未知函数" : chunk.functionName());
        vars.put("content", chunk.content());
        vars.put("language", chunk.language().isEmpty() ? "java" : chunk.language());
        vars.put("contextBefore", chunk.contextBefore().isEmpty() ? "(无)" : chunk.contextBefore());
        vars.put("contextAfter", chunk.contextAfter().isEmpty() ? "(无)" : chunk.contextAfter());

        // 简易变量替换，避免依赖 SpringAI 模板引擎版本差异
        String result = template;
        for (Map.Entry<String, Object> e : vars.entrySet()) {
            result = result.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
        }
        return result;
    }

    public String buildChatPrompt(String userMessage, String context) {
        StringBuilder sb = new StringBuilder();
        if (context != null && !context.isBlank()) {
            sb.append("以下是与本次问题相关的团队规范与历史经验：\n")
                    .append(context)
                    .append("\n\n---\n\n");
        }
        sb.append("用户问题：").append(userMessage);
        return sb.toString();
    }

    private String readResource(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取资源失败: " + resource, e);
        }
    }
}
