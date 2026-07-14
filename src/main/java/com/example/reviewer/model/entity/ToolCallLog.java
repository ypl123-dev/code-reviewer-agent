package com.example.reviewer.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 工具调用日志（面试展示亮点）
 */
@Data
@Entity
@Table(name = "tool_call_log")
public class ToolCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long reviewId;
    private String toolName;
    @Column(columnDefinition = "TEXT")
    private String toolInput;
    @Column(columnDefinition = "TEXT")
    private String toolOutput;
    private Long durationMs;
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
