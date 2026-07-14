package com.example.reviewer.model.dto;

/**
 * 切分后的文本块
 */
public record TextChunk(
        String content,
        int tokenCount,
        int index
) {}
