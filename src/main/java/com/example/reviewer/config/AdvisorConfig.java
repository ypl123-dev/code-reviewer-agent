package com.example.reviewer.config;

import com.example.reviewer.advisor.SensitivityFilterAdvisor;
import com.example.reviewer.advisor.TokenLimitAdvisor;
import com.example.reviewer.advisor.ToolCallLogAdvisor;
import com.example.reviewer.common.TokenCounter;
import com.example.reviewer.config.ReviewerProperties;
import com.example.reviewer.repository.ToolCallLogRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Advisor 配置 - 装配自定义 Advisor
 */
@Configuration
public class AdvisorConfig {

    @Bean
    public TokenLimitAdvisor tokenLimitAdvisor(ReviewerProperties properties,
                                                 TokenCounter tokenCounter) {
        return new TokenLimitAdvisor(properties.getReview().getMaxTokenPerChunk(), tokenCounter);
    }

    @Bean
    public SensitivityFilterAdvisor sensitivityFilterAdvisor() {
        // 敏感词正则模式：API key、密码、手机号、身份证
        return new SensitivityFilterAdvisor(List.of(
                "(?i)(sk-[A-Za-z0-9]{20,})",                     // OpenAI 风格 key
                "(?i)(password\\s*=\\s*['\"][^'\"]+['\"])",        // 密码赋值
                "(1[3-9]\\d{9})",                                  // 手机号
                "([1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx])"  // 身份证
        ));
    }

    @Bean
    public ToolCallLogAdvisor toolCallLogAdvisor(ToolCallLogRepository repository) {
        return new ToolCallLogAdvisor(repository);
    }
}
