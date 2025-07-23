package com.bank.gateway.filter.ratelimit;

import com.alibaba.fastjson.JSON;
import lombok.Data;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class RateLimitConfigService {
    // 可从Nacos/DB/Redis动态加载，这里用本地Map模拟
    private static final Map<String, LimitConfig> configMap = new HashMap<>();
    static {
        configMap.put("order-service", new LimitConfig("token-bucket", 10, 20, 0, 0));
        configMap.put("user-service", new LimitConfig("sliding-window", 0, 0, 60, 100));
        //configMap.put("provider-a", new LimitConfig("sliding-window", 0, 0, 60, 1));
    }

    // 因为不同微服务的策略/参数不一致，所以这里需要根据微服务名获取对应策略/参数
    public LimitConfig getConfig(String serviceId) {
        return configMap.getOrDefault(serviceId, new LimitConfig("token-bucket", 5, 10, 0, 0));
    }

    @Data
    public static class LimitConfig {
        private String type; // token-bucket or sliding-window
        private int rate;    // 令牌桶每秒填充速率
        private int capacity;// 令牌桶容量
        private int window;  // 滑动窗口秒数
        private int threshold;// 滑动窗口最大请求数

        public LimitConfig(String type, int rate, int capacity, int window, int threshold) {
            this.type = type;
            this.rate = rate;
            this.capacity = capacity;
            this.window = window;
            this.threshold = threshold;
        }
    }
}
