package com.bank.gateway.filter.ratelimit.ratelimitImpl;

import com.bank.gateway.filter.ratelimit.LuaScriptManager;
import com.bank.gateway.filter.ratelimit.RateLimitConfigService;
import com.bank.gateway.filter.ratelimit.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component("tokenBucketRateLimiter")
public class TokenBucketRateLimiter implements RateLimiter {

    private final LuaScriptManager luaScriptManager;

    public TokenBucketRateLimiter(LuaScriptManager luaScriptManager) {
        this.luaScriptManager = luaScriptManager;
    }

    @Override
    public boolean allowRequest(String key, RateLimitConfigService.LimitConfig config) {
        long start = System.nanoTime();
        
        String redisKey = "rate_limit:" + key;
        long now = System.currentTimeMillis();
        int capacity = config.getTkbCapacity();
        int rate = config.getTkbRate();
        int expireSeconds = 2 * 60; // 2分钟过期时间

        // 使用Lua脚本执行令牌桶算法
        boolean result = luaScriptManager.executeTokenBucket(redisKey, now, capacity, rate, expireSeconds);
        
        long end = System.nanoTime();
        log.debug("【TokenBucketRateLimiter】总耗时: {} ns, 约 {} us", end - start, (end - start) / 1000.0);
        
        return result;
    }
}
