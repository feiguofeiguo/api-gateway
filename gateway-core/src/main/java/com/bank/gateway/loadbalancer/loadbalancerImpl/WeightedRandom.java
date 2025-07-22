package com.bank.gateway.loadbalancer.loadbalancerImpl;

import com.bank.gateway.loadbalancer.LoadBalancer;
import com.bank.gateway.router.entity.ServiceProviderInstance;

import java.util.List;
import java.util.Random;

/*
 * 带权重的随机算法做负载均衡
 */
public class WeightedRandom implements LoadBalancer {
    private final Random random = new Random();

    private final String WEIGHT = "nacos.weight";

    @Override
    public ServiceProviderInstance choose(String serviceId, List<ServiceProviderInstance> instances, String clientIP) {
        double totalWeight = 0;
        for(ServiceProviderInstance instance : instances) {
            totalWeight += Double.parseDouble(instance.getMetadata().get(WEIGHT));
        }
        double curIndex = random.nextDouble() * totalWeight;
        for(ServiceProviderInstance instance : instances) {
            double weigth = Double.parseDouble(instance.getMetadata().get(WEIGHT));
            curIndex -= weigth;
            if(curIndex < 0) {
                return instance;
            }
        }
        return null;
    }
}
