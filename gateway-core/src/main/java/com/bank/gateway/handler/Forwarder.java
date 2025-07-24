package com.bank.gateway.handler;

import com.bank.gateway.loadbalancer.loadbalancerImpl.LeastConnection;
import com.bank.gateway.plugin.GatewayPlugin;
import com.bank.gateway.plugin.PluginChain;
import com.bank.gateway.plugin.PluginContext;
import com.bank.gateway.router.entity.ServiceProviderInstance;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.URISyntaxException;

@Component
@Slf4j
public class Forwarder implements GatewayPlugin {
    private static final EventLoopGroup group = new NioEventLoopGroup();
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

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new HttpClientCodec());
                        pipeline.addLast(new HttpObjectAggregator(65536));
                        pipeline.addLast(new ForwardResponseHandler(ctx));
                    }
                });

        ChannelFuture connectFuture = bootstrap.connect(instance.getHost(), instance.getPort());
        log.debug("connectFuture:" + connectFuture);
        connectFuture.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                Channel targetChannel = future.channel();

                // 构造转发请求
                FullHttpRequest forwardRequest = createForwardRequest(request, instance);
                log.debug("forwardRequest:" + forwardRequest);

                // 发送请求到目标服务
                targetChannel.writeAndFlush(forwardRequest).addListener(writeFuture -> {
                    if (!writeFuture.isSuccess()) {
                        log.info("Failed to forward request to " + instance.getHost() + ":" + instance.getPort());
                        sendErrorResponse(ctx, "Failed to forward request");
                        targetChannel.close();
                    } else {
                      log.info("forward request success...");
                      // 在成功转发请求后给选中的服务实例增加连接数
                      serviceIdContext.set(request.uri().split("/")[1]);
                      instanceContext.set(instance);
                      LeastConnection.increaseConnection(serviceIdContext.get(), instanceContext.get().getPort());
                    }
                });
            } else {
                log.info("Failed to connect to " + instance.getHost() + ":" + instance.getPort());
                sendErrorResponse(ctx, "Service unavailable");
            }
        });
    }

    private FullHttpRequest createForwardRequest(FullHttpRequest originalRequest, ServiceProviderInstance instance) {
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
        forwardRequest.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

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
    private static class ForwardResponseHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
        private final ChannelHandlerContext originalCtx;

        public ForwardResponseHandler(ChannelHandlerContext originalCtx) {
            this.originalCtx = originalCtx;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
            // 将响应写回原始客户端
            log.debug("response:" + response);
            originalCtx.writeAndFlush(response.retain()).addListener(ChannelFutureListener.CLOSE);
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