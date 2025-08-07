package com.bank.gateway.loadbalancer.loadbalancerImpl;

import com.bank.gateway.loadbalancer.LoadBalancer;
import com.bank.gateway.router.entity.ServiceProviderInstance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/*
 * 带权重的轮询算法做负载均衡
 */
public class WeightedRoundRobin implements LoadBalancer {
    private final Map<String, AtomicInteger> indexes = new ConcurrentHashMap<>();

    private final String WEIGHT = "nacos.weight";

    @Override
    public ServiceProviderInstance choose(String serviceId, List<ServiceProviderInstance> instances, String clientIP) {
        indexes.putIfAbsent(serviceId, new AtomicInteger(-1));
        int totalWeight = 0;
        for(ServiceProviderInstance instance : instances) {
            totalWeight += (int)Double.parseDouble(instance.getMetadata().get(WEIGHT));
        }
        synchronized (serviceId) {
            int curIndex = indexes.get(serviceId).incrementAndGet() % totalWeight;
            for(ServiceProviderInstance instance : instances) {
                int weigth = (int)Double.parseDouble(instance.getMetadata().get(WEIGHT));
                curIndex -= weigth;
                if(curIndex < 0) {
                    return instance;
                }
            }
        }
        return null;
    }
}
