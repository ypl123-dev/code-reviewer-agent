package com.example.reviewer.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.Collections;

/**
 * Dev 环境配置 - 零外部依赖运行
 *
 * - 不装配 VectorStore（DeepSeek 无 embedding API）
 * - 不装配 RabbitMQ（已通过 exclude 禁用）
 * - 不装配 Redis（已通过 exclude 禁用）
 * - RateLimiter 内置降级逻辑（Redis 不可用时自动放行）
 *
 * 启用方式：spring.profiles.active=dev
 *
 * 注意：dev 模式下 RAG 功能不可用，ChatClient 中的 QuestionAnswerAdvisor
 * 通过 ChatClientConfig 中的 @ConditionalOnBean 控制
 */
@Configuration
@Profile("dev")
public class DevConfig {
    // 占位类 - dev 不需要额外 Bean
    // VectorStore 缺失时，ChatClientConfig 会跳过 QuestionAnswerAdvisor
}
