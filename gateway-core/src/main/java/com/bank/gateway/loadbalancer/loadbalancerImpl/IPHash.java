package com.bank.gateway.loadbalancer.loadbalancerImpl;

import com.bank.gateway.loadbalancer.LoadBalancer;
import com.bank.gateway.router.entity.ServiceProviderInstance;

import java.util.List;

/*
 * 根据请求源ip哈希值做负载均衡
 */
public class IPHash implements LoadBalancer {
    @Override
    public ServiceProviderInstance choose(String serviceId, List<ServiceProviderInstance> instances, String clientIP) {
        return instances.get(clientIP.hashCode() % instances.size());
    }
}
