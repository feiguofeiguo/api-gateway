package com.bank.gateway.loadbalancer;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "loadbalancer")
@Component
public class LoadBalancerConfig {

    @Value("${algo:Random}")
    private LoadBalancerEnum loadBalancerStrategy;

    public LoadBalancerEnum getLoadBalancerStrategy() {
        return this.loadBalancerStrategy;
    }
}
