package com.bank.gateway.loadbalancer.loadbalancerImpl;

import com.bank.gateway.loadbalancer.LoadBalancer;
import com.bank.gateway.router.entity.ServiceProviderInstance;

import java.util.List;

public class Random implements LoadBalancer {
    private final java.util.Random random = new java.util.Random();

    @Override
    public ServiceProviderInstance choose(String serviceId, List<ServiceProviderInstance> instances, String clientIP) {
        return instances.get(random.nextInt(instances.size()));
    }
}
