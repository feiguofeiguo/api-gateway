package com.bank.gateway.loadbalancer.loadbalancerImpl;

import com.bank.gateway.loadbalancer.LoadBalancer;
import com.bank.gateway.router.entity.ServiceProviderInstance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class RoundRobin implements LoadBalancer {
    private final Map<String, AtomicInteger> indexes = new HashMap<>();

    @Override
    public ServiceProviderInstance choose(String serviceId, List<ServiceProviderInstance> instances, String clientIP) {
        if(!indexes.containsKey(serviceId)) {
            synchronized (serviceId) {
                if(!indexes.containsKey(serviceId)) {
                    indexes.put(serviceId, new AtomicInteger(-1));
                }
            }
        }
        return instances.get(indexes.get(serviceId).incrementAndGet() % instances.size());
    }
}
