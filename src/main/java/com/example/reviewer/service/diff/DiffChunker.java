package com.example.reviewer.service.diff;

import com.example.reviewer.common.TokenCounter;
import com.example.reviewer.config.ReviewerProperties;
import com.example.reviewer.model.dto.DiffChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Diff 智能分块器
 *
 * 策略：
 * 1. 第一级 - 按文件切分（每个文件一个审查单元）
 * 2. 第二级 - 按 hunk 切分（@@ 标记）
 * 3. 第三级 - 滑动窗口（hunk 仍超长则切分）
 * 4. 上下文增强 - 附带文件路径 + 函数名 + 上下文 ±N 行
 *
 * 关键技术点（面试）：
 * - Token 估算使用 JTokkit (tiktoken Java 实现)
 * - 单次审查 token 控制在 maxTokenPerChunk 以内
 * - 上下文行数从配置读取
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiffChunker {

    private final TokenCounter tokenCounter;
    private final ReviewerProperties properties;

    // 匹配 unified diff 文件头: diff --git a/xxx b/xxx
    private static final Pattern FILE_HEADER = Pattern.compile("^diff --git a/(.+?) b/(.+)$");
    // 匹配 hunk 头: @@ -1,5 +1,7 @@
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@(.*)$");
    // 简单的函数名识别（Java）
    private static final Pattern JAVA_METHOD = Pattern.compile("(public|private|protected|static).*\\(.*\\)\\s*\\{?");

    /**
     * 将 unified diff 切分为多个 DiffChunk
     */
    public List<DiffChunk> chunk(String unifiedDiff) {
        if (unifiedDiff == null || unifiedDiff.isBlank()) {
            return List.of();
        }

        List<DiffChunk> chunks = new ArrayList<>();
        String[] lines = unifiedDiff.split("\n");

        String currentFile = null;
        StringBuilder currentHunk = new StringBuilder();
        String currentFuncName = "";
        List<String> contextBuffer = new ArrayList<>();
        int contextLines = properties.getReview().getContextLines();
        boolean inHunk = false;

        for (String line : lines) {
            Matcher fileMatcher = FILE_HEADER.matcher(line);
            if (fileMatcher.matches()) {
                // 切换文件前先保存上一个 hunk
                if (!currentHunk.isEmpty()) {
                    addChunk(chunks, currentFile, currentFuncName, currentHunk.toString(), contextBuffer);
                }
                currentFile = fileMatcher.group(1);
                currentHunk.setLength(0);
                contextBuffer.clear();
                currentFuncName = "";
                inHunk = false;
                continue;
            }

            Matcher hunkMatcher = HUNK_HEADER.matcher(line);
            if (hunkMatcher.matches()) {
                // 进入新 hunk
                if (!currentHunk.isEmpty()) {
                    addChunk(chunks, currentFile, currentFuncName, currentHunk.toString(), contextBuffer);
                }
                currentHunk.setLength(0);
                contextBuffer.clear();
                String hint = hunkMatcher.group(2).trim();
                if (!hint.isEmpty()) {
                    currentFuncName = hint;
                }
                inHunk = true;
                currentHunk.append(line).append('\n');
                continue;
            }

            // hunk 头之前的 git 元信息行（index/--- /+++ 等）不累积
            if (!inHunk) {
                continue;
            }

            // 累积上下文（保留最近 N 行 + 行信息）
            if (contextBuffer.size() >= contextLines) {
                contextBuffer.remove(0);
            }
            contextBuffer.add(line);

            // 识别函数名
            if (currentFuncName.isEmpty()) {
                Matcher methodMatcher = JAVA_METHOD.matcher(line);
                if (methodMatcher.find()) {
                    currentFuncName = extractMethodName(line);
                }
            }

            currentHunk.append(line).append('\n');
        }
        // 收尾
        if (!currentHunk.isEmpty()) {
            addChunk(chunks, currentFile, currentFuncName, currentHunk.toString(), contextBuffer);
        }

        // 容错：输入非 unified diff 格式（无 diff --git 头）时，整段当作代码片段审查
        if (chunks.isEmpty() && !unifiedDiff.isBlank()) {
            String content = unifiedDiff.trim();
            String lang = detectLanguage("snippet.java");
            int tokens = tokenCounter.count(content);
            int maxTokens = properties.getReview().getMaxTokenPerChunk();
            if (tokens > maxTokens) {
                chunks.addAll(slidingWindow("snippet.java", "snippet", lang, content, maxTokens));
            } else {
                chunks.add(new DiffChunk("snippet.java", "snippet", lang, "", content, "", tokens));
            }
            log.info("输入非 diff 格式，整段作为 snippet 审查 ({} tokens)", tokens);
        }

        log.info("Diff 切分完成: {} 个 chunk", chunks.size());
        return chunks;
    }

    private void addChunk(List<DiffChunk> chunks, String file, String funcName,
                          String content, List<String> context) {
        if (file == null || content.isBlank()) return;

        String language = detectLanguage(file);
        int tokens = tokenCounter.count(content);
        String contextBefore = joinFirst(context, properties.getReview().getContextLines());
        String contextAfter = joinLast(context, properties.getReview().getContextLines());

        // 如果单 chunk 超长，使用滑动窗口进一步切分
        int maxTokens = properties.getReview().getMaxTokenPerChunk();
        if (tokens > maxTokens) {
            List<DiffChunk> sliced = slidingWindow(file, funcName, language, content, maxTokens);
            chunks.addAll(sliced);
            return;
        }

        chunks.add(new DiffChunk(file, funcName, language, contextBefore, content, contextAfter, tokens));
    }

    /**
     * 滑动窗口切分 - 处理超长 hunk
     *
     * 切分规则：累积到当前行之前先判断「加入本行后是否会超限」，
     * 若会超限则先把已有内容作为一个 chunk 落盘，本行作为新块起点。
     *
     * 注意：早期实现在 append 之后再判断 >= maxTokens，会让单块最多超出
     * 一整行的 token 数（实测可出现 4005 > 4000），无法保证「单块不超过上限」的承诺。
     */
    private List<DiffChunk> slidingWindow(String file, String funcName, String language,
                                            String content, int maxTokens) {
        List<DiffChunk> result = new ArrayList<>();
        String[] lines = content.split("\n");
        StringBuilder buffer = new StringBuilder();
        int bufferTokens = 0;

        for (String line : lines) {
            String candidate = line + "\n";
            int candidateTokens = tokenCounter.count(candidate);

            // 单行本身就超限：独立成块，避免死循环与无限增长
            if (candidateTokens >= maxTokens) {
                flush(result, file, funcName, language, buffer);
                buffer.setLength(0);
                bufferTokens = 0;
                result.add(new DiffChunk(file, funcName, language, "", candidate, "", candidateTokens));
                continue;
            }

            // 加入本行会超限 -> 先落盘当前块
            if (bufferTokens + candidateTokens > maxTokens) {
                flush(result, file, funcName, language, buffer);
                buffer.setLength(0);
                bufferTokens = 0;
            }

            buffer.append(candidate);
            bufferTokens += candidateTokens;
        }

        flush(result, file, funcName, language, buffer);
        return result;
    }

    /** 将缓冲区内容作为一个 chunk 落盘（空缓冲则跳过） */
    private void flush(List<DiffChunk> result, String file, String funcName,
                       String language, StringBuilder buffer) {
        if (buffer.isEmpty()) {
            return;
        }
        String text = buffer.toString();
        result.add(new DiffChunk(file, funcName, language, "", text, "",
                tokenCounter.count(text)));
    }

    private String detectLanguage(String filePath) {
        if (filePath == null) return "text";
        if (filePath.endsWith(".java")) return "java";
        if (filePath.endsWith(".kt")) return "kotlin";
        if (filePath.endsWith(".py")) return "python";
        if (filePath.endsWith(".js") || filePath.endsWith(".mjs")) return "javascript";
        if (filePath.endsWith(".ts")) return "typescript";
        if (filePath.endsWith(".go")) return "go";
        if (filePath.endsWith(".xml")) return "xml";
        if (filePath.endsWith(".yml") || filePath.endsWith(".yaml")) return "yaml";
        return "text";
    }

    private String extractMethodName(String line) {
        String trimmed = line.trim();
        int parenIdx = trimmed.indexOf('(');
        if (parenIdx < 0) return "";
        int spaceIdx = trimmed.lastIndexOf(' ', parenIdx);
        if (spaceIdx < 0) return "";
        return trimmed.substring(spaceIdx + 1, parenIdx);
    }

    private String joinFirst(List<String> list, int n) {
        int limit = Math.min(n, list.size());
        return String.join("\n", list.subList(0, limit));
    }

    private String joinLast(List<String> list, int n) {
        int size = list.size();
        int limit = Math.min(n, size);
        return String.join("\n", list.subList(size - limit, size));
    }
}
