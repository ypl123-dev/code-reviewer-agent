package com.example.reviewer.service.ratelimit;

import com.example.reviewer.config.ReviewerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis 令牌桶限流器
 *
 * 算法：令牌桶
 * - 容量 capacity：桶最大令牌数
 * - 速率 rate：每秒填充令牌数
 *
 * 实现：基于 Redis Lua 脚本保证原子性
 *
 * 简历亮点：
 * - Lua 脚本保证读-改-写原子性
 * - 分布式限流（多实例生效）
 * - 滑动窗口思想填充令牌
 *
 * 降级：Redis 不可用时使用本地 Map 实现（单实例限流）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimiter {

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final ReviewerProperties properties;

    /** 本地降级桶：key -> [tokens, lastRefillNanos] */
    private final Map<String, long[]> localBuckets = new ConcurrentHashMap<>();

    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])

            local bucket = redis.call('hmget', key, 'tokens', 'timestamp')
            local tokens = tonumber(bucket[1]) or capacity
            local last_time = tonumber(bucket[2]) or now

            -- 计算自上次请求以来应该填充的令牌
            local elapsed = math.max(0, now - last_time)
            tokens = math.min(capacity, tokens + elapsed * rate)

            if tokens >= requested then
                tokens = tokens - requested
                redis.call('hmset', key, 'tokens', tokens, 'timestamp', now)
                redis.call('expire', key, 3600)
                return 1
            else
                redis.call('hmset', key, 'tokens', tokens, 'timestamp', now)
                redis.call('expire', key, 3600)
                return 0
            end
            """;

    private final DefaultRedisScript<Long> script = new DefaultRedisScript<>(LUA_SCRIPT, Long.class);

    public boolean tryAcquire(String key) {
        return tryAcquire(key, 1);
    }

    public boolean tryAcquire(String key, int requestTokens) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            // dev 模式：用本地 Map 降级
            return tryAcquireLocal(key, requestTokens);
        }

        try {
            Long result = redisTemplate.execute(
                    script,
                    List.of("rate_limit:" + key),
                    String.valueOf(properties.getRatelimit().getCapacity()),
                    String.valueOf(properties.getRatelimit().getRate()),
                    String.valueOf(System.currentTimeMillis() / 1000),
                    String.valueOf(requestTokens)
            );
            boolean allowed = result != null && result == 1L;
            if (!allowed) {
                log.warn("限流触发: key={}", key);
            }
            return allowed;
        } catch (Exception e) {
            log.error("Redis 限流异常，降级放行: {}", e.getMessage());
            return true;
        }
    }

    /**
     * 本地降级实现（dev 模式）
     */
    private boolean tryAcquireLocal(String key, int requestTokens) {
        int capacity = properties.getRatelimit().getCapacity();
        int rate = properties.getRatelimit().getRate();
        long now = System.currentTimeMillis() / 1000;

        long[] bucket = localBuckets.compute(key, (k, v) -> {
            if (v == null) v = new long[]{capacity, now};
            long elapsed = Math.max(0, now - v[1]);
            v[0] = Math.min(capacity, v[0] + elapsed * rate);
            v[1] = now;
            return v;
        });

        if (bucket[0] >= requestTokens) {
            bucket[0] -= requestTokens;
            return true;
        }
        log.warn("本地限流触发: key={}", key);
        return false;
    }
}
