package com.bank.gateway.filter.ratelimit;

import com.alibaba.fastjson.JSON;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component("tokenBucketRateLimiter")
public class TokenBucketRateLimiter implements RateLimiter {

    private final StringRedisTemplate redisTemplate;

    public TokenBucketRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean allowRequest(String key, RateLimitConfigService.LimitConfig config) {
        String redisKey = "rate_limit:" + key;
        long now = System.currentTimeMillis();
        // 令牌桶状态结构
        Map<String, String> bucket = redisTemplate.<String, String>opsForHash().entries(redisKey);
        int capacity = config.getCapacity();
        int rate = config.getRate();
        int tokens = capacity;
        long lastRefillTime = now;

        if (!bucket.isEmpty()) {
            tokens = Integer.parseInt(bucket.getOrDefault("tokens", String.valueOf(capacity)));
            lastRefillTime = Long.parseLong(bucket.getOrDefault("lastRefillTime", String.valueOf(now)));
            // 补充令牌
            long delta = (now - lastRefillTime) / 1000;
            if (delta > 0) {
                int addTokens = (int) (delta * rate);
                tokens = Math.min(capacity, tokens + addTokens);
                lastRefillTime = now;
            }
        }
        if (tokens > 0) {
            tokens--;
            Map<String, String> newBucket = new HashMap<>();
            newBucket.put("tokens", String.valueOf(tokens));
            newBucket.put("lastRefillTime", String.valueOf(lastRefillTime));
            redisTemplate.opsForHash().putAll(redisKey, newBucket);
            redisTemplate.expire(redisKey, 2 * 60, java.util.concurrent.TimeUnit.SECONDS);
            return true;
        } else {
            // 没有令牌，限流
            Map<String, String> newBucket = new HashMap<>();
            newBucket.put("tokens", String.valueOf(tokens));
            newBucket.put("lastRefillTime", String.valueOf(lastRefillTime));
            redisTemplate.opsForHash().putAll(redisKey, newBucket);
            redisTemplate.expire(redisKey, 2 * 60, java.util.concurrent.TimeUnit.SECONDS);
            return false;
        }
    }
}
