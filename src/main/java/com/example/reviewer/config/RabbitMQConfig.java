package com.example.reviewer.config;

import org.springframework.amqp.core.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置 - Webhook 异步处理
 *
 * 通过 @ConditionalOnClass 控制：
 * - 当 classpath 有 AMQP 类时装配（生产环境）
 * - dev 模式下通过 exclude 排除 AMQP 自动配置，本配置类也会跳过
 */
@Configuration
@ConditionalOnClass(name = "org.springframework.amqp.core.Queue")
public class RabbitMQConfig {

    public static final String REVIEW_QUEUE = "review.queue";
    public static final String REVIEW_EXCHANGE = "review.exchange";
    public static final String REVIEW_ROUTING_KEY = "review.mr";

    @Bean
    public DirectExchange reviewExchange() {
        return new DirectExchange(REVIEW_EXCHANGE, true, false);
    }

    @Bean
    public Queue reviewQueue() {
        return QueueBuilder.durable(REVIEW_QUEUE).build();
    }

    @Bean
    public Binding reviewBinding() {
        return BindingBuilder.bind(reviewQueue())
                .to(reviewExchange())
                .with(REVIEW_ROUTING_KEY);
    }
}
