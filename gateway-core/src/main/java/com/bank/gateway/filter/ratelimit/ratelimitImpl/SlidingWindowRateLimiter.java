package com.bank.gateway.filter.ratelimit.ratelimitImpl;

import com.bank.gateway.filter.ratelimit.LuaScriptManager;
import com.bank.gateway.filter.ratelimit.RateLimitConfigService;
import com.bank.gateway.filter.ratelimit.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component("slidingWindowRateLimiter")
public class SlidingWindowRateLimiter implements RateLimiter {

    private final LuaScriptManager luaScriptManager;

    public SlidingWindowRateLimiter(LuaScriptManager luaScriptManager) {
        this.luaScriptManager = luaScriptManager;
    }

    @Override
    public boolean allowRequest(String key, RateLimitConfigService.LimitConfig config) {
        long start = System.nanoTime();
        
        String redisKey = "sliding_window:" + key;
        long now = System.currentTimeMillis();
        long windowMillis = config.getSlwWindow() * 1000L;
        int threshold = config.getSlwThreshold();
        int expireSeconds = config.getSlwWindow() * 2; // 窗口大小的2倍作为过期时间

        // 使用Lua脚本执行滑动窗口算法
        boolean result = luaScriptManager.executeSlidingWindow(redisKey, now, windowMillis, threshold, expireSeconds);
        
        long end = System.nanoTime();
        log.debug("【SlidingWindowRateLimiter】总耗时: {} ns, 约 {} us", end - start, (end - start) / 1000.0);
        
        return result;
    }
}
