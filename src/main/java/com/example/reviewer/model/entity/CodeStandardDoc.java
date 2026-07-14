package com.example.reviewer.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 团队代码规范文档实体（元数据，向量存储在 pgvector 表）
 */
@Data
@Entity
@Table(name = "code_standard_doc")
public class CodeStandardDoc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String category;   // 命名 / 异常 / 日志 / 并发 / 安全
    private String title;
    @Column(columnDefinition = "TEXT")
    private String content;
    private String docSource;
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
