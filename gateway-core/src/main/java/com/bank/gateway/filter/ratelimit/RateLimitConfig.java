package com.bank.gateway.filter.ratelimit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.Map;

@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "ratelimit")
public class RateLimitConfig {
    private Map<String, RateLimitConfigService.LimitConfig> strategy;
}