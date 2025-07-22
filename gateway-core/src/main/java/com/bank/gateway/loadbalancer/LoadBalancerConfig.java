package com.bank.gateway.loadbalancer;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/*
 * 用于读取用户配置的负载均衡策略，支持动态变更
 */
@Data
@Component
@ConfigurationProperties(prefix = "loadbalancer")
public class LoadBalancerConfig {
    // 策略枚举类
    private LoadBalancerEnum Strategy;
}
