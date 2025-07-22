package com.bank.gateway.loadbalancer.loadbalancerImpl;

import com.bank.gateway.loadbalancer.LoadBalancer;
import com.bank.gateway.router.entity.ServiceProviderInstance;

import java.util.List;

public class IPHash implements LoadBalancer {
    @Override
    public ServiceProviderInstance choose(String serviceId, List<ServiceProviderInstance> instances, String clientIP) {
        return instances.get(clientIP.hashCode() % instances.size());
    }
}
