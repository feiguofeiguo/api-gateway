package com.bank.gateway.plugin;

import com.bank.gateway.monitor.server.MetricsServer;
import com.codahale.metrics.Timer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;

import java.util.List;

public class PluginDispatcherHandler extends ChannelInboundHandlerAdapter {
    private final PluginManager pluginManager;

    private final MetricsServer metricsServer;


    public PluginDispatcherHandler(PluginManager pluginManager, MetricsServer metricsServer) {
        this.pluginManager = pluginManager;
        this.metricsServer = metricsServer;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof FullHttpRequest)) {
            ctx.fireChannelRead(msg);
            return;
        }
        Timer.Context context = null;
        try {
            FullHttpRequest request = (FullHttpRequest) msg;
            // Number of all income jobs
            metricsServer.getTotalJobs().inc();
            // Length of request message calculate by histogram
            metricsServer.getRequestSize().update(request.content().readableBytes());
            // Timer start
            context = metricsServer.getResponsesTime().time();
            PluginContext pluginContext = new PluginContext(request, ctx);
            List<GatewayPlugin> plugins = pluginManager.getPlugins();
            PluginChain chain = new PluginChainImpl(plugins, 0);
            chain.doNext(pluginContext);
        } finally {
            context.stop();
        }
    }

    // 插件链实现
    private static class PluginChainImpl implements PluginChain {
        private final List<GatewayPlugin> plugins;
        private int index;

        public PluginChainImpl(List<GatewayPlugin> plugins, int index) {
            this.plugins = plugins;
            this.index = index;
        }

        @Override
        public void doNext(PluginContext context) {
            if (index < plugins.size()) {
                GatewayPlugin plugin = plugins.get(index++);
                if (plugin.enabled()) {
                    plugin.execute(context, this);
                } else {
                    doNext(context);
                }
            } else {
                // 插件链结束，继续 Netty pipeline
                context.getNettyCtx().fireChannelRead(context.getRequest());
            }
        }
    }
} 