package com.bank.gateway.loadbalancer;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "loadbalancer")
public class LoadBalancerConfig {

    private LoadBalancerEnum Strategy;

}
