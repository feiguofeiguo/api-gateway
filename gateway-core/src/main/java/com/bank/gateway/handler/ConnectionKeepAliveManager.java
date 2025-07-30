package com.bank.gateway.handler;

import io.netty.channel.Channel;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;

/**
 * 连接保活管理器
 * 实现连接的保活和直接复用机制
 */
@Component
@Slf4j
@Data
public class ConnectionKeepAliveManager {
    
    // 保活连接映射：key为"host:port"，value为保活的连接
    private final ConcurrentHashMap<String, KeepAliveConnection> keepAliveConnections = new ConcurrentHashMap<>();
    
    // 调度器，用于管理保活超时
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    
    // 保活时间配置（毫秒）
    private static final long KEEP_ALIVE_TIME = 60000; // 60秒
    
    /**
     * 获取保活连接
     * @param host 目标主机
     * @param port 目标端口
     * @param requestId 请求ID
     * @return 可用的连接，如果没有则返回null
     */
    public Channel getKeepAliveConnection(String host, int port, String requestId) {
        String key = host + ":" + port;
        KeepAliveConnection keepAliveConn = keepAliveConnections.get(key);
        
        if (keepAliveConn != null && keepAliveConn.isValid()) {
            // 刷新保活时间
            keepAliveConn.refresh();
            log.info("复用保活连接: {}:{} -> {}", host, port, keepAliveConn.getChannel().remoteAddress());
            return keepAliveConn.getChannel();
        }
        
        return null;
    }
    
    /**
     * 注册保活连接
     * @param host 目标主机
     * @param port 目标端口
     * @param channel 连接
     * @param connectionPool 连接池引用
     */
    public void registerKeepAliveConnection(String host, int port, Channel channel, ConnectionPool connectionPool) {
        String key = host + ":" + port;
        
        // 取消之前的保活任务（如果存在）
        KeepAliveConnection existing = keepAliveConnections.get(key);
        if (existing != null) {
            existing.cancelTimeoutTask();
        }
        
        // 创建新的保活连接
        KeepAliveConnection keepAliveConn = new KeepAliveConnection(channel, connectionPool, host, port);
        keepAliveConnections.put(key, keepAliveConn);
        
        // 添加连接关闭监听器，确保保活连接被意外关闭时能正确清理
        channel.closeFuture().addListener(future -> {
            log.debug("保活连接被关闭: {}:{}", host, port);
            KeepAliveConnection conn = keepAliveConnections.get(key);
            if (conn != null && conn.getChannel() == channel) {
                keepAliveConnections.remove(key);
                conn.cancelTimeoutTask();
                log.info("清理已关闭的保活连接: {}:{}", host, port);
            }
        });
        
        // 启动保活超时任务
        keepAliveConn.startTimeoutTask(scheduler, KEEP_ALIVE_TIME, () -> {
            log.info("保活连接超时，归还到连接池: {}:{}", host, port);
            keepAliveConnections.remove(key);
            connectionPool.returnConnection(channel, host, port);
        });
        
        log.info("注册保活连接: {}:{} -> {}", host, port, channel.remoteAddress());
    }
    
    /**
     * 移除保活连接
     * @param host 目标主机
     * @param port 目标端口
     */
    public void removeKeepAliveConnection(String host, int port) {
        String key = host + ":" + port;
        KeepAliveConnection keepAliveConn = keepAliveConnections.remove(key);
        if (keepAliveConn != null) {
            keepAliveConn.cancelTimeoutTask();
            log.info("移除保活连接: {}:{}", host, port);
        }
    }
    
    /**
     * 获取保活连接统计信息
     */
    public void printKeepAliveStats() {
        log.info("=== 保活连接统计 ===");
        keepAliveConnections.forEach((key, conn) -> {
            log.info("保活连接 - {}: 连接={}, 最后活跃时间={}", 
                    key, conn.getChannel().remoteAddress(), conn.getLastActiveTime());
        });
        log.info("总保活连接数: {}", keepAliveConnections.size());
        log.info("=== 统计结束 ===");
    }
    
    /**
     * 清理无效的保活连接
     */
    public void cleanupInvalidConnections() {
        keepAliveConnections.entrySet().removeIf(entry -> {
            KeepAliveConnection conn = entry.getValue();
            boolean invalid = !conn.isValid();
            if (invalid) {
                log.info("清理无效保活连接: {}", entry.getKey());
                conn.cancelTimeoutTask();
            }
            return invalid;
        });
    }
    
    /**
     * 关闭管理器
     */
    public void shutdown() {
        scheduler.shutdown();
        keepAliveConnections.forEach((key, conn) -> {
            conn.cancelTimeoutTask();
            conn.getChannel().close();
        });
        keepAliveConnections.clear();
    }
    
    /**
     * 保活连接包装类
     */
    @Data
    private static class KeepAliveConnection {
        private final Channel channel;
        private final ConnectionPool connectionPool;
        private final String host;
        private final int port;
        private volatile long lastActiveTime;
        private volatile ScheduledFuture<?> timeoutTask;
        
        public KeepAliveConnection(Channel channel, ConnectionPool connectionPool, String host, int port) {
            this.channel = channel;
            this.connectionPool = connectionPool;
            this.host = host;
            this.port = port;
            this.lastActiveTime = System.currentTimeMillis();
        }
        
        public Channel getChannel() {
            return channel;
        }
        
        public long getLastActiveTime() {
            return lastActiveTime;
        }
        
        public boolean isValid() {
            return channel != null && channel.isActive();
        }
        
        public void refresh() {
            this.lastActiveTime = System.currentTimeMillis();
        }
        
        public void startTimeoutTask(ScheduledExecutorService scheduler, long timeout, Runnable task) {
            this.timeoutTask = scheduler.schedule(task, timeout, TimeUnit.MILLISECONDS);
        }
        
        public void cancelTimeoutTask() {
            if (timeoutTask != null && !timeoutTask.isDone()) {
                timeoutTask.cancel(false);
            }
        }
    }
} 