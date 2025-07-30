package com.bank.gateway.handler;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 连接池管理器
 * 实现Bootstrap复用和连接池机制
 */
@Component
@Slf4j
public class ConnectionPool {
    
    // 共享的EventLoopGroup，避免为每个连接创建新的线程组
    private static final EventLoopGroup SHARED_GROUP = new NioEventLoopGroup();
    
    // 可复用的Bootstrap模板
    private static final Bootstrap BOOTSTRAP_TEMPLATE = new Bootstrap();
    
    // 连接池：key为"host:port"，value为可用连接队列
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<Channel>> connectionPools = new ConcurrentHashMap<>();
    
    @Autowired
    private ConnectionPoolConfig config;
    
    @Autowired
    private DynamicResponseHandler dynamicResponseHandler;
    
    @Autowired
    private ConnectionKeepAliveManager keepAliveManager;
    
    // 连接池配置
    private int maxPoolSize = 50;  // 每个目标的最大连接数
    private int minPoolSize = 10;  // 每个目标的最小连接数
    private long connectionTimeout = 50000; // 连接超时时间(ms)
    private long keepAliveTime = 60000; // 连接保活时间(ms)
    
    public ConnectionPool() {
        // 初始化共享的Bootstrap模板
        BOOTSTRAP_TEMPLATE.group(SHARED_GROUP)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectionTimeout)
                .option(ChannelOption.TCP_NODELAY, true);
    }
    
    /**
     * 初始化配置
     */
    public void initConfig() {
        if (config != null) {
            this.maxPoolSize = config.getMaxPoolSize();
            this.minPoolSize = config.getMinPoolSize();
            this.connectionTimeout = config.getConnectionTimeout();
            this.keepAliveTime = config.getKeepAliveTime();
        }
    }
    
    /**
     * 获取连接
     * @param host 目标主机
     * @param port 目标端口
     * @param requestId 请求ID，用于绑定到连接
     * @return 可用的Channel
     */
    public Channel getConnection(String host, int port, String requestId) {
        // 1. 首先尝试从保活连接中获取
        Channel channel = keepAliveManager.getKeepAliveConnection(host, port, requestId);
        if (channel != null && channel.isActive()) {
            log.info("复用保活连接: {}:{}", host, port);
            // 绑定请求上下文
            DynamicResponseHandler.bindRequestContext(channel, requestId, host, port, this);
            return channel;
        }
        
        // 2. 尝试从连接池获取
        String key = host + ":" + port;
        ConcurrentLinkedQueue<Channel> pool = connectionPools.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>());
        channel = pool.poll();
        if (channel != null && channel.isActive()) {
            log.info("从连接池获取到连接: {}:{}", host, port);
            // 绑定请求上下文
            DynamicResponseHandler.bindRequestContext(channel, requestId, host, port, this);
            return channel;
        }
        
        // 3. 创建新连接
        log.warn("连接池中没有可用连接，创建新连接: {}:{}", host, port);
        return createNewConnection(host, port, requestId);
    }
    

    
    /**
     * 创建新连接
     */
    private Channel createNewConnection(String host, int port, String requestId) {
        try {
            // 克隆Bootstrap模板并设置处理器
            Bootstrap bootstrap = BOOTSTRAP_TEMPLATE.clone();
            bootstrap.handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ChannelPipeline pipeline = ch.pipeline();
                    pipeline.addLast(new HttpClientCodec());
                    pipeline.addLast(new HttpObjectAggregator(65536));
                    pipeline.addLast(dynamicResponseHandler);
                }
            });
            
            // 连接目标服务器
            ChannelFuture connectFuture = bootstrap.connect(host, port);
            Channel channel = connectFuture.sync().channel();
            
            // 绑定请求ID到新创建的连接
            DynamicResponseHandler.bindRequestContext(channel, requestId, host, port, this);
            
            // 添加连接关闭监听器，用于连接池管理
            channel.closeFuture().addListener((ChannelFutureListener) future -> {
                log.debug("连接关闭: {}:{}", host, port);
                // 连接关闭时从池中移除
                String key = host + ":" + port;
                ConcurrentLinkedQueue<Channel> pool = connectionPools.get(key);
                if (pool != null) {
                    pool.remove(channel);
                }
            });
            
            return channel;
        } catch (Exception e) {
            log.error("创建连接失败: {}:{}, 错误: {}", host, port, e.getMessage());
            throw new RuntimeException("Failed to create connection to " + host + ":" + port, e);
        }
    }
    
    /**
     * 归还连接到池中
     * @param channel 要归还的连接
     * @param host 目标主机
     * @param port 目标端口
     */
    public void returnConnection(Channel channel, String host, int port) {
        if (channel == null || !channel.isActive()) {
            log.debug("连接无效，直接关闭: {}:{}", host, port);
            if (channel != null) {
                channel.close();
            }
            return;
        }
        
        // 将连接注册为保活连接，而不是归还到池中
        keepAliveManager.registerKeepAliveConnection(host, port, channel, this);
        log.info("连接进入保活状态: {}:{}", host, port);
    }
    

    
    /**
     * 重置Channel的Pipeline
     */
    private void resetChannelPipeline(Channel channel) {
        try {
            ChannelPipeline pipeline = channel.pipeline();
            // 移除所有处理器
            while (pipeline.last() != null) {
                pipeline.removeLast();
            }
        } catch (Exception e) {
            log.warn("重置Channel Pipeline失败: {}", e.getMessage());
        }
    }
    
    /**
     * 预创建连接池
     * @param host 目标主机
     * @param port 目标端口
     */
    public void preWarmPool(String host, int port) {
        String key = host + ":" + port;
        ConcurrentLinkedQueue<Channel> pool = connectionPools.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>());
        
        // 预创建最小连接数
        for (int i = 0; i < minPoolSize; i++) {
            try {
                Channel channel = createNewConnection(host, port, null);
                pool.offer(channel);
            } catch (Exception e) {
                log.warn("预创建连接失败: {}:{}, 错误: {}", host, port, e.getMessage());
            }
        }
        log.info("预创建连接池完成: {}:{}, 连接数: {}", host, port, pool.size());
    }
    
    /**
     * 清理连接池
     */
    public void cleanup() {
        // 清理主连接池
        connectionPools.forEach((key, pool) -> {
            Channel channel;
            while ((channel = pool.poll()) != null) {
                channel.close();
            }
        });
        connectionPools.clear();
        
        log.info("连接池清理完成");
    }
    
    /**
     * 获取连接池统计信息
     */
    public void printPoolStats() {
        log.info("=== 连接池统计信息 ===");
        int totalPools = connectionPools.size();
        final int[] totalConnections = {0};
        
        connectionPools.forEach((key, pool) -> {
            int size = pool.size();
            totalConnections[0] += size;
            log.info("连接池 - {}: 连接数={}", key, size);
        });
        
        log.info("总连接池数: {}, 总连接数: {}", totalPools, totalConnections[0]);
        log.info("=== 统计信息结束 ===");
    }
    
    /**
     * 获取指定目标的连接池大小
     */
    public int getPoolSize(String host, int port) {
        String key = host + ":" + port;
        ConcurrentLinkedQueue<Channel> pool = connectionPools.get(key);
        return pool != null ? pool.size() : 0;
    }
    
    /**
     * 获取总连接数
     */
    public int getTotalConnections() {
        final int[] total = {0};
        connectionPools.values().forEach(pool -> total[0] += pool.size());
        return total[0];
    }
    
} 