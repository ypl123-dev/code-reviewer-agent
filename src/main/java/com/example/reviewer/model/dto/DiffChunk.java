package com.example.reviewer.model.dto;

/**
 * Diff 切分后的代码块
 */
public record DiffChunk(
        String filePath,
        String functionName,
        String language,
        String contextBefore,  // 前 N 行
        String content,        // diff 内容
        String contextAfter,   // 后 N 行
        int estimatedTokens
) {
    public static DiffChunk empty(String filePath) {
        return new DiffChunk(filePath, "", "", "", "", "", 0);
    }
}
