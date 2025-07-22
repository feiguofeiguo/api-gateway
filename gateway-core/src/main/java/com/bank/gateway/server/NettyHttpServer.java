package com.bank.gateway.server;

import com.bank.gateway.handler.Forwarder;
import com.bank.gateway.loadbalancer.LoadBalancer;
import com.bank.gateway.loadbalancer.LoadBalancerContext;
import com.bank.gateway.router.RouterService;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import lombok.extern.java.Log;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import com.bank.gateway.router.entity.ServiceProviderInstance;

import java.net.InetSocketAddress;

@Component
@Slf4j
public class NettyHttpServer implements CommandLineRunner {

    @Autowired
    private RouterService routerService;

    @Autowired
    private Forwarder forwarder;

    @Autowired
    private LoadBalancerContext loadBalancerContext;

    private void startServer() throws InterruptedException {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new HttpServerCodec());
                            pipeline.addLast(new HttpObjectAggregator(65536));
                            pipeline.addLast(new SimpleHttpHandler(routerService, forwarder, loadBalancerContext));
                        }
                    });

            ChannelFuture future = bootstrap.bind(9261).sync();
            log.info("ApiGateway started on port 9261");
            future.channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    @Override
    public void run(String... args) throws Exception {
        routerService.init();  //初始化路由表
        startServer();         //启动网关服务器
    }
}

@Slf4j
class SimpleHttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    //路由服务
    private final RouterService routerService;
    //转发服务
    private final Forwarder forwarder;
    //负载均衡策略上下文
    private final LoadBalancerContext loadBalancerContext;

    public SimpleHttpHandler(RouterService routerService, Forwarder forwarder, LoadBalancerContext loadBalancerContext){
        this.routerService = routerService;
        this.forwarder = forwarder;
        this.loadBalancerContext = loadBalancerContext;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        request.retain();

        try {
            // 1 通过RouterService获取服务名
            String uri=request.uri();
            String serviceId = routerService.getServiceId(uri);
            if (serviceId == null) {                // 如果没有找到匹配的路由，返回404
                sendErrorResponse(ctx, "ServiceID not found", HttpResponseStatus.NOT_FOUND);
                return;
            }
            log.info("Service ID: " + serviceId);

            //获取源ip地址，可用于负载均衡
            InetSocketAddress insocket = (InetSocketAddress) ctx.channel().remoteAddress();
            String clientIP = insocket.getAddress().getHostAddress();
            //2. 通过LoadBalancer选择服务实例
            ServiceProviderInstance instance = loadBalancerContext.choose(serviceId, clientIP);
            if (instance == null){                // 如果没有可用的服务实例，返回503
                sendErrorResponse(ctx, "No available service instances", HttpResponseStatus.SERVICE_UNAVAILABLE);
                return;
            }
            log.info("Instance found: " + instance);

            // 3. 通过Forwarder转发请求
            forwarder.forward(request, instance, ctx);
            //这里无论返回什么，作为网关的工作都算完成了，因此没有异常处理
            log.info("==forward done==");
        }catch (Exception e){
            sendErrorResponse(ctx, "Service not found", HttpResponseStatus.NOT_FOUND);
            e.printStackTrace();
        }
    }

    private void sendErrorResponse(ChannelHandlerContext ctx, String message, HttpResponseStatus status) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                ctx.alloc().buffer().writeBytes(message.getBytes())
        );

        response.headers().set(HttpHeaderNames.CONTENT_TYPE,"text/plain;charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
