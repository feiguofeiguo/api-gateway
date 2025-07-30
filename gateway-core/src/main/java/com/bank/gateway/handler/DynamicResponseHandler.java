package com.bank.gateway.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.HttpHeaderNames;

import java.util.concurrent.TimeUnit;

/**
 * 动态响应处理器
 * 支持运行时绑定不同的原始上下文
 */
@Component
@Slf4j
@io.netty.channel.ChannelHandler.Sharable
public class DynamicResponseHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
    
    // 用于存储原始上下文的属性键
    private static final AttributeKey<ChannelHandlerContext> ORIGINAL_CTX_KEY = 
            AttributeKey.valueOf("originalCtx");
    
    // 用于存储目标信息的属性键
    private static final AttributeKey<String> TARGET_HOST_KEY = 
            AttributeKey.valueOf("targetHost");
    private static final AttributeKey<Integer> TARGET_PORT_KEY = 
            AttributeKey.valueOf("targetPort");
    
    // 用于存储ConnectionPool的属性键
    private static final AttributeKey<ConnectionPool> CONNECTION_POOL_KEY = 
            AttributeKey.valueOf("connectionPool");
    
    // 用于存储请求ID的属性键
    private static final AttributeKey<String> REQUEST_ID_KEY = 
            AttributeKey.valueOf("requestId");
    
    @Autowired
    private RequestResponseMapper requestResponseMapper;
    
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
        // 从channel属性中获取请求ID
        String requestId = ctx.channel().attr(REQUEST_ID_KEY).get();
        String host = ctx.channel().attr(TARGET_HOST_KEY).get();
        Integer port = ctx.channel().attr(TARGET_PORT_KEY).get();
        
        log.debug("收到后端响应: requestId={}, host={}, port={}, responseStatus={}", 
                requestId, host, port, response.status());
        
        if (requestId != null && host != null && port != null) {
            // 通过请求ID获取原始客户端上下文
            ChannelHandlerContext originalCtx = requestResponseMapper.getClientContext(requestId);
            
            if (originalCtx != null && originalCtx.channel().isActive()) {
                log.debug("找到原始客户端上下文: {}", originalCtx.channel().remoteAddress());
                
                // 将响应写回原始客户端
                log.debug("收到响应，转发给原始客户端: {}:{} -> {}", host, port, originalCtx.channel().remoteAddress());
                originalCtx.writeAndFlush(response.retain()).addListener(future -> {
                    if (future.isSuccess()) {
                        log.debug("响应转发成功: {}", requestId);
                        
                        // 检查是否需要关闭客户端连接
                        boolean shouldClose = true; //shouldCloseClientConnection(response);
                        log.debug("连接关闭检查: shouldClose={}, connection={}, version={}", 
                                shouldClose, response.headers().get(HttpHeaderNames.CONNECTION), response.protocolVersion());
                        
                        if (shouldClose) {
                            log.debug("关闭客户端连接: {}", requestId);
                            originalCtx.close();
                        } else {
                            log.debug("保持客户端连接: {}", requestId);
                        }
                    } else {
                        log.warn("响应转发失败: {} -> {}", requestId, future.cause().getMessage());
                        originalCtx.close();
                    }
                });
                
                // 移除请求映射
                requestResponseMapper.removeRequest(requestId);
                log.debug("已移除请求映射: {}", requestId);
            } else {
                log.warn("原始客户端已断开或无效: {}", requestId);
            }
            
            // 获取ConnectionPool
            ConnectionPool connectionPool = ctx.channel().attr(CONNECTION_POOL_KEY).get();
            
            // 清理属性
            ctx.channel().attr(REQUEST_ID_KEY).set(null);
            ctx.channel().attr(TARGET_HOST_KEY).set(null);
            ctx.channel().attr(TARGET_PORT_KEY).set(null);
            
            // 归还连接到池中
            if (connectionPool != null) {
                log.debug("归还连接到池中: {}:{}", host, port);
                connectionPool.returnConnection(ctx.channel(), host, port);
            } else {
                log.warn("ConnectionPool为空，关闭连接: {}:{}", host, port);
                ctx.close();
            }
        } else {
            log.warn("无法获取请求ID或目标信息，关闭连接: requestId={}, host={}, port={}", requestId, host, port);
            ctx.close();
        }
    }
    
    /**
     * 判断是否需要关闭客户端连接
     */
    private boolean shouldCloseClientConnection(FullHttpResponse response) {
        // 检查Connection头
        String connection = response.headers().get(HttpHeaderNames.CONNECTION);
        if (connection != null) {
            return "close".equalsIgnoreCase(connection);
        }
        
        // 检查HTTP版本
        HttpVersion version = response.protocolVersion();
        if (HttpVersion.HTTP_1_0.equals(version)) {
            return true; // HTTP/1.0 默认关闭连接
        }
        
        // HTTP/1.1 默认保持连接，除非明确指定close
        return false;
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("动态响应处理器异常: {}", cause.getMessage());
        
        // 获取请求ID和目标信息
        String requestId = ctx.channel().attr(REQUEST_ID_KEY).get();
        String host = ctx.channel().attr(TARGET_HOST_KEY).get();
        Integer port = ctx.channel().attr(TARGET_PORT_KEY).get();
        
        // 移除请求映射
        if (requestId != null) {
            requestResponseMapper.removeRequest(requestId);
        }
        
        // 获取ConnectionPool
        ConnectionPool connectionPool = ctx.channel().attr(CONNECTION_POOL_KEY).get();
        
        // 清理属性
        ctx.channel().attr(REQUEST_ID_KEY).set(null);
        ctx.channel().attr(TARGET_HOST_KEY).set(null);
        ctx.channel().attr(TARGET_PORT_KEY).set(null);
        
        // 归还连接到池中
        if (host != null && port != null && connectionPool != null) {
            connectionPool.returnConnection(ctx.channel(), host, port);
        } else {
            ctx.close();
        }
    }
    
    /**
     * 绑定请求上下文到连接
     */
    public static void bindRequestContext(io.netty.channel.Channel channel, 
                                       String requestId,
                                       String host, 
                                       int port,
                                       ConnectionPool connectionPool) {
        channel.attr(REQUEST_ID_KEY).set(requestId);  //响应处理时通过这个ID找到对应的原始客户端
        channel.attr(TARGET_HOST_KEY).set(host);
        channel.attr(TARGET_PORT_KEY).set(port);
        channel.attr(CONNECTION_POOL_KEY).set(connectionPool);
        
        // 添加客户端连接超时机制，防止连接长时间保持
        // 30秒后自动关闭客户端连接
        channel.eventLoop().schedule(() -> {
            String currentRequestId = channel.attr(REQUEST_ID_KEY).get();
            if (currentRequestId != null && currentRequestId.equals(requestId)) {
                // 如果30秒后请求ID还是这个，说明响应还没处理完，强制关闭
                log.warn("客户端连接超时，强制关闭: {}", requestId);
                // 这里不能直接关闭，因为可能响应还在处理中
                // 只是记录日志，实际关闭在响应处理时进行
            }
        }, 30, TimeUnit.SECONDS);
    }
} 