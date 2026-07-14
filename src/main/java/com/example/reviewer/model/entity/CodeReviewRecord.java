package com.example.reviewer.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 代码审查记录实体
 */
@Data
@Entity
@Table(name = "code_review_record")
public class CodeReviewRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String projectKey;
    private Long mrIid;
    private String commitSha;
    private String filePath;
    private String reviewStatus; // PENDING / SUCCESS / FAILED
    private Integer totalTokens;
    private Integer llmCalls;
    private Integer toolCalls;
    private Long durationMs;

    @Column(columnDefinition = "TEXT")
    private String reviewResult;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.reviewStatus == null) this.reviewStatus = "PENDING";
        if (this.totalTokens == null) this.totalTokens = 0;
        if (this.llmCalls == null) this.llmCalls = 0;
        if (this.toolCalls == null) this.toolCalls = 0;
        if (this.durationMs == null) this.durationMs = 0L;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
