package com.bank.gateway.loadbalancer;

public enum LoadBalancerEnum {
    IPHash, LeastConnection, Random, RoundRobin, WeightedRandom, WeightedRoundRobin
}
