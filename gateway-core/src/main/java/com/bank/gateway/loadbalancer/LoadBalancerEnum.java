package com.bank.gateway.loadbalancer;

/*
 * 负载均衡策略枚举类
 */
public enum LoadBalancerEnum {
    IPHash, LeastConnection, Random, RoundRobin, WeightedRandom, WeightedRoundRobin
}
