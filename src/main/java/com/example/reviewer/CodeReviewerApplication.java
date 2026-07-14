package com.example.reviewer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * CodeReviewer 智能代码审查 Agent 启动类
 *
 * 注意：PgVectorStoreAutoConfiguration 在 dev 模式下需要排除（无 PostgreSQL），
 * 但因为该类在 classpath 中（pom 引入了 starter），主类无法直接引用。
 * 通过 application-{profile}.yml 的 spring.autoconfigure.exclude 配置排除。
 *
 * @author code-reviewer
 */
@SpringBootApplication
@EnableAsync
public class CodeReviewerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodeReviewerApplication.class, args);
    }
}
