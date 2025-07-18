package com.bank.gateway.router.entity;

import lombok.Data;

import java.util.Map;

@Data
public class ServiceProviderInstance {  //微服务提供者实例
    private String host;
    private int port;
    private Map<String, String> metadata;  //元数据
}