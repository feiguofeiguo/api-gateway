package com.bank.gateway.handler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 连接池统计定时任务
 */
@Component
@Slf4j
public class ConnectionPoolStatsTask {
    
//    @Autowired
//    private ConnectionPool connectionPool;
//
//    @Autowired
//    private ConnectionPoolConfig config;
//
//    /**
//     * 每分钟打印一次连接池统计信息
//     */
//    @Scheduled(fixedRate = 60000) // 每分钟执行一次
//    public void printPoolStats() {
//        if (config.isStatsEnabled()) {
//            connectionPool.printPoolStats();
//        }
//    }
} 