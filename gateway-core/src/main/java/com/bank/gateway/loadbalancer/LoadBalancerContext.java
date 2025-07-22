package com.bank.gateway.loadbalancer;

import com.bank.gateway.loadbalancer.loadbalancerImpl.*;
import com.bank.gateway.router.RouterService;
import com.bank.gateway.router.entity.ServiceProviderInstance;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
 * 策略上下文，用于调用具体的负载均衡算法
 */
@Component
public class LoadBalancerContext {
    private LoadBalancer loadBalancer;

    private static Map<LoadBalancerEnum, LoadBalancer> strategyMap;

    public LoadBalancerContext(LoadBalancerConfig loadBalancerConfig) {
        strategyMap = new HashMap<>();
        strategyMap.put(LoadBalancerEnum.IPHash, new IPHash());
        strategyMap.put(LoadBalancerEnum.LeastConnection, new LeastConnection());
        strategyMap.put(LoadBalancerEnum.Random, new Random());
        strategyMap.put(LoadBalancerEnum.RoundRobin, new RoundRobin());
        strategyMap.put(LoadBalancerEnum.WeightedRandom, new WeightedRandom());
        strategyMap.put(LoadBalancerEnum.WeightedRoundRobin, new WeightedRoundRobin());
        this.setLoadBalancer(loadBalancerConfig.getStrategy());
    }

    public void setLoadBalancer(LoadBalancerEnum loadBalancerEnum) {
        this.loadBalancer = strategyMap.get(loadBalancerEnum);
    }

    public ServiceProviderInstance choose(String serviceId, String clientIP) {
        List<ServiceProviderInstance> instances = RouterService.getServiceProviderInstanceMap().get(serviceId);
        if (instances == null || instances.isEmpty()) {
            return null;
        }
        return loadBalancer.choose(serviceId, instances, clientIP);
    }
}
