package com.bank.gateway.loadbalancer;

import com.bank.gateway.router.entity.ServiceProviderInstance;
import com.bank.gateway.router.RouterService;

import java.util.List;
import java.util.Random;

public class LoadBalancer {
    private static final Random random = new Random();

    public static ServiceProviderInstance choose(String serviceId) {
        //TODO 目前随机，后续补充
        List<ServiceProviderInstance> instances = RouterService.getServiceProviderInstanceMap().get(serviceId);
        if (instances == null || instances.isEmpty()) {
            return null;
        }
        return instances.get(random.nextInt(instances.size()));
    }
}
