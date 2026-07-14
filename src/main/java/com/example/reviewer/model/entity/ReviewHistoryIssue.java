package com.example.reviewer.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 历史代码评审问题记录
 */
@Data
@Entity
@Table(name = "review_history_issue")
public class ReviewHistoryIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String projectKey;
    private String title;
    @Column(columnDefinition = "TEXT")
    private String codeSnippet;
    private String issueType;
    @Column(columnDefinition = "TEXT")
    private String fixSolution;
    private String severity;
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
