package com.bank.gateway.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.HttpHeaderNames;

import java.util.concurrent.TimeUnit;

/**
 * 动态响应处理器
 * 支持运行时绑定不同的原始上下文
 * 修改：移除requestId绑定，改为请求队列管理
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
    
    // 用于存储KeepAliveConnection的属性键
    private static final AttributeKey<ConnectionKeepAliveManager.KeepAliveConnection> KEEP_ALIVE_CONN_KEY = 
            AttributeKey.valueOf("keepAliveConnection");
    
    @Autowired
    private RequestResponseMapper requestResponseMapper;
    
    @Autowired
    private ConnectionKeepAliveManager keepAliveManager;
    
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
        String host = ctx.channel().attr(TARGET_HOST_KEY).get();
        Integer port = ctx.channel().attr(TARGET_PORT_KEY).get();
        
        log.debug("收到后端响应: host={}, port={}, responseStatus={}", 
                host, port, response.status());
        log.debug("具体响应为："+response.content().toString(CharsetUtil.UTF_8));
        
        if (host != null && port != null) {
            // 获取KeepAliveConnection
            ConnectionKeepAliveManager.KeepAliveConnection keepAliveConn = 
                    ctx.channel().attr(KEEP_ALIVE_CONN_KEY).get();
            
            if (keepAliveConn != null) {
                // 处理保活连接中的下一个请求
                processNextRequest(keepAliveConn, response);
            } else {
                // 处理普通连接池中的连接
                processNormalConnection(ctx, response, host, port);
            }
        } else {
            log.warn("无法获取目标信息，关闭连接: host={}, port={}", host, port);
            ctx.close();
        }
    }
    
    /**
     * 处理保活连接中的下一个请求
     */
    private void processNextRequest(ConnectionKeepAliveManager.KeepAliveConnection keepAliveConn, 
                                 FullHttpResponse response) {
        // 获取下一个待处理请求
        ConnectionKeepAliveManager.PendingRequest pendingRequest = keepAliveConn.getNextPendingRequest();
        
        if (pendingRequest != null) {
            String requestId = pendingRequest.getRequestId();
            ChannelHandlerContext clientCtx = pendingRequest.getClientCtx();
            
            log.debug("处理保活连接中的请求: {} -> {}", requestId, keepAliveConn.getHost() + ":" + keepAliveConn.getPort());
            
            if (clientCtx != null && clientCtx.channel().isActive()) {
                // 将响应写回原始客户端
                clientCtx.writeAndFlush(response.retain()).addListener(future -> {
                    if (future.isSuccess()) {
                        log.debug("响应转发成功: {}", requestId);
                    } else {
                        log.warn("响应转发失败: {} -> {}", requestId, future.cause().getMessage());
                    }
                    // 关闭客户端连接
                    clientCtx.close();
                });
                
                // 移除请求映射
                requestResponseMapper.removeRequest(requestId);
                log.debug("已移除请求映射: {}", requestId);
            } else {
                log.warn("原始客户端已断开或无效: {}", requestId);
                requestResponseMapper.removeRequest(requestId);
            }
            
            // 检查是否还有待处理请求
            if (keepAliveConn.hasPendingRequests()) {
                log.debug("保活连接还有待处理请求，继续处理");
                // 继续处理下一个请求
                keepAliveConn.setProcessing(false);
            } else {
                log.debug("保活连接无待处理请求，归还到保活池");
                // 归还到保活池
                keepAliveConn.setProcessing(false);
            }
        } else {
            log.warn("保活连接中没有待处理请求");
            keepAliveConn.setProcessing(false);
        }
    }
    
    /**
     * 处理普通连接池中的连接
     */
    private void processNormalConnection(ChannelHandlerContext ctx, FullHttpResponse response, 
                                      String host, int port) {
        // 获取ConnectionPool
        ConnectionPool connectionPool = ctx.channel().attr(CONNECTION_POOL_KEY).get();
        
        // 归还连接到池中
        if (connectionPool != null) {
            log.debug("归还连接到池中: {}:{}", host, port);
            connectionPool.returnConnection(ctx.channel(), host, port);
        } else {
            log.warn("ConnectionPool为空，关闭连接: {}:{}", host, port);
            ctx.close();
        }
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("动态响应处理器异常: {}", cause.getMessage());
        
        String host = ctx.channel().attr(TARGET_HOST_KEY).get();
        Integer port = ctx.channel().attr(TARGET_PORT_KEY).get();
        
        // 获取ConnectionPool
        ConnectionPool connectionPool = ctx.channel().attr(CONNECTION_POOL_KEY).get();
        
        // 归还连接到池中
        if (host != null && port != null && connectionPool != null) {
            connectionPool.returnConnection(ctx.channel(), host, port);
        } else {
            ctx.close();
        }
    }
    
    /**
     * 绑定请求上下文到连接
     * 修改：不再绑定requestId，而是将请求添加到KeepAliveConnection的队列中
     */
    public static void bindRequestContext(io.netty.channel.Channel channel, 
                                       String requestId,
                                       String host, 
                                       int port,
                                       ConnectionPool connectionPool) {
        channel.attr(TARGET_HOST_KEY).set(host);
        channel.attr(TARGET_PORT_KEY).set(port);
        channel.attr(CONNECTION_POOL_KEY).set(connectionPool);
        
        // 如果是保活连接，将请求添加到队列中
        if (requestId != null) {
            // 这里需要获取KeepAliveConnection实例
            // 由于KeepAliveConnection是私有类，需要通过其他方式获取
            // 暂时先记录日志，实际实现需要修改架构
            log.debug("请求 {} 将使用连接 {}:{}", requestId, host, port);
        }
    }
    
    /**
     * 绑定保活连接上下文
     */
    public static void bindKeepAliveContext(io.netty.channel.Channel channel,
                                          ConnectionKeepAliveManager.KeepAliveConnection keepAliveConn,
                                          String requestId,
                                          ChannelHandlerContext clientCtx) {
        // 将请求添加到保活连接的队列中
        keepAliveConn.addPendingRequest(requestId, clientCtx);
        
        // 绑定保活连接到channel
        channel.attr(KEEP_ALIVE_CONN_KEY).set(keepAliveConn);
        channel.attr(TARGET_HOST_KEY).set(keepAliveConn.getHost());
        channel.attr(TARGET_PORT_KEY).set(keepAliveConn.getPort());
        channel.attr(CONNECTION_POOL_KEY).set(keepAliveConn.getConnectionPool());
        
        log.debug("绑定保活连接上下文: {} -> {}:{}", requestId, keepAliveConn.getHost(), keepAliveConn.getPort());
    }
} 