package com.bank.gateway.loadbalancer.loadbalancerImpl;

import com.bank.gateway.loadbalancer.LoadBalancer;
import com.bank.gateway.router.entity.ServiceProviderInstance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/*
 * 轮询算法做负载均衡
 */
public class RoundRobin implements LoadBalancer {
    private final Map<String, AtomicInteger> indexes = new ConcurrentHashMap<>();

    @Override
    public ServiceProviderInstance choose(String serviceId, List<ServiceProviderInstance> instances, String clientIP) {
        indexes.putIfAbsent(serviceId, new AtomicInteger(-1));
        return instances.get((indexes.get(serviceId).incrementAndGet() & Integer.MAX_VALUE) % instances.size());
    }
}
