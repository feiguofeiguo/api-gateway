package com.bank.gateway.loadbalancer;

import com.bank.gateway.router.entity.ServiceProviderInstance;

import java.util.List;

public interface LoadBalancer {
    ServiceProviderInstance choose(String serviceId, List<ServiceProviderInstance> instances, String clientIP);
}
