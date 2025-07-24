package com.bank.gateway.server;

import com.bank.gateway.plugin.PluginDispatcherHandler;
import com.bank.gateway.router.RouterService;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import com.bank.gateway.plugin.PluginManager;


@Component
@Slf4j
public class NettyHttpServer implements CommandLineRunner {
    @Autowired
    private RouterService routerService;
    @Autowired
    private  PluginManager pluginManager;

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
                            pipeline.addLast(new PluginDispatcherHandler(pluginManager));
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