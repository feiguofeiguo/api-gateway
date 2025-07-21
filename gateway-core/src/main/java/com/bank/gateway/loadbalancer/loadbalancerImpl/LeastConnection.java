package com.bank.gateway.loadbalancer.loadbalancerImpl;

import com.bank.gateway.loadbalancer.LoadBalancer;
import com.bank.gateway.router.entity.ServiceProviderInstance;
import lombok.Getter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LeastConnection implements LoadBalancer {
    @Getter
    private static final Map<String, ConcurrentHashMap<Integer, Integer>> serviceConnectionCounts = new HashMap<>();

    @Override
    public ServiceProviderInstance choose(String serviceId, List<ServiceProviderInstance> instances, String clientIP) {
        if(!serviceConnectionCounts.containsKey(serviceId)) {
            synchronized (serviceId) {
                if(!serviceConnectionCounts.containsKey(serviceId)) {
                    serviceConnectionCounts.put(serviceId, new ConcurrentHashMap<>());
                }
            }
        }
        Map<Integer, Integer> instanceConnectionCounts = serviceConnectionCounts.get(serviceId);
        ServiceProviderInstance minConnectionService = instances.get(0);
        int minPort = minConnectionService.getPort();
        int minConnection = instanceConnectionCounts.getOrDefault(minPort, 0);
        for(ServiceProviderInstance instance : instances) {
            int port = instance.getPort();
            if(!instanceConnectionCounts.containsKey(port)) {
                synchronized (serviceId) {
                    if(!instanceConnectionCounts.containsKey(port)) {
                        instanceConnectionCounts.put(port, 0);
                    }
                }
            }
            if(instanceConnectionCounts.get(port) < minConnection) {
                minConnectionService = instance;
                minPort = port;
                minConnection = instanceConnectionCounts.get(port);
            }
        }
        instanceConnectionCounts.put(minPort, instanceConnectionCounts.get(minPort) + 1);
        return minConnectionService;
    }

    public static void releaseConnection(String serviceId, int port) {
        Map<Integer, Integer> instanceConnectionCounts = serviceConnectionCounts.get(serviceId);
        instanceConnectionCounts.put(port, instanceConnectionCounts.get(port) - 1);
    }
}
