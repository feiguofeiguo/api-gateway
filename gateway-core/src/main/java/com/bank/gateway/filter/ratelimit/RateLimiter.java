package com.bank.gateway.filter.ratelimit;

public interface RateLimiter {
    /**
     * @param key 限流key
     * @param limitConfig 限流参数
     * @return 是否允许通过
     */
    boolean allowRequest(String key, RateLimitConfigService.LimitConfig limitConfig);
}
