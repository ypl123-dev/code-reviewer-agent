package com.example.reviewer.service.ratelimit;

import com.example.reviewer.config.ReviewerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RateLimiter 本地降级路径单元测试
 *
 * Redis 不可用时（ObjectProvider 返回 null）应走本地令牌桶，
 * 验证容量耗尽与按速率恢复两个核心行为。
 */
class RateLimiterTest {

    private ObjectProvider<StringRedisTemplate> nullProvider;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        nullProvider = mock(ObjectProvider.class);
        // 模拟 Redis 不可用
        when(nullProvider.getIfAvailable()).thenReturn(null);
    }

    private RateLimiter limiter(int capacity, int rate) {
        ReviewerProperties properties = new ReviewerProperties();
        ReviewerProperties.RateLimitConfig config = new ReviewerProperties.RateLimitConfig();
        config.setCapacity(capacity);
        config.setRate(rate);
        properties.setRatelimit(config);
        return new RateLimiter(nullProvider, properties);
    }

    @Test
    @DisplayName("容量内请求全部放行")
    void allowsWithinCapacity() {
        RateLimiter limiter = limiter(5, 1);

        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("k1"), "第 " + (i + 1) + " 次应放行");
        }
    }

    @Test
    @DisplayName("超出容量后拒绝")
    void rejectsAfterCapacityExhausted() {
        RateLimiter limiter = limiter(3, 1);

        assertTrue(limiter.tryAcquire("k2"));
        assertTrue(limiter.tryAcquire("k2"));
        assertTrue(limiter.tryAcquire("k2"));
        assertFalse(limiter.tryAcquire("k2"), "容量耗尽后应拒绝");
    }

    @Test
    @DisplayName("不同 key 使用独立令牌桶")
    void isolatedBucketsPerKey() {
        RateLimiter limiter = limiter(2, 1);

        assertTrue(limiter.tryAcquire("a"));
        assertTrue(limiter.tryAcquire("a"));
        assertFalse(limiter.tryAcquire("a"), "key=a 已耗尽");

        assertTrue(limiter.tryAcquire("b"), "key=b 应有独立额度");
        assertTrue(limiter.tryAcquire("b"));
    }

    @Test
    @DisplayName("按速率随时间恢复令牌（秒级时间粒度）")
    void refillsOverTime() throws InterruptedException {
        // 注意：本地降级实现使用 System.currentTimeMillis()/1000 的秒级时间戳，
        // 恢复量 = elapsed(秒) * rate，因此测试需等待跨越 1 秒边界。
        RateLimiter limiter = limiter(2, 2);

        assertTrue(limiter.tryAcquire("slow"));
        assertTrue(limiter.tryAcquire("slow"));
        assertFalse(limiter.tryAcquire("slow"), "初始容量已耗尽");

        Thread.sleep(1200);
        assertTrue(limiter.tryAcquire("slow"), "跨越 1 秒后应按速率恢复令牌");
    }

    @Test
    @DisplayName("批量请求令牌不足时拒绝")
    void rejectsWhenRequestExceedsAvailable() {
        RateLimiter limiter = limiter(5, 1);

        assertTrue(limiter.tryAcquire("batch", 3));
        assertFalse(limiter.tryAcquire("batch", 3), "剩余 2 个令牌不足以满足 3 个请求");
        assertTrue(limiter.tryAcquire("batch", 2), "剩余令牌应仍可满足 2 个请求");
    }
}
