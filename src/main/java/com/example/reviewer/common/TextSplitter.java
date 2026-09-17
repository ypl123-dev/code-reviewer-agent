package com.example.reviewer.common;

import com.example.reviewer.common.TokenCounter;
import com.example.reviewer.model.dto.TextChunk;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于令牌的文本切分器
 *
 * 替代 SpringAI 不同版本中 TokenTextSplitter 包路径不稳定的问题，
 * 自行实现一个简单的滑动窗口切分器，使用 JTokkit 进行 token 估算。
 *
 * 切分策略：
 * 1. 按段落（双换行）预切分
 * 2. 累积到目标大小后形成 chunk
 * 3. chunk 之间保留 overlap 行，避免上下文割裂
 *
 * 说明：自行实现而非依赖 SpringAI 的 TokenTextSplitter，
 * 是为了避免其在不同版本间包路径变更带来的兼容问题，
 * 同时便于按业务需要调整切分粒度与 overlap 策略。
 */
@Component
@RequiredArgsConstructor
public class TextSplitter {

    private final TokenCounter tokenCounter;

    /**
     * 切分文本
     *
     * @param text         原始文本
     * @param chunkSize    单个 chunk 目标 token 数（推荐 512）
     * @param overlap      chunk 之间重叠 token 数（推荐 100）
     * @return 切分后的 chunk 列表
     */
    public List<TextChunk> split(String text, int chunkSize, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<TextChunk> result = new ArrayList<>();

        // 按段落预切分（保留段落完整性）
        String[] paragraphs = text.split("\n\n+");

        StringBuilder buffer = new StringBuilder();
        int bufferTokens = 0;
        int chunkIndex = 0;

        for (String para : paragraphs) {
            String paragraph = para.strip();
            if (paragraph.isEmpty()) continue;

            int paraTokens = tokenCounter.count(paragraph);

            // 段落本身超过 chunkSize，按行再切
            if (paraTokens > chunkSize) {
                // 先把 buffer 输出
                if (bufferTokens > 0) {
                    result.add(new TextChunk(buffer.toString(), bufferTokens, chunkIndex++));
                    buffer.setLength(0);
                    bufferTokens = 0;
                }
                // 按行切大段落
                List<TextChunk> sliced = sliceLargeParagraph(paragraph, chunkSize, overlap, chunkIndex);
                result.addAll(sliced);
                chunkIndex += sliced.size();
                continue;
            }

            // 累积超过 chunkSize 则输出
            if (bufferTokens + paraTokens > chunkSize) {
                result.add(new TextChunk(buffer.toString(), bufferTokens, chunkIndex++));
                // 保留 overlap 部分
                String tail = tailByTokens(buffer.toString(), overlap);
                buffer.setLength(0);
                buffer.append(tail).append("\n\n").append(paragraph);
                bufferTokens = tokenCounter.count(tail) + paraTokens;
            } else {
                if (buffer.length() > 0) buffer.append("\n\n");
                buffer.append(paragraph);
                bufferTokens += paraTokens;
            }
        }
        if (bufferTokens > 0) {
            result.add(new TextChunk(buffer.toString(), bufferTokens, chunkIndex));
        }
        return result;
    }

    private List<TextChunk> sliceLargeParagraph(String paragraph, int chunkSize, int overlap, int startIndex) {
        List<TextChunk> chunks = new ArrayList<>();
        String[] lines = paragraph.split("\n");
        StringBuilder buf = new StringBuilder();
        int bufTokens = 0;
        int idx = startIndex;

        for (String line : lines) {
            int lineTokens = tokenCounter.count(line);
            if (bufTokens + lineTokens > chunkSize && bufTokens > 0) {
                chunks.add(new TextChunk(buf.toString(), bufTokens, idx++));
                String tail = tailByTokens(buf.toString(), overlap);
                buf.setLength(0);
                buf.append(tail).append("\n").append(line);
                bufTokens = tokenCounter.count(tail) + lineTokens;
            } else {
                if (buf.length() > 0) buf.append("\n");
                buf.append(line);
                bufTokens += lineTokens;
            }
        }
        if (bufTokens > 0) {
            chunks.add(new TextChunk(buf.toString(), bufTokens, idx));
        }
        return chunks;
    }

    private String tailByTokens(String text, int maxTokens) {
        if (maxTokens <= 0 || text == null) return "";
        if (tokenCounter.count(text) <= maxTokens) return text;
        // 取尾部 maxTokens 个 token 对应的文本（粗略：按字符近似）
        double ratio = (double) maxTokens / tokenCounter.count(text);
        int startChar = (int) (text.length() * (1 - ratio));
        return text.substring(Math.min(startChar, text.length()));
    }
}
