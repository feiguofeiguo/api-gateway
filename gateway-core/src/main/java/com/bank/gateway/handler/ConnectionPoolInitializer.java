package com.bank.gateway.handler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 连接池初始化器
 * 在应用启动时初始化连接池配置
 */
@Component
@Slf4j
public class ConnectionPoolInitializer implements CommandLineRunner {
    
    @Autowired
    private ConnectionPool connectionPool;
    
    @Autowired
    private ConnectionPoolConfig config;
    
    @Override
    public void run(String... args) throws Exception {
        log.info("初始化连接池配置...");
        
        // 初始化连接池配置
        connectionPool.initConfig();
        
        log.info("连接池配置初始化完成");
        log.info("最大连接数: {}", config.getMaxPoolSize());
        log.info("最小连接数: {}", config.getMinPoolSize());
        log.info("连接超时时间: {}ms", config.getConnectionTimeout());
        log.info("空闲超时时间: {}ms", config.getIdleTimeout());
        
        // 如果启用预热，预创建连接
        if (config.isPreWarmEnabled()) {
            log.info("开始预热连接池...");
            // 这里可以根据实际的服务实例来预热连接池
            // 暂时跳过预热，因为需要知道具体的服务实例
        }
    }
} 