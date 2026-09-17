package com.example.reviewer.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 工具调用日志
 *
 * 记录 LLM 每次 Function Calling 的工具名、入参、出参与耗时，
 * 用于回溯审查决策过程。
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
