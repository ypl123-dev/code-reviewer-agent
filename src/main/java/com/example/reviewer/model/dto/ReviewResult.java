package com.example.reviewer.model.dto;

import lombok.Builder;
import lombok.Data;

/**
 * LLM 审查结果
 */
@Data
@Builder
public class ReviewResult {

    private Long reviewId;
    private String filePath;
    private String content;        // LLM 输出
    private int totalTokens;
    private int llmCalls;
    private int toolCalls;
    private long durationMs;
    private String status;         // SUCCESS / FAILED
    private String errorMessage;
}
