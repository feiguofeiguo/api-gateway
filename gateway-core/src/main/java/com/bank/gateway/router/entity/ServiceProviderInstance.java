package com.bank.gateway.router.entity;

import lombok.Data;

import java.util.Map;

@Data
public class ServiceProviderInstance {
    private String host;
    private int port;
    private Map<String, String> metadata;

    public String getUri() {
        return "http://" + host + ":" + port + "/";
    }
}