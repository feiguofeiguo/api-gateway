package com.bank.gateway.handler;

import com.bank.gateway.loadbalancer.loadbalancerImpl.LeastConnection;
import com.bank.gateway.plugin.GatewayPlugin;
import com.bank.gateway.plugin.PluginChain;
import com.bank.gateway.plugin.PluginContext;
import com.bank.gateway.router.entity.ServiceProviderInstance;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.URISyntaxException;

@Component
@Slf4j
public class Forwarder implements GatewayPlugin {
    @Autowired
    private ConnectionPool connectionPool;
    
    @Autowired
    private RequestResponseMapper requestResponseMapper;
    
    @Autowired
    private ConnectionKeepAliveManager keepAliveManager;
    
    private static ThreadLocal<String> serviceIdContext = new ThreadLocal<>();
    private static ThreadLocal<ServiceProviderInstance> instanceContext = new ThreadLocal<>();

    @Override
    public String name() { return "ForwarderPlugin"; }
    @Override
    public int order() { return 50; }
    @Override
    public boolean enabled() { return true; }

    @Override
    public void execute(PluginContext context, PluginChain chain) {
        FullHttpRequest request = context.getRequest();
        ServiceProviderInstance instance = context.getInstance();
        ChannelHandlerContext ctx = context.getNettyCtx();
        forward(request, instance, ctx);
        log.debug("插件版-转发回传，完成！");
        // 转发后不再调用 chain.doNext(context)，因为这是最后一个插件
    }

    public void forward(FullHttpRequest request, ServiceProviderInstance instance, ChannelHandlerContext ctx) {
        log.debug("===Call forward===");
        if (instance == null) {
            sendErrorResponse(ctx, "No available service instance");
            return;
        }
        log.debug("instance:" + instance);

        // 生成请求ID并注册映射
        String requestId = requestResponseMapper.generateRequestId();
        requestResponseMapper.registerRequest(requestId, ctx);
        log.debug("生成请求ID: {} -> {}", requestId, ctx.channel().remoteAddress());
        log.debug(requestId+" 用于处理 request name: "+request.headers().get("REQUEST-NAME"));

        try {
            // 从连接池获取连接
            Channel targetChannel = connectionPool.getConnection(instance.getHost(), instance.getPort());
            
            // 检查是否是保活连接
            ConnectionKeepAliveManager.KeepAliveConnection keepAliveConn = 
                    keepAliveManager.getKeepAliveConnectionInstance(instance.getHost(), instance.getPort());
            
            if (keepAliveConn != null && targetChannel == keepAliveConn.getChannel()) {
                // 使用保活连接，将请求添加到队列中
                log.debug("使用保活连接处理请求: {} -> {}:{}", requestId, instance.getHost(), instance.getPort());
                DynamicResponseHandler.bindKeepAliveContext(targetChannel, keepAliveConn, requestId, ctx);
            } else {
                // 使用普通连接池中的连接
                log.debug("使用普通连接处理请求: {} -> {}:{}", requestId, instance.getHost(), instance.getPort());
                DynamicResponseHandler.bindRequestContext(targetChannel, requestId, instance.getHost(), instance.getPort(), connectionPool);
            }
            
            // 构造转发请求
            FullHttpRequest forwardRequest = createForwardRequest(request, instance, requestId);

            // 发送请求到目标服务
            targetChannel.writeAndFlush(forwardRequest).addListener(writeFuture -> {
                if (!writeFuture.isSuccess()) {
                    log.info(requestId+" Failed to forward request to " + instance.getHost() + ":" + instance.getPort());
                    sendErrorResponse(ctx, "Failed to forward request");
                    connectionPool.returnConnection(targetChannel, instance.getHost(), instance.getPort());
                } else {
                    log.info(requestId+" forward request success...");
                    // 在成功转发请求后给选中的服务实例增加连接数
                    serviceIdContext.set(request.uri().split("/")[1]);
                    instanceContext.set(instance);
                    LeastConnection.increaseConnection(serviceIdContext.get(), instanceContext.get().getPort());
                }
            });
        } catch (Exception e) {
            log.error(requestId+" Failed to get connection from pool: " + e.getMessage());
            sendErrorResponse(ctx, "Service unavailable");
        }
    }

    private FullHttpRequest createForwardRequest(FullHttpRequest originalRequest, ServiceProviderInstance instance, String requestId) {
        // 创建新的请求对象
        log.debug("uri:" + originalRequest.uri());
        String uri = null;
        try {
            uri = new URI(originalRequest.uri()).getRawPath();
            if (originalRequest.uri().contains("?")) {
                uri += "?" + new URI(originalRequest.uri()).getRawQuery();
            }
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        log.debug("调整后的uri:" + uri);
        FullHttpRequest forwardRequest = new DefaultFullHttpRequest(
                originalRequest.protocolVersion(),
                originalRequest.method(),
                uri,
                originalRequest.content().copy()
        );

        // 复制请求头
        forwardRequest.headers().setAll(originalRequest.headers());
        // 设置必要的请求头
        forwardRequest.headers().set(HttpHeaderNames.HOST, instance.getHost() + ":" + instance.getPort());
        forwardRequest.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);  //如果keepalive,客户端就会一直卡住

        forwardRequest.headers().set("X-REQUEST-ID", requestId);

        return forwardRequest;
    }

    private void sendErrorResponse(ChannelHandlerContext ctx, String errorMessage) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.SERVICE_UNAVAILABLE,
                ctx.alloc().buffer().writeBytes(errorMessage.getBytes(CharsetUtil.UTF_8))
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain;charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}