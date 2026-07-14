package com.example.reviewer.common;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.stereotype.Component;

/**
 * Token 计数工具 - 使用 tiktoken 算法
 * 用于估算 prompt 长度，避免超过模型上下文限制
 */
@Component
public class TokenCounter {

    private final EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
    private final Encoding encoding = registry.getEncoding(EncodingType.CL100K_BASE);

    /**
     * 估算文本的 token 数
     */
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }

    /**
     * 将文本截断到指定 token 数以内（保留尾部，模拟截断历史）
     */
    public String truncate(String text, int maxTokens) {
        if (text == null) return "";
        if (count(text) <= maxTokens) return text;
        com.knuddels.jtokkit.api.EncodingResult result = encoding.encode(text, maxTokens);
        return encoding.decode(result.getTokens());
    }
}
