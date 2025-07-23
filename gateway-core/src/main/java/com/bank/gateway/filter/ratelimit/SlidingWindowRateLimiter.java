package com.bank.gateway.filter.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component("slidingWindowRateLimiter")
public class SlidingWindowRateLimiter implements RateLimiter {

    private final StringRedisTemplate redisTemplate;

    public SlidingWindowRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean allowRequest(String key, RateLimitConfigService.LimitConfig config) {
        String redisKey = "sliding_window:" + key;
        long now = System.currentTimeMillis();
        long windowMillis = config.getWindow() * 1000L;
        long minTime = now - windowMillis;

        // 移除窗口外的请求
        redisTemplate.opsForZSet().removeRangeByScore(redisKey, 0, minTime);

        // 统计窗口内请求数
        Long count = redisTemplate.opsForZSet().zCard(redisKey);
        if (count != null && count < config.getThreshold()) {
            // 允许请求，记录本次
            redisTemplate.opsForZSet().add(redisKey, String.valueOf(now), now);
            redisTemplate.expire(redisKey, config.getWindow() * 2, TimeUnit.SECONDS);
            return true;
        } else {
            return false;
        }
    }
}
