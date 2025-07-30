package com.bank.gateway.handler;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 连接池配置类
 */
@Component
@ConfigurationProperties(prefix = "gateway.connection-pool")
@Data
public class ConnectionPoolConfig {
    
    /**
     * 每个目标的最大连接数
     */
    private int maxPoolSize = 20;
    
    /**
     * 每个目标的最小连接数
     */
    private int minPoolSize = 5;
    
    /**
     * 连接超时时间(毫秒)
     */
    private long connectionTimeout = 5000;
    
    /**
     * 连接空闲超时时间(毫秒)
     */
    private long idleTimeout = 30000;
    
    /**
     * 连接保活时间(毫秒)
     */
    private long keepAliveTime = 60000;
    
    /**
     * 连接池预热开关
     */
    private boolean preWarmEnabled = true;
    
    /**
     * 连接池统计开关
     */
    private boolean statsEnabled = true;
} 