package com.bank.gateway.filter.ratelimit.ratelimitImpl;

import com.bank.gateway.filter.ratelimit.LuaScriptManager;
import com.bank.gateway.filter.ratelimit.RateLimitConfigService;
import com.bank.gateway.filter.ratelimit.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;



import com.bank.gateway.filter.ratelimit.LuaScriptManager;
import com.bank.gateway.filter.ratelimit.RateLimitConfigService;
import com.bank.gateway.filter.ratelimit.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component("fixedWindowRateLimiter")
public class FixedWindowRateLimiter implements RateLimiter {

    private final LuaScriptManager luaScriptManager;

    public FixedWindowRateLimiter(LuaScriptManager luaScriptManager) {
        this.luaScriptManager = luaScriptManager;
    }

    @Override
    public boolean allowRequest(String key, RateLimitConfigService.LimitConfig config) {
        long start = System.nanoTime();

        String redisKey = "fixed_window:" + key;
        int threshold = config.getSlwThreshold(); // 名字虽然是 slw，但如果复用结构也 OK
        int windowSeconds = config.getSlwWindow(); // 传递 window 秒数

        boolean result = luaScriptManager.executeFixedWindow(redisKey, threshold, windowSeconds);

        long end = System.nanoTime();
        log.debug("【FixedWindowRateLimiter】总耗时: {} ns, 约 {} us", end - start, (end - start) / 1000.0);

        return result;
    }
}

