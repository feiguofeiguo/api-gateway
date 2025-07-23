package com.bank.gateway.filter.ratelimit;

import com.alibaba.fastjson.JSON;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class RateLimitConfigService {
    // 初始化时从yaml文件读取限流配置
    private static final Map<String, LimitConfig> configMap = new HashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public RateLimitConfigService(org.springframework.core.env.Environment environment) {
        log.debug("RateLimitConfigService init");
        String[] services = {"order-service", "user-service", "provider-a"};
        for (String service : services) {
            String prefix = "rate-limit." + service + ".";
            String typeStr = environment.getProperty(prefix + "type");
            RateLimitEnum type = typeStr != null ? RateLimitEnum.valueOf(typeStr) : RateLimitEnum.TOKEN_BUCKET;
            int TkbRate = environment.getProperty(prefix + "TkbRate", Integer.class, 0);
            int TkbCapacity = environment.getProperty(prefix + "TkbCapacity", Integer.class, 0);
            int SlwWindow = environment.getProperty(prefix + "SlwWindow", Integer.class, 0);
            int SlwThreshold = environment.getProperty(prefix + "SlwThreshold", Integer.class, 0);
            log.debug("get from yaml, type: {}, TknRate: {}, TkbCapacity: {}, SlwWindow: {}, SlwThreshold: {}",type, TkbRate, TkbCapacity, SlwWindow, SlwThreshold);
            configMap.put(service, new LimitConfig(type, TkbRate, TkbCapacity, SlwWindow, SlwThreshold));
        }
    }

    /**
     * 从本地yaml文件加载限流配置
     */
    public void loadConfigFromYaml(org.springframework.core.env.Environment environment) {
        // 这里只做简单演示，实际可用@Value或@ConfigurationProperties等方式
        // TODO-功能  能否从nacos上获取微服务列表
        String[] services = {"order-service", "user-service","provider-a"};
        for (String service : services) {
            String prefix = "rate-limit." + service + ".";
            String typeStr = environment.getProperty(prefix + "type");
            RateLimitEnum type = typeStr != null ? RateLimitEnum.valueOf(typeStr) : RateLimitEnum.TOKEN_BUCKET;
            int TkbRate = environment.getProperty(prefix + "TkbRate", Integer.class, 10);
            int TkbCapacity = environment.getProperty(prefix + "TkbCapacity", Integer.class, 20);
            int SlwWindow = environment.getProperty(prefix + "SlwWindow", Integer.class, 60);
            int SlwThreshold = environment.getProperty(prefix + "SlwThreshold", Integer.class, 100);
            configMap.put(service, new LimitConfig(type, TkbRate, TkbCapacity, SlwWindow, SlwThreshold));
        }
    }
   
    // 因为不同微服务的策略/参数不一致，所以这里需要根据微服务名获取对应策略/参数
    public LimitConfig getConfig(String serviceId) {
        return configMap.getOrDefault(serviceId, new LimitConfig(RateLimitEnum.TOKEN_BUCKET, 5, 10, 0, 0));
    }

    /**
     * 监听Nacos上的配置修改
     * @param newConfigMap 新的配置Map，key为serviceId，value为LimitConfig
     */
    public void onNacosConfigChanged(Map<String, LimitConfig> newConfigMap) {
        for (Map.Entry<String, LimitConfig> entry : newConfigMap.entrySet()) {
            String serviceId = entry.getKey();
            LimitConfig newConfig = entry.getValue();
            if (configMap.containsKey(serviceId)) {
                // 如果涉及当前已存在的微服务限流器，则需要修改该限流器
                configMap.put(serviceId, newConfig);
            }
        }
    }

    @Data
    public static class LimitConfig {
        private RateLimitEnum type; // token-bucket or sliding-window
        private int TkbRate;    // 令牌桶每秒填充速率
        private int TkbCapacity;// 令牌桶容量
        private int SlwWindow;  // 滑动窗口秒数
        private int SlwThreshold;// 滑动窗口最大请求数

        public LimitConfig(RateLimitEnum type, int TkbRate, int TkbCapacity, int SlwWindow, int SlwThreshold) {
            this.type = type;
            this.TkbRate = TkbRate;
            this.TkbCapacity = TkbCapacity;
            this.SlwWindow = SlwWindow;
            this.SlwThreshold = SlwThreshold;
        }
    }
}
