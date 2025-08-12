package com.bank.gateway.handler;

import com.bank.gateway.plugin.GatewayPlugin;
import com.bank.gateway.plugin.PluginChain;
import com.bank.gateway.plugin.PluginContext;
import com.bank.gateway.router.entity.ServiceProviderInstance;
import com.bank.gateway.monitor.server.MetricsServer;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import javax.annotation.PostConstruct;

@Component
@Slf4j
public class Forwarder implements GatewayPlugin {
    @Autowired
    private RequestResponseMapper requestResponseMapper;
    @Autowired
    private DynamicResponseHandler dynamicResponseHandler;

    private static final EventLoopGroup SHARED_GROUP = new NioEventLoopGroup();
    private static final Bootstrap SHARED_BOOTSTRAP = new Bootstrap();

    @PostConstruct
    public void init() {
        SHARED_BOOTSTRAP.group(SHARED_GROUP)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new HttpClientCodec());
                        p.addLast(new HttpObjectAggregator(65536));
                        p.addLast(dynamicResponseHandler); // 单例@Sharable
                    }
                });
    }

    @Autowired
    private MetricsServer metricsServer;

    @Override
    public String name() { return "ForwarderPlugin"; }
    @Override
    public int order() { return 50; }
    @Override
    public boolean enabled() { return true; }

    @Override
    public void execute(PluginContext context, PluginChain chain) {
        long start = System.nanoTime();
        FullHttpRequest request = context.getRequest();
        ServiceProviderInstance instance = context.getInstance();
        ChannelHandlerContext ctx = context.getNettyCtx();
        String requestId=context.getRequestId();
        forward(request, instance, ctx, requestId);
        log.debug("插件版-转发回传，完成！");
        long end = System.nanoTime();
        log.warn("{}-【Forwarder】耗时: {} ns, 约 {} us, {} ms", context.getRequestId(), end - start,(end - start)/1000.0,(end - start)/1000000.0);
    }

    public void forward(FullHttpRequest request, ServiceProviderInstance instance, ChannelHandlerContext ctx,String requestId) {
        log.debug("===Call forward===");
        if (instance == null) {
            sendErrorResponse(ctx, "No available service instance");
            return;
        }
        log.debug("instance:" + instance);

        requestResponseMapper.registerRequest(requestId, ctx);
        log.info("forward: requestId={}, frontendCtxHash={}, instance={}", requestId, ctx.hashCode(), instance);
        log.debug(requestId+" 用于处理 request name: "+request.headers().get("REQUEST-NAME"));

        try {
            ChannelFuture future = SHARED_BOOTSTRAP.connect(instance.getHost(), instance.getPort());
            future.addListener((ChannelFutureListener) connectFuture -> {
                try {
                    if (connectFuture.isSuccess()) {
                        Channel backendChannel = connectFuture.channel();
                        log.info("forward: requestId={}, backendChannelHash={}, frontendCtxHash={}", requestId, backendChannel.hashCode(), ctx.hashCode());
                        backendChannel.attr(AttributeKey.valueOf("requestId")).set(requestId);
                        backendChannel.attr(AttributeKey.valueOf("frontendCtx")).set(ctx);
                        FullHttpRequest forwardRequest = createForwardRequest(request, instance, requestId);
                        backendChannel.writeAndFlush(forwardRequest);
                    } else {
                        sendErrorResponse(ctx, "后端服务连接失败");  //here 此处报错过，高并发下
                    }
                } finally {
                    // 释放原始请求
                    ReferenceCountUtil.release(request);
                }
            });
        } catch (Exception e) {
            log.error(requestId+" Failed to connect to backend: " + e.getMessage());
            sendErrorResponse(ctx, "Service unavailable");
        }
    }

    private FullHttpRequest createForwardRequest(FullHttpRequest originalRequest, ServiceProviderInstance instance, String requestId) {
        log.debug("uri:" + originalRequest.uri());
        String uri = null;
        try {
            uri = new java.net.URI(originalRequest.uri()).getRawPath();
            if (originalRequest.uri().contains("?")) {
                uri += "?" + new java.net.URI(originalRequest.uri()).getRawQuery();
            }
        } catch (java.net.URISyntaxException e) {
            throw new RuntimeException(e);
        }
        log.debug("调整后的uri:" + uri);
        FullHttpRequest forwardRequest = new DefaultFullHttpRequest(
                originalRequest.protocolVersion(),
                originalRequest.method(),
                uri,
                originalRequest.content().copy()
        );
        forwardRequest.headers().setAll(originalRequest.headers());
        forwardRequest.headers().set(HttpHeaderNames.HOST, instance.getHost() + ":" + instance.getPort());
        forwardRequest.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
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

    /**
     * 处理从目标服务返回的响应
     */
    private class ForwardResponseHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
        private final ChannelHandlerContext originalCtx;

        public ForwardResponseHandler(ChannelHandlerContext originalCtx) {
            this.originalCtx = originalCtx;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
            // Length of response
            metricsServer.getResponseSize().update(response.content().readableBytes());
            // 将响应写回原始客户端
            log.debug("response:" + response);
            originalCtx.writeAndFlush(response.retain()).addListener(ChannelFutureListener.CLOSE);
            // Number of success jobs.
            metricsServer.getSuccessJobs().inc();
            // 在请求响应后给选中的服务实例减少连接数
            LeastConnection.releaseConnection(serviceIdContext.get(), instanceContext.get().getPort());
            ctx.close();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.info("Error in forward response handler: " + cause.getMessage());
            new Forwarder().sendErrorResponse(originalCtx, "Internal server error");
            // 在请求响应后给选中的服务实例减少连接数
            LeastConnection.releaseConnection(serviceIdContext.get(), instanceContext.get().getPort());
            ctx.close();
        }
    }
}