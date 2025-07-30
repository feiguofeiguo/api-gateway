package com.bank.gateway.handler;

import io.netty.channel.ChannelHandlerContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 请求-响应映射器
 * 管理请求ID与原始客户端Channel的映射关系
 */
@Component
@Slf4j
public class RequestResponseMapper {
    
    // 请求ID生成器
    private static final AtomicLong REQUEST_ID_GENERATOR = new AtomicLong(0);
    
    // 请求ID到原始客户端的映射
    private final ConcurrentHashMap<String, ChannelHandlerContext> requestToClientMap = new ConcurrentHashMap<>();
    
    /**
     * 生成唯一的请求ID
     */
    public String generateRequestId() {
        return "req_" + REQUEST_ID_GENERATOR.incrementAndGet() + "_" + System.currentTimeMillis();
    }
    
    /**
     * 注册请求映射
     * @param requestId 请求ID
     * @param clientCtx 原始客户端上下文
     */
    public void registerRequest(String requestId, ChannelHandlerContext clientCtx) {
        requestToClientMap.put(requestId, clientCtx);
        log.debug("注册请求映射: {} -> {}", requestId, clientCtx.channel().remoteAddress());
    }
    
    /**
     * 获取原始客户端上下文
     * @param requestId 请求ID
     * @return 原始客户端上下文，如果不存在则返回null
     */
    public ChannelHandlerContext getClientContext(String requestId) {
        ChannelHandlerContext ctx = requestToClientMap.get(requestId);
        if (ctx != null) {
            log.debug("找到客户端上下文: {} -> {}", requestId, ctx.channel().remoteAddress());
        } else {
            log.warn("未找到客户端上下文: {}", requestId);
        }
        return ctx;
    }
    
    /**
     * 移除请求映射
     * @param requestId 请求ID
     */
    public void removeRequest(String requestId) {
        ChannelHandlerContext removed = requestToClientMap.remove(requestId);
        if (removed != null) {
            log.debug("移除请求映射: {} -> {}", requestId, removed.channel().remoteAddress());
        }
    }
    
    /**
     * 获取当前活跃请求数
     */
    public int getActiveRequestCount() {
        return requestToClientMap.size();
    }
    
    /**
     * 清理无效的映射
     */
    public void cleanupInvalidMappings() {
        requestToClientMap.entrySet().removeIf(entry -> {
            ChannelHandlerContext ctx = entry.getValue();
            boolean invalid = !ctx.channel().isActive();
            if (invalid) {
                log.debug("清理无效映射: {} -> {}", entry.getKey(), ctx.channel().remoteAddress());
            }
            return invalid;
        });
    }
} 