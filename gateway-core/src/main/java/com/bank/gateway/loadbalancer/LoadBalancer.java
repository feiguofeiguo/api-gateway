package com.bank.gateway.loadbalancer;

import com.bank.gateway.router.entity.ServiceProviderInstance;

import java.util.List;

/*
 * 利用策略模式实现负载均衡功能，定义负载均衡策略接口
 */
public interface LoadBalancer {
    // 用负载均衡策略选择服务实例
    ServiceProviderInstance choose(String serviceId, List<ServiceProviderInstance> instances, String clientIP);
}
