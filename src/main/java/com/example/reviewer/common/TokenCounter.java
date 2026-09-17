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
     * 将文本截断到指定 token 数以内
     *
     * 注意：JTokkit 的 encode(text, maxTokens) 取的是文本【前段】的 maxTokens 个 token，
     * 即保留头部、丢弃尾部。对代码审查场景，丢弃尾部意味着后半段代码不被审查，
     * 因此调用方应优先依赖 DiffChunker 的分块保证不超限，截断仅作为最后兜底。
     */
    public String truncate(String text, int maxTokens) {
        if (text == null) return "";
        if (count(text) <= maxTokens) return text;
        com.knuddels.jtokkit.api.EncodingResult result = encoding.encode(text, maxTokens);
        return encoding.decode(result.getTokens());
    }
}
