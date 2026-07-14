package com.example.reviewer.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Data;

/**
 * 业务配置属性
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "code-reviewer")
public class ReviewerProperties {

    private GitConfig git = new GitConfig();
    private ReviewConfig review = new ReviewConfig();
    private RagConfig rag = new RagConfig();
    private RateLimitConfig ratelimit = new RateLimitConfig();

    @Data
    public static class GitConfig {
        private String platform = "gitea";
        private String baseUrl;
        private String token;
        private String webhookSecret;
    }

    @Data
    public static class ReviewConfig {
        private int maxTokenPerChunk = 4000;
        private int contextLines = 10;
        private int maxFilesPerReview = 30;
    }

    @Data
    public static class RagConfig {
        private int topK = 3;
        private int candidateCount = 10;
        private String rerankUrl;
        private boolean enabled = true;
    }

    @Data
    public static class RateLimitConfig {
        private int capacity = 10;
        private int rate = 2;
    }
}
